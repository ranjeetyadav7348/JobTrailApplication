package com.jobtrail.service;

import com.jobtrail.domain.AppSettings;
import com.jobtrail.web.dto.SettingsForm;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The anti-spam guarantee, pinned down: whatever the UI asks for, two sends can
 * never be closer together than the configured floor.
 */
@SpringBootTest
class SendPacingTest {

    @Autowired
    private SettingsService settingsService;

    @Test
    void intervalBelowTheFloorIsClampedUp() {
        SettingsForm form = new SettingsForm();
        form.setMinIntervalSeconds(1);
        AppSettings saved = settingsService.update(form);

        assertThat(saved.getMinIntervalSeconds()).isEqualTo(settingsService.intervalFloorSeconds());
        assertThat(saved.getMinIntervalSeconds()).isGreaterThanOrEqualTo(5);
    }

    @Test
    void gapNeverDropsBelowTheFloorEvenWithoutJitter() {
        SettingsForm form = new SettingsForm();
        form.setMinIntervalSeconds(0);
        form.setJitterSeconds(0);
        AppSettings settings = settingsService.update(form);

        for (int i = 0; i < 200; i++) {
            assertThat(settingsService.nextGapMillis(settings)).isGreaterThanOrEqualTo(5_000L);
        }
    }

    @Test
    void jitterStaysInsideItsBand() {
        SettingsForm form = new SettingsForm();
        form.setMinIntervalSeconds(10);
        form.setJitterSeconds(4);
        AppSettings settings = settingsService.update(form);

        for (int i = 0; i < 200; i++) {
            long gap = settingsService.nextGapMillis(settings);
            assertThat(gap).isBetween(10_000L, 14_000L);
        }
    }

    /**
     * A stale entity carrying an out-of-range value must still be paced safely —
     * the floor is applied when the gap is computed, not only when it is stored.
     */
    @Test
    void floorIsAppliedAtComputeTimeToo() {
        AppSettings rogue = new AppSettings();
        rogue.setMinIntervalSeconds(-99);
        rogue.setJitterSeconds(0);

        assertThat(settingsService.nextGapMillis(rogue)).isGreaterThanOrEqualTo(5_000L);
    }
}
