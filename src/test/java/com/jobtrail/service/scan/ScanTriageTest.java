package com.jobtrail.service.scan;

import com.jobtrail.domain.ApplicationEventKind;
import com.jobtrail.domain.JobPlatform;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The escalation policy. These assertions govern both how much the scan gets
 * right and how much it costs, so they are worth pinning down precisely.
 */
class ScanTriageTest {

    private final MailClassifier classifier = new MailClassifier();

    private Classification confident() {
        return new Classification(ApplicationEventKind.APPLIED, JobPlatform.GREENHOUSE,
                "Northwind Labs", "Java Engineer", null, null, null, null, 0.9d);
    }

    private Classification weak() {
        return new Classification(ApplicationEventKind.STATUS_UPDATE, JobPlatform.LEVER,
                "Acme", null, null, null, null, null, 0.3d);
    }

    @Test
    void aConfidentRuleMatchNeverCostsAnApiCall() {
        assertThat(ScanTriage.of(confident(), true, false, true)).isEqualTo(ScanTriage.TRUST_RULES);
    }

    @Test
    void aWeakRuleMatchIsVerified() {
        assertThat(ScanTriage.of(weak(), true, false, true)).isEqualTo(ScanTriage.VERIFY_WITH_AI);
    }

    /**
     * The case that motivated AI triage: an advert the rules scored confidently.
     * Confidence is no defence when the message smells of marketing.
     */
    @Test
    void aConfidentMatchIsStillVerifiedWhenItLooksLikeAnAdvert() {
        assertThat(ScanTriage.of(confident(), true, true, true)).isEqualTo(ScanTriage.VERIFY_WITH_AI);
    }

    @Test
    void mailTheRulesRejectedIsRescuedOnlyIfItReadsLikeJobMail() {
        assertThat(ScanTriage.of(null, true, false, true)).isEqualTo(ScanTriage.RESCUE_WITH_AI);
        assertThat(ScanTriage.of(null, false, false, true)).isEqualTo(ScanTriage.IGNORE);
    }

    /** Nothing should reach the model when the feature is off. */
    @Test
    void nothingEscalatesWhenAiIsUnavailable() {
        assertThat(ScanTriage.of(confident(), true, true, false).needsAi()).isFalse();
        assertThat(ScanTriage.of(weak(), true, true, false).needsAi()).isFalse();
        assertThat(ScanTriage.of(null, true, true, false).needsAi()).isFalse();
    }

    /** Switching AI off must not lose a match the rules already made. */
    @Test
    void turningAiOffKeepsTheOldBehaviourExactly() {
        assertThat(ScanTriage.of(weak(), true, false, false)).isEqualTo(ScanTriage.TRUST_RULES);
        assertThat(ScanTriage.of(null, true, false, false)).isEqualTo(ScanTriage.IGNORE);
    }

    // ---- the gate that decides what is even worth escalating ---------------

    private ScannedMessage mail(String subject, String from, String body) {
        return new ScannedMessage("<id@mail>", subject, "", from, body, List.of(), Instant.now());
    }

    @Test
    void unrelatedMailIsNotWorthAskingAbout() {
        assertThat(classifier.mightBeJobMail(
                mail("Your invoice is ready", "billing@saas.example",
                        "Your monthly invoice is attached."))).isFalse();
    }

    /** The recall case: an ATS the keyword rules have no phrases for. */
    @Test
    void mailFromAKnownPlatformIsAlwaysWorthAsking() {
        assertThat(classifier.mightBeJobMail(
                mail("A note from the team", "no-reply@greenhouse.io",
                        "Some wording our rules have never seen."))).isTrue();
    }

    @Test
    void jobVocabularyAloneIsEnoughToAsk() {
        assertThat(classifier.mightBeJobMail(
                mail("Hello", "someone@startup.example",
                        "We reviewed your application and wanted to reach out."))).isTrue();
    }
}
