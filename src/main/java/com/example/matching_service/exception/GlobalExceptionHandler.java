package com.example.matching_service.exception;

import com.example.matching_service.dto.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(RuntimeException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleRuntimeException(RuntimeException ex, ServerWebExchange exchange) {
        log.error("❌ 비즈니스 로직 에러: {}", ex.getMessage());

        return Mono.just(
                ResponseEntity.status(HttpStatus.BAD_REQUEST)
                              .body(ErrorResponse.builder()
                                                 .status(HttpStatus.BAD_REQUEST.value())
                                                 .error("MATCHING_ERROR")
                                                 .message(ex.getMessage())
                                                 .path(exchange.getRequest().getPath().value())
                                                 .build())
        );
    }

    // 그 외 예상치 못한 모든 시스템 에러 처리
    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<ErrorResponse>> handleAllException(Exception ex, ServerWebExchange exchange) {
        log.error("🔥 서버 내부 치명적 에러: ", ex);

        return Mono.just(
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR) // 500 에러
                              .body(ErrorResponse.builder()
                                                 .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                                                 .error("INTERNAL_SERVER_ERROR")
                                                 .message("서버 내부 오류가 발생했습니다. 관리자에게 문의하세요.")
                                                 .path(exchange.getRequest().getPath().value())
                                                 .build())
        );
    }
}