package com.jobtrail.web;

import com.jobtrail.service.rag.KnowledgeIngestService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rebuilding the knowledge base the grounded decisions read from.
 *
 * <p>Re-indexing is explicit rather than automatic. Reading a CV and embedding
 * it takes seconds of CPU, and doing that on a schedule would spend it
 * repeatedly on a file that changes a few times a year. Ingest is idempotent —
 * an unchanged document is detected by hash and skipped without re-embedding —
 * so pressing this more than once is cheap and safe.
 */
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeIngestService ingest;

    /** Re-reads the CV and the pipeline. */
    @PostMapping("/reindex")
    public KnowledgeIngestService.IngestReport reindex() {
        return ingest.reindexAll();
    }

    /** Re-reads only the CV — the common case after editing it. */
    @PostMapping("/reindex/resume")
    public KnowledgeIngestService.IngestReport reindexResume() {
        return ingest.reindexResume();
    }
}
