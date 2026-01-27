package com.example.matching_service.service;

import com.example.matching_service.client.LocationServiceClient;
import com.example.matching_service.client.LocationServiceClient.NearbyDriver;
import com.example.matching_service.dto.MatchRequest;
import com.example.matching_service.entity.MatchingOutbox;
import com.example.matching_service.repository.MatchingOutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveHashOperations;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MatchingServiceTest {

    private MatchingService matchingService;

    @Mock private LocationServiceClient locationServiceClient;
    @Mock private ReactiveRedisTemplate<String, String> reactiveRedisTemplate;
    @Mock private MatchingOutboxRepository outboxRepository;

    @Mock private ReactiveValueOperations<String, String> valueOps;
    @Mock private ReactiveHashOperations<String, Object, Object> hashOps;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        lenient().when(reactiveRedisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(reactiveRedisTemplate.opsForHash()).thenReturn(hashOps);

        matchingService = new MatchingService(
                locationServiceClient,
                reactiveRedisTemplate,
                outboxRepository,
                objectMapper
        );
    }

    @Test
    @DisplayName("정상 흐름: 락 획득 -> 상태 확인 -> 상태 변경 -> Outbox 저장 -> 성공 응답")
    void requestMatch_Success() {
        // given
        String userId = "user-1";
        MatchRequest request = new MatchRequest(
                new MatchRequest.Location(127.0, 37.5),
                new MatchRequest.Location(127.1, 37.6)
        );
        NearbyDriver driver = new NearbyDriver("driver-A", 0.5);

        lenient().when(locationServiceClient.findNearbyDrivers(anyDouble(), anyDouble(), anyInt()))
                 .thenReturn(Flux.empty());

        given(locationServiceClient.findNearbyDrivers(anyDouble(), anyDouble(), eq(1)))
                .willReturn(Flux.just(driver));

        // Redis & DB Mocking
        given(valueOps.setIfAbsent(eq("matching_lock:driver-A"), eq("LOCKED"), any(Duration.class)))
                .willReturn(Mono.just(true));

        given(hashOps.get("driver_status:driver-A", "isAvailable"))
                .willReturn(Mono.just("1"));

        given(hashOps.put("driver_status:driver-A", "isAvailable", "0"))
                .willReturn(Mono.just(true));

        given(outboxRepository.save(any(MatchingOutbox.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        // when & then
        StepVerifier.create(matchingService.requestMatch(userId, request))
                    .assertNext(response -> {
                        assertThat(response.message()).isEqualTo("매칭 성공!");
                        assertThat(response.matchRequestId()).isNotNull();
                    })
                    .verifyComplete();

        verify(outboxRepository, times(1)).save(any(MatchingOutbox.class));
    }

    @Test
    @DisplayName("롤백 테스트: DB 저장 실패 시 -> 기사 상태 원복(1) 및 락 해제 후 에러 반환")
    void requestMatch_Rollback_WhenDbFails() {
        // given
        String userId = "user-1";
        MatchRequest request = new MatchRequest(new MatchRequest.Location(127.0, 37.5), new MatchRequest.Location(127.1, 37.6));
        NearbyDriver driver = new NearbyDriver("driver-A", 0.5);

        given(locationServiceClient.findNearbyDrivers(anyDouble(), anyDouble(), anyInt())).willReturn(Flux.just(driver));
        given(valueOps.setIfAbsent(anyString(), anyString(), any())).willReturn(Mono.just(true));
        given(hashOps.get(anyString(), any())).willReturn(Mono.just("1"));
        given(hashOps.put(anyString(), any(), eq("0"))).willReturn(Mono.just(true));

        // DB 저장 실패 가정
        given(outboxRepository.save(any(MatchingOutbox.class)))
                .willThrow(new RuntimeException("DB Connection Error"));

        // 롤백 동작 Mocking
        given(hashOps.put("driver_status:driver-A", "isAvailable", "1"))
                .willReturn(Mono.just(true));
        given(valueOps.delete("matching_lock:driver-A"))
                .willReturn(Mono.just(true));

        // when & then
        StepVerifier.create(matchingService.requestMatch(userId, request))
                    .expectErrorMatches(throwable -> throwable.getMessage().equals("DB Connection Error"))
                    .verify();

        // Verify
        verify(hashOps).put("driver_status:driver-A", "isAvailable", "1");
        verify(valueOps).delete("matching_lock:driver-A");
    }
}