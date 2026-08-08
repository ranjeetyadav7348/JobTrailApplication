package com.jobtrail.service.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextChunkerTest {

    private final TextChunker chunker = new TextChunker(40, 10);

    // ---- sectioning --------------------------------------------------------

    @Test
    void labelsChunksWithTheSectionTheyCameFrom() {
        List<TextChunker.Chunk> chunks = chunker.chunk("""
                EXPERIENCE

                Built the payments ingestion service on Kafka at Northwind.

                EDUCATION

                BSc Computer Science, University of Pune.
                """, "Résumé");

        assertThat(chunks).extracting(TextChunker.Chunk::title)
                .containsExactly("EXPERIENCE", "EDUCATION");
    }

    @Test
    void neverMergesTwoSectionsIntoOneChunk() {
        // Both blocks are small enough to pack together on word count alone.
        // Only the heading between them should keep them apart — and it must,
        // or a skill ends up attributed to the wrong employer.
        List<TextChunker.Chunk> chunks = chunker.chunk("""
                EXPERIENCE

                Kafka at Northwind.

                EDUCATION

                BSc at Pune.
                """, "Résumé");

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).text()).contains("Kafka").doesNotContain("BSc");
        assertThat(chunks.get(1).text()).contains("BSc").doesNotContain("Kafka");
    }

    @Test
    void usesTheFallbackTitleBeforeAnyHeading() {
        List<TextChunker.Chunk> chunks =
                chunker.chunk("Ranjeet Yadav, Java engineer, Pune.", "Résumé");

        assertThat(chunks).singleElement()
                .extracting(TextChunker.Chunk::title).isEqualTo("Résumé");
    }

    @Test
    void treatsAllCapsLinesAsHeadingsEvenWhenUnrecognised() {
        List<TextChunker.Chunk> chunks = chunker.chunk("""
                OPEN SOURCE

                Maintainer of a Spring Boot starter.
                """, "Résumé");

        assertThat(chunks).singleElement()
                .extracting(TextChunker.Chunk::title).isEqualTo("OPEN SOURCE");
    }

    @Test
    void doesNotMistakeASentenceForAHeading() {
        // Starts with a section word, but is prose and ends in a full stop.
        List<TextChunker.Chunk> chunks =
                chunker.chunk("Skills were applied across three teams.", "Résumé");

        assertThat(chunks).singleElement()
                .extracting(TextChunker.Chunk::title).isEqualTo("Résumé");
    }

    // ---- windowing and overlap ---------------------------------------------

    @Test
    void splitsAParagraphLongerThanOneChunk() {
        String longParagraph = ("word ".repeat(200)).strip();

        List<TextChunker.Chunk> chunks = chunker.chunk(longParagraph, "Résumé");

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(c ->
                assertThat(c.text().split("\\s+")).hasSizeLessThanOrEqualTo(40));
    }

    @Test
    void carriesOverlapIntoTheFollowingChunk() {
        // Two paragraphs that cannot share a chunk. The second chunk should
        // begin with the tail of the first, so a fact spanning the boundary
        // survives whole somewhere.
        String text = "alpha " + "filler ".repeat(38) + "omega\n\n" + "beta ".repeat(30);

        List<TextChunker.Chunk> chunks = chunker.chunk(text.strip(), "Résumé");

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.get(1).text()).contains("omega");
    }

    @Test
    void numbersChunksInReadingOrder() {
        List<TextChunker.Chunk> chunks =
                chunker.chunk(("word ".repeat(300)).strip(), "Résumé");

        assertThat(chunks).extracting(TextChunker.Chunk::ordinal)
                .isEqualTo(java.util.stream.IntStream.range(0, chunks.size()).boxed().toList());
    }

    // ---- degenerate input --------------------------------------------------

    @Test
    void returnsNothingForEmptyInput() {
        assertThat(chunker.chunk(null, "Résumé")).isEmpty();
        assertThat(chunker.chunk("   \n\n  ", "Résumé")).isEmpty();
    }

    @Test
    void returnsNothingWhenTheDocumentIsOnlyHeadings() {
        assertThat(chunker.chunk("EXPERIENCE\n\nEDUCATION\n", "Résumé")).isEmpty();
    }

    @Test
    void clampsAnOverlapThatWouldStallTheWindow() {
        // Overlap >= chunk size would advance zero words per step and loop for
        // ever. The constructor has to clamp it rather than trust the caller.
        TextChunker degenerate = new TextChunker(30, 500);

        List<TextChunker.Chunk> chunks =
                degenerate.chunk(("word ".repeat(200)).strip(), "Résumé");

        assertThat(chunks).isNotEmpty().hasSizeLessThan(100);
    }
}
