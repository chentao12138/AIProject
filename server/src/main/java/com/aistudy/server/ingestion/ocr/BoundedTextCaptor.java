package com.aistudy.server.ingestion.ocr;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Byte-capped, thread-safe capture of a subprocess stream (F-09).
 *
 * <p>The engine used to append every line of PaddleOCR stdout into an
 * unbounded {@code StringBuilder} and then log the whole thing. A chatty or
 * hostile OCR build could therefore exhaust the worker heap through a single
 * ingestion job, and the resulting log line was useless for triage.
 *
 * <p>Reading stops accumulating once {@code maxBytes} is reached but the
 * stream keeps being drained: a full pipe would block the child process,
 * turning a size problem into a hang. {@link #truncated()} records that the
 * ceiling was hit so the caller can fail with a diagnosable code instead of
 * parsing half an answer.
 *
 * <p>Decoding happens once at the end, so a multi-byte UTF-8 sequence split
 * across reads can never produce a mojibake diagnostic.
 */
final class BoundedTextCaptor {

    /** Read chunk size; small enough that the cap is honoured closely. */
    private static final int CHUNK = 8192;

    private final int maxBytes;
    private final ByteArrayOutputStream buffer;
    private volatile boolean truncated;
    private volatile boolean ioError;

    BoundedTextCaptor(int maxBytes) {
        this.maxBytes = Math.max(0, maxBytes);
        // Initial capacity is only a hint: the ceiling is enforced in drain().
        this.buffer = new ByteArrayOutputStream(Math.min(this.maxBytes, 16384));
    }

    /**
     * Drains {@code in} completely, keeping at most {@code maxBytes}.
     *
     * @param in stream to drain; never null, not closed here by the caller's
     *           contract the stream is closed by the caller after this returns
     * @throws IOException when the stream itself fails; the caller maps that to
     *                     {@link OcrOutcome#PROCESS_FAILED}
     */
    void drain(InputStream in) throws IOException {
        byte[] chunk = new byte[CHUNK];
        int n;
        while ((n = in.read(chunk)) != -1) {
            int room = maxBytes - buffer.size();
            if (room > 0) {
                buffer.write(chunk, 0, Math.min(n, room));
            }
            if (n > 0 && buffer.size() >= maxBytes) {
                truncated = true;
            }
        }
    }

    /** Marks a stream failure without losing whatever was already captured. */
    void markIoError() {
        this.ioError = true;
    }

    boolean ioError() {
        return ioError;
    }

    /** @return true when more bytes were available than the configured cap */
    boolean truncated() {
        return truncated;
    }

    int capturedBytes() {
        return buffer.size();
    }

    /** @return the captured bytes decoded as UTF-8 with replacement chars */
    String text() {
        return new String(buffer.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
