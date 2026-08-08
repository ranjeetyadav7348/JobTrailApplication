package com.jobtrail.repo;

import com.jobtrail.domain.KnowledgeChunk;
import com.jobtrail.domain.KnowledgeSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {

    /**
     * Just the vectors, for the dense arm. A projection rather than whole
     * entities because scoring needs nothing else — the bodies of the handful
     * that survive ranking are fetched afterwards by id.
     */
    interface ChunkVector {
        Long getId();

        byte[] getEmbedding();
    }

    @Query("select c.id as id, c.embedding as embedding from KnowledgeChunk c where c.embedding is not null")
    List<ChunkVector> findVectors();

    List<KnowledgeChunk> findByIdIn(Collection<Long> ids);

    List<KnowledgeChunk> findBySourceAndSourceRefOrderByOrdinalAsc(KnowledgeSource source, String sourceRef);

    @Query("select c.contentHash from KnowledgeChunk c where c.source = :source and c.sourceRef = :ref")
    Set<String> findHashes(@Param("source") KnowledgeSource source, @Param("ref") String ref);

    void deleteBySourceAndSourceRef(KnowledgeSource source, String sourceRef);

    long countBySource(KnowledgeSource source);

    /**
     * The lexical arm on Postgres: full-text search ranked by
     * {@code ts_rank_cd}, which rewards passages where the query terms sit
     * close together rather than merely all appearing somewhere.
     *
     * <p>Native, and deliberately so — this is Postgres-only syntax. Native
     * queries are not parsed when the context starts, so its presence cannot
     * break the H2 test context; it is simply never called there (see
     * {@code HybridRetriever}, which picks an arm from JDBC metadata).
     *
     * <p><strong>{@code to_tsquery} with an OR expression, not
     * {@code plainto_tsquery}.</strong> The obvious choice is
     * {@code plainto_tsquery}, which takes a plain sentence — but it joins every
     * term with AND, so a passage has to contain all of them to match at all.
     * Real questions are not phrased that way: "how many years of experience do
     * I have with Kafka" returns nothing, because no single passage contains
     * every word. Retrieval that silently returns an empty set for ordinary
     * questions is worse than useless, so the caller builds an OR expression
     * instead and lets {@code ts_rank_cd} sort partial matches — passages
     * matching more terms rank higher, which is the behaviour wanted.
     *
     * <p>The caller is responsible for producing valid tsquery syntax; see
     * {@code HybridRetriever#toTsQuery}, which quotes every lexeme so that
     * punctuation in a query cannot be parsed as an operator.
     */
    @Query(value = """
            select c.id
            from knowledge_chunk c
            where to_tsvector('english', c.body) @@ to_tsquery('english', :q)
            order by ts_rank_cd(to_tsvector('english', c.body), to_tsquery('english', :q)) desc,
                     c.id asc
            limit :limit
            """, nativeQuery = true)
    List<Long> searchFullText(@Param("q") String tsQuery, @Param("limit") int limit);

    /**
     * Bodies for the portable lexical arm, used off Postgres. Scoring happens in
     * Java there — see {@code HybridRetriever#lexicalInJava}.
     */
    @Query("select c.id as id, c.body as body from KnowledgeChunk c")
    List<ChunkBody> findBodies();

    interface ChunkBody {
        Long getId();

        String getBody();
    }
}
