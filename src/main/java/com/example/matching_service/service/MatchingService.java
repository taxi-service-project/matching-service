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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

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

    private Flux<DriverCandidate> findBestDriver(MatchRequest request) {
        return locationServiceClient.findNearbyDrivers(
                                            request.origin().longitude(), request.origin().latitude(), 5)
                                    .filterWhen(this::isDriverAvailable) // ❗️ this::isDriverAvailable로 변경
                                    .collectList()
                                    .flatMapMany(nearbyDrivers -> {
                                        if (nearbyDrivers.isEmpty()) return Flux.empty();
                                        List<Long> driverIds = nearbyDrivers.stream().map(LocationServiceClient.NearbyDriver::driverId).toList();
                                        return driverServiceClient.getDriversInfo(driverIds)
                                                                  .map(driverInfo -> {
                                                                      double distance = nearbyDrivers.stream()
                                                                                                     .filter(d -> d.driverId().equals(driverInfo.id()))
                                                                                                     .findFirst()
                                                                                                     .map(LocationServiceClient.NearbyDriver::distance)
                                                                                                     .orElse(Double.MAX_VALUE);
                                                                      return new DriverCandidate(driverInfo.id(), distance, driverInfo.ratingAvg());
                                                                  });
                                    })
                                    .sort(Comparator.comparingDouble((DriverCandidate candidate) -> calculateScore(candidate.distance(), candidate.ratingAvg())).reversed())
                                    .take(1);
    }

    private Mono<Boolean> isDriverAvailable(LocationServiceClient.NearbyDriver driver) {
        // blocking I/O 작업을 별도 스레드에서 실행하도록 하여 메인 스레드를 막지 않음
        return Mono.fromCallable(() -> {
            String key = "driver_status:" + driver.driverId();
            String value = (String) redisTemplate.opsForHash().get(key, "isAvailable");
            return "1".equals(value);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private double calculateScore(double distance, double rating) {
        if (distance == 0) return rating * 10; // 0으로 나누는 경우 방지
        return (1.0 / distance) * 100 + (rating * 10);
    }
}