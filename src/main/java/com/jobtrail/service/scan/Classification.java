package com.jobtrail.service.scan;

import com.jobtrail.domain.ApplicationEventKind;
import com.jobtrail.domain.JobPlatform;

import java.time.Instant;

/**
 * What the classifier made of one message. A {@code null} classification means
 * "this is not job mail" — the scanner then ignores the message entirely.
 *
 * @param kind       what the mail was
 * @param platform   where it came from
 * @param company    employer, best effort; never blank (falls back to "Unknown")
 * @param roleTitle  role, or null when the mail never named one
 * @param location   job location, or null
 * @param actionUrl  the link worth clicking — test, scheduling or portal link
 * @param jobUrl     link to the posting, when distinguishable from the action link
 * @param deadlineAt parsed assessment deadline, or null
 * @param confidence 0..1, how much of this was a clear signal rather than a guess
 */
public record Classification(
        ApplicationEventKind kind,
        JobPlatform platform,
        String company,
        String roleTitle,
        String location,
        String actionUrl,
        String jobUrl,
        Instant deadlineAt,
        double confidence) {

    /** Below this the UI marks the row as needing a human glance. */
    public static final double LOW_CONFIDENCE = 0.45d;

    public boolean lowConfidence() {
        return confidence < LOW_CONFIDENCE;
    }
}
