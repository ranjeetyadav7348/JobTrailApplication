package com.jobtrail.service.scan;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * A single mailbox message, flattened into the few things the classifier
 * actually looks at. Keeping this a plain record means the rules can be unit
 * tested without a mail server anywhere in sight.
 *
 * @param messageId  RFC {@code Message-ID}, or a synthetic digest when absent
 * @param subject    subject line, never null
 * @param fromName   display name on the From header ("Acme Careers")
 * @param fromAddress From address, lower-cased
 * @param body       plain text of the mail, HTML already stripped
 * @param links      every {@code href} found, in document order
 * @param receivedAt when it arrived
 */
public record ScannedMessage(
        String messageId,
        String subject,
        String fromName,
        String fromAddress,
        String body,
        List<String> links,
        Instant receivedAt) {

    public ScannedMessage {
        subject = subject == null ? "" : subject;
        fromName = fromName == null ? "" : fromName;
        fromAddress = fromAddress == null ? "" : fromAddress.toLowerCase(Locale.ROOT).trim();
        body = body == null ? "" : body;
        links = links == null ? List.of() : List.copyOf(links);
        receivedAt = receivedAt == null ? Instant.now() : receivedAt;
    }

    /** Everything the keyword rules read, lower-cased once. */
    public String haystack() {
        return (subject + '\n' + fromName + '\n' + body).toLowerCase(Locale.ROOT);
    }

    /** Host part of the From address, or "" when there isn't one. */
    public String senderHost() {
        int at = fromAddress.indexOf('@');
        return at < 0 ? "" : fromAddress.substring(at + 1);
    }
}
