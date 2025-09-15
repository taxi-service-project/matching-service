package com.example.matching_service.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient.Builder webClientBuilder() {
        HttpClient httpClient = HttpClient.create()
                                          // Connection Timeout 설정 (서버와 연결을 맺는 시간)
                                          .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                                          // Response Timeout 설정 (연결 후 응답을 기다리는 시간)
                                          .responseTimeout(Duration.ofSeconds(10));

        // 2. 설정된 HttpClient를 WebClient가 사용할 수 있도록 ReactorClientHttpConnector로 감쌉니다.
        ReactorClientHttpConnector connector = new ReactorClientHttpConnector(httpClient);

        // 3. 생성된 커넥터를 WebClient.Builder에 적용하고, 공통 헤더 등을 설정합니다.
        return WebClient.builder()
                        .clientConnector(connector)
                        .defaultHeader("Content-Type", "application/json");
    }
}