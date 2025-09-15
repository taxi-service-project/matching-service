package com.example.matching_service.service;

import com.example.matching_service.client.DriverServiceClient;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class MatchingService {

    private final LocationServiceClient locationServiceClient;
    private final DriverServiceClient driverServiceClient;
    private final RedisTemplate<String, String> redisTemplate;
    private final MatchingKafkaProducer kafkaProducer;

    private record DriverCandidate(Long driverId, double distance, double ratingAvg) {}

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
        return locationServiceClient.findNearbyDrivers(
                                            request.origin().longitude(), request.origin().latitude(), 5)
                                    .filterWhen(this::isDriverAvailable)
                                    .collectList()
                                    .flatMap(nearbyDrivers -> {
                                        if (nearbyDrivers.isEmpty()) {
                                            return Mono.empty();
                                        }

                                        // 1. 빠른 조회를 위해 List를 Map으로 변환 (조회 성능 O(N) -> O(1))
                                        Map<Long, Double> distanceMap = nearbyDrivers.stream()
                                                                                     .collect(Collectors.toMap(
                                                                                             LocationServiceClient.NearbyDriver::driverId,
                                                                                             LocationServiceClient.NearbyDriver::distance
                                                                                     ));

                                        List<Long> driverIds = nearbyDrivers.stream().map(LocationServiceClient.NearbyDriver::driverId).toList();

                                        // 2. 기사 상세 정보 조회 후, 최고점 후보 1명만 찾는 reduce 사용
                                        return driverServiceClient.getDriversInfo(driverIds)
                                                                  .map(driverInfo -> {
                                                                      double distance = distanceMap.getOrDefault(driverInfo.id(), Double.MAX_VALUE);
                                                                      return new DriverCandidate(driverInfo.id(), distance, driverInfo.ratingAvg());
                                                                  })
                                                                  // 3. 전체 정렬 대신, 최고점 후보만 갱신하며 최종 1명을 찾음 (메모리 효율적)
                                                                  .reduce((candidate1, candidate2) ->
                                                                          calculateScore(candidate1.distance, candidate1.ratingAvg) >
                                                                                  calculateScore(candidate2.distance, candidate2.ratingAvg)
                                                                                  ? candidate1 : candidate2
                                                                  );
                                    });
    }

    private Mono<Boolean> isDriverAvailable(LocationServiceClient.NearbyDriver driver) {
        return Mono.fromCallable(() -> {
            String key = "driver_status:" + driver.driverId();
            String value = (String) redisTemplate.opsForHash().get(key, "isAvailable");
            return "1".equals(value);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private double calculateScore(double distanceKm, double rating) {
        final double MIN_DISTANCE_KM = 0.01;

        double effectiveDistance = Math.max(distanceKm, MIN_DISTANCE_KM);

        return (1.0 / effectiveDistance) * 100 + (rating * 10);
    }
}