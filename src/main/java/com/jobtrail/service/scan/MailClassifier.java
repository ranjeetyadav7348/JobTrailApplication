package com.jobtrail.service.scan;

import com.jobtrail.domain.ApplicationEventKind;
import com.jobtrail.domain.JobPlatform;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides what a piece of inbound mail is: which platform sent it, whether it
 * relates to a job application, what stage it represents, and which link (if
 * any) is the one you actually need to click.
 *
 * <p>This is deliberately rule-based rather than model-backed — it runs on
 * every message in a 30-day mailbox scan, offline, in milliseconds, and its
 * mistakes are inspectable. Phrases are scored rather than matched in order,
 * because real mail mixes vocabularies: a rejection routinely contains the word
 * "interview", and an assessment invite routinely contains "interview process".
 * Scoring with strong/weak weights settles those without a fragile if-ladder.
 */
@Component
public class MailClassifier {

    /** Hosts that only ever appear in a real assessment invite. */
    private static final List<String> ASSESSMENT_HOSTS = List.of(
            "hackerrank.com", "codility.com", "hackerearth.com", "codesignal.com",
            "coderbyte.com", "testgorilla.com", "mettl.com", "imocha.io", "testdome.com",
            "karat.com", "woven.teams", "devskiller.com", "qualified.io", "glider.ai",
            "adaface.com", "doselect.com", "talview.com", "xobin.com", "wheebox.com",
            "testlify.com", "byteboard.dev", "codesubmit.io", "codeinterview.io",
            "hirevue.com", "spark-hire.com", "willo.video", "vervoe.com", "harver.com");

    /** Scheduling links — an interview invite's actionable link. */
    private static final List<String> SCHEDULING_HOSTS = List.of(
            "calendly.com", "greenhouse.io", "goodtime.io", "cronofy.com", "savvycal.com",
            "hubspot.com", "youcanbook.me", "calendar.google.com", "outlook.office365.com",
            "teams.microsoft.com", "zoom.us", "meet.google.com");

    /** Bulk senders that relay for everyone, so their domain says nothing about the employer. */
    private static final List<String> RELAY_HOSTS = List.of(
            "sendgrid.net", "sendgrid.com", "mailgun.org", "amazonses.com", "mandrillapp.com",
            "sparkpostmail.com", "mailchimp.com", "mcsv.net", "rsgsv.net", "postmarkapp.com",
            "mailjet.com", "sendinblue.com", "customeriomail.com", "mailer.com", "email.com",
            "gmail.com", "googlemail.com", "outlook.com", "hotmail.com", "yahoo.com");

    /**
     * Subjects that mean "here are jobs you might like", not "you applied".
     * Naukri and LinkedIn send far more of these than real application mail, so
     * without this gate a scan invents dozens of applications that never happened.
     */
    private static final Pattern NOISE_SUBJECT = Pattern.compile(
            "job alert|jobs for you|recommended (?:jobs|for you)|new jobs|similar jobs|"
            + "job recommendations|top jobs|openings for you|jobs matching|hot jobs|"
            + "people also viewed|your job search|jobs you may|apply now|"
            + "newsletter|webinar|course|certification|premium|subscribe",
            Pattern.CASE_INSENSITIVE);

    /**
     * Markers of mail selling something — a course, a cohort, a webinar, a
     * "live session" — rather than corresponding about an application.
     *
     * <p>These are counted rather than matched once. A single hit is weak
     * ("register" appears in legitimate assessment invites, and a real offer
     * states a salary), but two independent hits in the same message is a
     * reliable signal of an advert.
     */
    private static final Pattern PROMOTIONAL = Pattern.compile(
            "webinar|masterclass|master class|bootcamp|boot camp|cohort|"
            + "live (?:trial |demo |free )?(?:session|class|workshop)|trial session|"
            + "free demo|book (?:your|a) (?:seat|slot|spot)|limited seats|"
            + "enroll|enrol\\b|register now|reserve your|sign up now|"
            + "\\blpa\\b|salary range|ctc\\b|"
            + "land (?:a|your) (?:job|dream)|crack (?:the|your) interview|"
            + "clear (?:this|the|your) interview|top developers|"
            + "upskill|placement (?:guarantee|assistance|support)|"
            + "referral bonus|earn up to|career transformation|"
            + "new job alert|job of the day|hiring alert",
            Pattern.CASE_INSENSITIVE);

