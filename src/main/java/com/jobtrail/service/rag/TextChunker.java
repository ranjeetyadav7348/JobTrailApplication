package com.jobtrail.service.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Splits a document into passages small enough to retrieve precisely and large
 * enough to answer from.
 *
 * <p>Chunking is the part of a RAG pipeline that quietly decides how good it
 * can ever be: retrieve a chunk that is too small and the answer is missing its
 * context, too large and the evidence is diluted by irrelevant text. Three
 * choices here, each earning its complexity:
 *
 * <ol>
 *   <li><strong>Sections are respected.</strong> A CV is not prose — it is
 *       headed blocks. Splitting blind to those headings produces chunks that
 *       start mid-way through Experience and end inside Education, which reads
 *       as a single passage to the model and invites it to attribute a skill to
 *       the wrong employer. Each chunk therefore stays within one section and
 *       carries that section's name.</li>
 *   <li><strong>Paragraphs are kept whole where they fit.</strong> A bullet is a
 *       complete thought; cutting one in half helps nobody.</li>
 *   <li><strong>Chunks overlap.</strong> A fact split across a boundary — the
 *       skill at the end of one chunk, its duration at the start of the next —
 *       is retrievable from neither half alone. Repeating a tail of words into
 *       the following chunk means at least one copy holds the whole fact.</li>
 * </ol>
 *
 * <p>Pure and dependency-free by design, so the behaviour above is directly
 * testable without a database or a model.
 */
public class TextChunker {

    /** A passage, with the section it came from. */
    public record Chunk(String title, String text, int ordinal) {
    }

    /**
     * Headings that mark a new section of a CV. Matched case-insensitively and
     * as a prefix, so "TECHNICAL SKILLS" and "Skills & Tools" both land.
     */
    private static final Set<String> SECTION_WORDS = Set.of(
            "summary", "objective", "profile", "about",
            "experience", "employment", "work history", "professional",
            "skills", "technical", "technologies", "tools", "expertise",
            "education", "academic", "qualifications",
            "projects", "portfolio",
            "certifications", "certificates", "licenses",
            "achievements", "awards", "honors", "honours",
            "publications", "patents",
            "languages", "interests", "hobbies",
            "contact", "references");

    private static final Pattern BLANK_LINE = Pattern.compile("\\n\\s*\\n");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** Longest a line can be and still plausibly be a heading rather than a sentence. */
    private static final int MAX_HEADING_CHARS = 60;

    private final int chunkWords;
    private final int overlapWords;

    public TextChunker(int chunkWords, int overlapWords) {
        this.chunkWords = Math.max(20, chunkWords);
        // An overlap at or above the chunk size would never advance the window.
        this.overlapWords = Math.max(0, Math.min(overlapWords, this.chunkWords / 2));
    }

    /**
     * Splits {@code text}, labelling each chunk with its section, or with
     * {@code fallbackTitle} for anything appearing before the first heading.
     */
    public List<Chunk> chunk(String text, String fallbackTitle) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<Chunk> chunks = new ArrayList<>();
        String section = fallbackTitle;
        List<String> pending = new ArrayList<>();
        int pendingWords = 0;

        for (String block : blocks(text)) {
            String heading = headingOf(block);
            if (heading != null) {
                // A heading closes whatever was accumulating: chunks never span
                // sections, which is the whole point of tracking them.
                //
                // The overlap is deliberately NOT carried across this boundary.
                // Overlap exists to protect a fact split by an arbitrary cut,
                // but a section change is not arbitrary — carrying the tail of
                // Experience into the first chunk of Education would file a job
                // under a degree, which is exactly the misattribution sections
                // are here to prevent.
                flush(chunks, pending, section, false);
                pendingWords = 0;
                section = heading;
                continue;
            }

            int blockWords = wordCount(block);

            if (blockWords > chunkWords) {
                // One oversized paragraph. Nothing to pack it with, so emit what
                // is pending and window through it on its own — the windows
                // carry their own overlap, so seeding another here would just
                // duplicate text.
                flush(chunks, pending, section, false);
                pendingWords = 0;
                for (String window : windows(block)) {
                    chunks.add(new Chunk(section, window, chunks.size()));
                }
                continue;
            }

            if (pendingWords + blockWords > chunkWords && !pending.isEmpty()) {
                pendingWords = flush(chunks, pending, section, true);
            }
            pending.add(block);
            pendingWords += blockWords;
        }

