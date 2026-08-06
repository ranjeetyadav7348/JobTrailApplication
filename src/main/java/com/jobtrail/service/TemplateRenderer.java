package com.jobtrail.service;

import com.jobtrail.domain.AppSettings;
import com.jobtrail.domain.Outreach;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills {@code {{placeholder}}} tokens and wraps a body into an email-safe
 * HTML document. A matching plain-text part is generated too: multipart
 * text+HTML mail scores noticeably better with spam filters than HTML alone.
 */
@Component
public class TemplateRenderer {

    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*}}");
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_FMT =
            DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH);

    /** The tokens offered in the template editor's help panel. */
    public static final String[] SUPPORTED_TOKENS = {
            "name", "first_name", "company", "role", "my_name", "my_email", "date", "day"
    };

    public Map<String, String> variables(Outreach o, AppSettings s) {
        Map<String, String> vars = new HashMap<>();
        String name = o.displayName();
        vars.put("name", name);
        vars.put("first_name", name.split("[\\s.]+")[0]);
        vars.put("company", blankToFallback(o.getCompany(), "your team"));
        vars.put("role", blankToFallback(o.getPosition(), "the role"));
        vars.put("position", blankToFallback(o.getPosition(), "the role"));
        vars.put("my_name", blankToFallback(s.getFromName(), ""));
        vars.put("my_email", blankToFallback(s.getFromEmail(), ""));
        vars.put("date", LocalDate.now().format(DATE_FMT));
        vars.put("day", LocalDate.now().format(DAY_FMT));
        return vars;
    }

    /** The recipient-independent subset, for things like the signature block. */
    public Map<String, String> variables(AppSettings s) {
        Map<String, String> vars = new HashMap<>();
        vars.put("my_name", blankToFallback(s.getFromName(), ""));
        vars.put("my_email", blankToFallback(s.getFromEmail(), ""));
        vars.put("date", LocalDate.now().format(DATE_FMT));
        vars.put("day", LocalDate.now().format(DAY_FMT));
        return vars;
    }

    public String render(String template, Map<String, String> vars) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        Matcher m = TOKEN.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = vars.getOrDefault(m.group(1).toLowerCase(Locale.ROOT), "");
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    public String render(String template, Outreach o, AppSettings s) {
        return render(template, variables(o, s));
    }

    /** Wraps the rendered body in a minimal, client-safe HTML shell. */
    public String wrapHtml(String bodyHtml, String signatureHtml, String trackingPixelUrl) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
          .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
          .append("</head><body style=\"margin:0;padding:0;background:#ffffff;\">")
          .append("<div style=\"font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;")
          .append("font-size:15px;line-height:1.6;color:#1f2933;max-width:620px;\">")
          .append(bodyHtml == null ? "" : bodyHtml);

        if (signatureHtml != null && !signatureHtml.isBlank()) {
            sb.append("<div style=\"margin-top:18px;color:#52606d;font-size:14px;\">")
              .append(signatureHtml)
              .append("</div>");
        }
        sb.append("</div>");

        if (trackingPixelUrl != null && !trackingPixelUrl.isBlank()) {
            sb.append("<img src=\"").append(trackingPixelUrl)
              .append("\" width=\"1\" height=\"1\" alt=\"\" style=\"display:block;border:0;\">");
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    /** Best-effort plain-text alternative derived from the HTML body. */
    public String toPlainText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String text = html
                .replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", "")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p\\s*>", "\n\n")
                .replaceAll("(?i)</(div|tr|li|h[1-6])\\s*>", "\n")
                .replaceAll("(?i)<li[^>]*>", "  - ")
                .replaceAll("<[^>]+>", "");
        text = text.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        return text.replaceAll("[ \\t]+\n", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    private static String blankToFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