    /** Words that make a message plausibly about hiring at all. */
    private static final Pattern JOB_VOCABULARY = Pattern.compile(
            "application|applying|applied|candidate|recruit|hiring|interview|"
            + "position|vacancy|job opening|career|resume|cv\\b|talent acquisition");

    private static final double STRONG = 12d;
    private static final double WEAK = 3d;
    /** The subject line is a much better signal than body boilerplate. */
    private static final double SUBJECT_MULTIPLIER = 2d;

    private record Rule(ApplicationEventKind kind, double weight, Pattern pattern) {
    }

    private static final List<Rule> RULES = List.of(
            // --- rejection: checked with the heaviest weights, because these mails
            // --- quote the whole prior process ("thank you for interviewing…").
            strong(ApplicationEventKind.REJECTED,
                    "regret to inform|we (?:have )?decided (?:not to|to move forward with other)|"
                    + "not (?:be )?(?:moving|proceeding|progressing) (?:forward|ahead)|"
                    + "will not be moving forward|not (?:been )?(?:selected|shortlisted|successful)|"
                    + "unsuccessful on this occasion|no longer under consideration|"
                    + "pursue other candidates|other candidates whose|"
                    + "unable to (?:offer|progress|move forward)|"
                    + "position has been (?:filled|closed)|we(?:'| ha)ve closed this (?:role|position)|"
                    + "application (?:was )?(?:not|un)successful"),
            weak(ApplicationEventKind.REJECTED, "unfortunately|we appreciate your interest, (?:but|however)"),

            // --- offer
            strong(ApplicationEventKind.OFFER,
                    "pleased to offer|delighted to offer|happy to offer|offer of employment|"
                    + "your offer letter|job offer|extend (?:you )?an offer|offer has been (?:sent|released)"),
            weak(ApplicationEventKind.OFFER, "compensation package|joining date|onboarding formalities"),

            // --- assessment
            strong(ApplicationEventKind.ASSESSMENT_INVITE,
                    "online (?:assessment|test)|coding (?:test|challenge|assessment|round)|"
                    + "technical assessment|skills? (?:test|assessment)|aptitude test|"
                    + "take[- ]home (?:test|assignment|exercise)|hackerrank|codility|hackerearth|"
                    + "codesignal|testgorilla|mettl|imocha|complete (?:your|the) (?:assessment|test|challenge)|"
                    + "assessment (?:link|invite|invitation)|invited to (?:take|complete)|"
                    + "test link|start your (?:test|assessment)|programming challenge|"
                    + "psychometric|screening test"),
            weak(ApplicationEventKind.ASSESSMENT_INVITE, "assessment|challenge|proctored|time limit"),

            // --- interview
            strong(ApplicationEventKind.INTERVIEW_INVITE,
                    "invite you (?:to|for) (?:an? )?interview|interview (?:invitation|invite)|"
                    + "schedule (?:an?|your|the) (?:interview|call|chat|conversation)|"
                    + "book (?:a|your) (?:slot|time|interview)|"
                    + "(?:phone|technical|hr|final|onsite) (?:screen|round|interview)|"
                    + "would like to (?:speak|talk|meet|chat) with you|"
                    + "share your availability|let us know your availability|"
                    + "confirm(?:ing)? your interview|interview (?:is )?scheduled|"
                    + "meet (?:the|our) team"),
            weak(ApplicationEventKind.INTERVIEW_INVITE, "interview|availability|calendly|zoom link"),

            // --- submission confirmation
            strong(ApplicationEventKind.APPLIED,
                    "thank you for applying|thanks for applying|"
                    + "(?:we(?:'ve| have)? )?received your application|application (?:has been )?received|"
                    + "application (?:was |has been )?(?:submitted|sent)|"
                    + "successfully applied|your application to|application confirmation|"
                    + "thank you for your application|we got your application"),
            weak(ApplicationEventKind.APPLIED, "thank you for your interest"),

            // --- acknowledgement / in review
            strong(ApplicationEventKind.ACKNOWLEDGED,
                    "application is (?:currently )?(?:under|in) review|reviewing your application|"
                    + "being reviewed|has been shortlisted|profile (?:has been )?shortlisted|"
                    + "moved to the next (?:stage|round)|advanced to the next"),
            weak(ApplicationEventKind.ACKNOWLEDGED, "under review|in progress|next steps"),

            // --- inbound recruiter
            strong(ApplicationEventKind.RECRUITER_OUTREACH,
                    "came across your (?:profile|resume|cv)|found your profile|"
                    + "would you be (?:interested|open)|exciting opportunity|"
                    + "reaching out (?:about|regarding) an? (?:role|opportunity|position)|"
                    + "i(?:'m| am) a (?:technical )?recruiter"),

            // --- generic movement
            weak(ApplicationEventKind.STATUS_UPDATE,
                    "application status|status update|update on your application|"
                    + "your candidacy|stage change|background check|reference check"));

