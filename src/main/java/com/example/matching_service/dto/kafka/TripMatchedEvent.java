package com.example.matching_service.dto.kafka;

import com.example.matching_service.dto.MatchRequest;
import java.time.LocalDateTime;

public record TripMatchedEvent(
        String tripId,
        Long userId,
        Long driverId,
        MatchRequest.Location origin,
        MatchRequest.Location destination,
        LocalDateTime matchedAt
) {}