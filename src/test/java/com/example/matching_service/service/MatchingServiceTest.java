package com.example.matching_service.service;

import com.example.matching_service.client.LocationServiceClient;
import com.example.matching_service.client.LocationServiceClient.NearbyDriver;
import com.example.matching_service.dto.MatchRequest;
import com.example.matching_service.dto.kafka.TripMatchedEvent;
import com.example.matching_service.kafka.MatchingKafkaProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveHashOperations;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MatchingServiceTest {

    private MatchingService matchingService;

    @Mock
    private LocationServiceClient locationServiceClient;

    @Mock
    private ReactiveRedisTemplate<String, String> reactiveRedisTemplate;

    @Mock
    private ReactiveHashOperations<String, Object, Object> reactiveHashOperations;

    @Mock
    private MatchingKafkaProducer kafkaProducer;

    @BeforeEach
    void setUp() {
        matchingService = new MatchingService(locationServiceClient, reactiveRedisTemplate, kafkaProducer);
    }

    @Test
    @DisplayName("1km 내에 기사가 있으면 즉시 매칭하고, Redis 상태를 업데이트한 뒤 이벤트를 발행한다")
    void requestMatch_Success_InFirstRadius() {
        // given
        String userId = "user-1";
        MatchRequest request = new MatchRequest(
                new MatchRequest.Location(127.0, 37.5),
                new MatchRequest.Location(127.1, 37.6)
        );

        NearbyDriver driverA = new NearbyDriver("driver-A", 0.5);

        given(locationServiceClient.findNearbyDrivers(anyDouble(), anyDouble(), anyInt()))
                .willReturn(Flux.empty());

        given(locationServiceClient.findNearbyDrivers(anyDouble(), anyDouble(), eq(1)))
                .willReturn(Flux.just(driverA));


        // Redis Setup
        given(reactiveRedisTemplate.opsForHash()).willReturn(reactiveHashOperations);
        given(reactiveHashOperations.get("driver_status:driver-A", "isAvailable"))
                .willReturn(Mono.just("1"));
        given(reactiveHashOperations.put(eq("driver_status:driver-A"), eq("isAvailable"), eq("0")))
                .willReturn(Mono.just(true));

        // Kafka Setup
        given(kafkaProducer.sendTripMatchedEvent(any(TripMatchedEvent.class)))
                .willReturn(Mono.empty());

        // when
        matchingService.requestMatch(userId, request);

        // then
        ArgumentCaptor<TripMatchedEvent> captor = ArgumentCaptor.forClass(TripMatchedEvent.class);
        verify(kafkaProducer, timeout(1000)).sendTripMatchedEvent(captor.capture());

        assertThat(captor.getValue().driverId()).isEqualTo("driver-A");
        verify(reactiveHashOperations).put("driver_status:driver-A", "isAvailable", "0");
    }

    @Test
    @DisplayName("1km 내에 기사가 없고 2km 내에 기사가 존재하면 2km 검색 결과로 매칭된다 (확장 검색 검증)")
    void requestMatch_ExpandRadius_Success() {
        // given
        String userId = "user-1";
        MatchRequest request = new MatchRequest(
                new MatchRequest.Location(127.0, 37.5),
                new MatchRequest.Location(127.1, 37.6)
        );

        NearbyDriver driverB = new NearbyDriver("driver-B", 1.8);

        given(locationServiceClient.findNearbyDrivers(anyDouble(), anyDouble(), anyInt()))
                .willReturn(Flux.empty());

        given(locationServiceClient.findNearbyDrivers(anyDouble(), anyDouble(), eq(2)))
                .willReturn(Flux.just(driverB));

        // Redis Setup
        given(reactiveRedisTemplate.opsForHash()).willReturn(reactiveHashOperations);
        given(reactiveHashOperations.get("driver_status:driver-B", "isAvailable"))
                .willReturn(Mono.just("1"));
        given(reactiveHashOperations.put(anyString(), anyString(), anyString()))
                .willReturn(Mono.just(true));

        // Kafka Setup
        given(kafkaProducer.sendTripMatchedEvent(any())).willReturn(Mono.empty());

        // when
        matchingService.requestMatch(userId, request);

        // then
        ArgumentCaptor<TripMatchedEvent> captor = ArgumentCaptor.forClass(TripMatchedEvent.class);
        verify(kafkaProducer, timeout(1000)).sendTripMatchedEvent(captor.capture());

        assertThat(captor.getValue().driverId()).isEqualTo("driver-B");
    }
}