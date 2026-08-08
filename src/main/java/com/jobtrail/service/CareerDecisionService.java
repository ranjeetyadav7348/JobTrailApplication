package com.jobtrail.service;

import com.jobtrail.domain.JobApplication;
import com.jobtrail.domain.KnowledgeSource;
import com.jobtrail.repo.JobApplicationRepository;
import com.jobtrail.service.rag.HybridRetriever;
import com.jobtrail.service.rag.KnowledgeIngestService;
import com.jobtrail.service.rag.RetrievedChunk;
import com.jobtrail.web.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The part of the assistant that is asked to decide something rather than
 * merely describe it.
 *
 * <h2>Everything here is grounded</h2>
 * Every method retrieves evidence first and passes it to the model as the only
 * admissible basis for an answer. That is not ceremony. The questions asked here
 * — "am I a fit", "how many years of X do I have", "write this employer an
 * email" — are precisely the ones a general model will answer fluently and
 * wrongly, because a plausible CV is easy to invent and the output looks
 * identical either way. Grounding turns a confident guess into either a cited
 * claim or an admission of ignorance, and both of those are useful where a guess
 * is not.
 *
 * <p>Each verdict therefore carries the citations behind it, so any sentence can
 * be traced back to a passage of the CV or the application history.
 *
 * <h2>Where the boundary sits</h2>
 * These methods draft, score and rank. None of them send anything. The output of
 * a bad decision here is an email to a real employer, so a human stays in the
 * loop by construction rather than by configuration.
 */
@Service
@Slf4j
public class CareerDecisionService {

    /**
     * The rules every decision runs under. Written once and shared, because the
     * grounding contract must not drift between the four entry points — an
     * assistant that is scrupulous about screening answers and inventive in
     * drafted emails is not meaningfully grounded at all.
     */
    private static final String GROUNDING_RULES = """
            You are the decision assistant inside JobTrail, working on behalf of
            one job seeker. Below you are given EVIDENCE: numbered passages
            retrieved from their own résumé and their tracked applications.

            Rules, in order of importance:

            1. The evidence is your only source of fact about this person. If it
               does not support a claim, you do not make the claim. Say what is
               missing instead. An invented employer, skill, date or duration is
               far worse than an admitted gap — this output can end up in an
               email to a real employer, and a fabricated claim there is one they
               can check.
            2. Cite the passages you used by their number, like [2]. Every
               concrete claim about the person's background needs one.
            3. Never inflate. If the evidence shows two years of something, say
               two years. Do not round experience up, do not translate
               familiarity into expertise, and do not describe exposure as
               ownership.
            4. Where the evidence is thin or absent, say so plainly and move on.
               "The résumé does not mention Kubernetes" is a good answer.

            Today is %s.

            EVIDENCE
            %s
            """;

    private final AiAvailability availability;
    private final HybridRetriever retriever;
    private final KnowledgeIngestService ingest;
    private final JobApplicationRepository applications;

    public CareerDecisionService(AiAvailability availability,
                                 HybridRetriever retriever,
                                 KnowledgeIngestService ingest,
                                 JobApplicationRepository applications) {
        this.availability = availability;
        this.retriever = retriever;
        this.ingest = ingest;
        this.applications = applications;
    }

    // ---- results -----------------------------------------------------------

    /** One passage the model was shown, echoed back so the UI can display it. */
    public record Citation(int number, String source, String title, String excerpt) {
    }

    /**
     * @param verdict     APPLY, STRETCH or SKIP
     * @param confidence  0..1
     * @param matches     requirements the evidence actually supports
     * @param gaps        requirements it does not
     * @param reasoning   short justification, citing passage numbers
     */
    public record FitVerdict(String verdict, double confidence, List<String> matches,
                             List<String> gaps, String reasoning, List<Citation> citations) {
    }

    /** A drafted email. Never sent by this class. */
    public record DraftEmail(String subject, String body, String rationale, List<Citation> citations) {
    }

    /** One thing worth doing, and why now. */
    public record PriorityItem(int rank, String company, String action, String urgency, String why) {
    }

    public record Priorities(List<PriorityItem> items, String summary, List<Citation> citations) {
    }

    /** An answer to a factual screening question, or an honest miss. */
    public record ScreeningAnswer(String answer, boolean supported, String caveat, List<Citation> citations) {
    }

