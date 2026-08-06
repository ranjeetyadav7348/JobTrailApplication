package com.jobtrail.web.dto;

import com.jobtrail.domain.AppSettings;
import com.jobtrail.domain.EmailMessage;
import com.jobtrail.domain.EmailTemplate;
import com.jobtrail.domain.Outreach;

import java.time.Instant;
import java.util.List;

/** Read models handed to the browser. Entities are never serialised directly. */
public final class Views {

    private Views() {
    }

    public record OutreachView(
            Long id,
            String recipientName,
            String recipientEmail,
            String company,
            String position,
            String notes,
            String status,
            Long initialTemplateId,
            Long followUpTemplateId,
            int followUpIntervalDays,
            int maxFollowUps,
            int followUpsSent,
            boolean autoFollowUp,
            Instant nextFollowUpAt,
            Instant firstSentAt,
            Instant lastSentAt,
            Instant openedAt,
            int openCount,
            Instant repliedAt,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record MessageView(
            Long id,
            Long outreachId,
            String recipientEmail,
            String recipientName,
            String company,
            String kind,
            String status,
            int sequenceNo,
            String subject,
            String bodyHtml,
            Instant scheduledAt,
            Instant queuedAt,
            Instant sentAt,
            int attempts,
            String lastError,
            Instant openedAt,
            int openCount) {
    }

    public record OutreachDetail(OutreachView outreach, List<MessageView> messages) {
    }

    public record QueueItem(MessageView message, int position, long etaSeconds) {
    }

    public record QueueView(
            List<QueueItem> items,
            boolean paused,
            String dispatcherState,
            String dispatcherDetail,
            long nextSendInSeconds,
            int minIntervalSeconds,
            int jitterSeconds,
            long sentToday,
            int dailySendLimit) {
    }

    public record TemplateView(
            Long id,
            String name,
            String kind,
            String subject,
            String bodyHtml,
            boolean isDefault,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record SettingsView(
            String fromName,
            String fromEmail,
            String replyTo,
            String signatureHtml,
            String attachmentPath,
            String attachmentName,
            boolean attachmentReady,
            String smtpHost,
            int smtpPort,
            String smtpUsername,
            boolean smtpPasswordSet,
            boolean smtpStartTls,
            boolean smtpSsl,
            boolean smtpAuth,
            int minIntervalSeconds,
            int minIntervalFloorSeconds,
            int jitterSeconds,
            int dailySendLimit,
            boolean sendingPaused,
            boolean sendWindowEnabled,
            int sendWindowStartHour,
            int sendWindowEndHour,
            int maxAttempts,
            boolean trackOpens,
            boolean imapEnabled,
            String imapHost,
            int imapPort,
            String imapUsername,
            boolean imapPasswordSet,
            String imapFolder,
            int imapPollMinutes,
            int defaultFollowUpIntervalDays,
            int defaultMaxFollowUps,
            boolean smtpConfigured) {
    }

    public record DayCount(String date, long sent) {
    }

    public record StatsView(
            long totalOutreach,
            long queued,
            long sentTotal,
            long sentToday,
            long opened,
            long replied,
            long failed,
            long followUpsDue,
            double openRate,
            double replyRate,
            List<DayCount> last14Days,
            List<StatusCount> byStatus,
            List<MessageView> recentActivity,
            List<OutreachView> upcomingFollowUps) {
    }

    public record StatusCount(String status, long count) {
    }

    public record PreviewView(String subject, String bodyHtml, String plainText) {
    }

    public record ActionResult(boolean ok, String message) {
    }

    // ---- mappers -----------------------------------------------------------

    public static OutreachView of(Outreach o) {
        return new OutreachView(
                o.getId(), o.getRecipientName(), o.getRecipientEmail(), o.getCompany(),
                o.getPosition(), o.getNotes(), o.getStatus().name(),
                o.getInitialTemplateId(), o.getFollowUpTemplateId(),
                o.getFollowUpIntervalDays(), o.getMaxFollowUps(), o.getFollowUpsSent(),
                o.isAutoFollowUp(), o.getNextFollowUpAt(), o.getFirstSentAt(), o.getLastSentAt(),
                o.getOpenedAt(), o.getOpenCount(), o.getRepliedAt(), o.getCreatedAt(), o.getUpdatedAt());
    }

    public static MessageView of(EmailMessage m) {
        Outreach o = m.getOutreach();
        return new MessageView(
                m.getId(), o.getId(), o.getRecipientEmail(), o.getRecipientName(), o.getCompany(),
                m.getKind().name(), m.getStatus().name(), m.getSequenceNo(),
                m.getSubject(), m.getBodyHtml(), m.getScheduledAt(), m.getQueuedAt(), m.getSentAt(),
                m.getAttempts(), m.getLastError(), m.getOpenedAt(), m.getOpenCount());
    }

    public static TemplateView of(EmailTemplate t) {
        return new TemplateView(t.getId(), t.getName(), t.getKind().name(), t.getSubject(),
                t.getBodyHtml(), t.isDefault(), t.getCreatedAt(), t.getUpdatedAt());
    }

    public static SettingsView of(AppSettings s, int floorSeconds) {
        return new SettingsView(
                s.getFromName(), s.getFromEmail(), s.getReplyTo(), s.getSignatureHtml(),
                s.getAttachmentPath(), s.getAttachmentName(), s.attachmentReady(),
                s.getSmtpHost(), s.getSmtpPort(), s.getSmtpUsername(),
                s.getSmtpPassword() != null && !s.getSmtpPassword().isBlank(),
                s.isSmtpStartTls(), s.isSmtpSsl(), s.isSmtpAuth(),
                s.getMinIntervalSeconds(), floorSeconds, s.getJitterSeconds(), s.getDailySendLimit(),
                s.isSendingPaused(), s.isSendWindowEnabled(), s.getSendWindowStartHour(),
                s.getSendWindowEndHour(), s.getMaxAttempts(), s.isTrackOpens(),
                s.isImapEnabled(), s.getImapHost(), s.getImapPort(), s.getImapUsername(),
                s.getImapPassword() != null && !s.getImapPassword().isBlank(),
                s.getImapFolder(), s.getImapPollMinutes(),
                s.getDefaultFollowUpIntervalDays(), s.getDefaultMaxFollowUps(),
                s.smtpConfigured());
    }
}