    private static Rule strong(ApplicationEventKind kind, String regex) {
        return new Rule(kind, STRONG, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
    }

    private static Rule weak(ApplicationEventKind kind, String regex) {
        return new Rule(kind, WEAK, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
    }

    /** Tie-break order when two kinds score identically. Earlier wins. */
    private static final List<ApplicationEventKind> PRIORITY = List.of(
            ApplicationEventKind.REJECTED,
            ApplicationEventKind.OFFER,
            ApplicationEventKind.ASSESSMENT_INVITE,
            ApplicationEventKind.INTERVIEW_INVITE,
            ApplicationEventKind.APPLIED,
            ApplicationEventKind.ACKNOWLEDGED,
            ApplicationEventKind.RECRUITER_OUTREACH,
            ApplicationEventKind.STATUS_UPDATE,
            ApplicationEventKind.OTHER);

    // ---- entry point -------------------------------------------------------

    /**
     * @return what the message is, or {@code null} when it is not job mail and
     *         should be ignored entirely.
     */
    public Classification classify(ScannedMessage msg) {
        String subject = msg.subject();
        String haystack = squash(msg.haystack());
        String subjectLower = subject.toLowerCase(Locale.ROOT);

        JobPlatform platform = detectPlatform(msg);

        Map<ApplicationEventKind, Double> scores = score(subjectLower, haystack);
        ApplicationEventKind kind = pick(scores);
        double best = scores.getOrDefault(kind, 0d);

        // A "jobs you might like" digest scores on vocabulary alone. Drop it
        // unless the subject itself carries a real application signal.
        if (NOISE_SUBJECT.matcher(subject).find() && !subjectCarriesStrongSignal(subjectLower)) {
            return null;
        }
        // Course adverts and webinar pitches are the hardest false positive:
        // they discuss hiring, salaries and interviews in detail, so keyword
        // scoring alone reads them as real correspondence. Marketing markers
        // veto the message unless it also carries a strong application phrase.
        if (promotionalMarkers(subjectLower, haystack) >= 2 && !carriesStrongSignal(haystack)) {
            return null;
        }
        if (kind == null || best <= 0d) {
            return null;
        }
        // Weak-only evidence is worth keeping when a real ATS sent it, and not
        // worth keeping otherwise. DIRECT does not count: it is assigned to any
        // sender that merely isn't a bulk relay, which includes every marketing
        // domain on the internet.
        boolean knownPlatform = platform != null
                && platform != JobPlatform.UNKNOWN
                && platform != JobPlatform.DIRECT;
        if (best < STRONG && !knownPlatform) {
            return null;
        }
        if (!knownPlatform && !JOB_VOCABULARY.matcher(haystack).find()) {
            return null;
        }

        String company = extractCompany(msg, platform, subject);
        String role = extractRole(subject, msg.body());
        String location = extractLocation(msg.body());
        String actionUrl = pickActionUrl(msg, kind);
        String jobUrl = pickJobUrl(msg);
        Instant deadline = kind == ApplicationEventKind.ASSESSMENT_INVITE
                ? parseDeadline(haystack, msg.receivedAt())
                : null;

        return new Classification(
                kind,
                platform == null ? JobPlatform.UNKNOWN : platform,
                company,
                role,
                location,
                actionUrl,
                jobUrl,
                deadline,
                confidence(best, knownPlatform, company, role));
    }

    /**
     * Whether a message the rules rejected is still worth a second opinion.
     *
     * <p>Deliberately broader than {@link #classify}: this is the gate for
     * escalating to the model, and its job is to catch the mail the keyword
     * rules miss — an ATS wording nobody anticipated, or an employer writing in
     * their own words. {@link JobPlatform#DIRECT} does not count as evidence,
     * because it is assigned to any sender that merely isn't a bulk relay.
     */
    public boolean mightBeJobMail(ScannedMessage msg) {
        JobPlatform platform = detectPlatform(msg);
        if (platform != null && platform != JobPlatform.UNKNOWN && platform != JobPlatform.DIRECT) {
            return true;
        }
        return JOB_VOCABULARY.matcher(squash(msg.haystack())).find();
    }

    // ---- kind --------------------------------------------------------------

    private Map<ApplicationEventKind, Double> score(String subjectLower, String haystack) {
        Map<ApplicationEventKind, Double> scores = new EnumMap<>(ApplicationEventKind.class);
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(haystack).find()) {
                double weight = rule.weight();
                if (rule.pattern().matcher(subjectLower).find()) {
                    weight *= SUBJECT_MULTIPLIER;
                }
                scores.merge(rule.kind(), weight, Double::sum);
            }
        }
        return scores;
    }

