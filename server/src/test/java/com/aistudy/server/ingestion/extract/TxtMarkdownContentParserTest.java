package com.aistudy.server.ingestion.extract;

import com.aistudy.server.ingestion.extract.TxtMarkdownContentParser.ParsedBlock;
import com.aistudy.server.ingestion.extract.TxtMarkdownContentParser.ParsedDocument;
import com.aistudy.server.ingestion.job.service.IngestionErrorCode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUSINESS-006 — pure unit tests for the deterministic TXT/Markdown
 * parser (no Spring, no MySQL, no storage).
 */
class TxtMarkdownContentParserTest {

    // ==================== TXT ====================

    /** (1) blank lines separate paragraphs; line ranges are 1-based inclusive. */
    @Test
    void txtBlankLinesSeparateParagraphs() {
        ParsedDocument doc = TxtMarkdownContentParser.parseTxt(bytes("line one\nline two\n\npara two\n"));

        assertEquals("line one\nline two\n\npara two\n", doc.fullText());
        assertEquals(2, doc.blocks().size());
        assertEquals(TxtMarkdownContentParser.TYPE_PARAGRAPH, doc.blocks().get(0).type());
        assertEquals("line one\nline two", doc.blocks().get(0).text());
        assertEquals(1, doc.blocks().get(0).lineStart());
        assertEquals(2, doc.blocks().get(0).lineEnd());
        assertEquals("para two", doc.blocks().get(1).text());
        assertEquals(4, doc.blocks().get(1).lineStart());
        assertEquals(4, doc.blocks().get(1).lineEnd());
    }

    /** (2) whitespace-only lines are blank separators. */
    @Test
    void txtWhitespaceOnlyLinesAreBlank() {
        ParsedDocument doc = TxtMarkdownContentParser.parseTxt(bytes("a\n   \n\t\nb"));

        assertEquals(2, doc.blocks().size());
        assertEquals("a", doc.blocks().get(0).text());
        assertEquals("b", doc.blocks().get(1).text());
    }

    /** (3) empty document → no blocks, empty full text. */
    @Test
    void txtEmptyDocumentHasNoBlocks() {
        ParsedDocument doc = TxtMarkdownContentParser.parseTxt(new byte[0]);

        assertEquals("", doc.fullText());
        assertTrue(doc.blocks().isEmpty());
    }

    /** (4) CRLF and lone CR are normalized to LF in the EXTRACTED text. */
    @Test
    void txtLineEndingsNormalized() {
        ParsedDocument doc = TxtMarkdownContentParser.parseTxt(bytes("a\r\nb\rc\r\n"));

        assertEquals("a\nb\nc\n", doc.fullText());
        assertEquals(1, doc.blocks().size());
        assertEquals("a\nb\nc", doc.blocks().get(0).text());
    }

    /** (5) UTF-8 BOM is stripped. */
    @Test
    void utf8BomStripped() {
        ParsedDocument doc = TxtMarkdownContentParser.parseTxt(
                "\uFEFFhello\nworld".getBytes(StandardCharsets.UTF_8));

        assertEquals("hello\nworld", doc.fullText());
        assertEquals("hello\nworld", doc.blocks().get(0).text());
    }

