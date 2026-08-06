package com.jobtrail.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TemplateForm {

    @NotBlank(message = "Give the template a name")
    @Size(max = 120)
    private String name;

    /** INITIAL or FOLLOW_UP. */
    private String kind;

    @Size(max = 500)
    private String subject;

    @NotBlank(message = "The template body cannot be empty")
    @Size(max = 20000)
    private String bodyHtml;

    private Boolean isDefault;
}