    private ApplicationEventKind pick(Map<ApplicationEventKind, Double> scores) {
        ApplicationEventKind best = null;
        double bestScore = 0d;
        for (ApplicationEventKind kind : PRIORITY) {
            double s = scores.getOrDefault(kind, 0d);
            if (s > bestScore) {
                bestScore = s;
                best = kind;
            }
        }
        return best;
    }

    private boolean subjectCarriesStrongSignal(String subjectLower) {
        return carriesStrongSignal(subjectLower);
    }

    private boolean carriesStrongSignal(String text) {
        return RULES.stream()
                .filter(r -> r.weight() >= STRONG)
                .anyMatch(r -> r.pattern().matcher(text).find());
    }

    /**
     * How many <em>distinct</em> marketing markers the message carries. Counting
     * distinct matches rather than total occurrences stops one repeated word in
     * a footer from convicting an otherwise genuine email.
     */
    private int promotionalMarkers(String subjectLower, String haystack) {
        java.util.Set<String> hits = new java.util.HashSet<>();
        Matcher m = PROMOTIONAL.matcher(haystack);
        while (m.find() && hits.size() < 8) {
            hits.add(m.group().toLowerCase(Locale.ROOT));
        }
        // A marker in the subject line is worth more than one buried in a footer.
        if (PROMOTIONAL.matcher(subjectLower).find()) {
            hits.add("subject-marker");
        }
        return hits.size();
    }

    /**
     * Whether this message looks like an advert, for triage purposes. Used to
     * escalate to the model even when the keyword rules sound confident —
     * marketing mail is precisely the case where they are confidently wrong.
     */
    public boolean looksPromotional(ScannedMessage msg) {
        String haystack = squash(msg.haystack());
        String subjectLower = msg.subject().toLowerCase(Locale.ROOT);
        return promotionalMarkers(subjectLower, haystack) >= 1
                || NOISE_SUBJECT.matcher(msg.subject()).find();
    }

    /**
     * Dominated by the phrase score, because that is what says how sure we are
     * of the <em>kind</em>. Knowing the platform and pulling out a company name
     * are corroboration, so they nudge rather than carry — otherwise a message
     * matched only on weak phrases scores as confident purely because a
     * recognisable ATS happened to send it.
     */
    private double confidence(double score, boolean knownPlatform, String company, String role) {
        double c = Math.min(1d, score / (STRONG * SUBJECT_MULTIPLIER));
        if (knownPlatform) {
            c += 0.10d;
        }
        if (company != null && !"Unknown".equals(company)) {
            c += 0.05d;
        }
        if (role != null) {
            c += 0.05d;
        }
        return Math.min(1d, Math.round(c * 100d) / 100d);
    }

    // ---- platform ----------------------------------------------------------

    /** Sender domain first, then link hosts, then a name mentioned in the body. */
    JobPlatform detectPlatform(ScannedMessage msg) {
        JobPlatform bySender = JobPlatform.fromHost(msg.senderHost());
        if (bySender != null) {
            return bySender;
        }
        for (String link : msg.links()) {
            JobPlatform byLink = JobPlatform.fromHost(hostOf(link));
            if (byLink != null) {
                return byLink;
            }
        }
        String haystack = msg.haystack();
        for (JobPlatform p : JobPlatform.values()) {
            for (String domain : p.domains()) {
                if (haystack.contains(domain)) {
                    return p;
                }
            }
        }
        // A real company address (not a relay, not a free mailbox) means you
        // applied straight to them rather than through a platform.
        String host = msg.senderHost();
        if (!host.isBlank() && !isRelay(host)) {
            return JobPlatform.DIRECT;
        }
        return JobPlatform.UNKNOWN;
    }

