package com.jobtrail.service;

import com.jobtrail.domain.ApplicationEvent;
import com.jobtrail.domain.ApplicationEventKind;
import com.jobtrail.domain.ApplicationStatus;
import com.jobtrail.domain.JobApplication;
import com.jobtrail.domain.JobPlatform;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules that decide what the dashboard's headline numbers mean. These are
 * pure-domain assertions — no database, no mailbox.
 */
class ApplicationFunnelTest {

    private JobApplication application() {
        JobApplication a = new JobApplication();
        a.setCompany("Northwind Labs");
        a.setPlatform(JobPlatform.GREENHOUSE);
        a.setAppliedAt(Instant.parse("2026-07-01T09:00:00Z"));
        a.setDedupeKey(JobApplication.dedupeKey(JobPlatform.GREENHOUSE, "Northwind Labs", null));
        return a;
    }

    private ApplicationEvent event(ApplicationEventKind kind, String at) {
        ApplicationEvent e = new ApplicationEvent();
        e.setKind(kind);
        e.setMessageId("<" + kind + "@mail>");
        e.setReceivedAt(Instant.parse(at));
        return e;
    }

    @Test
    void statusMovesForwardAsEventsArrive() {
        JobApplication a = application();
        a.apply(event(ApplicationEventKind.APPLIED, "2026-07-01T09:00:00Z"));
        assertThat(a.getStatus()).isEqualTo(ApplicationStatus.APPLIED);

        a.apply(event(ApplicationEventKind.ASSESSMENT_INVITE, "2026-07-04T09:00:00Z"));
        assertThat(a.getStatus()).isEqualTo(ApplicationStatus.ASSESSMENT);
    }

    /** A confirmation mail arriving late must not undo real progress. */
    @Test
    void aLateConfirmationDoesNotDragTheStatusBackwards() {
        JobApplication a = application();
        a.apply(event(ApplicationEventKind.INTERVIEW_INVITE, "2026-07-10T09:00:00Z"));
        a.apply(event(ApplicationEventKind.APPLIED, "2026-07-01T09:00:00Z"));

        assertThat(a.getStatus()).isEqualTo(ApplicationStatus.INTERVIEW);
    }

    @Test
    void aTerminalOutcomeAlwaysWins() {
        JobApplication a = application();
        a.apply(event(ApplicationEventKind.INTERVIEW_INVITE, "2026-07-10T09:00:00Z"));
        a.apply(event(ApplicationEventKind.REJECTED, "2026-07-12T09:00:00Z"));

        assertThat(a.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(a.staleDays()).isEqualTo(-1);
    }

    @Test
    void theResponseClockIgnoresTheSubmissionConfirmation() {
        JobApplication a = application();
        a.apply(event(ApplicationEventKind.APPLIED, "2026-07-01T09:00:00Z"));
        assertThat(a.getFirstResponseAt()).isNull();

        a.apply(event(ApplicationEventKind.ACKNOWLEDGED, "2026-07-05T09:00:00Z"));
        assertThat(a.getFirstResponseAt()).isEqualTo(Instant.parse("2026-07-05T09:00:00Z"));
    }

    @Test
    void assessmentInvitesCarryTheirLinkAndDeadlineOntoTheApplication() {
        JobApplication a = application();
        ApplicationEvent e = event(ApplicationEventKind.ASSESSMENT_INVITE, "2026-07-04T09:00:00Z");
        e.setActionUrl("https://www.hackerrank.com/test/abc");
        e.setDeadlineAt(Instant.parse("2026-07-06T09:00:00Z"));
        a.apply(e);

        assertThat(a.getAssessmentUrl()).isEqualTo("https://www.hackerrank.com/test/abc");
        assertThat(a.getAssessmentDueAt()).isEqualTo(Instant.parse("2026-07-06T09:00:00Z"));
    }

    @Test
    void companiesAreMatchedAcrossLegalSuffixes() {
        assertThat(JobApplication.normalise("Acme Technologies Pvt. Ltd."))
                .isEqualTo(JobApplication.normalise("Acme"));
        assertThat(JobApplication.normalise("Northwind Labs"))
                .isNotEqualTo(JobApplication.normalise("Southwind Labs"));
    }

    /** A name made entirely of stripped words still has to produce a key. */
    @Test
    void normalisationNeverCollapsesANameToNothing() {
        assertThat(JobApplication.normalise("Technologies Ltd")).isNotEmpty();
    }

    @Test
    void aLiveApplicationGoesStaleButATerminalOneDoesNot() {
        JobApplication live = application();
        live.setLastEventAt(Instant.now().minusSeconds(40L * 86400));
        assertThat(live.looksGhosted()).isTrue();

        JobApplication done = application();
        done.setStatus(ApplicationStatus.REJECTED);
        done.setLastEventAt(Instant.now().minusSeconds(40L * 86400));
        assertThat(done.looksGhosted()).isFalse();
    }
}
