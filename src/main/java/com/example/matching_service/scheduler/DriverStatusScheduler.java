package com.example.matching_service.scheduler;

import com.example.matching_service.client.TripServiceClient;
import com.example.matching_service.service.MatchingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@Slf4j
@RequiredArgsConstructor
public class DriverStatusScheduler {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final MatchingService matchingService;
    private final TripServiceClient tripServiceClient;

    // 1분마다 실행
    @Scheduled(fixedDelay = 60000)
    @SchedulerLock(name = "DriverStatusScheduler_syncDriverStatus", lockAtLeastFor = "PT30S", lockAtMostFor = "PT50S")
    public void syncDriverStatus() {
        log.info("🧹 [Scheduler] 기사 상태 정합성 검사 시작 (Zombie Cleaner)...");

        redisTemplate.scan(ScanOptions.scanOptions().match("driver_status:*").count(1000).build())
                     .flatMap(key ->
                             redisTemplate.opsForHash().get(key, "isAvailable")
                                          .filter(status -> "0".equals(status)) // '0'(운행중)인 녀석들만 검사 대상
                                          .flatMap(status -> {
                                              String driverId = key.replace("driver_status:", "");
                                              return checkAndFixZombieDriver(driverId);
                                          })
                     )
                     .subscribe(
                             null,
                             error -> log.error("❌ [Scheduler] 스케줄러 실행 중 에러 발생", error),
                             () -> log.info("✅ [Scheduler] 기사 상태 정합성 검사 완료")
                     );
    }

    private Mono<Void> checkAndFixZombieDriver(String driverId) {
        return tripServiceClient.isDriverOnTrip(driverId)
                                .flatMap(isActuallyOnTrip -> {
                                    // 운행 중 아니면
                                    if (!isActuallyOnTrip) {
                                        log.warn("🧟 [Zombie Detected] 기사({})는 Redis상 운행 중이나, 실제로는 운행 종료 상태입니다. 강제 복구합니다.", driverId);
                                        // Redis 상태를 '1'(대기 중)로 강제 변경
                                        return matchingService.releaseDriver(driverId).then();
                                    }
                                    // 운행 중이면
                                    return Mono.empty();
                                });
    }
}