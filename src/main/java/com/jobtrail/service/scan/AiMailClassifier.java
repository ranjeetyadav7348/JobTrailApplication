package com.jobtrail.service.scan;

import com.jobtrail.domain.AppSettings;
import com.jobtrail.domain.ApplicationEventKind;
import com.jobtrail.domain.JobPlatform;
import com.jobtrail.service.AiAvailability;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A second opinion on mail the keyword rules could not settle.
 *
 * <p>This does <em>not</em> replace {@link MailClassifier}. The rules stay in
 * front because they are free, instant, and exact on the things that are
 * genuinely exact — a sending domain either is {@code greenhouse.io} or it is
 * not, and a model can only make that worse. What the rules cannot do is read
 * an email written in wording nobody anticipated, or tell a real application
 * confirmation from a course advert that uses a real vacancy as its hook. That
 * judgement is what gets escalated here.
 *
 * <p>Only the semantic fields are taken from the model — whether the mail is
 * job-related at all, what stage it represents, the employer and the role.
 * Platform, links and deadlines stay with the deterministic path.
 */
@Component
@Slf4j
public class AiMailClassifier {

    private static final String SYSTEM_PROMPT = """
            You are triaging the mailbox of someone job hunting. For each email, \
            decide whether it concerns one of their own job applications.

            The hard part is telling two things apart:

            1. Real correspondence about a specific application they made — a \
               submission confirmation, a status update, an assessment or test \
               invitation, an interview invitation, an offer, or a rejection.
            2. Bulk mail that uses identical vocabulary but concerns no actual \
               application — job alerts, "N new jobs for you", recommended \
               listings, "apply now" marketing, recruiting newsletters, and \
               adverts for courses, bootcamps, webinars and "live sessions" that \
               use a real job opening as the hook.

            Category 2 must be reported as jobRelated=false and promotional=true, \
            however much it talks about jobs, roles, salaries and interviews.

            The hardest case looks like this, and is category 2:

                New Job Alert: <Company> is hiring for a <Role> with a salary \
                range of X. Want to clear this interview and land a job like \
                this? Join <Name>, Senior Engineer @ <BigCo>, today at 2 PM in a \
                live trial session on how top developers prepare for roles \
                like this.

            It names a real employer, a real role and a real salary, and still \
            concerns no application the reader made. What gives it away is that \
            it sells attendance at something rather than responding to them.

            Ask yourself: is this addressed to this person about something they \
            did, or broadcast to a list? A real application email refers to an \
            action the reader already took. Marketing invites them to take a new \
            one. Generic openings like "Hi There" alongside a call to register, \
            enrol, book a seat or attend are category 2.

            Judge only what the email says. Do not infer an application exists \
            because the sender is a job platform — those platforms send far more \
            marketing than real application mail.""";

    /** Body text beyond this is footer and legal boilerplate. */
    private static final int BODY_BUDGET = 4000;

    private final AiAvailability availability;

    private volatile ChatClient chatClient;
    private volatile boolean disabledForSession;

    private final AtomicInteger callsThisScan = new AtomicInteger();
    private final AtomicInteger rejectedAsPromotional = new AtomicInteger();

    public AiMailClassifier(AiAvailability availability) {
        this.availability = availability;
    }

    /** Whether a model is actually wired up and holds a credential. */
    public boolean available() {
        return availability.ready();
    }

    /** Called at the start of each scan so the per-scan budget starts fresh. */
    public void resetBudget() {
        callsThisScan.set(0);
        rejectedAsPromotional.set(0);
        disabledForSession = false;
    }

    public int callsUsed() {
        return callsThisScan.get();
    }

    /** How many messages the model judged to be advertising rather than correspondence. */
    public int promotionalRejects() {
        return rejectedAsPromotional.get();
    }

    /**
     * Asks the model to settle one message.
     *
     * @param ruleResult what the rules concluded, or {@code null} if they
     *                   rejected the message; used as the fallback
     * @return a corrected classification, or {@code null} to mean "not job mail"
     */
    public Classification adjudicate(ScannedMessage message, Classification ruleResult, AppSettings settings) {
        ChatClient client = client();
        if (client == null || disabledForSession || !withinBudget(settings)) {
            return ruleResult;
        }

        try {
            callsThisScan.incrementAndGet();
            AiVerdict verdict = client.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(render(message))
                    .call()
                    .entity(AiVerdict.class);

            return verdict == null ? ruleResult : merge(message, ruleResult, verdict);
        } catch (Exception e) {
            // A scan must survive a bad key, a rate limit or an outage. Fall
            // back to whatever the rules said and stop asking for this run.
            log.warn("AI triage unavailable, falling back to keyword rules: {}",
                    MailboxScanner.rootMessage(e));
            disabledForSession = true;
            return ruleResult;
        }
    }

    private String render(ScannedMessage message) {
        return """
                From: %s <%s>
                Subject: %s

                %s
                """.formatted(
                nullToEmpty(message.fromName()),
                nullToEmpty(message.fromAddress()),
                nullToEmpty(message.subject()),
                truncate(message.body()));
    }

    /**
     * Combines both opinions. The model owns the semantics; the rules keep
     * everything they derived deterministically from domains and links, which
     * is information the model was never shown.
     */
    private Classification merge(ScannedMessage message, Classification rules, AiVerdict verdict) {
        if (!verdict.jobRelated() || verdict.promotional()) {
            rejectedAsPromotional.incrementAndGet();
            log.debug("AI rejected \"{}\" as promotional: {}", message.subject(), verdict.reason());
            return null;
        }

        ApplicationEventKind kind = parseKind(verdict.kind());
        if (kind == null) {
            return rules;
        }

        return new Classification(
                kind,
                rules != null ? rules.platform() : JobPlatform.UNKNOWN,
                firstNonBlank(verdict.company(), rules == null ? null : rules.company(), "Unknown"),
                firstNonBlank(verdict.roleTitle(), rules == null ? null : rules.roleTitle(), null),
                rules == null ? null : rules.location(),
                rules == null ? null : rules.actionUrl(),
                rules == null ? null : rules.jobUrl(),
                rules == null ? null : rules.deadlineAt(),
                clamp(verdict.confidence()));
    }

    // ---- plumbing ----------------------------------------------------------

    /** Built once, lazily, so an unconfigured app never fails at startup. */
    private ChatClient client() {
        ChatClient existing = chatClient;
        if (existing != null) {
            return existing;
        }
        if (!availability.ready()) {
            return null;
        }
        ChatModel model = availability.model();
        if (model == null) {
            return null;
        }
        ChatClient built = ChatClient.builder(model).build();
        chatClient = built;
        return built;
    }

    private boolean withinBudget(AppSettings settings) {
        int cap = Math.max(0, settings.getAiMaxCallsPerScan());
        if (callsThisScan.get() < cap) {
            return true;
        }
        log.info("AI triage budget of {} call(s) reached for this scan", cap);
        return false;
    }

    private static ApplicationEventKind parseKind(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ApplicationEventKind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String firstNonBlank(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return fallback;
    }

    private static double clamp(double value) {
        return Math.max(0d, Math.min(1d, value));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= BODY_BUDGET
                ? value
                : value.substring(0, BODY_BUDGET) + "\n[truncated]";
    }
}