    private static boolean isRelay(String host) {
        return RELAY_HOSTS.stream().anyMatch(r -> host.equals(r) || host.endsWith("." + r));
    }

    // ---- company -----------------------------------------------------------

    /** Company slugs sitting inside ATS job URLs — the most reliable source there is. */
    private static final List<Pattern> URL_COMPANY = List.of(
            Pattern.compile("https?://([a-z0-9-]+)\\.wd\\d*\\.myworkdayjobs\\.com", Pattern.CASE_INSENSITIVE),
            Pattern.compile("https?://([a-z0-9-]+)\\.myworkdaysite\\.com", Pattern.CASE_INSENSITIVE),
            Pattern.compile("https?://(?:boards|job-boards)\\.greenhouse\\.io/([a-z0-9_-]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("https?://jobs\\.lever\\.co/([a-z0-9_-]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("https?://jobs\\.ashbyhq\\.com/([a-z0-9_.-]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("https?://careers\\.smartrecruiters\\.com/([a-z0-9_-]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("https?://([a-z0-9-]+)\\.recruitee\\.com", Pattern.CASE_INSENSITIVE),
            Pattern.compile("https?://([a-z0-9-]+)\\.bamboohr\\.com", Pattern.CASE_INSENSITIVE),
            Pattern.compile("https?://apply\\.workable\\.com/([a-z0-9_-]+)", Pattern.CASE_INSENSITIVE));

    private static final List<Pattern> SUBJECT_COMPANY = List.of(
            Pattern.compile("application (?:for|to) .{2,60}? (?:at|with) ([^\\-–—|(\\[]{2,60})", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:applying|application) to ([^\\-–—|(\\[]{2,60})", Pattern.CASE_INSENSITIVE),
            Pattern.compile("thank you for (?:applying|your interest) (?:to|in) ([^\\-–—|(\\[]{2,60})", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^([^\\-–—|:]{2,40})\\s*[:\\-–—|]\\s*(?:thank you|your application|application|interview|assessment)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:your )?application (?:update|status|received)\\s*[\\-–—|:]\\s*([^\\-–—|(\\[]{2,60})", Pattern.CASE_INSENSITIVE));

    private static final List<Pattern> BODY_COMPANY = List.of(
            Pattern.compile("your application to ([A-Z][\\w&.,'’\\- ]{1,60}?)(?:\\.|,|\\n| for | has | is )"),
            Pattern.compile("(?:applying|apply) (?:to|at) ([A-Z][\\w&.,'’\\- ]{1,60}?)(?:\\.|,|\\n| for | and )"),
            Pattern.compile("(?:interest in|position at|role at|opportunity at|career(?:s)? at) ([A-Z][\\w&.,'’\\- ]{1,60}?)(?:\\.|,|\\n| for | as )"),
            Pattern.compile("(?:the )?([A-Z][\\w&.,'’\\- ]{1,50}?) (?:recruiting|recruitment|talent acquisition|hiring|people) team"),
            Pattern.compile("team at ([A-Z][\\w&.,'’\\- ]{1,60}?)(?:\\.|,|\\n)"));

    /** Words that are part of a mailbox persona, not the employer's name. */
    private static final Pattern SENDER_NOISE = Pattern.compile(
            "\\b(careers?|recruit(?:ing|ment)?|talent|acquisition|hiring|hr|human resources|"
            + "jobs?|no[- ]?reply|noreply|do[- ]?not[- ]?reply|team|notifications?|"
            + "support|info|admin|mailer|via|the)\\b", Pattern.CASE_INSENSITIVE);

    String extractCompany(ScannedMessage msg, JobPlatform platform, String subject) {
        for (String link : msg.links()) {
            for (Pattern p : URL_COMPANY) {
                Matcher m = p.matcher(link);
                if (m.find()) {
                    String slug = prettifySlug(m.group(1));
                    if (usable(slug)) {
                        return slug;
                    }
                }
            }
        }
        for (Pattern p : SUBJECT_COMPANY) {
            String hit = firstGroup(p, subject);
            if (usable(hit)) {
                return tidy(hit);
            }
        }
        for (Pattern p : BODY_COMPANY) {
            String hit = firstGroup(p, msg.body());
            if (usable(hit)) {
                return tidy(hit);
            }
        }
        String fromName = cleanSenderName(msg.fromName());
        if (usable(fromName)) {
            return fromName;
        }
        // Last resort: the sending domain, but only when it belongs to the
        // employer rather than to the ATS or a bulk relay.
        String host = msg.senderHost();
        boolean ownDomain = (platform == null || platform == JobPlatform.DIRECT
                || platform == JobPlatform.UNKNOWN) && !isRelay(host);
        if (ownDomain && host.contains(".")) {
            String label = host.substring(0, host.indexOf('.'));
            if ("mail".equals(label) || "email".equals(label) || "careers".equals(label)
                    || "jobs".equals(label) || "hr".equals(label) || "recruiting".equals(label)) {
                String rest = host.substring(host.indexOf('.') + 1);
                label = rest.contains(".") ? rest.substring(0, rest.indexOf('.')) : rest;
            }
            if (usable(label)) {
                return prettifySlug(label);
            }
        }
        return "Unknown";
    }

    /** "Careers at Acme" / "Acme Careers" / "Acme via Greenhouse" → "Acme". */
    private String cleanSenderName(String fromName) {
        if (fromName == null || fromName.isBlank()) {
            return null;
        }
        String name = fromName.replaceAll("(?i)\\s+via\\s+.*$", "");
        Matcher at = Pattern.compile("(?i)^(?:careers?|jobs?|recruiting|talent|hiring)\\s+at\\s+(.+)$")
                .matcher(name.trim());
        if (at.matches()) {
            name = at.group(1);
        }
        name = SENDER_NOISE.matcher(name).replaceAll(" ");
        return tidy(name);
    }

    private static String prettifySlug(String slug) {
        String spaced = slug.replaceAll("[._-]+", " ").trim();
        StringBuilder sb = new StringBuilder(spaced.length());
        for (String word : spaced.split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(word.charAt(0)))
              .append(word.length() > 1 ? word.substring(1) : "");
        }
        return sb.toString();
    }

    private static String tidy(String value) {
        if (value == null) {
            return null;
        }
        String v = value.replaceAll("[\\s\\u00a0]+", " ").trim()
                .replaceAll("^[^\\p{L}\\p{N}]+", "")
                .replaceAll("[\\s,.:;!\\-–—|]+$", "");
        return v.length() > 120 ? v.substring(0, 120).trim() : v;
    }

    private static boolean usable(String value) {
        String v = tidy(value);
        return v != null && v.length() >= 2 && v.length() <= 120
                && v.chars().anyMatch(Character::isLetter)
                && !v.matches("(?i)(unknown|team|company|the|your|our|us|we|hiring|careers?)");
    }

    // ---- role --------------------------------------------------------------

    private static final List<Pattern> ROLE_PATTERNS = List.of(
            Pattern.compile("application for (?:the )?([^,.\\n]{3,70}?)(?: (?:position|role|opening|job)\\b| at | with |[,.\\n]|$)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("applied (?:for|to) (?:the )?([^,.\\n]{3,70}?)(?: (?:position|role|opening|job)\\b| at | with |[,.\\n]|$)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:^|\\n)\\s*(?:position|role|job title|title)\\s*[:\\-]\\s*([^\\n]{3,70})", Pattern.CASE_INSENSITIVE),
            Pattern.compile("interest in (?:the |our )?([^,.\\n]{3,70}?) (?:position|role|opening)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("for the ([^,.\\n]{3,70}?) (?:position|role|opening|vacancy)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^([^\\-–—|:]{3,60})\\s*[\\-–—|]\\s*(?:application|interview|assessment|update)", Pattern.CASE_INSENSITIVE));

    /** Phrases that show the capture ran past the role into prose. */
    private static final Pattern ROLE_REJECT = Pattern.compile(
            "(?i)your application|thank you|we |our team|dear |please |following|recently|"
            + "have been|has been|the opportunity|unfortunately");

    String extractRole(String subject, String body) {
        for (Pattern p : ROLE_PATTERNS) {
            String hit = firstGroup(p, subject);
            String role = cleanRole(hit);
            if (role != null) {
                return role;
            }
        }
        for (Pattern p : ROLE_PATTERNS) {
            String hit = firstGroup(p, body);
            String role = cleanRole(hit);
            if (role != null) {
                return role;
            }
        }
        return null;
    }

    private String cleanRole(String raw) {
        String v = tidy(raw);
        if (v == null || v.length() < 3 || v.length() > 70) {
            return null;
        }
        v = v.replaceAll("(?i)\\s+(position|role|opening|vacancy|job)$", "").trim();
        v = v.replaceAll("(?i)^(the|a|an)\\s+", "").trim();
        if (v.length() < 3 || ROLE_REJECT.matcher(v).find()) {
            return null;
        }
        // A role title is a noun phrase; anything longer is a sentence fragment.
        if (v.split("\\s+").length > 9) {
            return null;
        }
        return v;
    }

    private static final Pattern LOCATION = Pattern.compile(
            "(?:^|\\n)\\s*(?:location|based in|work location|job location)\\s*[:\\-]\\s*([^\\n]{2,60})",
            Pattern.CASE_INSENSITIVE);

    String extractLocation(String body) {
        return tidyOrNull(firstGroup(LOCATION, body));
    }

    // ---- links -------------------------------------------------------------

    /** The one link the popup should offer, chosen by what the mail turned out to be. */
    String pickActionUrl(ScannedMessage msg, ApplicationEventKind kind) {
        List<String> links = msg.links();
        if (links.isEmpty()) {
            return null;
        }
        if (kind == ApplicationEventKind.ASSESSMENT_INVITE) {
            String host = firstMatchingHost(links, ASSESSMENT_HOSTS);
            if (host != null) {
                return host;
            }
            String worded = links.stream()
                    .filter(l -> l.toLowerCase(Locale.ROOT)
                            .matches(".*(assessment|/test|challenge|invite|exam|quiz|start).*"))
                    .findFirst().orElse(null);
            if (worded != null) {
                return worded;
            }
        }
        if (kind == ApplicationEventKind.INTERVIEW_INVITE) {
            String sched = firstMatchingHost(links, SCHEDULING_HOSTS);
            if (sched != null) {
                return sched;
            }
        }
        return links.stream().filter(MailClassifier::looksActionable).findFirst().orElse(null);
    }

    String pickJobUrl(ScannedMessage msg) {
        return msg.links().stream()
                .filter(l -> {
                    String low = l.toLowerCase(Locale.ROOT);
                    return low.contains("/job") || low.contains("/careers")
                            || low.contains("requisition") || low.contains("posting");
                })
                .findFirst().orElse(null);
    }

    private static String firstMatchingHost(List<String> links, List<String> hosts) {
        for (String link : links) {
            String h = hostOf(link);
            if (hosts.stream().anyMatch(x -> h.equals(x) || h.endsWith("." + x))) {
                return link;
            }
        }
        return null;
    }

    /** Filters out unsubscribe, privacy and tracking-pixel links. */
    private static boolean looksActionable(String link) {
        String low = link.toLowerCase(Locale.ROOT);
        return !low.contains("unsubscribe") && !low.contains("privacy")
                && !low.contains("preferences") && !low.contains("optout")
                && !low.contains("opt-out") && !low.endsWith(".gif") && !low.endsWith(".png")
                && !low.startsWith("mailto:");
    }

    static String hostOf(String url) {
        try {
            String host = URI.create(url.trim()).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    // ---- deadlines ---------------------------------------------------------

    private static final Pattern RELATIVE_DEADLINE = Pattern.compile(
            "within (?:the next )?(\\d{1,3})\\s*(hour|day|business day|week)s?"
            + "|(?:expires?|valid|open|available)\\s+(?:for|in)\\s+(\\d{1,3})\\s*(hour|day|week)s?"
            + "|(?:complete|submit|finish)[^.\\n]{0,30}?within (\\d{1,3})\\s*(hour|day|week)s?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ABSOLUTE_DEADLINE = Pattern.compile(
            "(?:by|before|on or before|due(?: on| by)?|deadline(?: is|:)?|expires? on|no later than)\\s+"
            + "(\\d{4}-\\d{2}-\\d{2}"
            + "|\\d{1,2}(?:st|nd|rd|th)? +(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]* +\\d{4}"
            + "|\\d{1,2}(?:st|nd|rd|th)? +(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*"
            + "|(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]* +\\d{1,2}(?:st|nd|rd|th)?,? +\\d{4}"
            + "|(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]* +\\d{1,2}(?:st|nd|rd|th)?)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Relative wording wins over an absolute date, because "within 48 hours"
     * anchored on the mail's own timestamp is unambiguous, whereas a bare
     * "by 5 March" needs a year we would have to guess.
     */
    Instant parseDeadline(String haystack, Instant receivedAt) {
        Instant base = receivedAt == null ? Instant.now() : receivedAt;

        Matcher rel = RELATIVE_DEADLINE.matcher(haystack);
        if (rel.find()) {
            for (int i = 1; i <= rel.groupCount(); i += 2) {
                if (rel.group(i) != null) {
                    return base.plus(toDuration(Integer.parseInt(rel.group(i)), rel.group(i + 1)));
                }
            }
        }

        Matcher abs = ABSOLUTE_DEADLINE.matcher(haystack);
        if (abs.find()) {
            LocalDate date = parseDate(abs.group(1), base);
            if (date != null) {
                return date.atTime(23, 59).atZone(ZoneId.systemDefault()).toInstant();
            }
        }
        return null;
    }

    private static Duration toDuration(int amount, String unit) {
        String u = unit.toLowerCase(Locale.ROOT);
        if (u.startsWith("hour")) {
            return Duration.ofHours(amount);
        }
        if (u.startsWith("week")) {
            return Duration.ofDays(7L * amount);
        }
        if (u.startsWith("business")) {
            // Approximate: five business days is a calendar week.
            return Duration.ofDays(amount + (amount / 5) * 2L);
        }
        return Duration.ofDays(amount);
    }

    /**
     * Case-insensitive on purpose: the text reaching this point has already been
     * lower-cased for matching, and {@code MMMM} would otherwise refuse "august".
     */
    private static DateTimeFormatter dateFormat(String pattern) {
        return new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern(pattern)
                .toFormatter(Locale.ENGLISH);
    }

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            dateFormat("yyyy-MM-dd"),
            dateFormat("d MMMM yyyy"),
            dateFormat("d MMM yyyy"),
            dateFormat("MMMM d yyyy"),
            dateFormat("MMM d yyyy"));

    private static LocalDate parseDate(String raw, Instant base) {
        String cleaned = raw.trim()
                .replaceAll("(?i)(\\d{1,2})(st|nd|rd|th)", "$1")
                .replace(",", " ")
                .replaceAll("\\s+", " ");
        LocalDate today = LocalDate.ofInstant(base, ZoneId.systemDefault());

        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try {
                return LocalDate.parse(cleaned, fmt);
            } catch (DateTimeParseException ignored) {
                // try the next shape
            }
        }
        // No year given: assume the next occurrence, so a December mail naming
        // "3 January" resolves to next year rather than ten months ago.
        for (String pattern : List.of("d MMMM", "d MMM", "MMMM d", "MMM d")) {
            try {
                LocalDate parsed = LocalDate.parse(cleaned + " " + today.getYear(),
                        dateFormat(pattern + " yyyy"));
                return parsed.isBefore(today) ? parsed.plusYears(1) : parsed;
            } catch (DateTimeParseException ignored) {
                // try the next shape
            }
        }
        return null;
    }

    // ---- small helpers -----------------------------------------------------

    private static String firstGroup(Pattern pattern, String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        Matcher m = pattern.matcher(input);
        return m.find() ? m.group(1) : null;
    }

    private static String tidyOrNull(String value) {
        String v = tidy(value);
        return v == null || v.isBlank() ? null : v;
    }

    /** Collapses the whitespace that HTML-to-text conversion leaves behind. */
    private static String squash(String text) {
        return text.replaceAll("[\\t\\u00a0 ]+", " ").replaceAll("\n{3,}", "\n\n");
    }

    /** Exposed for tests: the weekday helper used when resolving relative dates. */
    static DayOfWeek weekdayOf(Instant instant) {
        return LocalDate.ofInstant(instant, ZoneId.systemDefault()).getDayOfWeek();
    }

    /** Exposed for tests and the scanner: all recognised assessment hosts. */
    public static List<String> assessmentHosts() {
        return new ArrayList<>(ASSESSMENT_HOSTS);
    }
}
