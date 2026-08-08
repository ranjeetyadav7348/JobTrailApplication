package com.jobtrail.service.scan;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a JavaMail {@link Message} into the flat {@link ScannedMessage} the
 * classifier works on: readable text, a de-duplicated link list, and a stable
 * identifier.
 *
 * <p>Kept separate from the IMAP plumbing so the messy parts — MIME walking,
 * HTML stripping, link extraction — can be reasoned about on their own.
 */
@Component
public class MessageReader {

    /** Beyond this a body is boilerplate; the classifier has what it needs. */
    private static final int MAX_BODY_CHARS = 40_000;
    private static final int MAX_LINKS = 60;

    private static final Pattern HREF = Pattern.compile(
            "href\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE_URL = Pattern.compile(
            "https?://[\\w.-]+(?:/[^\\s<>\"')\\]]*)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCRIPT_OR_STYLE = Pattern.compile(
            "<(script|style)[^>]*>.*?</\\1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^>]+>", Pattern.DOTALL);

    public ScannedMessage read(Message message) throws Exception {
        String subject = decode(message.getSubject());
        Date when = message.getReceivedDate() != null ? message.getReceivedDate() : message.getSentDate();
        Instant receivedAt = when != null ? when.toInstant() : Instant.now();

        String fromAddress = "";
        String fromName = "";
        Address[] from = message.getFrom();
        if (from != null && from.length > 0 && from[0] instanceof InternetAddress ia) {
            fromAddress = ia.getAddress() == null ? "" : ia.getAddress();
            fromName = decode(ia.getPersonal());
        }

        Body body = new Body();
        collect(message, body);

        String text = body.plain.isEmpty() ? stripHtml(body.html) : body.plain.toString();
        List<String> links = extractLinks(body.html.toString(), text);

        return new ScannedMessage(
                identify(message, fromAddress, subject, receivedAt),
                subject, fromName, fromAddress,
                trim(text, MAX_BODY_CHARS), links, receivedAt);
    }

    /**
     * The RFC {@code Message-ID} when present, otherwise a digest of the
     * sender, subject and timestamp. Some ATS mailers omit the header, and
     * without a stable key every re-scan would insert the message again.
     */
    private String identify(Message message, String from, String subject, Instant receivedAt) {
        try {
            if (message instanceof MimeMessage mime) {
                String id = mime.getMessageID();
                if (id != null && !id.isBlank()) {
                    return trim(id.trim(), 400);
                }
            }
        } catch (Exception ignored) {
            // fall through to the synthetic key
        }
        return "synthetic:" + sha256(from + '\u0000' + subject + '\u0000' + receivedAt.getEpochSecond());
    }

    private static String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 20);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JLS", e);
        }
    }

    /** Plain and HTML parts accumulate separately so we can prefer the plain one. */
    private static final class Body {
        private final StringBuilder plain = new StringBuilder();
        private final StringBuilder html = new StringBuilder();
    }

    /** Depth-limited MIME walk. Attachments are skipped — only readable text matters. */
    private void collect(Part part, Body body) {
        collect(part, body, 0);
    }

    private void collect(Part part, Body body, int depth) {
        if (depth > 8 || body.plain.length() > MAX_BODY_CHARS) {
            return;
        }
        try {
            String disposition = part.getDisposition();
            if (Part.ATTACHMENT.equalsIgnoreCase(disposition)) {
                return;
            }
            Object content = part.getContent();
            if (content instanceof Multipart multipart) {
                for (int i = 0; i < multipart.getCount(); i++) {
                    collect(multipart.getBodyPart(i), body, depth + 1);
                }
            } else if (content instanceof String text) {
                if (part.isMimeType("text/html")) {
                    body.html.append(text).append('\n');
                } else if (part.isMimeType("text/*")) {
                    body.plain.append(text).append('\n');
                }
            } else if (content instanceof Part nested) {
                collect(nested, body, depth + 1);
            }
        } catch (Exception e) {
            // A single unreadable part must not cost us the whole message.
        }
    }

    /** HTML links first (they carry the real targets), then any bare URLs in the text. */
    private List<String> extractLinks(String html, String text) {
        Set<String> links = new LinkedHashSet<>();

        Matcher href = HREF.matcher(html);
        while (href.find() && links.size() < MAX_LINKS) {
            addLink(links, href.group(1));
        }
        Matcher bare = BARE_URL.matcher(text);
        while (bare.find() && links.size() < MAX_LINKS) {
            addLink(links, bare.group());
        }
        return new ArrayList<>(links);
    }

    private void addLink(Set<String> links, String raw) {
        String url = unescape(raw).trim();
        if (url.length() > 1000 || !url.regionMatches(true, 0, "http", 0, 4)) {
            return;
        }
        links.add(url);
    }

    /** Enough of an HTML-to-text conversion to feed keyword matching. */
    public String stripHtml(CharSequence html) {
        if (html == null || html.length() == 0) {
            return "";
        }
        String s = SCRIPT_OR_STYLE.matcher(html).replaceAll(" ");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n")
             .replaceAll("(?i)</(p|div|tr|li|h[1-6])>", "\n");
        s = TAG.matcher(s).replaceAll(" ");
        return unescape(s).replaceAll("[ \\t\\u00a0]+", " ").replaceAll("\n{3,}", "\n\n").trim();
    }

    private String unescape(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                .replace("&nbsp;", " ").replace("&mdash;", "—").replace("&ndash;", "–");
    }

    private String decode(String header) {
        if (header == null) {
            return "";
        }
        try {
            return MimeUtility.decodeText(header).trim();
        } catch (Exception e) {
            return header.trim();
        }
    }

    private static String trim(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
