package com.jobtrail.service.rag;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Pulls readable text out of the résumé file.
 *
 * <p>PDF is the awkward format here and the one the CV is actually in. A PDF
 * stores positioned glyphs, not paragraphs, so extraction quality depends
 * entirely on reconstructing reading order — hence {@code setSortByPosition},
 * without which a two-column CV interleaves the columns line by line and
 * produces text that is technically complete and semantically nonsense.
 *
 * <p>Plain text and Markdown are accepted too, so a user who would rather
 * maintain a clean {@code .md} version can point at that and get better
 * chunking than any PDF extraction will give them.
 */
@Component
@Slf4j
public class ResumeTextExtractor {

    /** Guard against a mis-pointed setting handing us a multi-hundred-page document. */
    private static final int MAX_CHARACTERS = 200_000;

    /**
     * Extracts the document's text.
     *
     * @throws IOException if the file cannot be read or parsed
     */
    public String extract(Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        String raw = name.endsWith(".pdf") ? fromPdf(file) : Files.readString(file, StandardCharsets.UTF_8);
        return tidy(raw);
    }

    private String fromPdf(Path file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            // Without this, multi-column layouts come out interleaved.
            stripper.setSortByPosition(true);
            stripper.setParagraphEnd("\n");
            return stripper.getText(document);
        }
    }

    /**
     * Normalises the extraction into something the chunker can read.
     *
     * <p>PDF extraction leaves artefacts that matter downstream: bullet glyphs
     * that carry no meaning, hard-wrapped lines that break sentences, and runs
     * of blank lines from page breaks. Blank lines are the chunker's paragraph
     * delimiter, so collapsing the stray ones is not cosmetic — it is what stops
     * every page break from being read as a paragraph boundary.
     */
    private String tidy(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');

        text = text
                // Bullet glyphs become a plain marker the chunker treats as text.
                .replaceAll("[\\u2022\\u25CF\\u25AA\\u00B7\\u2023\\u2043\\u204C\\u204D]", "-")
                // Non-breaking and exotic spaces are still spaces.
                .replaceAll("[\\u00A0\\u2007\\u202F\\u2009\\u200A]", " ")
                // Soft hyphens and zero-width joiners carry nothing.
                .replaceAll("[\\u00AD\\u200B\\u200C\\u200D\\uFEFF]", "")
                // Three or more newlines is a page break, not three paragraphs.
                .replaceAll("\\n{3,}", "\n\n")
                // Trailing spaces before a newline confuse the blank-line split.
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("[ \\t]{2,}", " ");

        text = text.strip();
        if (text.length() > MAX_CHARACTERS) {
            log.warn("Résumé text truncated at {} characters (was {})", MAX_CHARACTERS, text.length());
            text = text.substring(0, MAX_CHARACTERS);
        }
        return text;
    }
}
