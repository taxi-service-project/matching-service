package com.example.matching_service.dto;

public record MatchResponse(
        String message,
        String matchRequestId
) {}