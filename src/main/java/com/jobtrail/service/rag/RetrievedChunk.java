package com.jobtrail.service.rag;

import com.jobtrail.domain.KnowledgeSource;

/**
 * One passage that survived retrieval, with enough provenance to be cited.
 *
 * <p>The two rank fields are kept rather than discarded after fusion because
 * they are the only way to see <em>why</em> something was retrieved. A passage
 * found by the lexical arm alone almost always matched a literal token — a
 * version number, an employer, an acronym — while one found only by the dense
 * arm matched on meaning. When retrieval misbehaves, that distinction is the
 * first thing worth knowing, and reconstructing it after the fact is impossible.
 *
 * @param denseRank   1-based rank in the vector arm, or {@code 0} if not found there
 * @param lexicalRank 1-based rank in the keyword arm, or {@code 0} if not found there
 */
public record RetrievedChunk(Long id,
                             KnowledgeSource source,
                             String title,
                             String body,
                             double score,
                             int denseRank,
                             int lexicalRank) {

    /** Both arms agreed. Worth surfacing: agreement is a genuine confidence signal. */
    public boolean foundByBoth() {
        return denseRank > 0 && lexicalRank > 0;
    }

    /** A short label for a citation line. */
    public String citation() {
        return source.label() + (title == null || title.isBlank() ? "" : " — " + title);
    }
}