    /** (6) an oversized ASCII paragraph splits at line boundaries; every block
     *  stays within the UTF-8 BYTE budget and the content is lossless. */
    @Test
    void txtOversizedParagraphSplitsAtLineBoundary() {
        String bigLine = "x".repeat(TxtMarkdownContentParser.MAX_BLOCK_UTF8_BYTES);
        ParsedDocument doc = TxtMarkdownContentParser.parseTxt(bytes(bigLine + "\n" + bigLine));

        assertEquals(2, doc.blocks().size());
        for (ParsedBlock block : doc.blocks()) {
            assertTrue(utf8Bytes(block.text()) <= TxtMarkdownContentParser.MAX_BLOCK_UTF8_BYTES,
                    "block exceeds the UTF-8 byte budget");
        }
        assertEquals(TxtMarkdownContentParser.MAX_BLOCK_UTF8_BYTES,
                utf8Bytes(doc.blocks().get(0).text()));
        assertEquals(TxtMarkdownContentParser.MAX_BLOCK_UTF8_BYTES,
                utf8Bytes(doc.blocks().get(1).text()));
        assertEquals(1, doc.blocks().get(0).lineStart());
        assertEquals(1, doc.blocks().get(0).lineEnd());
        assertEquals(2, doc.blocks().get(1).lineStart());
        assertEquals(2, doc.blocks().get(1).lineEnd());
        // lossless reconstruction across the line boundary
        assertEquals(bigLine + "\n" + bigLine,
                doc.blocks().get(0).text() + "\n" + doc.blocks().get(1).text());
    }

    /** (7) invalid UTF-8 → typed ENCODING_ERROR (never mojibake). */
    @Test
    void invalidUtf8ThrowsEncodingError() {
        byte[] bad = new byte[]{(byte) 0xC3, 0x28, 0x61}; // 0xC3 0x28 is malformed UTF-8

        IngestionParseException ex = assertThrows(IngestionParseException.class,
                () -> TxtMarkdownContentParser.parseTxt(bad));

        assertEquals(IngestionErrorCode.ENCODING_ERROR, ex.errorCode());
        assertTrue(ex.safeMessage().contains("UTF-8"));
    }

    // ==================== Markdown ====================

    /** (8) ATX headings → HEADING blocks, marker stripped. */
    @Test
    void mdAtxHeadingsBecomeHeadingBlocks() {
        ParsedDocument doc = TxtMarkdownContentParser.parseMarkdown(bytes("# Title\n\n## Sub title\n"));

        assertEquals(2, doc.blocks().size());
        assertEquals(TxtMarkdownContentParser.TYPE_HEADING, doc.blocks().get(0).type());
        assertEquals("Title", doc.blocks().get(0).text());
        assertEquals(1, doc.blocks().get(0).lineStart());
        assertEquals(TxtMarkdownContentParser.TYPE_HEADING, doc.blocks().get(1).type());
        assertEquals("Sub title", doc.blocks().get(1).text());
        assertEquals(3, doc.blocks().get(1).lineStart());
    }

    /** (9) heading marker without space is NOT a heading (CommonMark-ish). */
    @Test
    void mdHeadingWithoutSpaceIsParagraph() {
        ParsedDocument doc = TxtMarkdownContentParser.parseMarkdown(bytes("#NoSpace"));

        assertEquals(1, doc.blocks().size());
        assertEquals(TxtMarkdownContentParser.TYPE_PARAGRAPH, doc.blocks().get(0).type());
        assertEquals("#NoSpace", doc.blocks().get(0).text());
    }

    /** (10) fenced code with language info → CODE block, fences stripped. */
    @Test
    void mdFencedCodeWithLanguageIsCodeBlock() {
        ParsedDocument doc = TxtMarkdownContentParser.parseMarkdown(
                bytes("```java\nint x = 1;\nreturn x;\n```\n"));

        assertEquals(1, doc.blocks().size());
        assertEquals(TxtMarkdownContentParser.TYPE_CODE, doc.blocks().get(0).type());
        assertEquals("int x = 1;\nreturn x;", doc.blocks().get(0).text());
        assertEquals(1, doc.blocks().get(0).lineStart());
        assertEquals(4, doc.blocks().get(0).lineEnd());
    }

    /** (11) unclosed fence runs to EOF. */
    @Test
    void mdUnclosedFenceRunsToEof() {
        ParsedDocument doc = TxtMarkdownContentParser.parseMarkdown(bytes("```\na\nb"));

        assertEquals(1, doc.blocks().size());
        assertEquals(TxtMarkdownContentParser.TYPE_CODE, doc.blocks().get(0).type());
        assertEquals("a\nb", doc.blocks().get(0).text());
    }

