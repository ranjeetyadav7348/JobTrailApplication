package com.jobtrail.service.rag;

import com.jobtrail.domain.KnowledgeChunk;
import com.jobtrail.domain.KnowledgeSource;
import com.jobtrail.repo.KnowledgeChunkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The transactional half of ingest, kept in its own bean on purpose.
 *
 * <p>Spring's {@code @Transactional} is applied by a proxy, so a call from one
 * method of a class to another method of the <em>same</em> class never passes
 * through it and silently runs with no transaction at all. Putting the write
 * operations here means {@link KnowledgeIngestService} reaches them through the
 * proxy and the delete-then-insert below is genuinely atomic.
 *
 * <p>The split has a second benefit: the slow part of ingest (embedding) stays
 * outside these methods, so no database connection is held while the CPU grinds
 * through a model.
 */
@Component
@RequiredArgsConstructor
public class KnowledgeWriter {

    private final KnowledgeChunkRepository chunks;

    /**
     * Swaps in a fresh set of chunks for one document.
     *
     * <p>Replace rather than merge: when a CV is edited, passages that no longer
     * exist have to disappear. Merging would leave the old wording retrievable
     * for ever, and a stale claim about the user's experience is exactly the
     * failure this whole feature is meant to prevent.
     *
     * @return the number of rows written
     */
    @Transactional
    public int replace(KnowledgeSource source, String ref, List<KnowledgeChunk> rows) {
        chunks.deleteBySourceAndSourceRef(source, ref);
        if (rows.isEmpty()) {
            return 0;
        }
        // A document can legitimately repeat a passage — a header line carried
        // across pages, say — and the (source, ref, hash) constraint would
        // reject the duplicate. Dropping it loses nothing: a second identical
        // chunk adds no new text to retrieve.
        Set<String> seen = new HashSet<>();
        List<KnowledgeChunk> unique = rows.stream()
                .filter(row -> seen.add(row.getContentHash()))
                .toList();
        chunks.saveAll(unique);
        return unique.size();
    }

    @Transactional
    public void delete(KnowledgeSource source, String ref) {
        chunks.deleteBySourceAndSourceRef(source, ref);
    }

    @Transactional(readOnly = true)
    public Set<String> hashes(KnowledgeSource source, String ref) {
        return chunks.findHashes(source, ref);
    }

    @Transactional(readOnly = true)
    public long count(KnowledgeSource source) {
        return chunks.countBySource(source);
    }
}
