package com.example.matching_service.kafka;

import com.example.matching_service.dto.kafka.TripMatchedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class MatchingKafkaProducer {

    private static final String TOPIC = "matching_events";
    private final KafkaTemplate<String, TripMatchedEvent> kafkaTemplate;

    public void sendTripMatchedEvent(TripMatchedEvent event) {
        log.info("배차 완료 이벤트 발행 -> topic: {}, tripId: {}, driverId: {}", TOPIC, event.tripId(), event.driverId());
        kafkaTemplate.send(TOPIC, event);
    }
}