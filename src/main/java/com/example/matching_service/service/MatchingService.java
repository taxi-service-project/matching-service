package com.example.matching_service.service;

import com.example.matching_service.client.LocationServiceClient;
import com.example.matching_service.dto.MatchRequest;
import com.example.matching_service.dto.MatchResponse;
import com.example.matching_service.dto.kafka.TripMatchedEvent;
import com.example.matching_service.kafka.MatchingKafkaProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class MatchingService {

    private final LocationServiceClient locationServiceClient;
    private final ReactiveRedisTemplate<String, String> reactiveRedisTemplate;
    private final MatchingKafkaProducer kafkaProducer;

    private record DriverCandidate(String driverId, double distance) {}

    public MatchResponse requestMatch(MatchRequest request) {
        String matchRequestId = UUID.randomUUID().toString();
        String tripId = UUID.randomUUID().toString();
        log.info("매칭 요청 접수. Request ID: {}, Trip ID: {}", matchRequestId, tripId);

        processMatchingAsync(request, tripId);

        return new MatchResponse("주변 기사를 검색중입니다.", matchRequestId);
    }

    private void processMatchingAsync(MatchRequest request, String tripId) {
        findBestDriver(request)
                .flatMap(bestDriver -> {
                    TripMatchedEvent event = new TripMatchedEvent(
                            tripId, request.userId(), bestDriver.driverId(),
                            request.origin(), request.destination(), LocalDateTime.now()
                    );
                    return kafkaProducer.sendTripMatchedEvent(event)
                                        .doOnSuccess(v -> log.info("최적 기사 선정 및 이벤트 발행 최종 완료. Trip ID: {}", tripId))
                                        .then();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        null,
                        error -> log.error("❌ 매칭 비동기 처리 중 치명적 오류. Trip ID: {}", tripId, error),
                        () -> log.info("매칭 프로세스 종료. Trip ID: {}", tripId)
                );
    }

    private Mono<DriverCandidate> findBestDriver(MatchRequest request) {
        // 1km -> 2km -> 3km 순차 확장 검색
        return findBestDriverInRadius(request, 1)
                .switchIfEmpty(findBestDriverInRadius(request, 2))
                .switchIfEmpty(findBestDriverInRadius(request, 3))
                // 3km까지 다 뒤져도 없으면?
                .doOnSuccess(candidate -> {
                    if (candidate == null) log.info("반경 3km 내 배차 가능 기사 없음.");
                });
    }

    private Mono<DriverCandidate> findBestDriverInRadius(MatchRequest request, int radiusKm) {
        return locationServiceClient.findNearbyDrivers(
                                            request.origin().longitude(), request.origin().latitude(), radiusKm)
                                    .filterWhen(this::isDriverAvailable)
                                    .next()
                                    .map(d -> new DriverCandidate(d.driverId(), d.distance()));
    }

    private Mono<Boolean> isDriverAvailable(LocationServiceClient.NearbyDriver driver) {
        String key = "driver_status:" + driver.driverId();
        return reactiveRedisTemplate.opsForHash().get(key, "isAvailable")
                                    .map(value -> "1".equals(value))
                                    .defaultIfEmpty(false); // 값이 없으면 불가능으로 처리
    }
}