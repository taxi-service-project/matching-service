package com.example.matching_service.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

@Component
@Slf4j
public class LocationServiceClient {
    private final WebClient webClient;
    private final ReactiveCircuitBreaker circuitBreaker;

    public record NearbyDriver(String driverId, Double distance) {}

    public LocationServiceClient(WebClient.Builder builder,
                                 @Value("${services.location-service.url}") String serviceUrl,
                                 ReactiveCircuitBreakerFactory cbFactory) {
        log.info("Location Service URL: {}", serviceUrl);
        this.webClient = builder.baseUrl(serviceUrl).build();
        this.circuitBreaker = cbFactory.create("geospatial-service");
    }

    public Flux<NearbyDriver> findNearbyDrivers(double longitude, double latitude, int radiusKm) {
        Flux<NearbyDriver> apiCall = webClient.get()
                                              .uri(uriBuilder -> uriBuilder
                                                      .path("/api/locations/search")
                                                      .queryParam("longitude", longitude)
                                                      .queryParam("latitude", latitude)
                                                      .queryParam("radius", radiusKm)
                                                      .build())
                                              .retrieve()
                                              .bodyToFlux(NearbyDriver.class);

        return circuitBreaker.run(apiCall, throwable -> {
            log.warn("위치 서비스(Geospatial) 호출 실패 또는 서킷 오픈. coords: {},{}, radius: {}km. Error: {}",
                    longitude, latitude, radiusKm, throwable.getMessage());

            return Flux.empty();
        });
    }
}