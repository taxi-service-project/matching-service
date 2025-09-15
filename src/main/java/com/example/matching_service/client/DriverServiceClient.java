package com.example.matching_service.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
@Slf4j
public class DriverServiceClient {
    private final WebClient webClient;

    public record InternalDriverInfo(Long id, String name, Double ratingAvg, VehicleInfo vehicle) {
        public record VehicleInfo(String licensePlate, String model) {}
    }

    public DriverServiceClient(WebClient.Builder builder,
                               @Value("${services.driver-service.url}") String serviceUrl) {
        log.info("Driver Service URL: {}", serviceUrl);
        this.webClient = builder.baseUrl(serviceUrl).build();
    }

    public Flux<InternalDriverInfo> getDriversInfo(List<Long> driverIds) {
        return Flux.fromIterable(driverIds)
                   .flatMap(this::getDriverInfo);
    }

    private Mono<InternalDriverInfo> getDriverInfo(Long driverId) {
        return webClient.get()
                        .uri("/internal/api/drivers/{driverId}", driverId)
                        .retrieve()
                        .bodyToMono(InternalDriverInfo.class);
    }
}