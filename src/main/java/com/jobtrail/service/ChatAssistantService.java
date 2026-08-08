package com.jobtrail.service;

import com.jobtrail.domain.JobApplication;
import com.jobtrail.repo.JobApplicationRepository;
import com.jobtrail.service.rag.HybridRetriever;
import com.jobtrail.web.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An assistant that can answer questions about the job search — which
 * applications have gone quiet, what to chase, how a company's process is
 * going.
 *
 * <p>Conversations are keyed by a conversation id and stored in Postgres by
 * Spring AI's JDBC chat memory, so history survives a restart.
 *
 * <p><strong>Two sources, deliberately.</strong> The pipeline snapshot is
 * summarised into the prompt wholesale, because a few hundred rows of structured
 * state are cheaper and more accurate to hand over in full than to retrieve
 * over. The résumé is the opposite: too long to include, and the questions asked
 * of it are narrow. So it is retrieved per question by {@link HybridRetriever}
 * and appended as evidence. The panel therefore answers "what should I chase
 * today?" from the snapshot and "how much Kafka have I done?" from the CV,
 * without the user having to know which is which.
 */
@Service
@Slf4j
public class ChatAssistantService {

    /** Turns kept in context. Older ones stay in the database, just not in the prompt. */
    private static final int MEMORY_WINDOW = 20;

    /** Applications summarised into the prompt. Enough to reason over, small enough to be cheap. */
    private static final int CONTEXT_ROWS = 60;

    private static final String SYSTEM_PROMPT = """
            You are the assistant inside JobTrail, a job-application tracker.
            You help the user reason about their own job search.

            You are given two things: a snapshot of their tracked applications,
            and passages retrieved from their own résumé and correspondence.
            Answer from those. If neither contains what was asked, say so plainly
            rather than guessing — inventing an employer, a date, a stage or a
            skill is worse than admitting the tracker does not know.

            Be concrete and brief. Prefer naming specific companies and dates
            over general advice. When asked what to do next, rank by what is
            actually time-sensitive: assessment deadlines first, then interviews,
            then applications going cold.

            Never overstate the person's experience. If the passages show two
            years of something, say two years.

            Today is %s.

            --- TRACKED APPLICATIONS ---
            %s
            --- END ---

            --- RETRIEVED PASSAGES ---
            %s
            --- END ---""";

    private final AiAvailability availability;
    private final ChatMemoryRepository chatMemoryRepository;
    private final JobApplicationRepository applicationRepo;
    private final HybridRetriever retriever;

    private volatile ChatMemory chatMemory;

    public ChatAssistantService(AiAvailability availability,
                                ChatMemoryRepository chatMemoryRepository,
                                JobApplicationRepository applicationRepo,
                                HybridRetriever retriever) {
        this.availability = availability;
        this.chatMemoryRepository = chatMemoryRepository;
        this.applicationRepo = applicationRepo;
        this.retriever = retriever;
    }

    public record Reply(String conversationId, String answer, Instant at) {
    }

    public boolean available() {
        return availability.ready();
    }

    /**
     * Answers one question in a conversation.
     *
     * <p>Deliberately not transactional. Chat memory writes the turn to
     * Postgres as part of the call, so a read-only transaction here fails, and
     * holding a writable one open across a network round trip to the model
     * would pin a connection for the length of the response. The one read this
     * needs — the pipeline snapshot — manages its own.
     *
     * @param conversationId existing conversation, or {@code null} to start one
     */
    public Reply ask(String conversationId, String question) {
        if (question == null || question.isBlank()) {
            throw ApiException.badRequest("Ask something first.");
        }
        ChatClient client = client(question);
        if (client == null) {
            throw ApiException.badRequest(
                    "No AI model is configured. Set ANTHROPIC_API_KEY and restart.");
        }

        String id = (conversationId == null || conversationId.isBlank())
                ? UUID.randomUUID().toString()
                : conversationId;

        try {
            // The system prompt is already set on the builder with the live
            // snapshot; overriding it here would send the raw template instead.
            String answer = client.prompt()
                    .user(question)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, id))
                    .call()
                    .content();

            return new Reply(id, answer == null ? "" : answer, Instant.now());
        } catch (Exception e) {
            log.warn("Chat failed: {}", e.toString());
            throw ApiException.badRequest("The assistant could not answer: " + rootMessage(e));
        }
    }

    /** Past turns of a conversation, for rebuilding the panel after a reload. */
    public List<Message> history(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        return memory().get(conversationId);
    }

    public void clear(String conversationId) {
        if (conversationId != null && !conversationId.isBlank()) {
            memory().clear(conversationId);
        }
    }

    // ---- wiring ------------------------------------------------------------

    /**
     * Built lazily so the app starts with no model configured. The system prompt
     * is rendered per call rather than baked into the builder, because it
     * carries both a live snapshot of the pipeline and the passages retrieved
     * for this particular question — a cached client would freeze the pipeline
     * as it looked at startup and answer every question from the same evidence.
     */
    private ChatClient client(String question) {
        if (!availability.ready()) {
            return null;
        }
        ChatModel model = availability.model();
        if (model == null) {
            return null;
        }
        return ChatClient.builder(model)
                .defaultSystem(SYSTEM_PROMPT.formatted(
                        java.time.LocalDate.now(), snapshot(), evidenceFor(question)))
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory()).build())
                .build();
    }

    /**
     * Passages relevant to this question, or a note that there are none.
     *
     * <p>Retrieval failure is swallowed on purpose. The snapshot alone still
     * answers most of what this panel is asked, so a retrieval problem should
     * cost the user the résumé half of the answer, not the whole conversation.
     */
    private String evidenceFor(String question) {
        try {
            return HybridRetriever.asEvidence(retriever.retrieve(question));
        } catch (Exception e) {
            log.warn("Retrieval failed, answering from the pipeline snapshot only: {}", e.toString());
            return "(retrieval unavailable)";
        }
    }

    private ChatMemory memory() {
        ChatMemory existing = chatMemory;
        if (existing != null) {
            return existing;
        }
        ChatMemory built = MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(MEMORY_WINDOW)
                .build();
        chatMemory = built;
        return built;
    }

    /**
     * The pipeline rendered as compact lines. Deliberately terse — this is sent
     * on every turn, so each column has to earn its tokens.
     */
    private String snapshot() {
        List<JobApplication> apps =
                applicationRepo.findByArchivedFalseOrderByLastEventAtDescAppliedAtDesc();
        if (apps.isEmpty()) {
            return "(no applications tracked yet)";
        }

        StringBuilder sb = new StringBuilder(1024);
        sb.append("company | role | platform | status | applied | last update | notes\n");
        apps.stream().limit(CONTEXT_ROWS).forEach(a -> sb
                .append(a.getCompany()).append(" | ")
                .append(orDash(a.getRoleTitle())).append(" | ")
                .append(a.getPlatform().label()).append(" | ")
                .append(a.getStatus().label()).append(" | ")
                .append(daysAgo(a.getAppliedAt())).append(" | ")
                .append(a.getLastEventAt() == null ? "never" : daysAgo(a.getLastEventAt()))
                .append(" | ")
                .append(a.getAssessmentDueAt() != null
                        ? "assessment due " + a.getAssessmentDueAt() : "")
                .append('\n'));

        if (apps.size() > CONTEXT_ROWS) {
            sb.append("(").append(apps.size() - CONTEXT_ROWS).append(" older rows omitted)\n");
        }
        return sb.toString();
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