    /** (12) tilde fences work too. */
    @Test
    void mdTildeFenceIsCodeBlock() {
        ParsedDocument doc = TxtMarkdownContentParser.parseMarkdown(bytes("~~~\ncode\n~~~"));

        assertEquals(1, doc.blocks().size());
        assertEquals(TxtMarkdownContentParser.TYPE_CODE, doc.blocks().get(0).type());
        assertEquals("code", doc.blocks().get(0).text());
    }

    /** (13) consecutive bullet items → one LIST block. */
    @Test
    void mdBulletListIsOneListBlock() {
        ParsedDocument doc = TxtMarkdownContentParser.parseMarkdown(bytes("- alpha\n- beta\n\nparagraph\n"));

        assertEquals(2, doc.blocks().size());
        assertEquals(TxtMarkdownContentParser.TYPE_LIST, doc.blocks().get(0).type());
        assertEquals("- alpha\n- beta", doc.blocks().get(0).text());
        assertEquals(TxtMarkdownContentParser.TYPE_PARAGRAPH, doc.blocks().get(1).type());
        assertEquals("paragraph", doc.blocks().get(1).text());
    }

    /** (14) ordered list items → one LIST block. */
    @Test
    void mdOrderedListIsOneListBlock() {
        ParsedDocument doc = TxtMarkdownContentParser.parseMarkdown(bytes("1. first\n2. second\n"));

        assertEquals(1, doc.blocks().size());
        assertEquals(TxtMarkdownContentParser.TYPE_LIST, doc.blocks().get(0).type());
        assertEquals("1. first\n2. second", doc.blocks().get(0).text());
    }

    /** (15) consecutive pipe lines → one TABLE block. */
    @Test
    void mdPipeTableIsOneTableBlock() {
        ParsedDocument doc = TxtMarkdownContentParser.parseMarkdown(
                bytes("| a | b |\n|---|---|\n| 1 | 2 |\n"));

        assertEquals(1, doc.blocks().size());
        assertEquals(TxtMarkdownContentParser.TYPE_TABLE, doc.blocks().get(0).type());
        assertEquals("| a | b |\n|---|---|\n| 1 | 2 |", doc.blocks().get(0).text());
    }

    /** (16) mixed document keeps deterministic block order + types. */
    @Test
    void mdMixedDocumentKeepsDeterministicOrder() {
        ParsedDocument doc = TxtMarkdownContentParser.parseMarkdown(bytes(
                "# Title\n"
                        + "\n"
                        + "intro paragraph\n"
                        + "\n"
                        + "- one\n"
                        + "- two\n"
                        + "\n"
                        + "```sql\n"
                        + "SELECT 1;\n"
                        + "```\n"
                        + "\n"
                        + "| h |\n"
                        + "|---|\n"));

        List<String> types = doc.blocks().stream().map(ParsedBlock::type).toList();
        assertEquals(List.of(
                TxtMarkdownContentParser.TYPE_HEADING,
                TxtMarkdownContentParser.TYPE_PARAGRAPH,
                TxtMarkdownContentParser.TYPE_LIST,
                TxtMarkdownContentParser.TYPE_CODE,
                TxtMarkdownContentParser.TYPE_TABLE), types);
        assertEquals("intro paragraph", doc.blocks().get(1).text());
        // line 1 = "# Title", line 2 = blank, line 3 = "intro paragraph"
        // (1-based normalized text; blank source lines count —
        // RUNTIME-FIX-02-E corrected the stale expectation).
        assertEquals(3, doc.blocks().get(1).lineStart());
    }

    /** (17) setext underlines are NOT interpreted (deferred) — plain paragraph text. */
    @Test
    void mdSetextUnderlineStaysParagraph() {
        ParsedDocument doc = TxtMarkdownContentParser.parseMarkdown(bytes("Title\n=====\n"));

        assertEquals(1, doc.blocks().size());
        assertEquals(TxtMarkdownContentParser.TYPE_PARAGRAPH, doc.blocks().get(0).type());
        assertEquals("Title\n=====", doc.blocks().get(0).text());
    }

