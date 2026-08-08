package com.jobtrail.service;

import com.jobtrail.domain.AppSettings;
import com.jobtrail.domain.ApplicationEventKind;
import com.jobtrail.domain.ApplicationStatus;
import com.jobtrail.domain.JobApplication;
import com.jobtrail.domain.JobPlatform;
import com.jobtrail.repo.ApplicationEventRepository;
import com.jobtrail.repo.JobApplicationRepository;
import com.jobtrail.service.scan.MailboxScanner;
import com.jobtrail.web.dto.AppViews;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the Applications dashboard shows, computed in one pass.
 *
 * <p>Two decisions shape the numbers here, and both matter for whether you can
 * trust them:
 *
 * <ul>
 *   <li>The funnel counts applications that <em>ever reached</em> a stage, read
 *       from the event history — not the current status. An application that
 *       was rejected after an on-site still counts as having interviewed, which
 *       is the only way "interview rate" means anything.</li>
 *   <li>Rates are quoted against applications that have had a fair chance to
 *       respond. Counting last night's twelve submissions as twelve
 *       non-responses would drag every rate down and make the dashboard lie to
 *       you on your most active days.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ApplicationStatsService {

    /** Weeks shown on the trend chart. */
    private static final int TREND_WEEKS = 12;
    /** Below this age an application has not had a fair chance to reply yet. */
    private static final int RESPONSE_GRACE_DAYS = 7;
    /** No reply after this long and it is worth a nudge. */
    private static final int NUDGE_AFTER_DAYS = 10;
    private static final int LIST_LIMIT = 8;

    private static final Set<ApplicationEventKind> RESPONSE_KINDS = EnumSet.of(
            ApplicationEventKind.ACKNOWLEDGED,
            ApplicationEventKind.ASSESSMENT_INVITE,
            ApplicationEventKind.INTERVIEW_INVITE,
            ApplicationEventKind.OFFER,
            ApplicationEventKind.REJECTED,
            ApplicationEventKind.STATUS_UPDATE);

    private final JobApplicationRepository applicationRepo;
    private final ApplicationEventRepository eventRepo;
    private final AlertService alertService;
    private final SettingsService settingsService;
    private final MailboxScanner scanner;

    @Transactional(readOnly = true)
    public AppViews.ApplicationStatsView dashboard() {
        List<JobApplication> apps = applicationRepo.findByArchivedFalseOrderByLastEventAtDescAppliedAtDesc();
        Instant now = Instant.now();
        ZoneId zone = ZoneId.systemDefault();

        Set<Long> reachedAssessment = eventRepo.findApplicationIdsByKinds(
                Set.of(ApplicationEventKind.ASSESSMENT_INVITE));
        Set<Long> reachedInterview = eventRepo.findApplicationIdsByKinds(
                Set.of(ApplicationEventKind.INTERVIEW_INVITE));
        Set<Long> reachedOffer = eventRepo.findApplicationIdsByKinds(
                Set.of(ApplicationEventKind.OFFER));
        Set<Long> responded = eventRepo.findApplicationIdsByKinds(RESPONSE_KINDS);

        Map<Long, Integer> reached = furthestStageReached(
                apps, responded, reachedAssessment, reachedInterview, reachedOffer);

        long total = apps.size();
        long active = apps.stream().filter(a -> !a.getStatus().terminal()).count();

        Instant weekAgo = now.minus(7, ChronoUnit.DAYS);
        Instant monthAgo = now.minus(30, ChronoUnit.DAYS);
        long appliedThisWeek = apps.stream().filter(a -> a.getAppliedAt().isAfter(weekAgo)).count();
        long appliedThisMonth = apps.stream().filter(a -> a.getAppliedAt().isAfter(monthAgo)).count();

        long awaiting = apps.stream()
                .filter(a -> !a.getStatus().terminal() && a.getFirstResponseAt() == null)
                .count();
        long assessmentsPending = apps.stream()
                .filter(a -> a.getStatus() == ApplicationStatus.ASSESSMENT)
                .count();
        long interviewsScheduled = apps.stream()
                .filter(a -> a.getStatus() == ApplicationStatus.INTERVIEW)
                .count();
        long offers = countStatus(apps, ApplicationStatus.OFFER);
        long rejected = countStatus(apps, ApplicationStatus.REJECTED);
        long ghosted = countStatus(apps, ApplicationStatus.GHOSTED);

        // Rates are quoted against applications old enough to have heard back.
        List<JobApplication> mature = apps.stream()
                .filter(a -> a.getAppliedAt().isBefore(now.minus(RESPONSE_GRACE_DAYS, ChronoUnit.DAYS)))
                .toList();
        long matureCount = mature.size();
        long matureResponded = countAtLeast(mature, reached, ApplicationStatus.ACKNOWLEDGED);
        long matureInterviewed = countAtLeast(mature, reached, ApplicationStatus.INTERVIEW);
        long matureOffered = countAtLeast(mature, reached, ApplicationStatus.OFFER);

        List<Double> responseDays = apps.stream()
                .filter(a -> a.getFirstResponseAt() != null)
                .map(a -> Duration.between(a.getAppliedAt(), a.getFirstResponseAt()).toHours() / 24d)
                .filter(d -> d >= 0)
                .sorted()
                .toList();

        return new AppViews.ApplicationStatsView(
                total,
                active,
                appliedThisWeek,
                appliedThisMonth,
                awaiting,
                assessmentsPending,
                interviewsScheduled,
                offers,
                rejected,
                ghosted,
                rate(matureResponded, matureCount),
                rate(matureInterviewed, matureCount),
                rate(matureOffered, matureCount),
                mean(responseDays),
                median(responseDays),
                funnel(apps, reached),
                platforms(apps, reached),
                weeklyTrend(apps, zone),
                statusBreakdown(apps),
                topCompanies(apps, reached),
                upcomingDeadlines(now),
                needsFollowUp(now),
                recentEvents(),
                alertService.unreadCount(),
                scanStatus());
    }

    // ---- funnel ------------------------------------------------------------

    /**
     * The furthest stage each application ever reached, as a stage rank.
     *
     * <p>Two sources are merged. Events are the stronger evidence — a rejection
     * after an on-site still proves the interview happened, which current
     * status alone would hide. But an application added by hand, or corrected
     * by hand, has no events behind it, and reading only events would report it
     * as merely "applied" while the row on screen says "Assessment".
     *
     * <p>Current status therefore also contributes, with one exception:
     * REJECTED, GHOSTED and WITHDRAWN say nothing about how far the application
     * got before it died, so they contribute nothing and leave the event
     * history to speak. OFFER is terminal but genuinely means the offer stage
     * was reached, so it counts.
     */
    private Map<Long, Integer> furthestStageReached(List<JobApplication> apps,
                                                    Set<Long> responded,
                                                    Set<Long> assessment,
                                                    Set<Long> interview,
                                                    Set<Long> offer) {
        Map<Long, Integer> reached = new HashMap<>();
        for (JobApplication a : apps) {
            int rank = ApplicationStatus.APPLIED.rank();
            if (responded.contains(a.getId())) {
                rank = Math.max(rank, ApplicationStatus.ACKNOWLEDGED.rank());
            }
            if (assessment.contains(a.getId())) {
                rank = Math.max(rank, ApplicationStatus.ASSESSMENT.rank());
            }
            if (interview.contains(a.getId())) {
                rank = Math.max(rank, ApplicationStatus.INTERVIEW.rank());
            }
            if (offer.contains(a.getId()) || a.getStatus() == ApplicationStatus.OFFER) {
                rank = Math.max(rank, ApplicationStatus.OFFER.rank());
            }
            if (!a.getStatus().terminal()) {
                rank = Math.max(rank, a.getStatus().rank());
            }
            reached.put(a.getId(), rank);
        }
        return reached;
    }

    private static long countAtLeast(List<JobApplication> apps,
                                     Map<Long, Integer> reached,
                                     ApplicationStatus stage) {
        return apps.stream()
                .filter(a -> reached.getOrDefault(a.getId(), 0) >= stage.rank())
                .count();
    }

    private List<AppViews.FunnelStage> funnel(List<JobApplication> apps, Map<Long, Integer> reached) {
        long applied = apps.size();
        List<AppViews.FunnelStage> stages = new ArrayList<>(5);
        for (ApplicationStatus stage : ApplicationStatus.funnel()) {
            long count = countAtLeast(apps, reached, stage);
            stages.add(new AppViews.FunnelStage(
                    stage.name(), stage.label(), count, rate(count, applied)));
        }
        return stages;
    }

    // ---- per platform ------------------------------------------------------

    private List<AppViews.PlatformStat> platforms(List<JobApplication> apps, Map<Long, Integer> reached) {
        Map<JobPlatform, long[]> buckets = new EnumMap<>(JobPlatform.class);
        for (JobApplication a : apps) {
            // [total, responded, interviews, offers, rejected, ghosted]
            long[] b = buckets.computeIfAbsent(a.getPlatform(), k -> new long[6]);
            int rank = reached.getOrDefault(a.getId(), 0);
            b[0]++;
            if (rank >= ApplicationStatus.ACKNOWLEDGED.rank()) {
                b[1]++;
            }
            if (rank >= ApplicationStatus.INTERVIEW.rank()) {
                b[2]++;
            }
            if (rank >= ApplicationStatus.OFFER.rank()) {
                b[3]++;
            }
            if (a.getStatus() == ApplicationStatus.REJECTED) {
                b[4]++;
            }
            if (a.getStatus() == ApplicationStatus.GHOSTED) {
                b[5]++;
            }
        }
        return buckets.entrySet().stream()
                .map(e -> new AppViews.PlatformStat(
                        e.getKey().name(), e.getKey().label(), e.getKey().colour(),
                        e.getValue()[0], e.getValue()[1], e.getValue()[2],
                        e.getValue()[3], e.getValue()[4], e.getValue()[5],
                        rate(e.getValue()[1], e.getValue()[0]),
                        rate(e.getValue()[2], e.getValue()[0])))
                .sorted(Comparator.comparingLong(AppViews.PlatformStat::total).reversed())
                .toList();
    }

    // ---- trend -------------------------------------------------------------

    /** One bucket per ISO week, oldest first, with no gaps. */
    private List<AppViews.WeekCount> weeklyTrend(List<JobApplication> apps, ZoneId zone) {
        LocalDate thisWeek = LocalDate.now(zone).with(java.time.DayOfWeek.MONDAY);
        LocalDate from = thisWeek.minusWeeks(TREND_WEEKS - 1L);

        Map<LocalDate, long[]> counts = new HashMap<>();
        for (JobApplication a : apps) {
            LocalDate appliedWeek = weekOf(a.getAppliedAt(), zone);
            if (!appliedWeek.isBefore(from)) {
                counts.computeIfAbsent(appliedWeek, k -> new long[2])[0]++;
            }
            if (a.getFirstResponseAt() != null) {
                LocalDate responseWeek = weekOf(a.getFirstResponseAt(), zone);
                if (!responseWeek.isBefore(from)) {
                    counts.computeIfAbsent(responseWeek, k -> new long[2])[1]++;
                }
            }
        }

        List<AppViews.WeekCount> series = new ArrayList<>(TREND_WEEKS);
        for (int i = 0; i < TREND_WEEKS; i++) {
            LocalDate week = from.plusWeeks(i);
            long[] c = counts.getOrDefault(week, new long[2]);
            series.add(new AppViews.WeekCount(week.toString(), c[0], c[1]));
        }
        return series;
    }

    private static LocalDate weekOf(Instant instant, ZoneId zone) {
        return LocalDate.ofInstant(instant, zone).with(java.time.DayOfWeek.MONDAY);
    }

    // ---- breakdowns --------------------------------------------------------

    private List<AppViews.StatusSlice> statusBreakdown(List<JobApplication> apps) {
        Map<ApplicationStatus, Long> counts = new EnumMap<>(ApplicationStatus.class);
        for (JobApplication a : apps) {
            counts.merge(a.getStatus(), 1L, Long::sum);
        }
        return counts.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted(Comparator.comparingInt(e -> e.getKey().ordinal()))
                .map(e -> new AppViews.StatusSlice(
                        e.getKey().name(), e.getKey().label(), e.getKey().cssVar(), e.getValue()))
                .toList();
    }

    private List<AppViews.CompanyCount> topCompanies(List<JobApplication> apps, Map<Long, Integer> reached) {
        Map<String, long[]> byCompany = new LinkedHashMap<>();
        for (JobApplication a : apps) {
            long[] b = byCompany.computeIfAbsent(a.getCompany(), k -> new long[2]);
            b[0]++;
            if (reached.getOrDefault(a.getId(), 0) >= ApplicationStatus.ACKNOWLEDGED.rank()) {
                b[1]++;
            }
        }
        return byCompany.entrySet().stream()
                .filter(e -> !"Unknown".equals(e.getKey()))
                .sorted(Comparator.<Map.Entry<String, long[]>>comparingLong(e -> e.getValue()[0]).reversed())
                .limit(LIST_LIMIT)
                .map(e -> new AppViews.CompanyCount(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .toList();
    }

    private List<AppViews.ApplicationView> upcomingDeadlines(Instant now) {
        return applicationRepo.findUpcomingDeadlines(now).stream()
                .limit(LIST_LIMIT)
                .map(AppViews::of)
                .toList();
    }

    /** Live, unanswered and old enough that chasing is reasonable. */
    private List<AppViews.ApplicationView> needsFollowUp(Instant now) {
        return applicationRepo.findStale(now.minus(NUDGE_AFTER_DAYS, ChronoUnit.DAYS)).stream()
                .filter(a -> a.getFirstResponseAt() == null)
                .limit(LIST_LIMIT)
                .map(AppViews::of)
                .toList();
    }

    private List<AppViews.ApplicationEventView> recentEvents() {
        return eventRepo.findTop20ByOrderByReceivedAtDesc().stream()
                .limit(10)
                .map(AppViews::of)
                .toList();
    }

    // ---- scan status -------------------------------------------------------

    public AppViews.ScanStatusView scanStatus() {
        AppSettings s = settingsService.get();
        MailboxScanner.ScanReport last = scanner.getLastReport();
        return new AppViews.ScanStatusView(
                s.isScanEnabled(),
                s.imapConfigured(),
                scanner.isRunning(),
                s.getScanDays(),
                s.scanFolderList(),
                scanner.getLastRunAt(),
                scanner.getLastSuccessAt() != null ? scanner.getLastSuccessAt() : s.getLastScanAt(),
                scanner.getLastError(),
                last == null ? null : last.messagesRead(),
                last == null ? null : last.stored(),
                last == null ? null : last.newApplications());
    }

    // ---- arithmetic --------------------------------------------------------

    private static long countStatus(List<JobApplication> apps, ApplicationStatus status) {
        return apps.stream().filter(a -> a.getStatus() == status).count();
    }

    /** One decimal place, and zero rather than NaN when nothing qualifies yet. */
    private static double rate(long part, long whole) {
        if (whole <= 0) {
            return 0d;
        }
        return Math.round((part * 1000d) / whole) / 10d;
    }

    private static Double mean(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        double sum = values.stream().mapToDouble(Double::doubleValue).sum();
        return Math.round((sum / values.size()) * 10d) / 10d;
    }

    /** Median, because one company that replied after four months skews the mean. */
    private static Double median(List<Double> sorted) {
        if (sorted.isEmpty()) {
            return null;
        }
        int mid = sorted.size() / 2;
        double value = sorted.size() % 2 == 1
                ? sorted.get(mid)
                : (sorted.get(mid - 1) + sorted.get(mid)) / 2d;
        return Math.round(value * 10d) / 10d;
    }
}