        flush(chunks, pending, section, false);
        return chunks;
    }

    // ---- internals ---------------------------------------------------------

    /**
     * Emits the accumulated blocks as one chunk.
     *
     * @param carryOverlap whether to seed the next chunk with the overlapping
     *                     tail of this one. False at a section boundary and at
     *                     the end of the document, where there is either nothing
     *                     to protect or nothing the overlap belongs to.
     * @return the word count now pending
     */
    private int flush(List<Chunk> chunks, List<String> pending, String section, boolean carryOverlap) {
        if (pending.isEmpty()) {
            return 0;
        }
        String joined = String.join("\n", pending).strip();
        pending.clear();
        if (joined.isEmpty()) {
            return 0;
        }
        chunks.add(new Chunk(section, joined, chunks.size()));

        if (!carryOverlap || overlapWords == 0) {
            return 0;
        }
        String tail = lastWords(joined, overlapWords);
        if (tail.isEmpty()) {
            return 0;
        }
        pending.add(tail);
        return wordCount(tail);
    }

    /** Paragraph-ish blocks: split on blank lines, then trim and drop empties. */
    private static List<String> blocks(String text) {
        String normalised = text.replace("\r\n", "\n").replace('\r', '\n');
        List<String> out = new ArrayList<>();
        for (String block : BLANK_LINE.split(normalised)) {
            String trimmed = block.strip();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /**
     * The heading this block represents, or {@code null} if it is body text.
     *
     * <p>A heading is a single short line that either shouts (all caps) or names
     * a section we recognise. Requiring a single line is what stops a bullet
     * that happens to begin with "Skills:" from being mistaken for one.
     */
    private static String headingOf(String block) {
        if (block.indexOf('\n') >= 0 || block.length() > MAX_HEADING_CHARS) {
            return null;
        }
        String line = block.strip();
        if (line.isEmpty() || line.endsWith(".")) {
            return null;
        }

        String cleaned = line.replaceAll("[:\\-–—_*#]+$", "").strip();
        if (cleaned.isEmpty()) {
            return null;
        }

        String lower = cleaned.toLowerCase(Locale.ROOT);
        boolean named = SECTION_WORDS.stream().anyMatch(lower::startsWith);
        boolean shouted = cleaned.equals(cleaned.toUpperCase(Locale.ROOT))
                          && cleaned.chars().anyMatch(Character::isLetter);

        return named || shouted ? cleaned : null;
    }

    /** Fixed word windows with overlap, for a paragraph too long to keep whole. */
    private List<String> windows(String block) {
        String[] words = WHITESPACE.split(block.strip());
        List<String> out = new ArrayList<>();
        int step = Math.max(1, chunkWords - overlapWords);
        for (int start = 0; start < words.length; start += step) {
            int end = Math.min(words.length, start + chunkWords);
            out.add(String.join(" ", List.of(words).subList(start, end)));
            if (end == words.length) {
                break;
            }
        }
        return out;
    }

    private static String lastWords(String text, int count) {
        String[] words = WHITESPACE.split(text.strip());
        if (words.length <= count) {
            return text.strip();
        }
        return String.join(" ", List.of(words).subList(words.length - count, words.length));
    }

    private static int wordCount(String text) {
        String trimmed = text.strip();
        return trimmed.isEmpty() ? 0 : WHITESPACE.split(trimmed).length;
    }
}
