package com.jobtrail.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Adding or editing an application by hand — for the ones that never sent a
 * confirmation email, and for correcting anything the scanner guessed wrong.
 */
@Getter
@Setter
public class ApplicationForm {

    @NotBlank(message = "Company is required")
    @Size(max = 200, message = "Company name is too long")
    private String company;

    @Size(max = 250, message = "Role title is too long")
    private String roleTitle;

    @Size(max = 160, message = "Location is too long")
    private String location;

    /** {@link com.jobtrail.domain.JobPlatform} name. Blank means DIRECT. */
    private String platform;

    /** {@link com.jobtrail.domain.ApplicationStatus} name. Blank leaves it unchanged. */
    private String status;

    @Size(max = 1000, message = "Job URL is too long")
    private String jobUrl;

    @Size(max = 2000, message = "Notes are too long")
    private String notes;

    private Instant appliedAt;
}
