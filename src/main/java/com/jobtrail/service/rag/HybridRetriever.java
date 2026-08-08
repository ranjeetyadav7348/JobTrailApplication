package com.jobtrail.service.rag;

import com.jobtrail.config.JobTrailProperties;
import com.jobtrail.domain.KnowledgeChunk;
import com.jobtrail.repo.KnowledgeChunkRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finds the passages most likely to answer a question, by asking two different
 * kinds of search and combining their answers.
 *
 * <h2>Why two arms</h2>
 * A vector search matches on <em>meaning</em>: a job description asking for
 * "event-driven microservices" will surface a CV bullet about Kafka consumers,
 * even with no word in common. That is exactly what keyword search cannot do.
 *
 * <p>But the reverse failure is just as real, and on a CV it is worse. Small
 * embedding models compress rare proper nouns badly — employer names, "Spring
 * Boot 3.5", "AZ-204", version numbers — and a query for one of them can rank
 * the exact passage that contains it below several that merely feel related.
 * Keyword search never has that problem: the token is present or it is not.
 *
 * <p>A CV is mostly rare proper nouns joined by ordinary prose, so it needs both.
 * Running only one arm would fail predictably on half of what gets asked.
 *
 * <h2>Why RRF rather than blending scores</h2>
 * The two arms return numbers that mean different things — a cosine similarity
 * in [-1, 1] and a {@code ts_rank_cd} whose scale depends on document length and
 * term frequency. Averaging them requires normalising both to a common range and
 * inventing a weight, and both of those need retuning whenever the corpus
 * changes. Reciprocal Rank Fusion sidesteps it: it throws the scores away and
 * uses only positions, scoring each result {@code 1/(k + rank)} summed across the
 * arms that found it. Nothing to normalise, nothing to tune, and a passage both
 * arms rank moderately well beats one that a single arm loves — which is the
 * behaviour worth having.
 *
 * <h2>Degradation</h2>
 * Both arms are optional. Without an embedding model there is no dense arm and
 * search is keyword-only; off Postgres the SQL full-text arm is replaced by an
 * equivalent computed in Java. Either way retrieval still returns something
 * useful, in the same spirit as {@code AiAvailability} elsewhere in this app.
 */
@Service
@Slf4j
public class HybridRetriever {

    /**
     * The RRF smoothing constant. 60 is the value from the original paper and
     * the de-facto default across search engines. It controls how sharply rank 1
     * outweighs rank 10; large enough that a single arm's top hit cannot
     * dominate outright, small enough that ordering still matters.
     */
    private static final int RRF_K = 60;

    private static final Pattern TOKEN = Pattern.compile("[^\\p{L}\\p{N}+#.]+");

