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
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MatchingServiceTest {

    @InjectMocks
    private MatchingService matchingService;

    @Mock
    private LocationServiceClient locationServiceClient;
    @Mock
    private RedisTemplate<String, String> redisTemplate;
    @Mock
    private MatchingKafkaProducer kafkaProducer;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;


    @Test
    @DisplayName("매칭 성공 시 가장 가까운 기사에 대한 Kafka 이벤트가 발행되어야 한다")
    void requestMatch_Success_ShouldSendKafkaEventForClosestDriver() {
        // given:
        String userId = "a1b2c3d4-e5f6-7890-1234-567890user";
        String driverIdA = "a1b2c3d4-e5f6-7890-1234-567890drvA";
        String driverIdC = "a1b2c3d4-e5f6-7890-1234-567890drvC";

        MatchRequest request = new MatchRequest(userId, new MatchRequest.Location(127.0, 37.5), null);

        var driverA = new LocationServiceClient.NearbyDriver(driverIdA, 100.0); // 100m (더 가까움)
        var driverC = new LocationServiceClient.NearbyDriver(driverIdC, 300.0); // 300m

        when(locationServiceClient.findNearbyDrivers(anyDouble(), anyDouble(), anyInt())).thenReturn(Flux.just(driverA, driverC));

        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("driver_status:" + driverIdA, "isAvailable")).thenReturn("1");
        when(hashOperations.get("driver_status:" + driverIdC, "isAvailable")).thenReturn("1");

        // when
        matchingService.requestMatch(request);

        // then
        ArgumentCaptor<TripMatchedEvent> eventCaptor = ArgumentCaptor.forClass(TripMatchedEvent.class);
        verify(kafkaProducer, timeout(1000)).sendTripMatchedEvent(eventCaptor.capture());

        TripMatchedEvent capturedEvent = eventCaptor.getValue();

        assertThat(capturedEvent.userId()).isEqualTo(userId);
        assertThat(capturedEvent.driverId()).isEqualTo(driverIdA); // 가장 가까운 기사는 A
    }

    @Test
    @DisplayName("주변에 기사가 한 명도 없는 경우 Kafka 이벤트가 발행되지 않아야 한다")
    void requestMatch_Fail_WhenNoNearbyDrivers() {
        // given
        MatchRequest request = new MatchRequest("1L", new MatchRequest.Location(127.0, 37.5), null);
        when(locationServiceClient.findNearbyDrivers(anyDouble(), anyDouble(), anyInt()))
                .thenReturn(Flux.empty());

        // when
        matchingService.requestMatch(request);

        // then: Kafka Producer가 절대 호출되지 않았는지 검증
        verify(kafkaProducer, after(1000).never()).sendTripMatchedEvent(any());
    }

    @Test
    @DisplayName("주변 기사가 모두 운행 불가능 상태일 경우 Kafka 이벤트가 발행되지 않아야 한다")
    void requestMatch_Fail_WhenAllDriversAreNotAvailable() {
        // given
        MatchRequest request = new MatchRequest("1L", new MatchRequest.Location(127.0, 37.5), null);
        var driverA = new LocationServiceClient.NearbyDriver("101L", 100.0);
        when(locationServiceClient.findNearbyDrivers(anyDouble(), anyDouble(), anyInt()))
                .thenReturn(Flux.just(driverA));

        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get(anyString(), anyString())).thenReturn("0"); // 모두 '운행 불가능'

        // when
        matchingService.requestMatch(request);

        // then
        verify(kafkaProducer, after(1000).never()).sendTripMatchedEvent(any());
    }
}