package com.jobtrail.domain;

/**
 * How far an application has got. {@link #rank()} exists so a late-arriving
 * "we received your application" mail cannot drag a thread back down from
 * INTERVIEW — status only ever moves forward, except into a terminal state.
 */
public enum ApplicationStatus {

    /** Submitted; nothing has come back yet. */
    APPLIED(0, "Applied", "--s-sent", false),
    /** The employer or platform confirmed receipt. */
    ACKNOWLEDGED(1, "Acknowledged", "--s-queued", false),
    /** An online test, coding challenge or take-home is outstanding. */
    ASSESSMENT(2, "Assessment", "--s-opened", false),
    /** An interview has been scheduled or invited. */
    INTERVIEW(3, "Interview", "--accent", false),
    /** An offer arrived. */
    OFFER(4, "Offer", "--s-replied", true),
    /** Turned down. */
    REJECTED(5, "Rejected", "--s-failed", true),
    /** You pulled out. */
    WITHDRAWN(5, "Withdrawn", "--s-closed", true),
    /** No response for long enough that it is fair to call it dead. */
    GHOSTED(5, "Ghosted", "--s-draft", true);

    private final int rank;
    private final String label;
    private final String cssVar;
    private final boolean terminal;

    ApplicationStatus(int rank, String label, String cssVar, boolean terminal) {
        this.rank = rank;
        this.label = label;
        this.cssVar = cssVar;
        this.terminal = terminal;
    }

    public int rank() {
        return rank;
    }

    public String label() {
        return label;
    }

    /**
     * A CSS custom property from the app's existing, validated palette rather
     * than a new hue — the stylesheet warns that those colours were checked as
     * a set, so a tracker status reuses one instead of inventing another.
     */
    public String cssVar() {
        return cssVar;
    }

    /** Terminal states stop the staleness clock and never advance again. */
    public boolean terminal() {
        return terminal;
    }

    /** The stages that make up the conversion funnel, in order. */
    public static ApplicationStatus[] funnel() {
        return new ApplicationStatus[]{APPLIED, ACKNOWLEDGED, ASSESSMENT, INTERVIEW, OFFER};
    }

    /**
     * Whether moving from {@code current} to {@code candidate} is a real
     * advance. A terminal outcome always wins — a rejection after an interview
     * invite is the truth of the matter — but two terminal states do not
     * overwrite each other, so an accidental second classification is harmless.
     */
    public boolean advancesFrom(ApplicationStatus current) {
        if (current == null) {
            return true;
        }
        if (current.terminal) {
            return false;
        }
        return this.terminal || this.rank > current.rank;
    }
}
