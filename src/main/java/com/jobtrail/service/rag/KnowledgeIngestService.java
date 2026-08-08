package com.jobtrail.service.rag;

import com.jobtrail.config.JobTrailProperties;
import com.jobtrail.domain.AppSettings;
import com.jobtrail.domain.ApplicationEvent;
import com.jobtrail.domain.JobApplication;
import com.jobtrail.domain.KnowledgeChunk;
import com.jobtrail.domain.KnowledgeSource;
import com.jobtrail.repo.ApplicationEventRepository;
import com.jobtrail.repo.JobApplicationRepository;
import com.jobtrail.service.SettingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Builds and refreshes the knowledge base the assistant reasons from.
 *
 * <p>Two kinds of source go in. The <strong>résumé</strong> is the authority on
 * what the user has done, and is the reason this exists at all. The
 * <strong>application history</strong> goes in alongside it so a single
 * retrieval can answer questions that span both — "have I already told Acme
 * about my Kafka work?" needs the CV and the thread together.
 *
 * <h2>Idempotence</h2>
 * Re-indexing is safe to run as often as you like. Each document is hashed
 * chunk by chunk, and if the resulting set of hashes matches what is already
 * stored the whole document is skipped without embedding anything. That matters
 * more than it looks: embedding is the slow step, and without this check a
 * "refresh" button would burn CPU re-deriving vectors that cannot have changed.
 *
 * <h2>Transactions</h2>
 * Embedding happens deliberately <em>outside</em> any transaction. It is
 * CPU-bound work measured in seconds for a full CV, and holding a database
 * connection open across it would pin a pool slot for the duration to no
 * purpose. Only the read of existing hashes and the final replace are
 * transactional, and both are short.
 */
@Service
@Slf4j
public class KnowledgeIngestService {

    /**
     * Texts embedded per call into the ONNX model. Large enough to amortise the
     * per-call overhead, small enough that a big document does not allocate one
     * enormous tensor.
     */
    private static final int EMBED_BATCH = 32;

    /** Events summarised per application. Recent mail says most of what matters. */
    private static final int EVENTS_PER_APPLICATION = 12;

    private final KnowledgeWriter writer;
    private final JobApplicationRepository applications;
    private final ApplicationEventRepository events;
    private final SettingsService settings;
    private final ResumeTextExtractor extractor;
    private final JobTrailProperties props;
    private final ObjectProvider<EmbeddingModel> embeddingModels;

    public KnowledgeIngestService(KnowledgeWriter writer,
                                  JobApplicationRepository applications,
                                  ApplicationEventRepository events,
                                  SettingsService settings,
                                  ResumeTextExtractor extractor,
                                  JobTrailProperties props,
                                  ObjectProvider<EmbeddingModel> embeddingModels) {
        this.writer = writer;
        this.applications = applications;
        this.events = events;
        this.settings = settings;
        this.extractor = extractor;
        this.props = props;
        this.embeddingModels = embeddingModels;
    }

    /** What one re-index did, for the UI and the logs. */
    public record IngestReport(int documents, int chunksWritten, int documentsUnchanged,
                               boolean embeddingsAvailable, List<String> notes) {

        static IngestReport empty(boolean embedded, String note) {
            return new IngestReport(0, 0, 0, embedded, List.of(note));
        }
    }

    /** Whether the dense half of retrieval can be built at all. */
    public boolean embeddingsAvailable() {
        return embeddingModels.getIfAvailable() != null;
    }

    /**
     * How many résumé passages are indexed. Zero is the signal that grounding
     * cannot work yet, which the UI needs in order to say so before the user
     * asks a question and gets a uselessly hedged answer.
     */
    public long resumeChunkCount() {
        return writer.count(KnowledgeSource.RESUME);
    }

    // ---- entry points ------------------------------------------------------

