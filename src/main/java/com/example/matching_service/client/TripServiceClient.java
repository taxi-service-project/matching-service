package com.example.matching_service.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class TripServiceClient {

    private final WebClient webClient;
    private final ReactiveCircuitBreaker circuitBreaker;

    public TripServiceClient(WebClient.Builder builder,
                             @Value("${services.trip-service.url}") String serviceUrl,
                             ReactiveCircuitBreakerFactory cbFactory) {
        log.info("Trip Service URL: {}", serviceUrl);
        this.webClient = builder.baseUrl(serviceUrl).build();
        this.circuitBreaker = cbFactory.create("trip-service");
    }

    public Mono<Boolean> isDriverOnTrip(String driverId) {
        Mono<Boolean> apiCall = webClient.get()
                                         .uri(uriBuilder -> uriBuilder
                                                 .path("/internal/drivers/{driverId}/in-progress")
                                                 .build(driverId))
                                         .retrieve()
                                         .bodyToMono(Boolean.class);

        return circuitBreaker.run(apiCall, throwable -> {
            log.warn("❌ 여정 서비스(Trip) 호출 실패 또는 서킷 오픈. DriverId: {}. Error: {}",
                    driverId, throwable.getMessage());

            // 확인이 불가능하면 일단 "운행 중(true)"이라고 가정하여 스케줄러가 함부로 기사 상태를 초기화하지 못하도록 방어함.
            return Mono.just(true);
        });
    }
}