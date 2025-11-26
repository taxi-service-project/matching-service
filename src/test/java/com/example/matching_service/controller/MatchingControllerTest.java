package com.example.matching_service.controller;

import com.example.matching_service.dto.MatchRequest;
import com.example.matching_service.dto.MatchResponse;
import com.example.matching_service.service.MatchingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MatchingController.class)
class MatchingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private MatchingService matchingService;

    @Test
    @DisplayName("정상 요청 시 202 Accepted와 RequestId를 반환한다")
    void requestMatch_Success() throws Exception {
        // Given
        String userId = "user-123";
        MatchRequest request = new MatchRequest(
                new MatchRequest.Location(127.123, 37.123),
                new MatchRequest.Location(127.456, 37.456)
        );
        MatchResponse response = new MatchResponse("매칭 중", "req-uuid-001");

        given(matchingService.requestMatch(eq(userId), any(MatchRequest.class)))
                .willReturn(response);

        // When & Then
        mockMvc.perform(post("/api/matches")
                       .header("X-User-Id", userId) // 필수 헤더 포함
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(objectMapper.writeValueAsString(request)))
               .andExpect(status().isAccepted())
               .andExpect(jsonPath("$.message").value("매칭 중"))
               .andExpect(jsonPath("$.matchRequestId").value("req-uuid-001"));
    }

    @Test
    @DisplayName("필수 헤더(X-User-Id)가 누락되면 400 Bad Request를 반환한다")
    void requestMatch_MissingHeader() throws Exception {
        // Given
        MatchRequest request = new MatchRequest(
                new MatchRequest.Location(127.123, 37.123),
                new MatchRequest.Location(127.456, 37.456)
        );

        // When & Then
        mockMvc.perform(post("/api/matches")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(objectMapper.writeValueAsString(request)))
               .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("요청 본문(Body)의 유효성 검증 실패 시 400 Bad Request를 반환한다")
    void requestMatch_InvalidBody() throws Exception {
        // Given
        // 위도/경도가 null이거나 범위를 벗어난 잘못된 요청 가정 (MatchRequest에 @NotNull 등이 있다고 가정)
        MatchRequest invalidRequest = new MatchRequest(null, null);

        // When & Then
        mockMvc.perform(post("/api/matches")
                       .header("X-User-Id", "user-123")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(objectMapper.writeValueAsString(invalidRequest)))
               .andExpect(status().isBadRequest());
    }
}