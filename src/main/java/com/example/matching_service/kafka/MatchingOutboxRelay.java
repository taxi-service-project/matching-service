package com.example.matching_service.kafka;

import com.example.matching_service.entity.MatchingOutbox;
import com.example.matching_service.entity.OutboxStatus;
import com.example.matching_service.repository.MatchingOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class MatchingOutboxRelay {

    private final MatchingOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(fixedDelay = 500)
    public void publishEvents() {
        List<MatchingOutbox> eventsToPublish = transactionTemplate.execute(status -> {
            List<MatchingOutbox> events = outboxRepository.findEventsForPublishing(100);
            if (events.isEmpty()) return null;

            List<Long> ids = events.stream().map(MatchingOutbox::getId).toList();
            outboxRepository.updateStatus(ids, OutboxStatus.PUBLISHING);
            return events;
        });

        if (eventsToPublish == null || eventsToPublish.isEmpty()) return;

        for (MatchingOutbox event : eventsToPublish) {
            sendToKafka(event);
        }
    }

    private void sendToKafka(MatchingOutbox event) {
        try {
            kafkaTemplate.send(event.getTopic(), event.getAggregateId(), event.getPayload()).get();

            log.info("✅ [Matching-Outbox] 발행 성공 | ID: {} | Topic: {} | Key: {}",
                    event.getId(), event.getTopic(), event.getAggregateId());
            updateStatus(event.getId(), OutboxStatus.DONE);

        } catch (Exception e) {
            log.error("❌ [Matching-Outbox] 발행 실패 | ID: {} | Topic: {} | Error: {}",
                    event.getId(), event.getTopic(), e.getMessage(), e);
            updateStatus(event.getId(), OutboxStatus.READY);
        }
    }

    public void updateStatus(Long eventId, OutboxStatus status) {
        transactionTemplate.execute(tx -> {
            outboxRepository.updateStatus(List.of(eventId), status);
            return null;
        });
    }

    @Scheduled(fixedRate = 60000)
    @SchedulerLock(name = "Matching_rescueStuckEvents", lockAtLeastFor = "PT30S", lockAtMostFor = "PT50S")
    public void rescueStuckEvents() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(10);
        transactionTemplate.execute(status -> {
            int count = outboxRepository.resetStuckEvents(OutboxStatus.PUBLISHING, OutboxStatus.READY, cutoff);
            if (count > 0) log.warn("🚨 [Matching] Stuck 이벤트 {}건 복구 완료", count);
            return null;
        });
    }

    @Scheduled(cron = "0 0 3 * * *")
    @SchedulerLock(name = "Matching_cleanupOldEvents", lockAtLeastFor = "PT30S", lockAtMostFor = "PT50S")
    public void cleanupOldEvents() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(3);
        transactionTemplate.execute(status -> {
            int count = outboxRepository.deleteOldEvents(OutboxStatus.DONE, cutoff);
            if (count > 0) log.info("🧹 [Matching] 오래된 이벤트 {}건 삭제 완료", count);
            return null;
        });
    }
}