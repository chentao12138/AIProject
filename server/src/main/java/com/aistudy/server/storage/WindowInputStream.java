package com.aistudy.server.storage;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * F-15 — a bounded read window over an underlying storage stream.
 *
 * <p>{@link StorageService#load} always opens at byte 0; a Range
 * request must nevertheless never copy the whole object into memory.
 * This stream positions once to {@code skipFirst} bytes (using the
 * delegate's {@code skip}, falling back to read-and-discard for
 * streams that decline to skip) and then reads at most
 * {@code maxBytes} — EOF is reported at the end of the window even
 * if the delegate has more bytes.
 *
 * <h3>Contract</h3>
 *
 * <ul>
 *   <li>The window is fixed at construction; there is no way to read
 *       past {@code maxBytes}.</li>
 *   <li>Positioning is lazy (first read) so constructing the stream
 *       never blocks on IO.</li>
 *   <li>{@link #close()} closes the delegate as well — the response
 *       writer owns the lifecycle and every controller path must
 *       either attach the stream to the response or close it.</li>
 *   <li>A delegate that ends before {@code skipFirst} is a corrupted
 *       storage root (DB size and file are written together), surfaced
 *       as {@link IOException} on the first read.</li>
 * </ul>
 *
 * <p>Not thread-safe by design: one instance per HTTP response.
 */
public class WindowInputStream extends FilterInputStream {

    private final long skipFirst;
    private long remaining;
    private boolean positioned;

    /**
     * @param delegate  freshly opened storage stream positioned at byte 0
     * @param skipFirst bytes to skip before the window starts (>= 0)
     * @param maxBytes  bytes readable inside the window (>= 0)
     */
    public WindowInputStream(InputStream delegate, long skipFirst, long maxBytes) {
        super(delegate);
        if (skipFirst < 0 || maxBytes < 0) {
            throw new IllegalArgumentException("window bounds must be non-negative");
        }
        this.skipFirst = skipFirst;
        this.remaining = maxBytes;
    }

    /** Positions once to the window start; invoked lazily on first read. */
    private void position() throws IOException {
        if (positioned) {
            return;
        }
        positioned = true;
        long left = skipFirst;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped <= 0) {
                // Stream declines to skip (or short-skips): discard one.
                if (in.read() == -1) {
                    throw new IOException(
                            "storage object ends before the requested window start");
                }
                left -= 1;
            } else {
                left -= skipped;
            }
        }
    }

    @Override
    public int read() throws IOException {
        position();
        if (remaining <= 0) {
            return -1;
        }
        int b = super.read();
        if (b >= 0) {
            remaining--;
        }
        return b;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        if (buf == null) {
            throw new NullPointerException("buf");
        }
        position();
        if (remaining <= 0) {
            return -1;
        }
        if (len <= 0) {
            return 0;
        }
        int capped = (int) Math.min(len, remaining);
        int n = super.read(buf, off, capped);
        if (n > 0) {
            remaining -= n;
        }
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        position();
        if (remaining <= 0 || n <= 0) {
            return 0;
        }
        long actual = super.skip(Math.min(n, remaining));
        if (actual < 0) {
            return 0;
        }
        remaining -= actual;
        return actual;
    }

    @Override
    public int available() throws IOException {
        if (remaining <= 0) {
            return 0;
        }
        return (int) Math.min(remaining, Math.max(super.available(), 0));
    }
}
