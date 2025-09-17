package com.example.matching_service.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

@Component
@Slf4j
public class LocationServiceClient {
    private final WebClient webClient;

    public record NearbyDriver(String driverId, Double distance) {}

    public LocationServiceClient(WebClient.Builder builder,
                                 @Value("${services.location-service.url}") String serviceUrl) {
        log.info("Location Service URL: {}", serviceUrl);
        this.webClient = builder.baseUrl(serviceUrl).build();
    }

    public Flux<NearbyDriver> findNearbyDrivers(double longitude, double latitude, int radiusKm) {
        return webClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/api/locations/search")
                                .queryParam("longitude", longitude)
                                .queryParam("latitude", latitude)
                                .queryParam("radius", radiusKm)
                                .build())
                        .retrieve()
                        .bodyToFlux(NearbyDriver.class);
    }
}