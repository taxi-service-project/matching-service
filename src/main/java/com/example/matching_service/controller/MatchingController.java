package com.example.matching_service.controller;

import com.example.matching_service.dto.MatchRequest;
import com.example.matching_service.dto.MatchResponse;
import com.example.matching_service.service.MatchingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import static org.springframework.http.HttpStatus.ACCEPTED;

@RestController
@RequestMapping("/api/matches")
@RequiredArgsConstructor
public class MatchingController {

    private final MatchingService matchingService;

    @PostMapping
    public ResponseEntity<MatchResponse> requestMatch(@Valid @RequestBody MatchRequest request,
                                                      @RequestHeader(value = "X-User-Id") String authenticatedUserId) {
        MatchResponse response = matchingService.requestMatch(authenticatedUserId, request);
        return new ResponseEntity<>(response, ACCEPTED);
    }
}