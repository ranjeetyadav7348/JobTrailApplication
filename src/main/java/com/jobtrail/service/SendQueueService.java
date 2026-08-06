package com.jobtrail.service;

import com.jobtrail.domain.AppSettings;
import com.jobtrail.domain.EmailMessage;
import com.jobtrail.domain.MessageKind;
import com.jobtrail.domain.MessageStatus;
import com.jobtrail.repo.EmailMessageRepository;
import com.jobtrail.web.dto.Views;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Claiming, inspecting and reporting on the outbound queue. */
@Service
@RequiredArgsConstructor
@Slf4j
public class SendQueueService {

    private final EmailMessageRepository messageRepo;
    private final SettingsService settingsService;
    private final DispatcherStatus status;

    /** Everything the dispatcher needs to build one MIME message. */
    public record SendPayload(
            Long messageId,
            String toEmail,
            String subject,
            String bodyHtml,
            String trackingToken,
            MessageKind kind,
            String inReplyToMessageId) {
    }

    /**
     * A crash can leave rows stuck in SENDING. Those emails were handed to the
     * transport but never confirmed, so they go back to the queue on restart.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void requeueInterrupted() {
        List<EmailMessage> stuck = messageRepo.findByStatusOrderByScheduledAtAscIdAsc(MessageStatus.SENDING);
        if (stuck.isEmpty()) {
            return;
        }
        for (EmailMessage m : stuck) {
            m.setStatus(MessageStatus.QUEUED);
            m.setScheduledAt(Instant.now());
        }
        messageRepo.saveAll(stuck);
        log.warn("Re-queued {} email(s) that were interrupted mid-send", stuck.size());
    }

    /** Flips the next due email to SENDING and returns what is needed to send it. */
    @Transactional
    public Optional<SendPayload> claimNext(Instant now) {
        Optional<EmailMessage> found = messageRepo
                .findFirstByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAscIdAsc(MessageStatus.QUEUED, now);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        EmailMessage m = found.get();
        m.setStatus(MessageStatus.SENDING);
        messageRepo.save(m);

        String inReplyTo = null;
        if (m.getKind() == MessageKind.FOLLOW_UP) {
            inReplyTo = messageRepo.findByOutreachIdOrderBySequenceNoAscIdAsc(m.getOutreach().getId()).stream()
                    .filter(prev -> prev.getKind() == MessageKind.INITIAL)
                    .map(EmailMessage::getMessageId)
                    .filter(id -> id != null && !id.isBlank())
                    .findFirst()
                    .orElse(null);
        }
        return Optional.of(new SendPayload(m.getId(), m.getToEmail(), m.getSubject(), m.getBodyHtml(),
                m.getTrackingToken(), m.getKind(), inReplyTo));
    }

    @Transactional(readOnly = true)
    public long sentToday() {
        return messageRepo.countByStatusAndSentAtGreaterThanEqual(MessageStatus.SENT, startOfToday());
    }

    public static Instant startOfToday() {
        ZoneId zone = ZoneId.systemDefault();
        return LocalDate.now(zone).atStartOfDay(zone).toInstant();
    }

    /** The queue as the UI shows it, including a projected send time per row. */
    @Transactional(readOnly = true)
    public Views.QueueView queueView() {
        AppSettings s = settingsService.get();
        List<EmailMessage> queued = messageRepo.findByStatusOrderByScheduledAtAscIdAsc(MessageStatus.QUEUED);
        List<EmailMessage> sending = messageRepo.findByStatusOrderByScheduledAtAscIdAsc(MessageStatus.SENDING);

        // Average spacing between two sends: the floor plus half the jitter range.
        long stepSeconds = Math.max(settingsService.intervalFloorSeconds(), s.getMinIntervalSeconds())
                + Math.max(0, s.getJitterSeconds()) / 2L;

        List<Views.QueueItem> items = new ArrayList<>();
        int position = 0;
        for (EmailMessage m : sending) {
            items.add(new Views.QueueItem(Views.of(m), position++, 0));
        }

        long now = System.currentTimeMillis();
        long cursor = Math.max(now, status.nextAllowedSendAtMillis());
        for (EmailMessage m : queued) {
            long earliest = Math.max(cursor, m.getScheduledAt().toEpochMilli());
            long eta = s.isSendingPaused() ? -1 : Math.max(0, (earliest - now + 999) / 1000);
            items.add(new Views.QueueItem(Views.of(m), position++, eta));
            cursor = earliest + stepSeconds * 1000L;
        }

        return new Views.QueueView(
                items,
                s.isSendingPaused(),
                status.getState(),
                status.getDetail(),
                status.secondsUntilSlot(),
                Math.max(settingsService.intervalFloorSeconds(), s.getMinIntervalSeconds()),
                Math.max(0, s.getJitterSeconds()),
                sentToday(),
                s.getDailySendLimit());
    }

    @Transactional(readOnly = true)
    public List<Views.MessageView> recent(int limit) {
        return messageRepo.findRecentWithOutreach(
                        org.springframework.data.domain.PageRequest.of(0, Math.max(1, Math.min(limit, 200))))
                .stream().map(Views::of).toList();
    }
}
