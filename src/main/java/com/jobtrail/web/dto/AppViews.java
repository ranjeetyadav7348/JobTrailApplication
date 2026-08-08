package com.jobtrail.web.dto;

import com.jobtrail.domain.Alert;
import com.jobtrail.domain.ApplicationEvent;
import com.jobtrail.domain.JobApplication;

import java.time.Instant;
import java.util.List;

/**
 * Read models for the application tracker. Kept apart from {@link Views}, which
 * serves the outreach side, so neither file becomes a dumping ground.
 */
public final class AppViews {

    private AppViews() {
    }

    public record ApplicationView(
            Long id,
            String company,
            String roleTitle,
            String location,
            String platform,
            String platformLabel,
            String platformColour,
            String status,
            String statusLabel,
            String sourceEmail,
            String jobUrl,
            String assessmentUrl,
            Instant assessmentDueAt,
            Instant appliedAt,
            Instant lastEventAt,
            Instant firstResponseAt,
            int eventCount,
            long staleDays,
            boolean manual,
            boolean archived,
            String notes) {
    }

    public record ApplicationEventView(
            Long id,
            Long applicationId,
            String kind,
            String kindLabel,
            String subject,
            String fromName,
            String fromAddress,
            Instant receivedAt,
            String snippet,
            String actionUrl,
            Instant deadlineAt,
            double confidence,
            boolean lowConfidence) {
    }

    public record ApplicationDetail(ApplicationView application, List<ApplicationEventView> events) {
    }

    public record AlertView(
            Long id,
            String kind,
            String kindLabel,
            String severity,
            boolean popup,
            String title,
            String body,
            String actionUrl,
            Long applicationId,
            Instant deadlineAt,
            Instant createdAt,
            boolean unread) {
    }

    /** One stage of the conversion funnel: how many applications ever reached it. */
    public record FunnelStage(String stage, String label, long count, double pctOfApplied) {
    }

    /** Per-platform performance — which job boards are actually worth your time. */
    public record PlatformStat(
            String platform,
            String label,
            String colour,
            long total,
            long responded,
            long interviews,
            long offers,
            long rejected,
            long ghosted,
            double responseRate,
            double interviewRate) {
    }

    public record WeekCount(String weekStart, long applied, long responses) {
    }

    public record StatusSlice(String status, String label, String cssVar, long count) {
    }

    public record CompanyCount(String company, long applications, long responses) {
    }

    public record ScanStatusView(
            boolean enabled,
            boolean configured,
            boolean running,
            int scanDays,
            List<String> folders,
            Instant lastRunAt,
            Instant lastSuccessAt,
            String lastError,
            Integer lastMessagesRead,
            Integer lastStored,
            Integer lastNewApplications) {
    }

    public record ScanResultView(
            boolean ok,
            String message,
            int messagesRead,
            int recognised,
            int stored,
            int newApplications,
            int alertsRaised,
            int foldersRead,
            int aiCalls,
            int promotionalFiltered,
            long durationMs,
            Instant from) {
    }

    /**
     * Everything the Applications dashboard renders in one call, so the page
     * cannot show figures from two different moments side by side.
     */
    public record ApplicationStatsView(
            long total,
            long active,
            long appliedThisWeek,
            long appliedThisMonth,
            long awaitingResponse,
            long assessmentsPending,
            long interviewsScheduled,
            long offers,
            long rejected,
            long ghosted,
            double responseRate,
            double interviewRate,
            double offerRate,
            Double avgDaysToFirstResponse,
            Double medianDaysToFirstResponse,
            List<FunnelStage> funnel,
            List<PlatformStat> platforms,
            List<WeekCount> weeklyTrend,
            List<StatusSlice> byStatus,
            List<CompanyCount> topCompanies,
            List<ApplicationView> upcomingDeadlines,
            List<ApplicationView> needsFollowUp,
            List<ApplicationEventView> recentEvents,
            long unreadAlerts,
            ScanStatusView scan) {
    }

    // ---- mappers -----------------------------------------------------------

    public static ApplicationView of(JobApplication a) {
        return new ApplicationView(
                a.getId(), a.getCompany(), a.getRoleTitle(), a.getLocation(),
                a.getPlatform().name(), a.getPlatform().label(), a.getPlatform().colour(),
                a.getStatus().name(), a.getStatus().label(),
                a.getSourceEmail(), a.getJobUrl(), a.getAssessmentUrl(), a.getAssessmentDueAt(),
                a.getAppliedAt(), a.getLastEventAt(), a.getFirstResponseAt(),
                a.getEventCount(), a.staleDays(), a.isManual(), a.isArchived(), a.getNotes());
    }

    public static ApplicationEventView of(ApplicationEvent e) {
        return new ApplicationEventView(
                e.getId(), e.getApplication().getId(), e.getKind().name(), e.getKind().label(),
                e.getSubject(), e.getFromName(), e.getFromAddress(), e.getReceivedAt(),
                e.getSnippet(), e.getActionUrl(), e.getDeadlineAt(), e.getConfidence(),
                e.getConfidence() < com.jobtrail.service.scan.Classification.LOW_CONFIDENCE);
    }

    public static AlertView of(Alert a) {
        return new AlertView(
                a.getId(), a.getKind().name(), a.getKind().label(), a.getKind().severity(),
                a.getKind().popup(), a.getTitle(), a.getBody(), a.getActionUrl(),
                a.getApplicationId(), a.getDeadlineAt(), a.getCreatedAt(), a.isUnread());
    }
}