    /** (18) a heading inside a paragraph run terminates the paragraph. */
    @Test
    void mdHeadingTerminatesParagraphRun() {
        ParsedDocument doc = TxtMarkdownContentParser.parseMarkdown(bytes("text\n# h\nmore\n"));

        assertEquals(3, doc.blocks().size());
        assertEquals(TxtMarkdownContentParser.TYPE_PARAGRAPH, doc.blocks().get(0).type());
        assertEquals("text", doc.blocks().get(0).text());
        assertEquals(TxtMarkdownContentParser.TYPE_HEADING, doc.blocks().get(1).type());
        assertEquals("h", doc.blocks().get(1).text());
        assertEquals(TxtMarkdownContentParser.TYPE_PARAGRAPH, doc.blocks().get(2).type());
        assertEquals("more", doc.blocks().get(2).text());
    }

    // ==================== UTF-8 byte-bounded emission (FIX-03) ====================

    /** (19) ASCII multi-line paragraph over the budget: split at line
     *  boundaries, each block within the UTF-8 byte budget, lossless. */
    @Test
    void asciiMultiLineParagraphSplitsWithoutLoss() {
        int lines = 3;
        String line = "y".repeat(25_000); // 25,000 bytes each; 2 lines = 50,001 bytes, 3 = 75,003
        String content = line + "\n" + line + "\n" + line;
        ParsedDocument doc = TxtMarkdownContentParser.parseTxt(bytes(content));

        assertEquals(2, doc.blocks().size(), "75,003 bytes must split into 2 blocks");
        for (ParsedBlock block : doc.blocks()) {
            assertTrue(utf8Bytes(block.text()) <= TxtMarkdownContentParser.MAX_BLOCK_UTF8_BYTES,
                    "block exceeds the UTF-8 byte budget");
        }
        assertEquals(line + "\n" + line, doc.blocks().get(0).text());
        assertEquals(line, doc.blocks().get(1).text());
        // lossless reconstruction
        assertEquals(content, doc.blocks().get(0).text() + "\n" + doc.blocks().get(1).text());
    }

    /** (20) Chinese content: byte budget, not char count, is enforced
     *  (60000 中-chars would be 180,000 UTF-8 bytes). */
    @Test
    void chineseContentBoundedByUtf8Bytes() {
        String line = "中".repeat(20_000); // 60,000 bytes — exactly one block
        ParsedDocument doc = TxtMarkdownContentParser.parseTxt(bytes(line + "\n" + line + "中"));

        // block1 = 20,000 中 (60,000 bytes, exactly at limit); block2 = 20,000 中 + 1 中 = 60,003 bytes
        // -> second line alone exceeds -> split into 2 chunks (60,000 + 3 bytes)
        assertEquals(3, doc.blocks().size());
        for (ParsedBlock block : doc.blocks()) {
            assertTrue(utf8Bytes(block.text()) <= TxtMarkdownContentParser.MAX_BLOCK_UTF8_BYTES,
                    "block exceeds the UTF-8 byte budget: " + utf8Bytes(block.text()));
        }
        assertEquals(60_000, utf8Bytes(doc.blocks().get(0).text()));
        // lossless reconstruction
        assertEquals(line + "\n" + line + "中",
                doc.blocks().get(0).text() + "\n" + doc.blocks().get(1).text() + doc.blocks().get(2).text());
    }