    /** Re-reads everything: the CV, then the pipeline. */
    public IngestReport reindexAll() {
        Instant started = Instant.now();
        IngestReport resume = reindexResume();
        IngestReport pipeline = reindexApplications();

        List<String> notes = new ArrayList<>(resume.notes());
        notes.addAll(pipeline.notes());

        IngestReport combined = new IngestReport(
                resume.documents() + pipeline.documents(),
                resume.chunksWritten() + pipeline.chunksWritten(),
                resume.documentsUnchanged() + pipeline.documentsUnchanged(),
                embeddingsAvailable(),
                notes);

        log.info("Knowledge re-index finished in {}ms: {} document(s), {} chunk(s) written, {} unchanged",
                Duration.between(started, Instant.now()).toMillis(),
                combined.documents(), combined.chunksWritten(), combined.documentsUnchanged());
        return combined;
    }

    /** Re-reads the CV. This is the one that matters for grounding. */
    public IngestReport reindexResume() {
        AppSettings current = settings.get();
        Path file = current.resolvedResume();
        if (file == null) {
            return IngestReport.empty(embeddingsAvailable(),
                    "No résumé configured — set a readable file path in Settings.");
        }

        String text;
        try {
            text = extractor.extract(file);
        } catch (Exception e) {
            log.warn("Could not read résumé {}: {}", file, e.toString());
            return IngestReport.empty(embeddingsAvailable(),
                    "Could not read " + file.getFileName() + ": " + e.getMessage());
        }

        if (text.isBlank()) {
            // A scanned CV is images, not text. Worth saying plainly, because
            // the failure is otherwise silent — indexing "succeeds" with nothing
            // in it and every later answer is unsupported.
            return IngestReport.empty(embeddingsAvailable(),
                    "No text could be extracted from " + file.getFileName()
                    + ". If it is a scanned document, export a text-based PDF.");
        }

        List<TextChunker.Chunk> pieces =
                chunker().chunk(text, "Résumé");
        int written = replace(KnowledgeSource.RESUME, file.getFileName().toString(), pieces);

        return new IngestReport(1, written, written == 0 ? 1 : 0, embeddingsAvailable(),
                List.of("Résumé: %d chunk(s) from %s".formatted(
                        Math.max(written, pieces.size()), file.getFileName())));
    }

    /** Re-reads the live pipeline so decisions can cite applications and mail. */
    public IngestReport reindexApplications() {
        List<JobApplication> live = applications.findByArchivedFalseOrderByLastEventAtDescAppliedAtDesc();
        if (live.isEmpty()) {
            return IngestReport.empty(embeddingsAvailable(), "No applications to index yet.");
        }

        int written = 0;
        int unchanged = 0;
        for (JobApplication application : live) {
            List<TextChunker.Chunk> pieces = chunker().chunk(
                    renderApplication(application), "Application — " + application.getCompany());
            int count = replace(KnowledgeSource.APPLICATION, String.valueOf(application.getId()), pieces);
            written += count;
            if (count == 0) {
                unchanged++;
            }
        }

        return new IngestReport(live.size(), written, unchanged, embeddingsAvailable(),
                List.of("Applications: %d indexed, %d unchanged".formatted(live.size(), unchanged)));
    }

    /**
     * Indexes a one-off document — a pasted job description, typically — so it
     * can be retrieved alongside the CV for the length of a decision.
     */
    public int indexAdhoc(String ref, String title, String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return replace(KnowledgeSource.ADHOC, ref, chunker().chunk(text, title));
    }

    public void forget(KnowledgeSource source, String ref) {
        writer.delete(source, ref);
    }

    // ---- the write path ----------------------------------------------------

