package com.jobtrail.web;

import com.jobtrail.service.CareerDecisionService;
import com.jobtrail.service.rag.HybridRetriever;
import com.jobtrail.service.rag.KnowledgeIngestService;
import com.jobtrail.service.rag.RetrievedChunk;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The grounded-decision surface: ask the assistant to judge something rather
 * than to chat about it.
 *
 * <p>Nothing here sends an email. {@code /draft} returns text for a human to
 * read, edit and dispatch through the existing outreach flow — the queue and
 * its pacing stay the only path to an actual send.
 */
@RestController
@RequestMapping("/api/decisions")
@RequiredArgsConstructor
public class DecisionController {

    private final CareerDecisionService decisions;
    private final KnowledgeIngestService ingest;
    private final HybridRetriever retriever;

    public record FitRequest(String jobDescription) {
    }

    public record DraftRequest(Long applicationId, String intent) {
    }

    public record QuestionRequest(String question) {
    }

    /**
     * Whether decisions can actually be made, and on what basis. Both flags
     * matter to the UI: without a chat model there are no decisions at all,
     * and without embeddings the answers still come but retrieval is
     * keyword-only, which is worth saying rather than hiding.
     */
    public record ReadinessView(boolean modelReady, boolean embeddingsReady, long resumeChunks) {
    }

    @GetMapping("/status")
    public ReadinessView status() {
        return new ReadinessView(
                decisions.available(),
                retriever.dense(),
                ingest.resumeChunkCount());
    }

    @PostMapping("/fit")
    public CareerDecisionService.FitVerdict fit(@RequestBody FitRequest body) {
        return decisions.assessFit(body.jobDescription());
    }

    @PostMapping("/draft")
    public CareerDecisionService.DraftEmail draft(@RequestBody DraftRequest body) {
        if (body.applicationId() == null) {
            throw ApiException.badRequest("Pick an application to draft for.");
        }
        return decisions.draftEmail(body.applicationId(), body.intent());
    }

    @PostMapping("/prioritise")
    public CareerDecisionService.Priorities prioritise() {
        return decisions.prioritise();
    }

    @PostMapping("/screening")
    public CareerDecisionService.ScreeningAnswer screening(@RequestBody QuestionRequest body) {
        return decisions.answerScreeningQuestion(body.question());
    }

    /**
     * Raw retrieval, no model involved. Exists so the retrieval layer can be
     * inspected on its own — when an answer looks wrong, the first question is
     * always whether the right passages were even found, and separating that
     * from how the model used them saves a lot of guessing.
     */
    @GetMapping("/retrieve")
    public List<RetrievedChunk> retrieve(@RequestParam String q,
                                         @RequestParam(defaultValue = "8") int k) {
        return retriever.retrieve(q, k);
    }
}
