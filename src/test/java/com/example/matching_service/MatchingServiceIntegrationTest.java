package com.example.matching_service;

import com.example.matching_service.service.MatchingService;
import com.example.matching_service.client.DriverServiceClient;
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
@SpringBootTest // 2. Spring Boot 통합 테스트
class MatchingServiceIntegrationTest {

    @Autowired
    private MatchingService matchingService;

    // 3. 실제 RedisTemplate Bean을 주입받음 (Mock 아님)
    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    // 4. 외부 서비스와 Kafka는 여전히 Mocking
    @MockitoBean
    private LocationServiceClient locationServiceClient;
    @MockitoBean
    private DriverServiceClient driverServiceClient;
    @MockitoBean
    private MatchingKafkaProducer kafkaProducer;

    // 5. 테스트 실행 시 자동으로 Redis Docker 컨테이너를 실행
    @Container
    private static final GenericContainer<?> redisContainer =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    // 6. 동적으로 생성된 Redis 컨테이너의 Port를 Spring Boot 설정에 주입
    @DynamicPropertySource
    private static void setRedisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redisContainer::getHost);
        registry.add("spring.data.redis.port", () -> redisContainer.getMappedPort(6379).toString());
    }

    @Test
    @DisplayName("실제 Redis 컨테이너와 연동하여 매칭 성공 시 Kafka 이벤트가 발행되어야 한다")
    void requestMatch_WithRealRedis_Success_ShouldSendKafkaEvent() {
        // given: 시나리오 설정
        MatchRequest request = new MatchRequest(1L, new MatchRequest.Location(127.0, 37.5), null);

        // 7. Mock이 아닌 실제 RedisTemplate을 사용해 테스트 데이터 준비
        redisTemplate.opsForHash().put("driver_status:101", "isAvailable", "1");
        redisTemplate.opsForHash().put("driver_status:103", "isAvailable", "1");

        // 외부 서비스 Mocking은 단위 테스트와 동일
        var driverA = new LocationServiceClient.NearbyDriver(101L, 100.0);
        var driverC = new LocationServiceClient.NearbyDriver(103L, 300.0);
        when(locationServiceClient.findNearbyDrivers(anyDouble(), anyDouble(), anyInt())).thenReturn(Flux.just(driverA, driverC));

        var infoA = new DriverServiceClient.InternalDriverInfo(101L, "A", 4.5, null);
        var infoC = new DriverServiceClient.InternalDriverInfo(103L, "C", 5.0, null);
        when(driverServiceClient.getDriversInfo(anyList())).thenReturn(Flux.just(infoA, infoC));
        // 예상 승자: 기사 C (103L)

        // when
        matchingService.requestMatch(request);

        // then
        ArgumentCaptor<TripMatchedEvent> eventCaptor = ArgumentCaptor.forClass(TripMatchedEvent.class);
        verify(kafkaProducer, timeout(2000)).sendTripMatchedEvent(eventCaptor.capture()); // 외부 연동으로 조금 더 넉넉하게

        TripMatchedEvent capturedEvent = eventCaptor.getValue();
        assertThat(capturedEvent.driverId()).isEqualTo(103L);
    }
}