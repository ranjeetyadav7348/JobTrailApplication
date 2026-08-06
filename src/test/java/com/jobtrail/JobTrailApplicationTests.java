package com.jobtrail;

import com.jobtrail.repo.EmailTemplateRepository;
import com.jobtrail.service.SettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the whole context. This is the cheapest way to catch a broken bean
 * graph or a bad JPA mapping before the app is ever run by hand.
 */
@SpringBootTest
class JobTrailApplicationTests {

    @Autowired
    private SettingsService settingsService;

    @Autowired
    private EmailTemplateRepository templates;

    @Test
    void contextLoadsAndSeedsStarterData() {
        assertThat(settingsService.get()).isNotNull();
        assertThat(templates.count()).isGreaterThan(0);
    }

    @Test
    void sendingIsInertUntilSmtpIsConfigured() {
        // No credentials in the test profile, so the dispatcher must refuse to send.
        assertThat(settingsService.get().smtpConfigured()).isFalse();
    }
}
