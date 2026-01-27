package com.example.matching_service.repository;

import com.example.matching_service.entity.MatchingOutbox;
import com.example.matching_service.entity.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface MatchingOutboxRepository extends JpaRepository<MatchingOutbox, Long> {

    @Query(value = """
            SELECT * FROM matching_outbox 
            WHERE status = 'READY' 
            ORDER BY created_at ASC 
            LIMIT :limit 
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<MatchingOutbox> findEventsForPublishing(@Param("limit") int limit);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE MatchingOutbox m SET m.status = :status WHERE m.id IN :ids")
    void updateStatus(@Param("ids") List<Long> ids, @Param("status") OutboxStatus status);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE MatchingOutbox m SET m.status = :newStatus WHERE m.status = :oldStatus AND m.createdAt < :cutoffTime")
    int resetStuckEvents(@Param("oldStatus") OutboxStatus oldStatus,
                         @Param("newStatus") OutboxStatus newStatus,
                         @Param("cutoffTime") LocalDateTime cutoffTime);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM MatchingOutbox m WHERE m.status = :status AND m.createdAt < :cutoffTime")
    int deleteOldEvents(@Param("status") OutboxStatus status,
                        @Param("cutoffTime") LocalDateTime cutoffTime);
}