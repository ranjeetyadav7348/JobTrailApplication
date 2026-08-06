package com.jobtrail.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
public class OutreachForm {

    @Size(max = 160)
    private String recipientName;

    @NotBlank(message = "Recipient email is required")
    @Email(message = "That does not look like a valid email address")
    @Size(max = 254)
    private String recipientEmail;

    @Size(max = 160)
    private String company;

    @Size(max = 160)
    private String position;

    @Size(max = 2000)
    private String notes;

    private Long initialTemplateId;
    private Long followUpTemplateId;

    private Integer followUpIntervalDays;
    private Integer maxFollowUps;
    private Boolean autoFollowUp;

    /** Put the opening email straight into the send queue. */
    private Boolean queueNow;

    /** Optional earliest send time; the pacing rules still apply on top. */
    private Instant scheduledAt;

    /** One-off overrides for this recipient, used instead of the template body. */
    private String subjectOverride;
    private String bodyOverride;
}
