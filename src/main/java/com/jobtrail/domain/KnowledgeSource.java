package com.jobtrail.domain;

/**
 * Where a {@link KnowledgeChunk} came from. Retrieval mixes all of these, but a
 * decision is only as trustworthy as the source behind it, so the origin is
 * carried all the way through to the citation the user sees.
 */
public enum KnowledgeSource {

    /**
     * The CV. This is the authority on what the user has actually done, and the
     * only source allowed to settle a claim about their experience.
     */
    RESUME("Résumé"),

    /** A tracked application: company, role, platform, stage, dates. */
    APPLICATION("Application"),

    /** One email in an application's thread. */
    EVENT("Email"),

    /** Text pasted in for a one-off question — a job description, usually. */
    ADHOC("Pasted");

    private final String label;

    KnowledgeSource(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
