package com.example.matching_service.controller;

import com.example.matching_service.dto.MatchRequest;
import com.example.matching_service.dto.MatchResponse;
import com.example.matching_service.service.MatchingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/matches")
@RequiredArgsConstructor
public class MatchingController {

    private final MatchingService matchingService;

    @PostMapping
    public Mono<ResponseEntity<MatchResponse>> requestMatch(@Valid @RequestBody MatchRequest request,
                                                            @RequestHeader(value = "X-User-Id") String authenticatedUserId) {

        return matchingService.requestMatch(authenticatedUserId, request)
                              .map(response -> ResponseEntity.ok(response));
    }
}