    /** Words too common to discriminate. Kept small — over-filtering loses real queries. */
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "but", "by", "do", "does", "for",
            "from", "had", "has", "have", "how", "i", "in", "is", "it", "its", "many",
            "me", "my", "of", "on", "or", "that", "the", "to", "was", "were", "what",
            "when", "where", "which", "who", "with", "you", "your");

    private final KnowledgeChunkRepository chunks;
    private final JobTrailProperties props;
    private final ObjectProvider<EmbeddingModel> embeddingModels;
    private final DataSource dataSource;

    /** Resolved once from JDBC metadata, because the datasource URL differs in tests. */
    private volatile Boolean postgres;

    public HybridRetriever(KnowledgeChunkRepository chunks,
                           JobTrailProperties props,
                           ObjectProvider<EmbeddingModel> embeddingModels,
                           DataSource dataSource) {
        this.chunks = chunks;
        this.props = props;
        this.embeddingModels = embeddingModels;
        this.dataSource = dataSource;
    }

    /** Retrieves using the configured top-k. */
    public List<RetrievedChunk> retrieve(String query) {
        return retrieve(query, props.getRag().getTopK());
    }

    /**
     * The top {@code topK} passages for {@code query}, best first.
     *
     * <p>Read-only and transactional so both arms and the final fetch share one
     * connection rather than taking three from the pool.
     */
    @Transactional(readOnly = true)
    public List<RetrievedChunk> retrieve(String query, int topK) {
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }

        int perArm = Math.max(topK, props.getRag().getCandidatesPerArm());
        List<Long> dense = denseArm(query, perArm);
        List<Long> lexical = lexicalArm(query, perArm);

        if (dense.isEmpty() && lexical.isEmpty()) {
            return List.of();
        }

        Map<Long, Double> fused = fuse(List.of(dense, lexical));
        List<Long> winners = fused.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(topK)
                .map(Map.Entry::getKey)
                .toList();

        Map<Long, Integer> denseRanks = ranksOf(dense);
        Map<Long, Integer> lexicalRanks = ranksOf(lexical);

        Map<Long, KnowledgeChunk> rows = new HashMap<>();
        chunks.findByIdIn(winners).forEach(row -> rows.put(row.getId(), row));

        List<RetrievedChunk> out = new ArrayList<>(winners.size());
        for (Long id : winners) {
            KnowledgeChunk row = rows.get(id);
            if (row == null) {
                // Deleted between ranking and fetch. Rare, and skipping is right.
                continue;
            }
            out.add(new RetrievedChunk(
                    id,
                    row.getSource(),
                    row.getTitle(),
                    row.getBody(),
                    fused.getOrDefault(id, 0d),
                    denseRanks.getOrDefault(id, 0),
                    lexicalRanks.getOrDefault(id, 0)));
        }
        return out;
    }

    /** Whether the dense arm can run at all. */
    public boolean dense() {
        return embeddingModels.getIfAvailable() != null;
    }

    // ---- fusion ------------------------------------------------------------

    /**
     * Reciprocal Rank Fusion over any number of ranked id lists.
     *
     * <p>Package-private and free of Spring or JDBC so the ranking behaviour can
     * be tested directly — it is the one piece of this class where a subtle bug
     * would silently degrade every answer rather than throwing.
     */
    static Map<Long, Double> fuse(List<List<Long>> rankings) {
        Map<Long, Double> scores = new LinkedHashMap<>();
        for (List<Long> ranking : rankings) {
            for (int i = 0; i < ranking.size(); i++) {
                scores.merge(ranking.get(i), 1d / (RRF_K + i + 1), Double::sum);
            }
        }
        return scores;
    }

    private static Map<Long, Integer> ranksOf(List<Long> ids) {
        Map<Long, Integer> ranks = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            ranks.putIfAbsent(ids.get(i), i + 1);
        }
        return ranks;
    }

    // ---- dense arm ---------------------------------------------------------

    /**
     * Vector search: embed the query, then score every stored vector against it.
     *
     * <p>A full scan, deliberately. The corpus here is on the order of a
     * thousand chunks; a thousand 384-wide dot products over pre-normalised
     * vectors costs well under a millisecond, and an approximate index would add
     * a Postgres extension that is not installed in exchange for nothing
     * measurable. Only the id and the vector are loaded — the bodies of the few
     * survivors are fetched later, by id.
     */
    private List<Long> denseArm(String query, int limit) {
        EmbeddingModel model = embeddingModels.getIfAvailable();
        if (model == null) {
            return List.of();
        }

        float[] queryVector;
        try {
            queryVector = Vectors.normalise(model.embed(query));
        } catch (Exception e) {
            log.warn("Query embedding failed, falling back to keyword search only: {}", e.toString());
            return List.of();
        }
        if (queryVector.length == 0) {
            return List.of();
        }

        record Scored(Long id, double score) {
        }

        List<Scored> scored = new ArrayList<>();
        for (KnowledgeChunkRepository.ChunkVector candidate : chunks.findVectors()) {
            double similarity = Vectors.dot(candidate.getEmbedding(), queryVector);
            if (similarity > 0d) {
                scored.add(new Scored(candidate.getId(), similarity));
            }
        }

        return scored.stream()
                .sorted(Comparator.comparingDouble(Scored::score).reversed()
                        .thenComparing(Scored::id))
                .limit(limit)
                .map(Scored::id)
                .toList();
    }

    // ---- lexical arm -------------------------------------------------------

    private List<Long> lexicalArm(String query, int limit) {
        if (isPostgres()) {
            String tsQuery = toTsQuery(query);
            if (tsQuery.isEmpty()) {
                return List.of();
            }
            try {
                return chunks.searchFullText(tsQuery, limit);
            } catch (Exception e) {
                // A malformed tsquery or a missing column should degrade, not
                // take the whole search down — the dense arm may still answer.
                log.warn("Full-text search failed, scoring keywords in Java instead: {}", e.toString());
            }
        }
        return lexicalInJava(query, limit);
    }

    /**
     * Turns a question into a tsquery that ORs its terms.
     *
     * <p>Two things are going on. The OR is what makes partial matches possible
     * at all — see the note on {@code searchFullText}, since ANDing every term
     * returns nothing for most real questions. The quoting is what makes it
     * safe: each lexeme is wrapped in single quotes, so a stray {@code &},
     * {@code |}, {@code !} or bracket in the user's text is read as literal text
     * rather than as tsquery syntax that would throw. Embedded quotes are
     * doubled for the same reason. Quoted lexemes are still stemmed by the
     * dictionary, so "running" continues to match "run".
     */
    static String toTsQuery(String query) {
        Set<String> terms = tokenise(query);
        if (terms.isEmpty()) {
            return "";
        }
        return terms.stream()
                .map(term -> "'" + term.replace("'", "''") + "'")
                .collect(java.util.stream.Collectors.joining(" | "));
    }

    /**
     * The portable keyword arm, used off Postgres and as a safety net.
     *
     * <p>Scores each passage by how many <em>distinct</em> query terms it
     * contains, with a small bonus for repeats. Counting distinct terms first is
     * what stops a passage that says "Java" six times from outranking one that
     * actually covers "Java", "Kafka" and "Postgres" — the multi-term match is
     * nearly always the one the user meant.
     */
    private List<Long> lexicalInJava(String query, int limit) {
        Set<String> terms = tokenise(query);
        if (terms.isEmpty()) {
            return List.of();
        }

        record Scored(Long id, double score) {
        }

        List<Scored> scored = new ArrayList<>();
        for (KnowledgeChunkRepository.ChunkBody candidate : chunks.findBodies()) {
            String body = candidate.getBody();
            if (body == null || body.isBlank()) {
                continue;
            }
            String haystack = body.toLowerCase(Locale.ROOT);

            int matched = 0;
            int occurrences = 0;
            for (String term : terms) {
                int count = countOccurrences(haystack, term);
                if (count > 0) {
                    matched++;
                    occurrences += count;
                }
            }
            if (matched > 0) {
                scored.add(new Scored(candidate.getId(), matched + Math.log1p(occurrences) / 10d));
            }
        }

        return scored.stream()
                .sorted(Comparator.comparingDouble(Scored::score).reversed()
                        .thenComparing(Scored::id))
                .limit(limit)
                .map(Scored::id)
                .toList();
    }

    /**
     * Splits a query into searchable terms.
     *
     * <p>Only leading and trailing <em>dots</em> are stripped — a sentence-ending
     * period is noise, but {@code +} and {@code #} are not. Trimming those would
     * turn "C++" and "C#" into "C", which is then dropped for being a single
     * character, and a CV search would silently lose two of the most literal
     * things anyone ever searches it for.
     */
    static Set<String> tokenise(String text) {
        Set<String> terms = new HashSet<>();
        for (String raw : TOKEN.split(text.toLowerCase(Locale.ROOT))) {
            String token = raw.replaceAll("^\\.+|\\.+$", "");
            if (token.length() > 1 && !STOP_WORDS.contains(token)) {
                terms.add(token);
            }
        }
        return terms;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }

    /**
     * Whether the database supports the SQL full-text arm. Read from JDBC
     * metadata rather than from configuration because tests override the
     * datasource URL, and cached because it cannot change while running.
     */
    private boolean isPostgres() {
        Boolean known = postgres;
        if (known != null) {
            return known;
        }
        boolean detected = false;
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            detected = product != null && product.toLowerCase(Locale.ROOT).contains("postgres");
        } catch (Exception e) {
            log.warn("Could not identify the database, assuming no full-text support: {}", e.toString());
        }
        postgres = detected;
        return detected;
    }

    /** Formats retrieved passages as the evidence block handed to the model. */
    public static String asEvidence(List<RetrievedChunk> retrieved) {
        if (retrieved.isEmpty()) {
            return "(nothing relevant was found in the knowledge base)";
        }
        StringBuilder sb = new StringBuilder(2048);
        for (int i = 0; i < retrieved.size(); i++) {
            RetrievedChunk chunk = retrieved.get(i);
            sb.append('[').append(i + 1).append("] ")
                    .append(chunk.citation()).append('\n')
                    .append(chunk.body().strip()).append("\n\n");
        }
        return sb.toString().strip();
    }
}
