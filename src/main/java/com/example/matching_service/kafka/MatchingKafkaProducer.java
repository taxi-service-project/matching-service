package com.example.matching_service.kafka;

import com.example.matching_service.dto.kafka.TripMatchedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class MatchingKafkaProducer {

    private static final String TOPIC = "matching_events";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public Mono<Void> sendTripMatchedEvent(TripMatchedEvent event) {
        return Mono.fromFuture(() -> kafkaTemplate.send(TOPIC, event.tripId(), event))
                   .doOnSuccess(result -> {
                       log.info("✅ 배차 완료 이벤트 발행 성공. Topic: {}, Partition: {}, Offset: {}, TripId: {}",
                               TOPIC,
                               result.getRecordMetadata().partition(),
                               result.getRecordMetadata().offset(),
                               event.tripId());
                   })
                   .doOnError(ex -> {
                       log.error("❌ 배차 완료 이벤트 발행 실패! TripId: {}, DriverId: {}, Error: {}",
                               event.tripId(), event.driverId(), ex.getMessage(), ex);
                   })
                   .then();
    }
}