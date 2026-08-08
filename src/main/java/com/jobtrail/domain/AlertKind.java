package com.jobtrail.domain;

/** What an {@link Alert} is telling you about, and how loudly. */
public enum AlertKind {

    /** A test or coding challenge landed — the one you asked to be interrupted for. */
    ASSESSMENT("Assessment link", "critical"),
    /** An interview invitation or scheduling request. */
    INTERVIEW("Interview invite", "critical"),
    /** An offer. */
    OFFER("Offer", "critical"),
    /** An assessment deadline is close and the application has not moved on. */
    DEADLINE("Deadline approaching", "warn"),
    /** The mailbox scan failed, so the numbers on screen may be stale. */
    SCAN_ERROR("Scan problem", "warn");

    private final String label;
    private final String severity;

    AlertKind(String label, String severity) {
        this.label = label;
        this.severity = severity;
    }

    public String label() {
        return label;
    }

    public String severity() {
        return severity;
    }

    /** Critical alerts open a popup; the rest only badge the alert centre. */
    public boolean popup() {
        return "critical".equals(severity);
    }
}
