package com.example.matching_service.service;

import com.example.matching_service.client.LocationServiceClient;
import com.example.matching_service.dto.MatchRequest;
import com.example.matching_service.dto.MatchResponse;
import com.example.matching_service.dto.kafka.TripMatchedEvent;
import com.example.matching_service.kafka.MatchingKafkaProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
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
    private final RedisTemplate<String, String> redisTemplate;
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
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        bestDriver -> {
                            log.info("최적 기사 선정 완료. Trip ID: {}, Driver ID: {}", tripId, bestDriver.driverId());
                            TripMatchedEvent event = new TripMatchedEvent(
                                    tripId, request.userId(), bestDriver.driverId(),
                                    request.origin(), request.destination(), LocalDateTime.now()
                            );
                            kafkaProducer.sendTripMatchedEvent(event);
                        },
                        error -> log.error("매칭 처리 중 오류 발생. Trip ID: {}", tripId, error),
                        () -> log.info("주변에 호출 가능한 기사가 없어 매칭에 실패했습니다. Trip ID: {}", tripId)
                );
    }

    private Mono<DriverCandidate> findBestDriver(MatchRequest request) {
        return findBestDriverInRadius(request, 1)
                .switchIfEmpty(findBestDriverInRadius(request, 2))
                .switchIfEmpty(findBestDriverInRadius(request, 3));
    }

    private Mono<DriverCandidate> findBestDriverInRadius(MatchRequest request, int radiusKm) {
        log.info("{}km 반경 내에서 기사 검색을 시작합니다.", radiusKm);
        return locationServiceClient.findNearbyDrivers(
                                            request.origin().longitude(), request.origin().latitude(), radiusKm)
                                    .filterWhen(this::isDriverAvailable)
                                    .reduce((driver1, driver2) ->
                                            driver1.distance() < driver2.distance() ? driver1 : driver2
                                    )
                                    .map(nearbyDriver -> new DriverCandidate(nearbyDriver.driverId(), nearbyDriver.distance()));
    }

    private Mono<Boolean> isDriverAvailable(LocationServiceClient.NearbyDriver driver) {
        return Mono.fromCallable(() -> {
            String key = "driver_status:" + driver.driverId();
            String value = (String) redisTemplate.opsForHash().get(key, "isAvailable");
            return "1".equals(value);
        }).subscribeOn(Schedulers.boundedElastic());
    }
}