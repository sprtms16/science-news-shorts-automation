#!/usr/bin/env python3
"""
Docker Autoscaler for Kafka Consumer-based Workloads

Monitors Kafka consumer group lag and automatically scales Docker containers
based on configurable thresholds.
"""

import os
import time
import docker
from kafka import KafkaAdminClient
from kafka.admin import NewTopic
from kafka import KafkaConsumer
from kafka.structs import TopicPartition

# Configuration from environment
KAFKA_BOOTSTRAP_SERVERS = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "kafka:29092")
CONSUMER_GROUP = os.getenv("CONSUMER_GROUP", "renderer-group")
TARGET_SERVICE = os.getenv("TARGET_SERVICE", "shorts-renderer")
IMAGE_NAME = os.getenv("IMAGE_NAME", "shorts-worker:latest") # Explicit image name
MIN_REPLICAS = int(os.getenv("MIN_REPLICAS", "1"))
MAX_REPLICAS = int(os.getenv("MAX_REPLICAS", "5"))
SCALE_UP_THRESHOLD = int(os.getenv("SCALE_UP_THRESHOLD", "5"))
SCALE_DOWN_THRESHOLD = int(os.getenv("SCALE_DOWN_THRESHOLD", "2"))
CHECK_INTERVAL = int(os.getenv("CHECK_INTERVAL", "30"))
COOLDOWN_PERIOD = int(os.getenv("COOLDOWN_PERIOD", "60"))
AUTO_UPGRADE = os.getenv("AUTO_UPGRADE", "true").lower() == "true"

# Topics to monitor
MONITORED_TOPICS = ["script-created", "assets-ready"]


