package com.jobtrail.service;

import com.jobtrail.domain.ApplicationStatus;
import com.jobtrail.domain.JobApplication;
import com.jobtrail.repo.JobApplicationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Housekeeping the tracker needs but no incoming email will ever trigger:
 * warning about assessment deadlines before they pass, and retiring
 * applications that have gone quiet.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApplicationMaintenanceService {

    private final JobApplicationRepository applicationRepo;
    private final SettingsService settingsService;
    private final AlertService alertService;

    /** Hourly is often enough for day-scale deadlines and costs nothing. */
    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 120_000L)
    public void sweep() {
        try {
            int warned = warnAboutDeadlines();
            int ghosted = markGhosted();
            if (warned > 0 || ghosted > 0) {
                log.info("Application sweep: {} deadline warning(s), {} marked ghosted", warned, ghosted);
            }
        } catch (Exception e) {
            log.warn("Application sweep failed: {}", e.toString());
        }
    }

    /** Raises a warning for each assessment closing inside the alert window. */
    @Transactional
    public int warnAboutDeadlines() {
        int raised = 0;
        for (JobApplication a : applicationRepo.findUpcomingDeadlines(Instant.now())) {
            if (alertService.raiseDeadlineWarning(a).isPresent()) {
                raised++;
            }
        }
        return raised;
    }

    /**
     * Moves silent applications to GHOSTED so the funnel reflects reality. This
     * is reversible by hand and never touches anything that already reached a
     * terminal state.
     */
    @Transactional
    public int markGhosted() {
        int days = Math.max(3, settingsService.get().getGhostAfterDays());
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);

        List<JobApplication> stale = applicationRepo.findStale(cutoff);
        for (JobApplication a : stale) {
            a.setStatus(ApplicationStatus.GHOSTED);
        }
        applicationRepo.saveAll(stale);
        return stale.size();
    }
}
