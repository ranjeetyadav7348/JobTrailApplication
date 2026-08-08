package com.jobtrail.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One retrievable passage of text, with the vector that lets it be found by
 * meaning rather than by wording.
 *
 * <p><strong>Why the vector is a {@code byte[]} and not a float array or JSON.</strong>
 * Postgres here has no {@code pgvector} extension, so there is no native vector
 * column to use. The remaining options are a JSON/text encoding, which is lossy
 * on round-trip unless you are careful and roughly six times larger, or the raw
 * IEEE-754 bytes. The bytes win: exact, compact (384 dimensions = 1536 bytes),
 * and they map to {@code bytea} on Postgres and {@code varbinary} on H2 without
 * a line of dialect-specific code. Encoding lives in
 * {@code com.jobtrail.service.rag.Vectors}.
 *
 * <p>Similarity is computed in Java rather than in SQL. That sounds like the
 * wrong shape until you count the corpus: one CV plus a few hundred
 * applications is on the order of a thousand chunks, and a thousand 384-wide
 * dot products is well under a millisecond. An index would buy nothing and cost
 * an extension that is not installed. Revisit past roughly 100k chunks.
 */
@Entity
@Table(name = "knowledge_chunk",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_knowledge_identity",
                columnNames = {"source", "source_ref", "content_hash"}),
        indexes = {
                @Index(name = "idx_knowledge_source", columnList = "source"),
                @Index(name = "idx_knowledge_source_ref", columnList = "source, source_ref")
        })
@Getter
@Setter
public class KnowledgeChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private KnowledgeSource source = KnowledgeSource.RESUME;

    /**
     * Identifies the specific document within the source — the résumé's file
     * name, or an application id. Ingest replaces a whole {@code sourceRef} at
     * a time, so re-reading an edited CV cannot leave stale passages behind.
     */
    @Column(name = "source_ref", nullable = false, length = 200)
    private String sourceRef = "";

    /** Short human label shown in citations, e.g. "Résumé — Experience". */
    @Column(nullable = false, length = 250)
    private String title = "";

    /**
     * The passage itself. Not named {@code text}, because the lexical arm reads
     * this column from hand-written SQL and {@code text} is a type name in
     * Postgres — avoidable quoting is avoidable breakage.
     */
    @Column(name = "body", nullable = false, length = 8000)
    private String body = "";

    /** Little-endian float32, one per dimension. Null when embedding was unavailable. */
    @Column(name = "embedding", length = 8192)
    private byte[] embedding;

    /** Dimension count, kept so a model change can be detected rather than guessed at. */
    @Column(name = "embedding_dims", nullable = false)
    private int embeddingDims = 0;

    /** SHA-256 of {@link #body}, hex. Lets an unchanged document skip re-embedding. */
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash = "";

    /** Position within the source document, so passages can be shown in order. */
    @Column(nullable = false)
    private int ordinal = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Whether this chunk can take part in the dense arm of retrieval. */
    public boolean embedded() {
        return embedding != null && embedding.length > 0;
    }
}
