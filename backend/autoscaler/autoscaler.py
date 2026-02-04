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
MIN_REPLICAS = int(os.getenv("MIN_REPLICAS", "1"))
MAX_REPLICAS = int(os.getenv("MAX_REPLICAS", "5"))
SCALE_UP_THRESHOLD = int(os.getenv("SCALE_UP_THRESHOLD", "5"))
SCALE_DOWN_THRESHOLD = int(os.getenv("SCALE_DOWN_THRESHOLD", "2"))
CHECK_INTERVAL = int(os.getenv("CHECK_INTERVAL", "30"))
COOLDOWN_PERIOD = int(os.getenv("COOLDOWN_PERIOD", "60"))

# Topics to monitor
MONITORED_TOPICS = ["script-created", "assets-ready"]


class DockerAutoscaler:
    def __init__(self):
        self.docker_client = docker.from_env()
        self.last_scale_time = 0
        self.current_replicas = self._get_current_replicas()
        print(f"🚀 Autoscaler initialized")
        print(f"   Target: {TARGET_SERVICE}")
        print(f"   Consumer Group: {CONSUMER_GROUP}")
        print(f"   Replicas: {MIN_REPLICAS}-{MAX_REPLICAS}")
        print(f"   Thresholds: up>{SCALE_UP_THRESHOLD}, down<{SCALE_DOWN_THRESHOLD}")

    def _get_current_replicas(self) -> int:
        """Get current number of running containers for target service."""
        try:
            containers = self.docker_client.containers.list(
                filters={"name": TARGET_SERVICE, "status": "running"}
            )
            return len(containers)
        except Exception as e:
            print(f"❌ Error getting replicas: {e}")
            return 1

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

    def scale_service(self, desired_replicas: int):
        """Scale the target service to desired replicas using Docker SDK."""
        if desired_replicas == self.current_replicas:
            return
        
        # Check cooldown
        if time.time() - self.last_scale_time < COOLDOWN_PERIOD:
            print(f"⏳ Cooldown active, skipping scale")
            return
        
        # Clamp to min/max
        desired_replicas = max(MIN_REPLICAS, min(MAX_REPLICAS, desired_replicas))
        
        if desired_replicas == self.current_replicas:
            return
        
        direction = "⬆️ UP" if desired_replicas > self.current_replicas else "⬇️ DOWN"
        print(f"{direction}: Scaling {TARGET_SERVICE} from {self.current_replicas} to {desired_replicas}")
        
        try:
            if desired_replicas > self.current_replicas:
                # Scale UP: Start additional containers
                containers_to_add = desired_replicas - self.current_replicas
                
                # Get existing container config from running container
                existing_containers = self.docker_client.containers.list(
                    filters={"name": TARGET_SERVICE, "status": "running"}
                )
                
                if existing_containers:
                    template = existing_containers[0]
                    image = template.image.tags[0] if template.image.tags else template.image.id
                    
                    for i in range(containers_to_add):
                        new_name = f"{TARGET_SERVICE}_{self.current_replicas + i + 1}"
                        print(f"   Starting container: {new_name}")
                        
                        # Parse environment variables (list of "KEY=VALUE" strings to dict)
                        env_list = template.attrs['Config']['Env'] or []
                        env_dict = {}
                        for env_str in env_list:
                            if '=' in env_str:
                                key, value = env_str.split('=', 1)
                                env_dict[key] = value
                        
                        # Create new container with same config
                        self.docker_client.containers.run(
                            image=image,
                            detach=True,
                            name=new_name,
                            network="science-news-shorts-automation_default",
                            environment=env_dict,
                            volumes={
                                'science-news-shorts-automation_shorts-shared-data': {'bind': '/app/shared-data', 'mode': 'rw'}
                            },
                            restart_policy={"Name": "unless-stopped"}
                        )
                    
                    self.current_replicas = desired_replicas
                    self.last_scale_time = time.time()
                    print(f"✅ Scaled UP to {desired_replicas} replicas")
            else:
                # Scale DOWN: Stop excess containers
                containers = self.docker_client.containers.list(
                    filters={"name": TARGET_SERVICE, "status": "running"}
                )
                
                # Sort by name to keep the first ones
                containers.sort(key=lambda c: c.name)
                containers_to_stop = containers[desired_replicas:]
                
                for container in containers_to_stop:
                    print(f"   Stopping container: {container.name}")
                    container.stop(timeout=30)
                    container.remove()
                
                self.current_replicas = desired_replicas
                self.last_scale_time = time.time()
                print(f"✅ Scaled DOWN to {desired_replicas} replicas")
                
        except Exception as e:
            print(f"❌ Scale error: {e}")

    def calculate_desired_replicas(self, lag: int) -> int:
        """Calculate desired replicas based on lag."""
        if lag > SCALE_UP_THRESHOLD * 3:
            return min(MAX_REPLICAS, self.current_replicas + 2)
        elif lag > SCALE_UP_THRESHOLD:
            return min(MAX_REPLICAS, self.current_replicas + 1)
        elif lag < SCALE_DOWN_THRESHOLD and self.current_replicas > MIN_REPLICAS:
            return max(MIN_REPLICAS, self.current_replicas - 1)
        return self.current_replicas

    def run(self):
        """Main loop."""
        print(f"🔄 Starting autoscaler loop (every {CHECK_INTERVAL}s)")
        
        while True:
            try:
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
