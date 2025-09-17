package com.example.matching_service.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record MatchRequest(
        @NotNull String userId,
        @NotNull @Valid Location origin,
        @NotNull @Valid Location destination
) {
    public record Location(
            @NotNull Double longitude,
            @NotNull Double latitude
    ) {}
}