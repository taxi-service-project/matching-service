package com.example.matching_service;

import com.example.matching_service.client.LocationServiceClient;
import com.example.matching_service.dto.MatchRequest;
import com.example.matching_service.dto.kafka.TripMatchedEvent;
import com.example.matching_service.kafka.MatchingKafkaProducer;
import com.example.matching_service.service.MatchingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveHashOperations;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MatchingServiceTest {

    @InjectMocks
    private MatchingService matchingService;

    @Mock
    private LocationServiceClient locationServiceClient;

    @Mock
    private ReactiveRedisTemplate<String, String> reactiveRedisTemplate;

    @Mock
    private ReactiveHashOperations<String, Object, Object> reactiveHashOperations;

    @Mock
    private MatchingKafkaProducer kafkaProducer;

    @Test
    @DisplayName("매칭 성공 시 가장 가까운 기사에 대한 Kafka 이벤트가 발행되어야 한다")
    void requestMatch_Success_ShouldSendKafkaEventForClosestDriver() {
        // given:
        String userId = "user-uuid-123";
        String driverIdA = "driver-uuid-A";
        String driverIdC = "driver-uuid-C";

        MatchRequest request = new MatchRequest(
                new MatchRequest.Location(127.0, 37.5),
                new MatchRequest.Location(127.1, 37.6)
        );

        var driverA = new LocationServiceClient.NearbyDriver(driverIdA, 100.0); // 100m
        var driverC = new LocationServiceClient.NearbyDriver(driverIdC, 300.0); // 300m

        when(locationServiceClient.findNearbyDrivers(anyDouble(), anyDouble(), anyInt()))
                .thenReturn(Flux.just(driverA, driverC));
        when(reactiveRedisTemplate.opsForHash()).thenReturn(reactiveHashOperations);
        when(reactiveHashOperations.get("driver_status:" + driverIdA, "isAvailable"))
                .thenReturn(Mono.just("1"));
        when(kafkaProducer.sendTripMatchedEvent(any(TripMatchedEvent.class)))
                .thenReturn(Mono.empty());

        // when
        matchingService.requestMatch(userId, request);

        // then
        ArgumentCaptor<TripMatchedEvent> eventCaptor = ArgumentCaptor.forClass(TripMatchedEvent.class);
        verify(kafkaProducer, timeout(2000)).sendTripMatchedEvent(eventCaptor.capture());

        TripMatchedEvent capturedEvent = eventCaptor.getValue();
        assertThat(capturedEvent.userId()).isEqualTo(userId);
        assertThat(capturedEvent.driverId()).isEqualTo(driverIdA); // 100m 기사가 선택됨
    }

    @Test
    @DisplayName("주변에 기사가 한 명도 없는 경우 Kafka 이벤트가 발행되지 않아야 한다")
    void requestMatch_Fail_WhenNoNearbyDrivers() {
        // given
        String userId = "user-uuid-123";
        MatchRequest request = new MatchRequest(
                new MatchRequest.Location(127.0, 37.5),
                new MatchRequest.Location(127.1, 37.6)
        );

        when(locationServiceClient.findNearbyDrivers(anyDouble(), anyDouble(), anyInt()))
                .thenReturn(Flux.empty()); // 기사 없음

        // when
        matchingService.requestMatch(userId, request);

        // then
        verify(kafkaProducer, after(1000).never()).sendTripMatchedEvent(any());
    }

    @Test
    @DisplayName("주변 기사가 모두 운행 불가능 상태일 경우 Kafka 이벤트가 발행되지 않아야 한다")
    void requestMatch_Fail_WhenAllDriversAreNotAvailable() {
        // given
        String userId = "user-uuid-123";
        MatchRequest request = new MatchRequest(
                new MatchRequest.Location(127.0, 37.5),
                new MatchRequest.Location(127.1, 37.6)
        );

        var driverA = new LocationServiceClient.NearbyDriver("driver-A", 100.0);

        when(locationServiceClient.findNearbyDrivers(anyDouble(), anyDouble(), anyInt()))
                .thenReturn(Flux.just(driverA));

        when(reactiveRedisTemplate.opsForHash()).thenReturn(reactiveHashOperations);

        when(reactiveHashOperations.get(anyString(), anyString()))
                .thenReturn(Mono.just("0"));

        // when
        matchingService.requestMatch(userId, request);

        // then
        verify(kafkaProducer, after(1000).never()).sendTripMatchedEvent(any());
    }
}