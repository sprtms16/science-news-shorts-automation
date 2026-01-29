package com.sciencepixel.config

import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.*

@Configuration
class KafkaConfig {

    @Value("\${spring.kafka.bootstrap-servers:kafka:29092}")
    private lateinit var bootstrapServers: String


    companion object {
        const val TOPIC_RSS_NEW_ITEM = "rss-new-item"
        const val TOPIC_SCRIPT_CREATED = "script-created"
        const val TOPIC_AUDIO_CREATED = "audio-created"
        const val TOPIC_ASSETS_READY = "assets-ready"
        const val TOPIC_VIDEO_READY = "video-ready"
        
        const val TOPIC_VIDEO_CREATED = "video-created" // Legacy support or final event
        const val TOPIC_VIDEO_UPLOADED = "video-uploaded"
        const val TOPIC_UPLOAD_FAILED = "upload-failed"
        const val TOPIC_REGENERATION_REQUESTED = "regeneration-requested"
        const val TOPIC_DLQ = "dead-letter-queue"
        
        const val GROUP_MAIN = "shorts-main-group"
        const val GROUP_UPLOAD = "shorts-upload-group"
        const val GROUP_RETRY = "retry-consumer-group"
        const val GROUP_REGEN = "regen-consumer-group"
    }

    // ==================== Topics ====================
    
    @Bean
    fun rssNewItemTopic(): NewTopic = TopicBuilder.name(TOPIC_RSS_NEW_ITEM).partitions(1).replicas(1).build()

    @Bean
    fun scriptCreatedTopic(): NewTopic = TopicBuilder.name(TOPIC_SCRIPT_CREATED).partitions(1).replicas(1).build()

    @Bean
    fun audioCreatedTopic(): NewTopic = TopicBuilder.name(TOPIC_AUDIO_CREATED).partitions(1).replicas(1).build()

    @Bean
    fun videoReadyTopic(): NewTopic = TopicBuilder.name(TOPIC_VIDEO_READY).partitions(1).replicas(1).build()

    @Bean
    fun videoCreatedTopic(): NewTopic = TopicBuilder
        .name(TOPIC_VIDEO_CREATED)
        .partitions(1)
        .replicas(1)
        .build()

    @Bean
    fun videoUploadedTopic(): NewTopic = TopicBuilder
        .name(TOPIC_VIDEO_UPLOADED)
        .partitions(1)
        .replicas(1)
        .build()

    @Bean
    fun uploadFailedTopic(): NewTopic = TopicBuilder
        .name(TOPIC_UPLOAD_FAILED)
        .partitions(1)
        .replicas(1)
        .build()

    @Bean
    fun regenerationRequestedTopic(): NewTopic = TopicBuilder
        .name(TOPIC_REGENERATION_REQUESTED)
        .partitions(1)
        .replicas(1)
        .build()

    @Bean
    fun dlqTopic(): NewTopic = TopicBuilder
        .name(TOPIC_DLQ)
        .partitions(1)
        .replicas(1)
        .build()

    // ==================== Producer (String 직렬화) ====================

    @Bean
    fun producerFactory(): ProducerFactory<String, String> {
        val configProps = mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java
        )
        return DefaultKafkaProducerFactory(configProps)
    }

    @Bean
    fun kafkaTemplate(): KafkaTemplate<String, String> = KafkaTemplate(producerFactory())

    // ==================== Consumer (String 역직렬화) ====================

    @Bean
    fun consumerFactory(): ConsumerFactory<String, String> {
        val configProps = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "latest"  // 이전 잘못된 메시지 건너뛰기
        )
        return DefaultKafkaConsumerFactory(configProps)
    }

    @Bean
    fun kafkaListenerContainerFactory(): ConcurrentKafkaListenerContainerFactory<String, String> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, String>()
        factory.consumerFactory = consumerFactory()
        factory.setConcurrency(1)
        return factory
    }
}
