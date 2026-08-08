package com.jobtrail.config;

import com.jobtrail.domain.EmailTemplate;
import com.jobtrail.domain.TemplateKind;
import com.jobtrail.repo.EmailTemplateRepository;
import com.jobtrail.service.SettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Creates the settings row and a starter set of templates on first boot. */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataSeeder {

    private final EmailTemplateRepository templates;
    private final SettingsService settingsService;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        settingsService.get();

        if (templates.count() > 0) {
            return;
        }

        templates.save(template("Cold application", TemplateKind.INITIAL, true,
                "Application for {{role}} at {{company}}",
                """
                <p>Hi {{first_name}},</p>
                <p>I came across the <strong>{{role}}</strong> opening at {{company}} and wanted to \
                reach out directly rather than disappear into the applicant pile.</p>
                <p>A quick sense of why I think I would be useful to you:</p>
                <ul>
                  <li>Replace this line with the result you are proudest of.</li>
                  <li>Replace this line with the skill that matches the job description most closely.</li>
                  <li>Replace this line with something specific about {{company}} you actually like.</li>
                </ul>
                <p>Happy to send my CV or a short portfolio link if that is easier. \
                Would you be open to a 15-minute chat this week or next?</p>
                <p>Thanks for your time,<br>{{my_name}}</p>
                """));

        templates.save(template("Referral request", TemplateKind.INITIAL, false,
                "Quick question about the {{role}} role at {{company}}",
                """
                <p>Hi {{first_name}},</p>
                <p>I am applying for the <strong>{{role}}</strong> role at {{company}} and noticed you \
                work there. Would you be open to sharing what the team is like, or pointing me to the \
                right person to speak with?</p>
                <p>I know referrals are a big ask, so no pressure at all — even a two-line answer \
                would help me a lot.</p>
                <p>Thanks either way,<br>{{my_name}}</p>
                """));

        templates.save(template("Gentle nudge", TemplateKind.FOLLOW_UP, true,
                "Following up on {{role}}",
                """
                <p>Dear Hiring Team,</p>
                <p>I hope you are doing well.</p>
                <p>I wanted to follow up regarding my application for the position at your \
                organization. I remain very interested in the opportunity and would appreciate any \
                update you can share regarding the status of my application.</p>
                <p>I am excited about the possibility of contributing to your team and would be \
                happy to provide any additional information if needed.</p>
                <p>Thank you for your time and consideration. I look forward to hearing from you.</p>
                """));

        templates.save(template("Last follow-up", TemplateKind.FOLLOW_UP, false,
                "One last note about {{role}}",
                """
                <p>Hi {{first_name}},</p>
                <p>This is my last note on the <strong>{{role}}</strong> role — I do not want to \
                clutter your inbox.</p>
                <p>If anything opens up at {{company}} later, I would still love to hear about it. \
                Wishing you a good rest of the week.</p>
                <p>Best,<br>{{my_name}}</p>
                """));

        log.info("Seeded {} starter email templates", templates.count());
    }

    private EmailTemplate template(String name, TemplateKind kind, boolean isDefault,
                                   String subject, String body) {
        EmailTemplate t = new EmailTemplate();
        t.setName(name);
        t.setKind(kind);
        t.setDefault(isDefault);
        t.setSubject(subject);
        t.setBodyHtml(body.strip());
        return t;
    }
}
