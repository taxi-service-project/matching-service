package com.example.matching_service;

import com.example.matching_service.client.DriverServiceClient;
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
    private DriverServiceClient driverServiceClient;
    @Mock
    private RedisTemplate<String, String> redisTemplate;
    @Mock
    private MatchingKafkaProducer kafkaProducer;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;


    @Test
    @DisplayName("매칭 성공 시 최적 기사에 대한 Kafka 이벤트가 발행되어야 한다")
    void requestMatch_Success_ShouldSendKafkaEvent() {
        // given: 성공 시나리오 설정
        MatchRequest request = new MatchRequest(1L, new MatchRequest.Location(127.0, 37.5), null);
        var driverA = new LocationServiceClient.NearbyDriver(101L, 100.0);
        var driverC = new LocationServiceClient.NearbyDriver(103L, 300.0);
        when(locationServiceClient.findNearbyDrivers(anyDouble(), anyDouble(), anyInt())).thenReturn(Flux.just(driverA, driverC));
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("driver_status:101", "isAvailable")).thenReturn("1");
        when(hashOperations.get("driver_status:103", "isAvailable")).thenReturn("1");
        var infoA = new DriverServiceClient.InternalDriverInfo(101L, "A", 4.5, null);
        var infoC = new DriverServiceClient.InternalDriverInfo(103L, "C", 5.0, null);
        when(driverServiceClient.getDriversInfo(anyList())).thenReturn(Flux.just(infoA, infoC));
        /* 예상 승자: 기사 C (103L) */

        // when: 공개된 메서드인 requestMatch() 호출
        matchingService.requestMatch(request);

        // then: Kafka Producer가 올바른 이벤트와 함께 호출되었는지 검증
        ArgumentCaptor<TripMatchedEvent> eventCaptor = ArgumentCaptor.forClass(TripMatchedEvent.class);
        // 비동기 처리를 1초간 기다려줌
        verify(kafkaProducer, timeout(1000)).sendTripMatchedEvent(eventCaptor.capture());

        TripMatchedEvent capturedEvent = eventCaptor.getValue();
        assertThat(capturedEvent.userId()).isEqualTo(1L);
        assertThat(capturedEvent.driverId()).isEqualTo(103L); // 최종 승자 C
    }

    @Test
    @DisplayName("주변에 기사가 한 명도 없는 경우 Kafka 이벤트가 발행되지 않아야 한다")
    void requestMatch_Fail_WhenNoNearbyDrivers() {
        // given
        MatchRequest request = new MatchRequest(1L, new MatchRequest.Location(127.0, 37.5), null);
        when(locationServiceClient.findNearbyDrivers(anyDouble(), anyDouble(), anyInt()))
                .thenReturn(Flux.empty());

        // when
        matchingService.requestMatch(request);

        // then: Kafka Producer가 절대 호출되지 않았는지 검증
        // 비동기 처리를 감안해 약간의 대기 시간을 줌
        verify(kafkaProducer, after(1000).never()).sendTripMatchedEvent(any());
    }

    @Test
    @DisplayName("주변 기사가 모두 운행 불가능 상태일 경우 Kafka 이벤트가 발행되지 않아야 한다")
    void requestMatch_Fail_WhenAllDriversAreNotAvailable() {
        // given
        MatchRequest request = new MatchRequest(1L, new MatchRequest.Location(127.0, 37.5), null);
        var driverA = new LocationServiceClient.NearbyDriver(101L, 100.0);
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