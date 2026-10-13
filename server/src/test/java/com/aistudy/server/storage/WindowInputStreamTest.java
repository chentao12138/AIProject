package com.aistudy.server.storage;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-15 — DB-free unit tests for {@link WindowInputStream}: a partial
 * fetch must never expose bytes outside the window, must position by
 * skipping (not by loading the whole file), and must close the
 * delegate.
 */
class WindowInputStreamTest {

    private static ByteArrayInputStream whole() {
        return new ByteArrayInputStream("0123456789".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void readsExactlyTheWindowBytes() throws IOException {
        try (InputStream window = new WindowInputStream(whole(), 2, 3)) {
            assertArrayEquals("234".getBytes(StandardCharsets.UTF_8), window.readAllBytes());
        }
    }

    @Test
    void eofAtWindowEndEvenWhenDelegateHasMore() throws IOException {
        try (WindowInputStream window = new WindowInputStream(whole(), 0, 4)) {
            assertArrayEquals("0123".getBytes(StandardCharsets.UTF_8), window.readAllBytes());
            assertEquals(-1, window.read());
            assertEquals(0, window.available());
        }
    }

    @Test
    void singleByteReadHonoursWindowCap() throws IOException {
        try (WindowInputStream window = new WindowInputStream(whole(), 5, 2)) {
            assertEquals('5', window.read());
            assertEquals('6', window.read());
            assertEquals(-1, window.read());
        }
    }

    @Test
    void bulkReadIsCappedToRemainingWindow() throws IOException {
        try (WindowInputStream window = new WindowInputStream(whole(), 0, 3)) {
            byte[] buf = new byte[100];
            int n = window.read(buf, 0, buf.length);
            assertEquals(3, n);
            assertArrayEquals("012".getBytes(StandardCharsets.UTF_8),
                    new String(buf, 0, n, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void skipInsideWindowSkipsRealBytes() throws IOException {
        try (WindowInputStream window = new WindowInputStream(whole(), 0, 10)) {
            assertEquals(4, window.skip(4));
            assertEquals('4', window.read());
        }
    }

    @Test
    void skipCannotEscapeTheWindow() throws IOException {
        try (WindowInputStream window = new WindowInputStream(whole(), 0, 3)) {
            window.skip(3);
            assertEquals(-1, window.read());
        }
    }

    @Test
    void closingClosesTheDelegate() throws IOException {
        AtomicBoolean closed = new AtomicBoolean(false);
        ByteArrayInputStream delegate = whole();
        InputStream tracking = new java.io.FilterInputStream(delegate) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };
        try (InputStream window = new WindowInputStream(tracking, 1, 2)) {
            window.readAllBytes();
        }
        assertTrue(closed.get(), "delegate must be closed with the window");
    }

    @Test
    void delegateShorterThanSkipTargetFailsFast() {
        WindowInputStream window = new WindowInputStream(whole(), 50, 3);
        assertThrows(IOException.class, window::readAllBytes,
                "positioning past EOF must surface as IOException, not silent empty body");
    }

    @Test
    void negativeWindowBoundsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new WindowInputStream(whole(), -1, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new WindowInputStream(whole(), 0, -3));
    }
}
