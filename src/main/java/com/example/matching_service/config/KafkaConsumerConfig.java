package com.example.matching_service.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@Slf4j
@RequiredArgsConstructor
public class KafkaConsumerConfig {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Bean
    public DefaultErrorHandler errorHandler() {
        // 2초 뒤 재시도 -> 4초 -> 8초 ... (최대 30초 동안 시도)
        ExponentialBackOff backOff = new ExponentialBackOff(2000L, 2.0);
        backOff.setMaxElapsedTime(30000L); // 최대 30초까지만 버팀

        DefaultErrorHandler errorHandler = new DefaultErrorHandler((record, exception) -> {
            // [최후의 수단] 재시도 30초 다 했는데도 안 되면? 나중에 스케줄러가 처리
            log.error("❌ Kafka 최종 실패 (재시도 초과). 스케줄러 보정 대상입니다. Topic: {}, Value: {}, Error: {}",
                    record.topic(), record.value(), exception.getMessage());
        }, backOff);

        // 치명적인 코드 에러는 바로 포기 (재시도 X)
        errorHandler.addNotRetryableExceptions(
                MessageConversionException.class,
                NullPointerException.class
        );

        return errorHandler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory,
            DefaultErrorHandler errorHandler) {

        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.setConcurrency(3);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setObservationEnabled(true);

        return factory;
    }

}