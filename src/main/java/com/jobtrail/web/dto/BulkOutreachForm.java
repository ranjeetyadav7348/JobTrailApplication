package com.jobtrail.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Add a batch of recipients that share one template and one follow-up policy.
 * They all land in the same queue and are still sent one at a time, spaced by
 * the configured interval.
 */
@Getter
@Setter
public class BulkOutreachForm {

    @NotEmpty(message = "Add at least one recipient")
    @Valid
    private List<Recipient> recipients;

    private Long initialTemplateId;
    private Long followUpTemplateId;
    private Integer followUpIntervalDays;
    private Integer maxFollowUps;
    private Boolean autoFollowUp;
    private Boolean queueNow;

    @Getter
    @Setter
    public static class Recipient {
        private String name;

        @NotBlank(message = "Recipient email is required")
        @Email(message = "That does not look like a valid email address")
        private String email;

        private String company;
        private String position;
    }
}
