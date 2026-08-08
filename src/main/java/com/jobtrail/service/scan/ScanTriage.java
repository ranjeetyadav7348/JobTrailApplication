package com.jobtrail.service.scan;

/**
 * Decides, for one message, whether the keyword rules can be trusted or a model
 * should be asked. Kept as a pure function of the rule result so the escalation
 * policy — the thing that governs both accuracy and cost — can be tested
 * without a mailbox or an API key.
 */
public enum ScanTriage {

    /** No job vocabulary at all. Ignore it; do not spend a call on it. */
    IGNORE,

    /** The rules were confident. Use them as-is. */
    TRUST_RULES,

    /**
     * The rules matched, but weakly. This is where wrong stages and wrong
     * company names come from, so it is worth a second opinion.
     */
    VERIFY_WITH_AI,

    /**
     * The rules rejected it but it still reads like job mail — an unfamiliar
     * ATS, or an employer writing in their own words. This is the recall gap
     * the rules cannot close on their own.
     */
    RESCUE_WITH_AI;

    public boolean needsAi() {
        return this == VERIFY_WITH_AI || this == RESCUE_WITH_AI;
    }

    /**
     * @param ruleResult what {@link MailClassifier#classify} returned, possibly null
     * @param mightBeJobMail {@link MailClassifier#mightBeJobMail} for the same message
     * @param looksPromotional {@link MailClassifier#looksPromotional} for the same message
     * @param aiAvailable whether AI triage is switched on and has a usable key
     */
    public static ScanTriage of(Classification ruleResult,
                                boolean mightBeJobMail,
                                boolean looksPromotional,
                                boolean aiAvailable) {
        if (ruleResult == null) {
            if (!aiAvailable) {
                return IGNORE;
            }
            return mightBeJobMail ? RESCUE_WITH_AI : IGNORE;
        }
        // Marketing mail discusses hiring, salaries and interviews in as much
        // detail as the real thing, so a confident keyword score is no defence
        // here. When it smells of advertising, ask — confidence notwithstanding.
        if (aiAvailable && looksPromotional) {
            return VERIFY_WITH_AI;
        }
        if (!ruleResult.lowConfidence()) {
            return TRUST_RULES;
        }
        // Without AI the old behaviour stands: keep a weak match rather than
        // lose it. Turning AI off must never discard data that used to work.
        return aiAvailable ? VERIFY_WITH_AI : TRUST_RULES;
    }
}
