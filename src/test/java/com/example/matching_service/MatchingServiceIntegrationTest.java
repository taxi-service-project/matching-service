package com.example.matching_service;

import com.example.matching_service.service.MatchingService;
import com.example.matching_service.client.LocationServiceClient;
import com.example.matching_service.dto.MatchRequest;
import com.example.matching_service.dto.kafka.TripMatchedEvent;
import com.example.matching_service.kafka.MatchingKafkaProducer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


@Testcontainers // 1. Testcontainers 사용을 선언
@SpringBootTest
class MatchingServiceIntegrationTest {

    @Autowired
    private MatchingService matchingService;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @MockitoBean
    private LocationServiceClient locationServiceClient;

    @MockitoBean
    private MatchingKafkaProducer kafkaProducer;

    @Container
    private static final GenericContainer<?> redisContainer =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    private static void setRedisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redisContainer::getHost);
        registry.add("spring.data.redis.port", () -> redisContainer.getMappedPort(6379).toString());
    }

    @Test
    @DisplayName("실제 Redis와 연동하여 가장 가까운 기사가 매칭되고 Kafka 이벤트가 발행되어야 한다")
    void requestMatch_WithRealRedis_ShouldMatchClosestDriver() {
        // given
        String userId = "a1b2c3d4-user-uuid";
        String driverIdA = "a1b2c3d4-driverA-uuid";
        String driverIdC = "a1b2c3d4-driverC-uuid";

        MatchRequest request = new MatchRequest(userId, new MatchRequest.Location(127.0, 37.5), null);

        redisTemplate.opsForHash().put("driver_status:" + driverIdA, "isAvailable", "1");
        redisTemplate.opsForHash().put("driver_status:" + driverIdC, "isAvailable", "1");

        var driverA = new LocationServiceClient.NearbyDriver(driverIdA, 100.0);
        var driverC = new LocationServiceClient.NearbyDriver(driverIdC, 300.0);
        when(locationServiceClient.findNearbyDrivers(anyDouble(), anyDouble(), anyInt())).thenReturn(Flux.just(driverA, driverC));

        // when
        matchingService.requestMatch(request);

        // then
        ArgumentCaptor<TripMatchedEvent> eventCaptor = ArgumentCaptor.forClass(TripMatchedEvent.class);
        verify(kafkaProducer, timeout(2000)).sendTripMatchedEvent(eventCaptor.capture());

        TripMatchedEvent capturedEvent = eventCaptor.getValue();

        assertThat(capturedEvent.driverId()).isEqualTo(driverIdA);
        assertThat(capturedEvent.userId()).isEqualTo(userId);
    }
}