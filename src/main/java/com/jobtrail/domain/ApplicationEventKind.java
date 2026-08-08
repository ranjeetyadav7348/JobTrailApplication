package com.jobtrail.domain;

/**
 * What a single piece of inbound job mail actually was. One email produces at
 * most one event; the event's kind is what moves the parent application's
 * {@link ApplicationStatus} along.
 */
public enum ApplicationEventKind {

    /** "Thanks for applying" / "Your application has been submitted". */
    APPLIED("Applied", ApplicationStatus.APPLIED, false),
    /** Recruiter or ATS confirming a human has it. */
    ACKNOWLEDGED("Acknowledged", ApplicationStatus.ACKNOWLEDGED, false),
    /** An online test, coding challenge or take-home, usually with a link and a deadline. */
    ASSESSMENT_INVITE("Assessment", ApplicationStatus.ASSESSMENT, true),
    /** An interview invitation or scheduling request. */
    INTERVIEW_INVITE("Interview", ApplicationStatus.INTERVIEW, true),
    /** An offer. */
    OFFER("Offer", ApplicationStatus.OFFER, true),
    /** A rejection, however politely worded. */
    REJECTED("Rejected", ApplicationStatus.REJECTED, false),
    /** Inbound from a recruiter about a role you did not apply for. */
    RECRUITER_OUTREACH("Recruiter", null, false),
    /** "Your application is under review", stage changes, generic progress mail. */
    STATUS_UPDATE("Update", null, false),
    /** Job mail that did not fit anything above. */
    OTHER("Other", null, false);

    private final String label;
    private final ApplicationStatus impliedStatus;
    private final boolean alertWorthy;

    ApplicationEventKind(String label, ApplicationStatus impliedStatus, boolean alertWorthy) {
        this.label = label;
        this.impliedStatus = impliedStatus;
        this.alertWorthy = alertWorthy;
    }

    public String label() {
        return label;
    }

    /** The status this event moves the application to, or {@code null} to leave it alone. */
    public ApplicationStatus impliedStatus() {
        return impliedStatus;
    }

    /** Whether seeing this should interrupt you with a popup. */
    public boolean alertWorthy() {
        return alertWorthy;
    }
}
