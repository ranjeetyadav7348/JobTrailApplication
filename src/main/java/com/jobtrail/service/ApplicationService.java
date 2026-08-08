package com.jobtrail.service;

import com.jobtrail.domain.ApplicationStatus;
import com.jobtrail.domain.JobApplication;
import com.jobtrail.domain.JobPlatform;
import com.jobtrail.repo.AlertRepository;
import com.jobtrail.repo.ApplicationEventRepository;
import com.jobtrail.repo.JobApplicationRepository;
import com.jobtrail.web.ApiException;
import com.jobtrail.web.dto.AppViews;
import com.jobtrail.web.dto.ApplicationForm;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Reading, editing and retiring tracked applications. */
@Service
@RequiredArgsConstructor
public class ApplicationService {

    private final JobApplicationRepository applicationRepo;
    private final ApplicationEventRepository eventRepo;
    private final AlertRepository alertRepo;

    @Transactional(readOnly = true)
    public List<AppViews.ApplicationView> list(String query, String status, String platform,
                                               Boolean includeArchived) {
        List<JobApplication> rows = (query == null || query.isBlank())
                ? applicationRepo.findByArchivedFalseOrderByLastEventAtDescAppliedAtDesc()
                : applicationRepo.search(query.trim());

        ApplicationStatus wantedStatus = parseStatus(status);
        JobPlatform wantedPlatform = parsePlatform(platform);

        return rows.stream()
                .filter(a -> Boolean.TRUE.equals(includeArchived) || !a.isArchived())
                .filter(a -> wantedStatus == null || a.getStatus() == wantedStatus)
                .filter(a -> wantedPlatform == null || a.getPlatform() == wantedPlatform)
                .sorted(Comparator.comparing(
                        (JobApplication a) -> a.getLastEventAt() == null ? a.getAppliedAt() : a.getLastEventAt())
                        .reversed())
                .map(AppViews::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public AppViews.ApplicationDetail detail(Long id) {
        JobApplication application = require(id);
        List<AppViews.ApplicationEventView> events =
                eventRepo.findByApplicationIdOrderByReceivedAtDesc(id).stream()
                        .map(AppViews::of)
                        .toList();
        return new AppViews.ApplicationDetail(AppViews.of(application), events);
    }

    @Transactional
    public AppViews.ApplicationView create(ApplicationForm form) {
        JobPlatform platform = parsePlatform(form.getPlatform());
        if (platform == null) {
            platform = JobPlatform.DIRECT;
        }
        String company = form.getCompany().trim();
        String role = blankToNull(form.getRoleTitle());

        String key = JobApplication.dedupeKey(platform, company, role);
        applicationRepo.findByDedupeKey(key).ifPresent(existing -> {
            throw ApiException.conflict("You are already tracking " + existing.getCompany()
                    + (existing.getRoleTitle() == null ? "" : " · " + existing.getRoleTitle()) + ".");
        });

        JobApplication application = new JobApplication();
        application.setCompany(company);
        application.setRoleTitle(role);
        application.setLocation(blankToNull(form.getLocation()));
        application.setPlatform(platform);
        application.setJobUrl(blankToNull(form.getJobUrl()));
        application.setNotes(blankToNull(form.getNotes()));
        application.setAppliedAt(form.getAppliedAt() == null ? Instant.now() : form.getAppliedAt());
        application.setDedupeKey(key);
        application.setManual(true);

        ApplicationStatus status = parseStatus(form.getStatus());
        if (status != null) {
            application.setStatus(status);
        }

        try {
            return AppViews.of(applicationRepo.save(application));
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("You are already tracking that application.");
        }
    }

    @Transactional
    public AppViews.ApplicationView update(Long id, ApplicationForm form) {
        JobApplication application = require(id);

        if (form.getCompany() != null && !form.getCompany().isBlank()) {
            application.setCompany(form.getCompany().trim());
        }
        if (form.getRoleTitle() != null) {
            application.setRoleTitle(blankToNull(form.getRoleTitle()));
        }
        if (form.getLocation() != null) {
            application.setLocation(blankToNull(form.getLocation()));
        }
        if (form.getJobUrl() != null) {
            application.setJobUrl(blankToNull(form.getJobUrl()));
        }
        if (form.getNotes() != null) {
            application.setNotes(blankToNull(form.getNotes()));
        }
        if (form.getAppliedAt() != null) {
            application.setAppliedAt(form.getAppliedAt());
        }

        JobPlatform platform = parsePlatform(form.getPlatform());
        if (platform != null) {
            application.setPlatform(platform);
        }
        ApplicationStatus status = parseStatus(form.getStatus());
        if (status != null) {
            application.setStatus(status);
        }

        // Identity is derived from platform, company and role, so any edit to
        // those has to be reflected in the key or the next scan creates a twin.
        String rekeyed = JobApplication.dedupeKey(
                application.getPlatform(), application.getCompany(), application.getRoleTitle());
        if (!rekeyed.equals(application.getDedupeKey())
                && applicationRepo.findByDedupeKey(rekeyed).isEmpty()) {
            application.setDedupeKey(rekeyed);
        }
        return AppViews.of(applicationRepo.save(application));
    }

    /**
     * Sets the status by hand. Unlike the scanner this ignores the
     * forward-only rule — a correction has to be able to move a row backwards.
     */
    @Transactional
    public AppViews.ApplicationView setStatus(Long id, String status) {
        ApplicationStatus parsed = parseStatus(status);
        if (parsed == null) {
            throw ApiException.badRequest("\"" + status + "\" is not a status.");
        }
        JobApplication application = require(id);
        application.setStatus(parsed);
        return AppViews.of(applicationRepo.save(application));
    }

    @Transactional
    public AppViews.ApplicationView setArchived(Long id, boolean archived) {
        JobApplication application = require(id);
        application.setArchived(archived);
        return AppViews.of(applicationRepo.save(application));
    }

    @Transactional
    public void delete(Long id) {
        JobApplication application = require(id);
        alertRepo.deleteByApplicationId(id);
        eventRepo.deleteByApplicationId(id);
        applicationRepo.delete(application);
    }

    private JobApplication require(Long id) {
        return applicationRepo.findById(id)
                .orElseThrow(() -> ApiException.notFound("That application does not exist."));
    }

    private static ApplicationStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ApplicationStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static JobPlatform parsePlatform(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return JobPlatform.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
