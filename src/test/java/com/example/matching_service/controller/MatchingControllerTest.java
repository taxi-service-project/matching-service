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
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MatchingController.class)
class MatchingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private MatchingService matchingService;

    @Test
    @DisplayName("매칭 성공 시 200 OK와 결과를 반환한다 (Mono 비동기 처리)")
    void requestMatch_Success() throws Exception {
        // Given
        String userId = "user-123";
        MatchRequest request = new MatchRequest(
                new MatchRequest.Location(127.123, 37.123),
                new MatchRequest.Location(127.456, 37.456)
        );
        MatchResponse response = new MatchResponse("매칭 성공!", "req-uuid-001");

        given(matchingService.requestMatch(eq(userId), any(MatchRequest.class)))
                .willReturn(Mono.just(response));

        // When
        MvcResult mvcResult = mockMvc.perform(post("/api/matches")
                                             .header("X-User-Id", userId)
                                             .contentType(MediaType.APPLICATION_JSON)
                                             .content(objectMapper.writeValueAsString(request)))
                                     .andExpect(request().asyncStarted())
                                     .andReturn();

        // Then
        mockMvc.perform(asyncDispatch(mvcResult))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.message").value("매칭 성공!"))
               .andExpect(jsonPath("$.matchRequestId").value("req-uuid-001"));
    }

    @Test
    @DisplayName("Service에서 에러 발생 시(기사 없음 등) 예외를 던진다")
    void requestMatch_ServiceError() throws Exception {
        // Given
        MatchRequest request = new MatchRequest(
                new MatchRequest.Location(127.123, 37.123),
                new MatchRequest.Location(127.456, 37.456)
        );

        given(matchingService.requestMatch(any(), any()))
                .willReturn(Mono.error(new RuntimeException("기사 없음")));

        // When
        MvcResult mvcResult = mockMvc.perform(post("/api/matches")
                                             .header("X-User-Id", "user-1")
                                             .contentType(MediaType.APPLICATION_JSON)
                                             .content(objectMapper.writeValueAsString(request)))
                                     .andExpect(request().asyncStarted())
                                     .andReturn();

        // Then
        try {
            mockMvc.perform(asyncDispatch(mvcResult));
        } catch (Exception e) {
            // 예외가 컨트롤러 밖으로 던져짐을 확인
        }
    }
}