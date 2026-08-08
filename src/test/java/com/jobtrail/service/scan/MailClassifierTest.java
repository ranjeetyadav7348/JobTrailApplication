package com.jobtrail.service.scan;

import com.jobtrail.domain.ApplicationEventKind;
import com.jobtrail.domain.JobPlatform;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MailClassifierTest {

    private final MailClassifier classifier = new MailClassifier();

    private static final Instant RECEIVED = Instant.parse("2026-08-03T09:00:00Z");

    private ScannedMessage mail(String subject, String fromName, String from, String body, String... links) {
        return new ScannedMessage("<id-" + subject.hashCode() + "@mail>", subject, fromName, from,
                body, List.of(links), RECEIVED);
    }

    // ---- platform detection ------------------------------------------------

    @Test
    void detectsPlatformFromSenderDomain() {
        ScannedMessage m = mail("Thank you for applying", "Greenhouse", "no-reply@greenhouse.io",
                "We have received your application.");
        assertThat(classifier.classify(m).platform()).isEqualTo(JobPlatform.GREENHOUSE);
    }

    @Test
    void detectsPlatformFromLinkWhenSenderIsGeneric() {
        ScannedMessage m = mail("Your application was submitted", "Careers", "noreply@sendgrid.net",
                "Track your application in the portal.",
                "https://acme.wd3.myworkdayjobs.com/en-US/careers/job/12345");
        assertThat(classifier.classify(m).platform()).isEqualTo(JobPlatform.WORKDAY);
    }

    @Test
    void treatsAnEmployerDomainAsADirectApplication() {
        ScannedMessage m = mail("Thank you for applying", "Northwind Careers", "careers@northwind.example",
                "We have received your application for the Backend Engineer position.");
        assertThat(classifier.classify(m).platform()).isEqualTo(JobPlatform.DIRECT);
    }

    @Test
    void longestSuffixWinsSoLookalikeDomainsDoNotMatch() {
        assertThat(JobPlatform.fromHost("jobs.eu.greenhouse.io")).isEqualTo(JobPlatform.GREENHOUSE);
        assertThat(JobPlatform.fromHost("greenhouse.io.phish.example")).isNull();
    }

    // ---- event kinds -------------------------------------------------------

    @Test
    void recognisesASubmissionConfirmation() {
        ScannedMessage m = mail("Thank you for applying to Northwind Labs",
                "Northwind Labs", "no-reply@greenhouse.io",
                "Hi Ranjeet,\n\nThank you for applying to Northwind Labs. "
                        + "We have received your application for the Senior Java Engineer position.");

        Classification c = classifier.classify(m);
        assertThat(c.kind()).isEqualTo(ApplicationEventKind.APPLIED);
        assertThat(c.company()).isEqualTo("Northwind Labs");
        assertThat(c.roleTitle()).isEqualTo("Senior Java Engineer");
    }

    /** Rejections quote the whole prior process, so "interview" must not win here. */
    @Test
    void rejectionBeatsTheInterviewVocabularyItContains() {
        ScannedMessage m = mail("Update on your application",
                "Acme Recruiting", "recruiting@acme.example",
                "Thank you for interviewing with us for the Platform Engineer role. "
                        + "After careful consideration we have decided to move forward with other "
                        + "candidates and will not be moving forward with your application.");

        assertThat(classifier.classify(m).kind()).isEqualTo(ApplicationEventKind.REJECTED);
    }

    @Test
    void recognisesAnAssessmentInviteAndItsLink() {
        ScannedMessage m = mail("Coding challenge for your application",
                "Acme Talent", "no-reply@greenhouse.io",
                "Please complete your online assessment. The test link is below and the "
                        + "assessment must be completed within 48 hours.",
                "https://www.hackerrank.com/test/abc123",
                "https://acme.example/unsubscribe");

        Classification c = classifier.classify(m);
        assertThat(c.kind()).isEqualTo(ApplicationEventKind.ASSESSMENT_INVITE);
        assertThat(c.actionUrl()).isEqualTo("https://www.hackerrank.com/test/abc123");
        assertThat(c.deadlineAt()).isEqualTo(RECEIVED.plus(48, ChronoUnit.HOURS));
    }

    @Test
    void recognisesAnInterviewInviteAndPrefersTheSchedulingLink() {
        ScannedMessage m = mail("Interview invitation - Backend Engineer",
                "Jane at Acme", "jane@acme.example",
                "We would like to invite you to an interview. Please share your availability "
                        + "using the link below.",
                "https://acme.example/careers",
                "https://calendly.com/acme/interview");

        Classification c = classifier.classify(m);
        assertThat(c.kind()).isEqualTo(ApplicationEventKind.INTERVIEW_INVITE);
        assertThat(c.actionUrl()).isEqualTo("https://calendly.com/acme/interview");
    }

    @Test
    void recognisesAnOffer() {
        ScannedMessage m = mail("Your offer from Northwind", "Northwind HR", "hr@northwind.example",
                "We are pleased to offer you the position of Senior Java Engineer.");
        assertThat(classifier.classify(m).kind()).isEqualTo(ApplicationEventKind.OFFER);
    }

    @Test
    void recognisesAnAcknowledgement() {
        ScannedMessage m = mail("Application update", "Acme", "no-reply@lever.co",
                "Your application is currently under review by the hiring team.");
        assertThat(classifier.classify(m).kind()).isEqualTo(ApplicationEventKind.ACKNOWLEDGED);
    }

    // ---- noise gates -------------------------------------------------------

    @Test
    void ignoresJobAlertDigests() {
        ScannedMessage m = mail("15 new jobs for you - Java Developer",
                "Naukri", "alerts@naukri.com",
                "Jobs matching your profile. Apply now to these openings. "
                        + "Similar jobs you may like.");
        assertThat(classifier.classify(m)).isNull();
    }

    /**
     * A course advert that uses a real vacancy as its hook. It names a company,
     * a role, a salary and an interview, so keyword scoring alone reads it as
     * genuine — this was counted as a reply before the marketing veto existed.
     */
    @Test
    void ignoresACourseAdvertDressedUpAsAJobAlert() {
        ScannedMessage m = mail("Turing is hiring — Software Engineer",
                "Careers Team", "hello@upskill.example",
                """
                Hi There,

                New Job Alert: Turing is hiring for a Software Engineer role with a \
                salary range of 10-15 LPA.

                Want to actually clear this interview and land a job like this?

                Today at 2:00 PM, join Saurabh, Senior Software Developer II @ Microsoft, \
                in a live trial session that shows you how top developers prepare for \
                roles like this — especially with GenAI and full-stack skills.
                """);

        assertThat(classifier.classify(m)).isNull();
        assertThat(classifier.looksPromotional(m)).isTrue();
    }

    /** A marketing domain is not an ATS, so weak evidence must not be enough. */
    @Test
    void aNonRelaySenderIsNotTreatedAsAKnownPlatform() {
        ScannedMessage m = mail("An interview tip for you", "Newsletter", "news@careers.example",
                "Here is one interview tip that helped a candidate this week.");

        assertThat(classifier.classify(m)).isNull();
    }

    /** The veto must not swallow genuine mail that happens to mention salary. */
    @Test
    void aRealOfferSurvivesTheMarketingVeto() {
        ScannedMessage m = mail("Your offer from Northwind", "Northwind HR", "hr@northwind.example",
                "We are pleased to offer you the position of Senior Java Engineer. "
                        + "Your CTC will be 24 LPA. Please register on the onboarding portal.");

        Classification c = classifier.classify(m);
        assertThat(c).isNotNull();
        assertThat(c.kind()).isEqualTo(ApplicationEventKind.OFFER);
    }

    @Test
    void ignoresMailThatIsNotAboutJobsAtAll() {
        ScannedMessage m = mail("Your invoice is ready", "Billing", "billing@saas.example",
                "Your monthly invoice for August is attached.");
        assertThat(classifier.classify(m)).isNull();
    }

    /** A real confirmation from a job board must survive the digest filter. */
    @Test
    void keepsARealConfirmationEvenFromAJobBoard() {
        ScannedMessage m = mail("Your application was sent to Northwind Labs",
                "Indeed", "no-reply@indeed.com",
                "Thank you for applying. Your application was submitted to Northwind Labs "
                        + "for the Java Developer position. See similar jobs below.");

        Classification c = classifier.classify(m);
        assertThat(c).isNotNull();
        assertThat(c.kind()).isEqualTo(ApplicationEventKind.APPLIED);
        assertThat(c.platform()).isEqualTo(JobPlatform.INDEED);
    }

    // ---- extraction --------------------------------------------------------

    @Test
    void takesTheCompanyFromAnAtsJobUrlWhenTheBodyIsVague() {
        ScannedMessage m = mail("Application received", "Recruiting Team", "no-reply@sendgrid.net",
                "Thanks for applying. We will be in touch.",
                "https://jobs.lever.co/northwind-labs/8f2c1a");

        assertThat(classifier.classify(m).company()).isEqualTo("Northwind Labs");
    }

    @Test
    void stripsPersonaWordsFromTheSenderName() {
        ScannedMessage m = mail("Application received", "Northwind Careers", "no-reply@sendgrid.net",
                "Thank you for applying. We have received your application.");
        assertThat(classifier.classify(m).company()).isEqualTo("Northwind");
    }

    @Test
    void parsesAnAbsoluteDeadline() {
        ScannedMessage m = mail("Online assessment", "Acme", "no-reply@greenhouse.io",
                "Please complete the online assessment by 15 August 2026.",
                "https://www.codility.com/test/xyz");

        Instant expected = java.time.LocalDate.of(2026, 8, 15)
                .atTime(23, 59).atZone(java.time.ZoneId.systemDefault()).toInstant();
        assertThat(classifier.classify(m).deadlineAt()).isEqualTo(expected);
    }

    @Test
    void neverReturnsASentenceAsARoleTitle() {
        ScannedMessage m = mail("Thank you for applying", "Acme", "no-reply@greenhouse.io",
                "Thank you for applying to Acme. Your application for the role has been "
                        + "received and we will review it shortly.");

        String role = classifier.classify(m).roleTitle();
        assertThat(role == null || role.split("\\s+").length <= 9).isTrue();
    }

    @Test
    void ignoresUnsubscribeLinksWhenPickingAnAction() {
        ScannedMessage m = mail("Application update", "Acme", "no-reply@greenhouse.io",
                "Your application is under review.",
                "https://acme.example/unsubscribe?u=1",
                "https://acme.example/status/42");

        assertThat(classifier.classify(m).actionUrl()).isEqualTo("https://acme.example/status/42");
    }

    @Test
    void marksAThinSignalAsLowConfidence() {
        ScannedMessage m = mail("Application status", "Acme", "no-reply@lever.co",
                "There is an update on your application status.");

        Classification c = classifier.classify(m);
        assertThat(c.kind()).isEqualTo(ApplicationEventKind.STATUS_UPDATE);
        assertThat(c.lowConfidence()).isTrue();
    }
}
