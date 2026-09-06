package com.aistudy.server.ingestion.extract;

import com.aistudy.server.ingestion.job.service.IngestionErrorCode;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * BUSINESS-006 — deterministic V1 TXT / Markdown parser (runbook §6.3:
 * no AI dependency, stable ordered blocks, provenance-preserving).
 *
 * <p>Pure Java, no Spring, no IO — bytes in, parsed document out.
 *
 * <h3>Encoding policy (documented)</h3>
 *
 * <p>UTF-8 only, strict decoding: malformed bytes fail with
 * {@link IngestionErrorCode#ENCODING_ERROR} and an actionable message
 * (never silently mojibake'd). A leading UTF-8 BOM is stripped.
 * GBK/GB18030 detection is deferred.
 *
 * <h3>Normalization</h3>
 *
 * <p>Line endings are normalized to LF ({@code \r\n} and lone
 * {@code \r} → {@code \n}) — the RAW bytes stay untouched in storage;
 * this applies to the EXTRACTED text only. {@code locator_json} line
 * numbers refer to the normalized text (1-based, inclusive).
 *
 * <h3>TXT block rules</h3>
 *
 * <p>Blank lines (empty or whitespace-only) separate runs of
 * non-blank lines; each run becomes one PARAGRAPH block. Empty input
 * produces zero blocks.
 *
 * <h3>Markdown block rules (deterministic subset, no heuristics)</h3>
 *
 * <ul>
 *   <li>ATX headings {@code #..#} → HEADING (marker stripped)</li>
 *   <li>fenced code blocks {@code ```} / {@code ~~~} → CODE (fences
 *       stripped; unclosed fence runs to EOF)</li>
 *   <li>consecutive list-item lines ({@code - * +} or {@code 1. 1)}
 *       with marker spacing) → LIST (one block, items joined by
 *       newline, markers kept)</li>
 *   <li>consecutive pipe-table lines (leading {@code |}) → TABLE
 *       (raw text only; structured parsing deferred)</li>
 *   <li>everything else: runs of plain lines → PARAGRAPH</li>
 * </ul>
 *
 * <p>Setext headings ({@code ===} / {@code ---} underlines), inline
 * formatting and nested structures are NOT interpreted (deferred) —
 * they remain PARAGRAPH text.
 *
 * <h3>Bounded blocks — UTF-8 BYTES, every block type
 * (AUTORUN-4H-PRE-RUNTIME-FIX-01)</h3>
 *
 * <p>{@code normalized_text} lives in a MySQL TEXT column (~64KB
 * <em>bytes</em>), so the real constraint is byte capacity, not
 * {@code String.length()}. The invariant is:
 *
 * <pre>
 * every ParsedBlock.text.getBytes(UTF_8).length &lt;= MAX_BLOCK_UTF8_BYTES (60,000)
 * </pre>
 *
 * <p>ALL block types (HEADING / PARAGRAPH / LIST / TABLE / CODE) go
 * through the same bounded emitter:
 *
 * <ul>
 *   <li>multi-line blocks are split at line boundaries so each group
 *       fits the budget (the {@code \n} separators count toward the
 *       byte budget);</li>
 *   <li>a single line that ALONE exceeds the budget is split INSIDE
 *       the line at Unicode code-point boundaries — nothing is
 *       truncated, no character is lost, and a UTF-16 surrogate pair
 *       is never cut in half;</li>
 *   <li>chunks of one original line keep
 *       {@code lineStart == lineEnd == that line} so provenance still
 *       locates the source line exactly.</li>
 * </ul>
 */
public final class TxtMarkdownContentParser {

    public static final String TYPE_HEADING = "HEADING";
    public static final String TYPE_PARAGRAPH = "PARAGRAPH";
    public static final String TYPE_LIST = "LIST";
    public static final String TYPE_TABLE = "TABLE";
    public static final String TYPE_CODE = "CODE";

    /**
     * Safety margin under the MySQL TEXT 64KB byte capacity.
     * Units are UTF-8 BYTES, not chars.
     */
    public static final int MAX_BLOCK_UTF8_BYTES = 60_000;

    private static final Pattern ATX_HEADING = Pattern.compile("^#{1,6}[ \t]+(.*)$");
    /** Opening fence: 3+ identical backticks/tildes, optional trailing info (e.g. ```java). */
    private static final Pattern FENCE_OPEN = Pattern.compile("^([`~])\\1{2,}.*$");
    private static final Pattern LIST_ITEM = Pattern.compile("^[ \t]*(?:[-*+]|\\d+[.)])[ \t]+.*$");
    private static final Pattern TABLE_LINE = Pattern.compile("^[ \t]*\\|.*$");

    private TxtMarkdownContentParser() {
    }

    /**
     * One parsed block. {@code text} is the normalized block text;
     * {@code lineStart}/{@code lineEnd} are 1-based inclusive line
     * ranges in the normalized document text (the locator that future
     * KnowledgePoint provenance will cite). Chunks produced by
     * splitting ONE overlong line keep {@code lineStart == lineEnd}
     * set to that source line.
     */
    public record ParsedBlock(String type, String text, int lineStart, int lineEnd) {
    }

    /** The full normalized document plus its ordered blocks. */
    public record ParsedDocument(String fullText, List<ParsedBlock> blocks) {
    }

    /** Parses a plain-text document (UTF-8 bytes). */
    public static ParsedDocument parseTxt(byte[] raw) {
        return parse(raw, false);
    }

    /** Parses a Markdown document (UTF-8 bytes). */
    public static ParsedDocument parseMarkdown(byte[] raw) {
        return parse(raw, true);
    }

    private static ParsedDocument parse(byte[] raw, boolean markdown) {
        String text = decodeUtf8(raw);
        String normalized = normalize(text);
        String[] lines = normalized.split("\n", -1);
        List<ParsedBlock> blocks = markdown
                ? markdownBlocks(lines)
                : txtBlocks(lines);
        return new ParsedDocument(normalized, List.copyOf(blocks));
    }

    // ==================== decoding / normalization ====================

    private static String decodeUtf8(byte[] raw) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IngestionParseException(IngestionErrorCode.ENCODING_ERROR,
                    "file is not valid UTF-8; please re-save as UTF-8 "
                            + "(GBK/GB18030 text encoding is not supported yet)");
        }
    }

    private static String normalize(String text) {
        String s = text;
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') {
            s = s.substring(1); // UTF-8 BOM
        }
        s = s.replace("\r\n", "\n").replace('\r', '\n');
        return s;
    }

    // ==================== TXT ====================

    private static List<ParsedBlock> txtBlocks(String[] lines) {
        List<ParsedBlock> blocks = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            if (isBlank(lines[i])) {
                i++;
                continue;
            }
            int start = i;
            while (i < lines.length && !isBlank(lines[i])) {
                i++;
            }
            emitBounded(blocks, TYPE_PARAGRAPH, lines, start, i - 1, start, i - 1);
        }
        return blocks;
    }

    // ==================== Markdown ====================

    private static List<ParsedBlock> markdownBlocks(String[] lines) {
        List<ParsedBlock> blocks = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            if (isBlank(lines[i])) {
                i++;
                continue;
            }
            var fence = FENCE_OPEN.matcher(lines[i]);
            if (fence.matches()) {
                char fenceChar = lines[i].charAt(0);
                int start = i;
                i++;
                int innerStart = i;
                while (i < lines.length
                        && !isClosingFence(lines[i], fenceChar)) {
                    i++;
                }
                int innerEnd = i - 1;
                if (i < lines.length) {
                    i++; // consume closing fence
                }
                // CODE content is the inner lines; the locator spans
                // the fences (or runs to EOF when unclosed).
                emitBounded(blocks, TYPE_CODE, lines, innerStart, innerEnd, start, i - 1);
                continue;
            }
            if (ATX_HEADING.matcher(lines[i]).matches()) {
                String heading = ATX_HEADING.matcher(lines[i]).replaceFirst("$1");
                emitSingleLineText(blocks, TYPE_HEADING, heading, i);
                i++;
                continue;
            }
            if (TABLE_LINE.matcher(lines[i]).matches()) {
                int start = i;
                while (i < lines.length && TABLE_LINE.matcher(lines[i]).matches()) {
                    i++;
                }
                emitBounded(blocks, TYPE_TABLE, lines, start, i - 1, start, i - 1);
                continue;
            }
            if (LIST_ITEM.matcher(lines[i]).matches()) {
                int start = i;
                while (i < lines.length && LIST_ITEM.matcher(lines[i]).matches()) {
                    i++;
                }
                emitBounded(blocks, TYPE_LIST, lines, start, i - 1, start, i - 1);
                continue;
            }
            int start = i;
            while (i < lines.length
                    && !isBlank(lines[i])
                    && !FENCE_OPEN.matcher(lines[i]).matches()
                    && !ATX_HEADING.matcher(lines[i]).matches()
                    && !TABLE_LINE.matcher(lines[i]).matches()
                    && !LIST_ITEM.matcher(lines[i]).matches()) {
                i++;
            }
            emitBounded(blocks, TYPE_PARAGRAPH, lines, start, i - 1, start, i - 1);
        }
        return blocks;
    }

    private static boolean isClosingFence(String line, char fenceChar) {
        if (line.isEmpty() || line.charAt(0) != fenceChar) {
            return false;
        }
        int count = 0;
        for (int i = 0; i < line.length() && line.charAt(i) == fenceChar; i++) {
            count++;
        }
        if (count < 3) {
            return false;
        }
        return line.substring(count).trim().isEmpty();
    }

    // ==================== bounded emission (UTF-8 BYTES) ====================

    private static boolean isBlank(String line) {
        return line.trim().isEmpty();
    }

    /**
     * THE unified bounded emitter — every block type passes through
     * here. Groups {@code lines[start..end]} (inclusive) so each
     * emitted block's UTF-8 byte size (&lt;= MAX_BLOCK_UTF8_BYTES,
     * {@code \n} separators counted) fits the MySQL TEXT capacity.
     * Splitting prefers line boundaries; a single overlong line is
     * split inside the line at code-point boundaries (nothing
     * truncated, surrogate pairs never cut). {@code locStart}/
     * {@code locEnd} are the 0-based locator range (usually the
     * content lines themselves; fence-to-fence for CODE).
     */
    private static void emitBounded(List<ParsedBlock> blocks, String type,
                                    String[] lines, int start, int end,
                                    int locStart, int locEnd) {
        if (start > end) {
            return;
        }
        int groupStart = start;
        int usedBytes = 0;
        boolean split = false;
        for (int j = start; j <= end; j++) {
            int lineBytes = utf8Len(lines[j]);
            int addBytes = lineBytes + (j > groupStart ? 1 : 0); // +1 for the '\n' join
            if (usedBytes + addBytes > MAX_BLOCK_UTF8_BYTES) {
                if (groupStart == j) {
                    // One line alone exceeds the budget — split INSIDE
                    // the line at code-point boundaries; each chunk's
                    // locator is that single source line.
                    emitSingleLineChunks(blocks, type, lines[j], j);
                    groupStart = j + 1;
                    usedBytes = 0;
                    split = true;
                } else {
                    // Multi-line group boundary: the emitted group's
                    // locator is ITS OWN line range, not the whole
                    // content range (RUNTIME-FIX-02-E).
                    emitJoined(blocks, type, lines, groupStart, j - 1, groupStart, j - 1);
                    split = true;
                    if (lineBytes > MAX_BLOCK_UTF8_BYTES) {
                        // The new group's first line ALONE exceeds the
                        // budget (e.g. it is the last line) — it must
                        // be split inside the line, never emitted whole.
                        emitSingleLineChunks(blocks, type, lines[j], j);
                        groupStart = j + 1;
                        usedBytes = 0;
                    } else {
                        groupStart = j;
                        usedBytes = lineBytes;
                    }
                }
            } else {
                usedBytes += addBytes;
            }
        }
        if (groupStart <= end) {
            // Unsplitted single group keeps the caller's locator
            // (fence-to-fence for CODE); after ANY split every group
            // locates its own line range.
            emitJoined(blocks, type, lines, groupStart, end,
                    split ? groupStart : locStart, split ? end : locEnd);
        }
    }

    /** Emits one block joining {@code lines[start..end]} with {@code \n}. */
    private static void emitJoined(List<ParsedBlock> blocks, String type,
                                   String[] lines, int start, int end,
                                   int locStart, int locEnd) {
        if (start > end) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int j = start; j <= end; j++) {
            if (j > start) {
                sb.append('\n');
            }
            sb.append(lines[j]);
        }
        blocks.add(new ParsedBlock(type, sb.toString(), locStart + 1, locEnd + 1));
    }

    /**
     * Splits ONE source line ({@code lines[lineIndex]}, overlong by
     * itself) into consecutive blocks at Unicode code-point
     * boundaries. Chunks are emitted whole — no truncation, no lost
     * characters, surrogate pairs never split. Each chunk keeps
     * {@code lineStart == lineEnd == lineIndex + 1} so provenance
     * still points at the exact source line.
     */
    private static void emitSingleLineChunks(List<ParsedBlock> blocks, String type,
                                             String line, int lineIndex) {
        emitSingleLineText(blocks, type, line, lineIndex);
    }

    /**
     * Splits an arbitrary single-line TEXT (e.g. a heading with the
     * marker stripped) into bounded blocks at code-point boundaries.
     */
    private static void emitSingleLineText(List<ParsedBlock> blocks, String type,
                                           String text, int lineIndex) {
        int offset = 0;
        while (offset < text.length()) {
            int cpEnd = offset;
            int bytes = 0;
            while (cpEnd < text.length()) {
                int cp = text.codePointAt(cpEnd);
                int cpBytes = utf8LenOfCodePoint(cp);
                if (bytes + cpBytes > MAX_BLOCK_UTF8_BYTES) {
                    break;
                }
                bytes += cpBytes;
                cpEnd += Character.charCount(cp);
            }
            if (cpEnd == offset) {
                // Defensive: a single code point cannot exceed 4 UTF-8
                // bytes vs a 60,000 budget, so this never happens for
                // valid input; avoid an infinite loop regardless.
                cpEnd = offset + Character.charCount(text.codePointAt(offset));
            }
            blocks.add(new ParsedBlock(type, text.substring(offset, cpEnd),
                    lineIndex + 1, lineIndex + 1));
            offset = cpEnd;
        }
    }

    /** UTF-8 byte length of a string — surrogate-pair aware, no allocation. */
    private static int utf8Len(String s) {
        int len = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                len += 1;
            } else if (c < 0x800) {
                len += 2;
            } else if (Character.isHighSurrogate(c)
                    && i + 1 < s.length()
                    && Character.isLowSurrogate(s.charAt(i + 1))) {
                len += 4; // supplementary plane: 4 UTF-8 bytes
                i++;
            } else {
                len += 3;
            }
        }
        return len;
    }

    /** UTF-8 byte length of one code point (1..4). */
    private static int utf8LenOfCodePoint(int cp) {
        if (cp <= 0x7F) {
            return 1;
        }
        if (cp <= 0x7FF) {
            return 2;
        }
        if (cp <= 0xFFFF) {
            return 3;
        }
        return 4;
    }
}
