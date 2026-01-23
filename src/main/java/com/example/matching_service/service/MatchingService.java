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
    private final ReactiveRedisTemplate<String, String> reactiveRedisTemplate; // 영속화용 레디스
    private final MatchingKafkaProducer kafkaProducer;

    private record DriverCandidate(String driverId, double distance) {}

    public MatchResponse requestMatch(String userId, MatchRequest request) {
        String matchRequestId = UUID.randomUUID().toString();
        String tripId = UUID.randomUUID().toString();
        log.info("매칭 요청 접수. Request ID: {}, Trip ID: {}", matchRequestId, tripId);

        processMatchingAsync(request, tripId, userId);

        return new MatchResponse("주변 기사를 검색중입니다.", matchRequestId);
    }

    private void processMatchingAsync(MatchRequest request, String tripId, String userId) {
        findBestDriver(request)
                .flatMap(bestDriver -> {
                    String key = "driver_status:" + bestDriver.driverId();
                    return reactiveRedisTemplate.opsForHash().put(key, "isAvailable", "0")
                                                .then(Mono.just(bestDriver));
                })
                .flatMap(bestDriver -> {
                    TripMatchedEvent event = new TripMatchedEvent(
                            tripId, userId, bestDriver.driverId(),
                            request.origin(), request.destination(), LocalDateTime.now()
                    );
                    return kafkaProducer.sendTripMatchedEvent(event)
                                        .doOnSuccess(v -> log.info("✅ 최적 기사 선정 및 Kafka 발행 완료. Trip ID: {}, Driver ID: {}", tripId, bestDriver.driverId()))
                                        .onErrorResume(error -> {
                                            log.error("❌ Kafka 전송 실패. 기사 상태 복구(Rollback) 시작. Driver ID: {}", bestDriver.driverId(), error);
                                            return releaseDriver(bestDriver.driverId())
                                                     .then(releaseLock(bestDriver.driverId()))
                                                     .then(Mono.error(error));
                                        })
                                        .then();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        null,
                        error -> log.error("❌ 매칭 비동기 처리 중 치명적 오류. Trip ID: {}", tripId, error),
                        () -> log.info("매칭 프로세스 종료. Trip ID: {}", tripId)
                );
    }

    public Mono<Boolean> releaseDriver(String driverId) {
        String key = "driver_status:" + driverId;

        return reactiveRedisTemplate.opsForHash().put(key, "isAvailable", "1")
                                    .doOnSuccess(v -> log.info("기사 상태 복구 완료: {}", driverId))
                                    .doOnError(e -> log.error("기사 상태 복구 실패: {}", driverId, e));
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
                                    .filterWhen(this::tryLockAndVerifyDriver)
                                    .next()
                                    .map(d -> new DriverCandidate(d.driverId(), d.distance()));
    }

    private Mono<Boolean> releaseLock(String driverId) {
        String lockKey = "matching_lock:" + driverId;
        return reactiveRedisTemplate.opsForValue().delete(lockKey);
    }

    private Mono<Boolean> tryLockAndVerifyDriver(LocationServiceClient.NearbyDriver driver) {
        String lockKey = "matching_lock:" + driver.driverId();

        // 우선 락 획득 시도 (동시성 방어)
        return reactiveRedisTemplate.opsForValue()
                                    .setIfAbsent(lockKey, "LOCKED", java.time.Duration.ofSeconds(10))
                                    .flatMap(locked -> {
                                        if (!locked) return Mono.just(false); // 락 획득 실패 시 즉시 탈락

                                        // 락 획득 성공 시, 실제 기사 상태가 '1(가능)'인지 확인 (영속성 확인)
                                        return isDriverAvailable(driver)
                                                .flatMap(available -> {
                                                    if (available) {
                                                        return Mono.just(true); // 상태도 '1'이면 최종 통과
                                                    } else {
                                                        // 락은 잡았지만 상태가 '0'이면, 락을 다시 풀어주고 탈락 처리
                                                        return releaseLock(driver.driverId()).thenReturn(false);
                                                    }
                                                });
                                    });
    }

    private Mono<Boolean> isDriverAvailable(LocationServiceClient.NearbyDriver driver) {
        String key = "driver_status:" + driver.driverId();
        return reactiveRedisTemplate.opsForHash().get(key, "isAvailable")
                                    .map(value -> "1".equals(value))
                                    .defaultIfEmpty(false);
    }
}