    public boolean available() {
        return availability.ready();
    }

    // ---- decisions ---------------------------------------------------------

    /**
     * Scores a job description against the CV.
     *
     * <p>The description is indexed before retrieval rather than merely pasted
     * into the prompt. That way the query used to search the CV is drawn from
     * the posting's own language, and the retrieved passages are the ones that
     * actually correspond to its requirements rather than whatever the CV
     * happens to lead with.
     */
    public FitVerdict assessFit(String jobDescription) {
        require(jobDescription, "Paste the job description first.");

        String ref = "fit-" + UUID.randomUUID();
        try {
            ingest.indexAdhoc(ref, "Job description", jobDescription);
            List<RetrievedChunk> evidence = retriever.retrieve(jobDescription);

            return call("""
                    Assess how well this person fits the role below.

                    Give a verdict of exactly one of: APPLY (clear fit, worth
                    applying), STRETCH (worth applying but there are real gaps),
                    SKIP (the gaps are large enough that applying is not a good
                    use of their time).

                    List the requirements the evidence genuinely supports, and
                    the ones it does not. A requirement the evidence is silent on
                    is a gap, not a match — do not assume competence that is not
                    written down.

                    JOB DESCRIPTION
                    %s
                    """.formatted(jobDescription), evidence, FitVerdict.class,
                    v -> new FitVerdict(v.verdict(), v.confidence(), v.matches(), v.gaps(),
                            v.reasoning(), citationsOf(evidence)));
        } finally {
            // The posting was indexed only to shape this one retrieval; leaving
            // it behind would slowly poison later searches with the language of
            // jobs the user never applied for.
            ingest.forget(KnowledgeSource.ADHOC, ref);
        }
    }

    /** Drafts a follow-up or reply for one application. */
    public DraftEmail draftEmail(Long applicationId, String intent) {
        JobApplication application = applications.findById(applicationId)
                .orElseThrow(() -> ApiException.badRequest("No such application."));

        String purpose = intent == null || intent.isBlank()
                ? "a polite follow-up asking for a status update"
                : intent.strip();

        String query = "%s %s %s".formatted(
                application.getCompany(),
                application.getRoleTitle() == null ? "" : application.getRoleTitle(),
                purpose);
        List<RetrievedChunk> evidence = retriever.retrieve(query);

        return call("""
                Draft an email for this application.

                Purpose: %s

                Company: %s
                Role: %s
                Current stage: %s
                Applied: %s
                Last contact: %s

                Write it as this person would: direct, specific, and short —
                under 150 words. Refer to concrete things from the evidence
                rather than generic enthusiasm; "I built the payments ingestion
                service on Kafka" earns a reply, "I am passionate about your
                mission" does not.

                Do not invent anything about their background. If you have
                nothing specific to point at, keep the email brief and factual
                rather than padding it.

                Put your citations in the rationale field, not in the email body
                — the body is what gets sent, and bracketed numbers in it would
                have to be stripped by hand.
                """.formatted(purpose,
                application.getCompany(),
                orDash(application.getRoleTitle()),
                application.getStatus().label(),
                application.getAppliedAt(),
                application.getLastEventAt() == null ? "none yet" : daysAgo(application.getLastEventAt())),
                evidence, DraftEmail.class,
                d -> new DraftEmail(d.subject(), d.body(), d.rationale(), citationsOf(evidence)));
    }

    /** Ranks what deserves attention today. */
    public Priorities prioritise() {
        List<JobApplication> live =
                applications.findByArchivedFalseOrderByLastEventAtDescAppliedAtDesc();
        if (live.isEmpty()) {
            return new Priorities(List.of(), "Nothing is being tracked yet.", List.of());
        }

        List<RetrievedChunk> evidence =
                retriever.retrieve("upcoming deadlines interviews assessments applications going quiet");

        return call("""
                Rank what this person should deal with today, most urgent first.

                Order by what is genuinely time-sensitive: assessment deadlines
                first, then scheduled interviews, then applications going cold.
                An application that is simply recent and quiet is not urgent —
                do not pad the list to make it look busy. Return at most eight
                items, fewer if fewer matter.

                For each, say what the action actually is ("submit the
                assessment", "chase the recruiter"), not merely that it needs
                attention.

                PIPELINE
                %s
                """.formatted(pipeline(live)), evidence, Priorities.class,
                p -> new Priorities(p.items(), p.summary(), citationsOf(evidence)));
    }

