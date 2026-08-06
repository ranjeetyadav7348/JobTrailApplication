package com.jobtrail.domain;

/** Lifecycle of a single job-outreach thread (the first email plus its follow-ups). */
public enum OutreachStatus {
    /** Created, nothing handed to the send queue yet. */
    DRAFT,
    /** An email for this thread is sitting in the send queue. */
    QUEUED,
    /** The opening email went out over SMTP. */
    SENT,
    /** The tracking pixel fired, so the recipient opened it at least once. */
    OPENED,
    /** A reply came back (detected over IMAP or marked by hand). Follow-ups stop. */
    REPLIED,
    /** Archived by the user. No further follow-ups. */
    CLOSED,
    /** Every send attempt failed. */
    FAILED
}
