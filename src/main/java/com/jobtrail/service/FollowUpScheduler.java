package com.jobtrail.service;

import com.jobtrail.domain.Outreach;
import com.jobtrail.repo.OutreachRepository;
import com.jobtrail.web.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Watches every thread that was emailed but never answered and drops the next
 * follow-up into the queue once the configured quiet period has elapsed.
 * The dispatcher then paces it like any other email.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FollowUpScheduler {

    private final OutreachRepository outreachRepo;
    private final OutreachService outreachService;

    private volatile Instant lastRunAt;
    private volatile int lastQueuedCount;

    @Scheduled(fixedDelay = 60_000L, initialDelay = 20_000L)
    public void queueDueFollowUps() {
        lastRunAt = Instant.now();
        List<Outreach> due = outreachRepo.findFollowUpsDue(lastRunAt);
        if (due.isEmpty()) {
            lastQueuedCount = 0;
            return;
        }

        int queued = 0;
        for (Outreach o : due) {
            try {
                outreachService.queueFollowUp(o.getId(), o.getFollowUpTemplateId(), false);
                queued++;
                log.info("Queued follow-up #{} for {}", o.getFollowUpsSent() + 1, o.getRecipientEmail());
            } catch (ApiException e) {
                // Expected when a reply landed or something is already queued.
                log.debug("Skipping follow-up for {}: {}", o.getRecipientEmail(), e.getMessage());
            } catch (Exception e) {
                log.warn("Could not queue follow-up for {}: {}", o.getRecipientEmail(), e.toString());
            }
        }
        lastQueuedCount = queued;
    }

    public Instant getLastRunAt() {
        return lastRunAt;
    }

    public int getLastQueuedCount() {
        return lastQueuedCount;
    }
}
