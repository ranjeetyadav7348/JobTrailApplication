package com.jobtrail.service.scan;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * What the model decided about one message. The field descriptions are part of
 * the contract — they are sent to the model as the JSON schema, so they are
 * written for it to read rather than for a human skimming the class.
 */
public record AiVerdict(

        @JsonPropertyDescription("""
                True only if this email is about a specific job application, \
                interview, assessment or offer involving the reader personally. \
                False for job-alert digests, "jobs you may like" recommendation \
                emails, marketing, newsletters, course adverts and any bulk mail \
                that is not about one particular application.""")
        boolean jobRelated,

        @JsonPropertyDescription("""
                One of: APPLIED, ACKNOWLEDGED, ASSESSMENT_INVITE, INTERVIEW_INVITE, \
                OFFER, REJECTED, RECRUITER_OUTREACH, STATUS_UPDATE, OTHER. \
                Use REJECTED for any rejection however politely worded, even when \
                the email also describes interviews that already happened. \
                Use ASSESSMENT_INVITE for online tests, coding challenges and \
                take-home exercises. Use RECRUITER_OUTREACH when a recruiter is \
                pitching a role the reader never applied for.""")
        String kind,

        @JsonPropertyDescription("""
                The employer's name only — not the applicant tracking system, \
                not the job board, not the sender's mailbox name. Use the empty \
                string if the email never names the employer.""")
        String company,

        @JsonPropertyDescription("""
                The job title only, without seniority boilerplate, location or \
                requisition numbers. Empty string if no role is named.""")
        String roleTitle,

        @JsonPropertyDescription("""
                True if this is bulk, promotional or automated marketing mail \
                rather than correspondence about the reader's own application.""")
        boolean promotional,

        @JsonPropertyDescription("How certain you are, from 0.0 to 1.0.")
        double confidence,

        @JsonPropertyDescription("One short sentence explaining the decision.")
        String reason) {
}
