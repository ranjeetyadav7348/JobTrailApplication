package com.jobtrail.service;

import com.jobtrail.domain.EmailMessage;
import com.jobtrail.domain.MessageKind;
import com.jobtrail.domain.MessageStatus;
import com.jobtrail.domain.Outreach;
import com.jobtrail.domain.OutreachStatus;
import com.jobtrail.repo.EmailMessageRepository;
import com.jobtrail.repo.OutreachRepository;
import com.jobtrail.web.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Fills the dashboard with believable sample threads so the charts and the
 * pipeline have shape before any real outreach exists.
 * <p>
 * Two safety properties matter here and are deliberate:
 * every address uses the reserved {@code .example} TLD, which by RFC 2606 can
 * never route to a real person; and nothing is created in a sendable state —
 * demo history is written directly as {@code SENT} rows with backdated
 * timestamps, so the dispatcher has nothing to pick up.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DemoDataService {

    /** Prefix on the notes field; removal matches on exactly this. */
    public static final String MARKER = "[demo]";

    private final OutreachRepository outreachRepo;
    private final EmailMessageRepository messageRepo;

    @Transactional(readOnly = true)
    public boolean isLoaded() {
        return outreachRepo.findAll().stream().anyMatch(DemoDataService::isDemo);
    }

    @Transactional
    public int seed() {
        if (isLoaded()) {
            throw ApiException.conflict("Demo data is already loaded.");
        }
        Instant now = Instant.now();
        int created = 0;

        created += thread("Priya Raman", "priya.raman@northwind-labs.example", "Northwind Labs",
                "Backend Engineer", OutreachStatus.REPLIED, now, 9, 1, true, true);
        created += thread("Tomas Vogel", "t.vogel@lumen-systems.example", "Lumen Systems",
                "Java Developer", OutreachStatus.OPENED, now, 6, 1, true, false);
        created += thread("Aisha Bello", "aisha@brightpath.example", "Brightpath",
                "Platform Engineer", OutreachStatus.OPENED, now, 4, 0, true, false);
        created += thread("Daniel Okafor", "d.okafor@harborstack.example", "Harborstack",
                "Software Engineer II", OutreachStatus.SENT, now, 3, 0, false, false);
        created += thread("Mei Lin", "mei.lin@quartzpeak.example", "Quartzpeak",
                "Full Stack Developer", OutreachStatus.SENT, now, 1, 0, false, false);
        created += thread("Ruben Castillo", "ruben@fernhollow.example", "Fernhollow",
                "Spring Boot Engineer", OutreachStatus.FAILED, now, 2, 0, false, false);
        created += thread("Hannah Wirth", "h.wirth@oakline-tech.example", "Oakline Tech",
                "Backend Developer", OutreachStatus.CLOSED, now, 12, 2, true, false);
        created += thread("Sofia Almeida", "sofia@driftwood-io.example", "Driftwood IO",
                "API Engineer", OutreachStatus.DRAFT, now, -1, 0, false, false);

        log.info("Seeded {} demo outreach threads", created);
        return created;
    }

    @Transactional
    public int remove() {
        List<Outreach> demo = outreachRepo.findAll().stream()
                .filter(DemoDataService::isDemo)
                .toList();
        if (demo.isEmpty()) {
            return 0;
        }
        for (Outreach o : demo) {
            messageRepo.deleteAll(messageRepo.findByOutreachIdOrderBySequenceNoAscIdAsc(o.getId()));
        }
        outreachRepo.deleteAll(demo);
        log.info("Removed {} demo outreach threads", demo.size());
        return demo.size();
    }

    private static boolean isDemo(Outreach o) {
        return o.getNotes() != null && o.getNotes().startsWith(MARKER);
    }

    /**
     * @param daysAgo when the opening email went out; negative means never sent
     * @param followUps how many follow-ups already went out
     */
    private int thread(String name, String email, String company, String position,
                       OutreachStatus status, Instant now, int daysAgo, int followUps,
                       boolean opened, boolean replied) {
        Outreach o = new Outreach();
        o.setRecipientName(name);
        o.setRecipientEmail(email);
        o.setCompany(company);
        o.setPosition(position);
        o.setNotes(MARKER + " sample thread — safe to delete");
        o.setStatus(status);
        o.setFollowUpIntervalDays(4);
        o.setMaxFollowUps(2);
        o.setFollowUpsSent(followUps);
        // Only threads the follow-up engine would really act on advertise a next date.
        o.setAutoFollowUp(status == OutreachStatus.SENT || status == OutreachStatus.OPENED
                || status == OutreachStatus.DRAFT);
        o.setCreatedAt(now.minus(Math.max(daysAgo, 0) + 1L, ChronoUnit.DAYS));
        o.setUpdatedAt(now.minus(Math.max(daysAgo, 0), ChronoUnit.DAYS));

        if (daysAgo < 0) {
            outreachRepo.save(o);
            return 1;
        }

        Instant firstSent = now.minus(daysAgo, ChronoUnit.DAYS).plus(9, ChronoUnit.HOURS);
        Instant lastSent = followUps > 0
                ? firstSent.plus(4L * followUps, ChronoUnit.DAYS)
                : firstSent;

        o.setFirstSentAt(firstSent);
        o.setLastSentAt(lastSent);
        if (opened) {
            o.setOpenedAt(firstSent.plus(5, ChronoUnit.HOURS));
            o.setOpenCount(replied ? 4 : 2);
        }
        if (replied) {
            o.setRepliedAt(lastSent.plus(1, ChronoUnit.DAYS));
        }
        if (o.isAutoFollowUp() && followUps < o.getMaxFollowUps()) {
            o.setNextFollowUpAt(lastSent.plus(4, ChronoUnit.DAYS));
        }
        outreachRepo.save(o);

        List<EmailMessage> messages = new ArrayList<>();
        messages.add(message(o, MessageKind.INITIAL, 0,
                "Application for " + position + " at " + company,
                "<p>Hi " + name.split(" ")[0] + ",</p><p>I came across the <strong>" + position
                        + "</strong> opening at " + company + " and wanted to reach out directly.</p>",
                firstSent, status == OutreachStatus.FAILED, opened));

        for (int i = 1; i <= followUps; i++) {
            messages.add(message(o, MessageKind.FOLLOW_UP, i,
                    "Re: Application for " + position + " at " + company,
                    "<p>Hi " + name.split(" ")[0] + ",</p><p>Just floating this back to the top of "
                            + "your inbox in case it got buried.</p>",
                    firstSent.plus(4L * i, ChronoUnit.DAYS), false, opened && i == followUps));
        }
        messageRepo.saveAll(messages);
        return 1;
    }

    private EmailMessage message(Outreach o, MessageKind kind, int sequence, String subject,
                                 String body, Instant sentAt, boolean failed, boolean opened) {
        EmailMessage m = new EmailMessage();
        m.setOutreach(o);
        m.setKind(kind);
        m.setSequenceNo(sequence);
        m.setToEmail(o.getRecipientEmail());
        m.setSubject(subject);
        m.setBodyHtml(body);
        m.setQueuedAt(sentAt.minus(2, ChronoUnit.MINUTES));
        m.setScheduledAt(sentAt.minus(2, ChronoUnit.MINUTES));
        m.setTrackingToken(UUID.randomUUID().toString().replace("-", ""));

        if (failed) {
            m.setStatus(MessageStatus.FAILED);
            m.setAttempts(3);
            m.setLastError("550 5.1.1 Recipient address rejected: User unknown");
        } else {
            m.setStatus(MessageStatus.SENT);
            m.setSentAt(sentAt);
            m.setMessageId("<" + UUID.randomUUID() + "@jobtrail.example>");
            if (opened) {
                m.setOpenedAt(sentAt.plus(5, ChronoUnit.HOURS));
                m.setOpenCount(2);
            }
        }
        return m;
    }
}