class DockerAutoscaler:
    def __init__(self):
        self.docker_client = docker.from_env()
        self.last_scale_time = 0
        self.last_pull_time = 0
        self.current_replicas = self._get_current_replicas()
        print(f"🚀 Autoscaler initialized")
        print(f"   Target: {TARGET_SERVICE}")
        print(f"   Image: {IMAGE_NAME}")
        print(f"   Consumer Group: {CONSUMER_GROUP}")
        print(f"   Replicas: {MIN_REPLICAS}-{MAX_REPLICAS}")
        print(f"   Thresholds: up>{SCALE_UP_THRESHOLD}, down<{SCALE_DOWN_THRESHOLD}")

    def _get_current_replicas(self) -> int:
        """Get current number of running/starting containers for target service."""
        try:
            # Count containers that are running, restarting, or just created (starting up)
            containers = self.docker_client.containers.list(
                all=True,  # Include all states
                filters={"status": "running"}
            )
            # Also get restarting containers
            restarting = self.docker_client.containers.list(
                all=True,
                filters={"status": "restarting"}
            )
            # And created (about to start)
            created = self.docker_client.containers.list(
                all=True,
                filters={"status": "created"}
            )
            
            all_active = list(containers) + list(restarting) + list(created)
            count = 0
            for c in all_active:
                # Match both 'shorts-renderer' and 'shorts-renderer_X' but not 'shorts-autoscaler'
                if TARGET_SERVICE in c.name and 'autoscaler' not in c.name:
                    count += 1
            return count
        except Exception as e:
            print(f"❌ Error getting replicas: {e}")
            return 1  # Assume at least 1 to prevent unnecessary scaling

    def get_consumer_lag(self) -> int:
        """Get total consumer lag for the monitored topics."""
        total_lag = 0
        try:
            consumer = KafkaConsumer(
                bootstrap_servers=KAFKA_BOOTSTRAP_SERVERS,
                group_id=f"{CONSUMER_GROUP}-monitor",
                enable_auto_commit=False,
            )
            
            for topic in MONITORED_TOPICS:
                try:
                    partitions = consumer.partitions_for_topic(topic)
                    if not partitions:
                        continue
                    
                    for partition in partitions:
                        tp = TopicPartition(topic, partition)
                        
                        # Get end offset (latest)
                        consumer.assign([tp])
                        consumer.seek_to_end(tp)
                        end_offset = consumer.position(tp)
                        
                        # Get committed offset
                        committed = consumer.committed(tp)
                        if committed is None:
                            committed = 0
                        
                        lag = max(0, end_offset - committed)
                        total_lag += lag
                        
                except Exception as e:
                    print(f"⚠️ Error checking lag for {topic}: {e}")
                    continue
            
            consumer.close()
            
        except Exception as e:
            print(f"❌ Kafka connection error: {e}")
            return 0
        
        return total_lag

    def refresh_image(self):
        """Pull the latest image and upgrade outdated containers."""
        if not AUTO_UPGRADE:
            return

        # Pull every 5 minutes or so
        if time.time() - self.last_pull_time < 300:
            return

        try:
            print(f"🔍 Checking for image updates: {IMAGE_NAME}")
            try:
                latest_image = self.docker_client.images.pull(IMAGE_NAME)
                latest_id = latest_image.id
            except Exception as pull_err:
                print(f"   ℹ️ Pull skipped/failed (likely local image): {pull_err}")
                latest_image = self.docker_client.images.get(IMAGE_NAME)
                latest_id = latest_image.id

            self.last_pull_time = time.time()

            containers = self.docker_client.containers.list(filters={"status": "running"})
            for c in containers:
                if TARGET_SERVICE in c.name and c.name != "shorts-autoscaler":
                    if c.image.id != latest_id:
                        print(f"🔄 Container {c.name} is outdated. Restarting with latest image...")
                        self._recreate_container(c, latest_id)
        except Exception as e:
            print(f"⚠️ Image refresh error: {e}")

    def _recreate_container(self, old_container, image_id):
        """Stop and recreate a container with the new image ID."""
        try:
            name = old_container.name
            env = old_container.attrs['Config']['Env']
            env_dict = {}
            for e in env:
                if '=' in e:
                    k, v = e.split('=', 1)
                    env_dict[k] = v
            
            old_container.stop(timeout=10)
            old_container.remove()

            # Fix volumes mapping
            volumes = {
                'science-news-shorts-automation_shorts-shared-data': {'bind': '/app/shared-data', 'mode': 'rw'},
                'science-news-shorts-automation_shorts-tokens': {'bind': '/app/tokens', 'mode': 'rw'}
            }
            
            self.docker_client.containers.run(
                image=image_id,
                detach=True,
                name=name,
                network="science-news-shorts-automation_default",
                environment=env_dict,
                volumes=volumes,
                restart_policy={"Name": "unless-stopped"}
            )
            print(f"   ✅ {name} recreated.")
        except Exception as e:
            print(f"❌ Failed to recreate {old_container.name}: {e}")

    def scale_service(self, desired_replicas: int):
        """Scale the target service to desired replicas."""
        if desired_replicas == self.current_replicas:
            return
        
        # Cooldown check
        if time.time() - self.last_scale_time < COOLDOWN_PERIOD:
            return
        
        # CRITICAL: Re-fetch current replica count right before scaling to avoid race conditions
        fresh_count = self._get_current_replicas()
        if fresh_count != self.current_replicas:
            print(f"   ⚠️ Stale count detected: cached={self.current_replicas}, fresh={fresh_count}. Updating...")
            self.current_replicas = fresh_count
            # Re-evaluate after updating
            if desired_replicas == self.current_replicas:
                return
        
        desired_replicas = max(MIN_REPLICAS, min(MAX_REPLICAS, desired_replicas))
        if desired_replicas == self.current_replicas:
            return
        
        direction = "⬆️ UP" if desired_replicas > self.current_replicas else "⬇️ DOWN"
        print(f"{direction}: Scaling from {self.current_replicas} to {desired_replicas}")
        
        try:
            if desired_replicas > self.current_replicas:
                containers_to_add = desired_replicas - self.current_replicas
                
                # Get env from any existing container if possible
                existing = self.docker_client.containers.list()
                env_dict = {}
                for c in existing:
                    if TARGET_SERVICE in c.name:
                        env_list = c.attrs['Config']['Env'] or []
                        for env_str in env_list:
                            if '=' in env_str:
                                k, v = env_str.split('=', 1)
                                env_dict[k] = v
                        break

                for i in range(containers_to_add):
                    # Find the next available index for naming
                    existing_names = [c.name for c in self.docker_client.containers.list(all=True)]
                    idx = 2
                    while f"{TARGET_SERVICE}_{idx}" in existing_names:
                        idx += 1
                    
                    new_name = f"{TARGET_SERVICE}_{idx}"
                    print(f"   Starting replica: {new_name}")
                    
                    self.docker_client.containers.run(
                        image=IMAGE_NAME,
                        detach=True,
                        name=new_name,
                        network="science-news-shorts-automation_default",
                        environment=env_dict,
                        volumes={
                            'science-news-shorts-automation_shorts-shared-data': {'bind': '/app/shared-data', 'mode': 'rw'},
                            'science-news-shorts-automation_shorts-tokens': {'bind': '/app/tokens', 'mode': 'rw'}
                        },
                        restart_policy={"Name": "unless-stopped"}
                    )
                
                self.current_replicas = desired_replicas
                self.last_scale_time = time.time()
                print(f"✅ Scaled UP to {desired_replicas} replicas")
            else:
                containers = [c for c in self.docker_client.containers.list() if TARGET_SERVICE in c.name]
                containers.sort(key=lambda c: c.name, reverse=True) # Remove replicas first
                
                containers_to_stop = containers[:(self.current_replicas - desired_replicas)]
                for container in containers_to_stop:
                    # Never stop the main one (the one created by docker-compose with fixed name or default index)
                    is_main = container.name == TARGET_SERVICE or \
                              container.name == f"{TARGET_SERVICE}-1" or \
                              container.name.endswith(f"_{TARGET_SERVICE}_1")
                    
                    if len(containers) > 1 and is_main:
                        continue

                    print(f"   Stopping container: {container.name}")
                    container.stop(timeout=10)
                    container.remove()
                
                self.current_replicas = desired_replicas
                self.last_scale_time = time.time()
                print(f"✅ Scaled DOWN to {desired_replicas} replicas")
                
        except Exception as e:
            print(f"❌ Scale error: {e}")

    def calculate_desired_replicas(self, lag: int) -> int:
        if lag > SCALE_UP_THRESHOLD * 2:
            return min(MAX_REPLICAS, self.current_replicas + 2)
        elif lag > SCALE_UP_THRESHOLD:
            return min(MAX_REPLICAS, self.current_replicas + 1)
        elif lag < SCALE_DOWN_THRESHOLD and self.current_replicas > MIN_REPLICAS:
            return max(MIN_REPLICAS, self.current_replicas - 1)
        return self.current_replicas

    def run(self):
        print(f"🔄 Starting autoscaler loop (every {CHECK_INTERVAL}s)")
        while True:
            try:
                self.refresh_image()
                lag = self.get_consumer_lag()
                self.current_replicas = self._get_current_replicas()
                
                print(f"📊 Lag: {lag} | Replicas: {self.current_replicas}")
                
                desired = self.calculate_desired_replicas(lag)
                self.scale_service(desired)
                
            except Exception as e:
                print(f"❌ Loop error: {e}")
            
            time.sleep(CHECK_INTERVAL)


if __name__ == "__main__":
    autoscaler = DockerAutoscaler()
    autoscaler.run()
