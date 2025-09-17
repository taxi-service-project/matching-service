package com.example.matching_service.dto.kafka;

import com.example.matching_service.dto.MatchRequest;
import java.time.LocalDateTime;

public record TripMatchedEvent(
        String tripId,
        String userId,
        String driverId,
        MatchRequest.Location origin,
        MatchRequest.Location destination,
        LocalDateTime matchedAt
) {}