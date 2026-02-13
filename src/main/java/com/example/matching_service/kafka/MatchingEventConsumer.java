package com.example.matching_service.kafka;

import com.example.matching_service.kafka.dto.TripCanceledEvent;
import com.example.matching_service.kafka.dto.TripCompletedEvent;
import com.example.matching_service.service.MatchingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@Slf4j
@RequiredArgsConstructor
@KafkaListener(topics = "trip_events", groupId = "matching-service-group")
public class MatchingEventConsumer {

    private final MatchingService matchingService;

    @KafkaHandler
    public void handleTripCompleted(TripCompletedEvent event) {
        log.info("운행 종료 이벤트 수신. 기사({}) 상태를 '대기 중'으로 복구합니다.", event.driverId());
        try {
            matchingService.releaseDriver(event.driverId())
                           .block(Duration.ofSeconds(2));
            log.info("기사({}) 상태 복구 완료", event.driverId());

        } catch (Exception e) {
            log.error("기사 상태 복구 중 오류 발생 (재시도 예정): {}", event.driverId(), e);
            throw new RuntimeException("Redis 복구 실패로 인한 재시도 유도", e);
        }
    }

    @KafkaHandler
    public void handleTripCanceled(TripCanceledEvent event) {
        log.info("여정 취소 이벤트 수신. 기사({}) 상태를 '대기 중'으로 복구합니다.", event.driverId());
        try {
            matchingService.releaseDriver(event.driverId())
                           .block(Duration.ofSeconds(2));
            log.info("기사({}) 상태 복구 완료", event.driverId());

        } catch (Exception e) {
            log.error("기사 상태 복구 중 오류 발생 (재시도 예정): {}", event.driverId(), e);
            throw new RuntimeException("Redis 복구 실패로 인한 재시도 유도", e);
        }
    }

    @KafkaHandler(isDefault = true)
    public void handleUnknown(Object event) {
        log.warn("알 수 없는 메시지입니다.");
    }
}