    /**
     * Replaces everything stored for one document.
     *
     * @return chunks written, or {@code 0} when the document was unchanged
     */
    private int replace(KnowledgeSource source, String ref, List<TextChunker.Chunk> pieces) {
        if (pieces.isEmpty()) {
            writer.delete(source, ref);
            return 0;
        }

        List<String> hashes = pieces.stream().map(p -> sha256(p.text())).toList();
        if (new HashSet<>(hashes).equals(writer.hashes(source, ref))) {
            log.debug("{}/{} unchanged, skipping re-embed", source, ref);
            return 0;
        }

        List<float[]> vectors = embed(pieces.stream().map(TextChunker.Chunk::text).toList());

        List<KnowledgeChunk> rows = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            TextChunker.Chunk piece = pieces.get(i);
            float[] vector = vectors.isEmpty() ? null : vectors.get(i);

            KnowledgeChunk row = new KnowledgeChunk();
            row.setSource(source);
            row.setSourceRef(ref);
            row.setTitle(trim(piece.title(), 250));
            row.setBody(trim(piece.text(), 8000));
            row.setContentHash(hashes.get(i));
            row.setOrdinal(piece.ordinal());
            if (vector != null && vector.length > 0) {
                row.setEmbedding(Vectors.encodeNormalised(vector));
                row.setEmbeddingDims(vector.length);
            }
            rows.add(row);
        }

        return writer.replace(source, ref, rows);
    }

    // ---- embedding ---------------------------------------------------------

    /**
     * Vectors for each text, or an empty list when no embedding model is wired
     * up. An empty result is not an error: retrieval falls back to its lexical
     * arm and the app stays useful, which is the same bargain the rest of the
     * AI features here make.
     */
    private List<float[]> embed(List<String> texts) {
        EmbeddingModel model = embeddingModels.getIfAvailable();
        if (model == null) {
            log.debug("No embedding model available; indexing text only");
            return List.of();
        }
        try {
            List<float[]> out = new ArrayList<>(texts.size());
            for (int start = 0; start < texts.size(); start += EMBED_BATCH) {
                int end = Math.min(texts.size(), start + EMBED_BATCH);
                out.addAll(model.embed(texts.subList(start, end)));
            }
            return out.size() == texts.size() ? out : List.of();
        } catch (Exception e) {
            log.warn("Embedding failed, storing text only: {}", e.toString());
            return List.of();
        }
    }

    // ---- rendering ---------------------------------------------------------

    /**
     * An application as prose, because that is what gets embedded. A row of
     * columns embeds poorly — the model has no way to tell a status from a
     * company name — so the facts are written out as sentences instead.
     */
    private String renderApplication(JobApplication a) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("Application to ").append(a.getCompany());
        if (a.getRoleTitle() != null && !a.getRoleTitle().isBlank()) {
            sb.append(" for the role of ").append(a.getRoleTitle());
        }
        if (a.getLocation() != null && !a.getLocation().isBlank()) {
            sb.append(" in ").append(a.getLocation());
        }
        sb.append(".\n");
        sb.append("Applied via ").append(a.getPlatform().label())
                .append(" on ").append(a.getAppliedAt()).append(".\n");
        sb.append("Current stage: ").append(a.getStatus().label()).append(".\n");
        if (a.getLastEventAt() != null) {
            sb.append("Last contact: ").append(a.getLastEventAt()).append(".\n");
        }
        if (a.getAssessmentDueAt() != null) {
            sb.append("Assessment deadline: ").append(a.getAssessmentDueAt()).append(".\n");
        }
        if (a.getNotes() != null && !a.getNotes().isBlank()) {
            sb.append("Notes: ").append(a.getNotes()).append('\n');
        }

        List<ApplicationEvent> thread = events.findByApplicationIdOrderByReceivedAtDesc(a.getId());
        if (!thread.isEmpty()) {
            sb.append("\nCorrespondence\n");
            thread.stream().limit(EVENTS_PER_APPLICATION).forEach(e -> sb
                    .append("- ").append(e.getReceivedAt())
                    .append(" (").append(e.getKind().name().toLowerCase()).append(") ")
                    .append(e.getSubject() == null ? "" : e.getSubject())
                    .append(e.getSnippet() == null || e.getSnippet().isBlank()
                            ? "" : " — " + e.getSnippet())
                    .append('\n'));
        }
        return sb.toString();
    }

    private TextChunker chunker() {
        JobTrailProperties.Rag rag = props.getRag();
        return new TextChunker(rag.getChunkWords(), rag.getChunkOverlapWords());
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // Every JVM ships SHA-256; this cannot happen.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return "";
        }
        String s = value.strip();
        return s.length() <= max ? s : s.substring(0, max);
    }
}
