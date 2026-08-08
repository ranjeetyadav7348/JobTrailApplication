package com.jobtrail.service.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fusion and tokenising, tested without a database or a model.
 *
 * <p>These are the parts where a bug would not throw — it would quietly return
 * slightly worse passages, every answer would get slightly vaguer, and nothing
 * would ever point at the cause. Worth pinning down directly.
 */
class HybridRetrieverTest {

    private static List<Long> ranked(Long... ids) {
        return List.of(ids);
    }

    // ---- reciprocal rank fusion --------------------------------------------

    @Test
    void ranksAResultBothArmsAgreeOnAboveEitherArmsFavourite() {
        // 3 is second in both arms; 1 and 2 each top exactly one arm.
        // Agreement should win — that is the entire reason for fusing.
        Map<Long, Double> scores = HybridRetriever.fuse(List.of(
                ranked(1L, 3L),
                ranked(2L, 3L)));

        assertThat(scores.get(3L)).isGreaterThan(scores.get(1L));
        assertThat(scores.get(3L)).isGreaterThan(scores.get(2L));
    }

    @Test
    void keepsAResultOnlyOneArmFound() {
        // The whole point of the dense arm is finding passages that share no
        // keyword with the query. If fusion dropped single-arm hits, the design
        // would collapse to an intersection and that capability would vanish.
        Map<Long, Double> scores = HybridRetriever.fuse(List.of(
                ranked(1L, 2L),
                ranked(3L)));

        assertThat(scores).containsKeys(1L, 2L, 3L);
        assertThat(scores.get(3L)).isPositive();
    }

    @Test
    void prefersHigherRanksWithinASingleArm() {
        Map<Long, Double> scores = HybridRetriever.fuse(List.of(ranked(1L, 2L, 3L)));

        assertThat(scores.get(1L)).isGreaterThan(scores.get(2L));
        assertThat(scores.get(2L)).isGreaterThan(scores.get(3L));
    }

    @Test
    void survivesAnEmptyArm() {
        Map<Long, Double> scores = HybridRetriever.fuse(List.of(ranked(1L), List.of()));

        assertThat(scores).containsOnlyKeys(1L);
    }

    @Test
    void scoresNothingWhenBothArmsAreEmpty() {
        assertThat(HybridRetriever.fuse(List.of(List.of(), List.of()))).isEmpty();
    }

    @Test
    void doesNotLetOneArmsTopHitOutrankBroadAgreement() {
        // A concrete regression guard on the k constant: with k too small, rank
        // 1 in one arm would beat rank 2-in-both, and fusion would degrade into
        // "whichever arm shouted loudest".
        Map<Long, Double> scores = HybridRetriever.fuse(List.of(
                ranked(99L, 7L),
                ranked(50L, 7L)));

        assertThat(scores.get(7L)).isGreaterThan(scores.get(99L));
    }

    // ---- tokenising --------------------------------------------------------

    @Test
    void keepsTechnicalTokensIntact() {
        assertThat(HybridRetriever.tokenise("Experience with C++, C#, .NET and Node.js"))
                .contains("c++", "c#", "node.js", "net");
    }

    @Test
    void dropsStopWordsAndSingleCharacters() {
        assertThat(HybridRetriever.tokenise("how many years of a b Kafka"))
                .containsExactlyInAnyOrder("years", "kafka");
    }

    @Test
    void isCaseInsensitive() {
        assertThat(HybridRetriever.tokenise("KAFKA Kafka kafka")).containsExactly("kafka");
    }

    @Test
    void returnsNothingForAQueryOfOnlyStopWords() {
        assertThat(HybridRetriever.tokenise("what is the of and to")).isEmpty();
    }

    // ---- tsquery construction ----------------------------------------------

    @Test
    void orsTheTermsSoPartialMatchesStillRank() {
        // ANDing is the Postgres default and returns nothing for a question no
        // single passage contains in full — the bug this guards against.
        String tsQuery = HybridRetriever.toTsQuery("years of Kafka experience");

        assertThat(tsQuery).contains(" | ").doesNotContain(" & ");
        assertThat(tsQuery).contains("'kafka'").contains("'years'").contains("'experience'");
    }

    @Test
    void quotesEveryLexemeSoPunctuationCannotBecomeAnOperator() {
        // Unquoted, the ampersand and bang would be parsed as tsquery operators
        // and Postgres would throw rather than search.
        String tsQuery = HybridRetriever.toTsQuery("R&D !important (scala)");

        assertThat(tsQuery).doesNotContain("!").doesNotContain("(").doesNotContain(")");
        assertThat(tsQuery).contains("'important'").contains("'scala'");
    }

    @Test
    void leavesNoUnbalancedQuoteWhenTheQueryContainsAnApostrophe() {
        // The tokeniser treats an apostrophe as a separator, so "o'reilly"
        // splits and the one-character "o" is dropped. What matters is the
        // invariant the expression has to hold either way: quotes come in
        // pairs, so nothing can terminate a lexeme early and turn the rest of
        // the query into tsquery syntax.
        String tsQuery = HybridRetriever.toTsQuery("o'reilly books");

        assertThat(tsQuery).contains("'reilly'").contains("'books'");
        assertThat(tsQuery.chars().filter(c -> c == '\'').count() % 2).isZero();
    }

    @Test
    void returnsAnEmptyExpressionWhenNothingIsSearchable() {
        // The caller must skip the query entirely — an empty tsquery is a syntax
        // error in Postgres, not a match-nothing.
        assertThat(HybridRetriever.toTsQuery("what is the of and to")).isEmpty();
    }
}