    /** (21) emoji / supplementary Unicode: splits never cut a surrogate
     *  pair, content round-trips exactly. */
    @Test
    void emojiSupplementaryNotBrokenAtSplit() {
        String emoji = "\uD83D\uDE00"; // U+1F600, 4 UTF-8 bytes
        String line = emoji.repeat(15_001); // 60,004 bytes — just over the budget
        ParsedDocument doc = TxtMarkdownContentParser.parseTxt(bytes(line));

        assertEquals(2, doc.blocks().size());
        for (ParsedBlock block : doc.blocks()) {
            assertTrue(utf8Bytes(block.text()) <= TxtMarkdownContentParser.MAX_BLOCK_UTF8_BYTES,
                    "block exceeds the UTF-8 byte budget");
            // every chunk must be a well-formed Unicode string: encoding
            // back to UTF-8 and decoding must reproduce the chunk exactly
            assertEquals(block.text(),
                    new String(block.text().getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
            // no chunk may begin or end inside a surrogate pair
            assertTrue(block.text().isEmpty()
                            || !Character.isLowSurrogate(block.text().charAt(0)),
                    "chunk must not start with a low surrogate");
            assertTrue(block.text().isEmpty()
                            || !Character.isHighSurrogate(block.text().charAt(block.text().length() - 1)),
                    "chunk must not end with a high surrogate");
        }
        // lossless: chunks of one line concatenate without any separator
        assertEquals(line, doc.blocks().get(0).text() + doc.blocks().get(1).text());
        assertEquals(15_000 * 4, utf8Bytes(doc.blocks().get(0).text()));
        assertEquals(4, utf8Bytes(doc.blocks().get(1).text()));
    }

    /** (22) a single overlong line splits into multiple blocks with
     *  lineStart == lineEnd == that source line. */
    @Test
    void singleOverlongLineSplitsIntoMultipleBlocks() {
        String line = "z".repeat(70_000); // 70,000 bytes in ONE line
        ParsedDocument doc = TxtMarkdownContentParser.parseTxt(bytes(line));

        assertEquals(2, doc.blocks().size());
        assertEquals(60_000, utf8Bytes(doc.blocks().get(0).text()));
        assertEquals(10_000, utf8Bytes(doc.blocks().get(1).text()));
        assertEquals(1, doc.blocks().get(0).lineStart());
        assertEquals(1, doc.blocks().get(0).lineEnd());
        assertEquals(1, doc.blocks().get(1).lineStart());
        assertEquals(1, doc.blocks().get(1).lineEnd());
        assertEquals(line, doc.blocks().get(0).text() + doc.blocks().get(1).text());
    }

    /** (23) an overlong fenced CODE block splits into bounded CODE blocks. */
    @Test
    void overlongFencedCodeSplits() {
        String inner = "c".repeat(70_000);
        ParsedDocument doc = TxtMarkdownContentParser.parseMarkdown(bytes("```\n" + inner + "\n```"));

        assertEquals(2, doc.blocks().size());
        for (ParsedBlock block : doc.blocks()) {
            assertEquals(TxtMarkdownContentParser.TYPE_CODE, block.type());
            assertTrue(utf8Bytes(block.text()) <= TxtMarkdownContentParser.MAX_BLOCK_UTF8_BYTES,
                    "CODE block exceeds the UTF-8 byte budget");
        }
        assertEquals(inner, doc.blocks().get(0).text() + doc.blocks().get(1).text());
    }

    /** (24) an overlong heading splits into bounded HEADING blocks
     *  (marker stripped, content preserved). */
    @Test
    void overlongHeadingSplits() {
        String body = "h".repeat(70_000);
        ParsedDocument doc = TxtMarkdownContentParser.parseMarkdown(bytes("# " + body));

        assertEquals(2, doc.blocks().size());
        for (ParsedBlock block : doc.blocks()) {
            assertEquals(TxtMarkdownContentParser.TYPE_HEADING, block.type());
            assertTrue(utf8Bytes(block.text()) <= TxtMarkdownContentParser.MAX_BLOCK_UTF8_BYTES,
                    "HEADING block exceeds the UTF-8 byte budget");
            assertEquals(1, block.lineStart());
            assertEquals(1, block.lineEnd());
        }
        assertEquals(body, doc.blocks().get(0).text() + doc.blocks().get(1).text());
    }

    // ==================== helpers ====================

    private static int utf8Bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