    /** Answers a factual question about the user's background from the CV. */
    public ScreeningAnswer answerScreeningQuestion(String question) {
        require(question, "Ask a question first.");
        List<RetrievedChunk> evidence = retriever.retrieve(question);

        return call("""
                Answer this screening question about the person, using only the
                evidence.

                Set supported=true only if the evidence actually contains the
                answer. If it does not, set supported=false, say plainly that
                the résumé does not cover it, and suggest what they would need
                to add. Do not guess a number, a duration or a technology that
                is not written down — a wrong answer here gets repeated to an
                employer.

                QUESTION
                %s
                """.formatted(question), evidence, ScreeningAnswer.class,
                a -> new ScreeningAnswer(a.answer(), a.supported(), a.caveat(), citationsOf(evidence)));
    }

    // ---- plumbing ----------------------------------------------------------

    /**
     * Runs one grounded decision.
     *
     * <p>Not transactional, for the same reason {@code ChatAssistantService} is
     * not: this blocks on a network round trip to the model, and holding a
     * connection open across it would pin a pool slot for the length of the
     * response.
     */
    private <T> T call(String instruction, List<RetrievedChunk> evidence, Class<T> type,
                       java.util.function.Function<T, T> withCitations) {
        ChatClient client = client(evidence);
        if (client == null) {
            throw ApiException.badRequest(
                    "No AI model is configured. Set ANTHROPIC_API_KEY and restart.");
        }
        try {
            T result = client.prompt().user(instruction).call().entity(type);
            if (result == null) {
                throw ApiException.badRequest("The assistant returned nothing usable.");
            }
            return withCitations.apply(result);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Decision failed: {}", e.toString());
            throw ApiException.badRequest("The assistant could not decide: " + rootMessage(e));
        }
    }

    /**
     * Built per call, because the system prompt carries this decision's evidence.
     * A cached client would answer every question from whatever happened to be
     * retrieved first.
     */
    private ChatClient client(List<RetrievedChunk> evidence) {
        if (!availability.ready()) {
            return null;
        }
        ChatModel model = availability.model();
        if (model == null) {
            return null;
        }
        return ChatClient.builder(model)
                .defaultSystem(GROUNDING_RULES.formatted(
                        LocalDate.now(), HybridRetriever.asEvidence(evidence)))
                .build();
    }

    private static List<Citation> citationsOf(List<RetrievedChunk> evidence) {
        List<Citation> out = new java.util.ArrayList<>(evidence.size());
        for (int i = 0; i < evidence.size(); i++) {
            RetrievedChunk chunk = evidence.get(i);
            out.add(new Citation(i + 1, chunk.source().label(), chunk.title(), excerpt(chunk.body())));
        }
        return out;
    }

    private static String pipeline(List<JobApplication> live) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("company | role | status | applied | last update | assessment due\n");
        live.stream().limit(60).forEach(a -> sb
                .append(a.getCompany()).append(" | ")
                .append(orDash(a.getRoleTitle())).append(" | ")
                .append(a.getStatus().label()).append(" | ")
                .append(daysAgo(a.getAppliedAt())).append(" | ")
                .append(a.getLastEventAt() == null ? "never" : daysAgo(a.getLastEventAt())).append(" | ")
                .append(a.getAssessmentDueAt() == null ? "-" : a.getAssessmentDueAt().toString())
                .append('\n'));
        return sb.toString();
    }

    private static String excerpt(String body) {
        if (body == null) {
            return "";
        }
        String trimmed = body.strip();
        return trimmed.length() <= 240 ? trimmed : trimmed.substring(0, 240) + "…";
    }

    private static void require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(message);
        }
    }

    private static String daysAgo(Instant at) {
        if (at == null) {
            return "unknown";
        }
        long days = Duration.between(at, Instant.now()).toDays();
        return days <= 0 ? "today" : days + "d ago";
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String message = cur.getMessage();
        return message == null || message.isBlank() ? cur.getClass().getSimpleName() : message;
    }
}
