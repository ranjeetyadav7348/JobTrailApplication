package com.jobtrail.service;

import com.jobtrail.domain.AppSettings;
import com.jobtrail.domain.Outreach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateRendererTest {

    private final TemplateRenderer renderer = new TemplateRenderer();

    private Outreach outreach() {
        Outreach o = new Outreach();
        o.setRecipientName("Priya Raman");
        o.setRecipientEmail("priya@northwind.example");
        o.setCompany("Northwind Labs");
        o.setPosition("Backend Engineer");
        return o;
    }

    private AppSettings settings() {
        AppSettings s = new AppSettings();
        s.setFromName("Alex Mercer");
        s.setFromEmail("alex@example.com");
        return s;
    }

    @Test
    void fillsKnownPlaceholders() {
        String out = renderer.render(
                "Hi {{first_name}}, about {{role}} at {{company}} — {{my_name}}",
                outreach(), settings());

        assertThat(out).isEqualTo("Hi Priya, about Backend Engineer at Northwind Labs — Alex Mercer");
    }

    /** An unknown token must never reach the recipient as literal braces. */
    @Test
    void unknownPlaceholdersBecomeEmpty() {
        String out = renderer.render("Hello {{nope}}!", outreach(), settings());
        assertThat(out).isEqualTo("Hello !");
    }

    @Test
    void fallsBackWhenNameIsMissing() {
        Outreach o = outreach();
        o.setRecipientName(null);
        assertThat(renderer.render("{{first_name}}", o, settings())).isEqualTo("priya");
    }

    @Test
    void derivesAReadablePlainTextPart() {
        String plain = renderer.toPlainText(
                "<p>Hi Priya,</p><p>I saw the <strong>Backend</strong> role.</p><br><p>Thanks &amp; bye</p>");

        assertThat(plain).contains("Hi Priya,");
        assertThat(plain).contains("I saw the Backend role.");
        assertThat(plain).contains("Thanks & bye");
        assertThat(plain).doesNotContain("<").doesNotContain("&amp;");
    }

    @Test
    void wrapsBodyAndAppendsTrackingPixelOnlyWhenAsked() {
        String withPixel = renderer.wrapHtml("<p>Hi</p>", "", "http://localhost:8080/t/abc.gif");
        String without = renderer.wrapHtml("<p>Hi</p>", "", null);

        assertThat(withPixel).contains("http://localhost:8080/t/abc.gif");
        assertThat(without).doesNotContain("<img");
    }
}
