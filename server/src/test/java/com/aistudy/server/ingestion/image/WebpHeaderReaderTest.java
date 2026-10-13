package com.aistudy.server.ingestion.image;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-08 unit tests for the WebP container header reader: the three dimension
 * layouts must yield the true canvas size, and every truncated or
 * self-inconsistent container must fail loudly instead of reporting 0x0.
 *
 * <p>Fixtures are hand-assembled RIFF bytes (there is no WebP encoder in the
 * JDK), which is also what makes these tests DB- and dependency-free.
 */
class WebpHeaderReaderTest {

    private static byte[] chunk(String fourCc, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(fourCc.getBytes(StandardCharsets.US_ASCII), 0, 4);
        writeLe32(out, payload.length);
        out.write(payload, 0, payload.length);
        if (payload.length % 2 == 1) {
            out.write(0); // RIFF chunks are padded to an even size
        }
        return out.toByteArray();
    }

    private static void writeLe32(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    /** RIFF header whose declared length matches the actual payload. */
    private static byte[] container(byte[] firstChunk) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("RIFF".getBytes(StandardCharsets.US_ASCII), 0, 4);
        writeLe32(out, 4 + firstChunk.length); // payload size = file size - 8
        out.write("WEBP".getBytes(StandardCharsets.US_ASCII), 0, 4);
        out.write(firstChunk, 0, firstChunk.length);
        return out.toByteArray();
    }

    private static byte[] vp8x(int width, int height, int flags) {
        byte[] payload = new byte[10];
        payload[0] = (byte) flags;
        payload[4] = (byte) ((width - 1) & 0xFF);
        payload[5] = (byte) (((width - 1) >> 8) & 0xFF);
        payload[6] = (byte) (((width - 1) >> 16) & 0xFF);
        payload[7] = (byte) ((height - 1) & 0xFF);
        payload[8] = (byte) (((height - 1) >> 8) & 0xFF);
        payload[9] = (byte) (((height - 1) >> 16) & 0xFF);
        return chunk("VP8X", payload);
    }

    private static byte[] vp8l(int width, int height, boolean alpha) {
        long bits = ((long) (width - 1) & 0x3FFFL)
                | (((long) (height - 1) & 0x3FFFL) << 14)
                | (alpha ? (1L << 28) : 0L);
        byte[] payload = new byte[5];
        payload[0] = 0x2F;
        payload[1] = (byte) (bits & 0xFF);
        payload[2] = (byte) ((bits >> 8) & 0xFF);
        payload[3] = (byte) ((bits >> 16) & 0xFF);
        payload[4] = (byte) ((bits >> 24) & 0xFF);
        return chunk("VP8L", payload);
    }

    private static byte[] vp8Lossy(int width, int height, boolean keyframe, int hScale) {
        byte[] payload = new byte[10];
        // frame tag: bit 0 = inter-frame flag (0 means keyframe)
        payload[0] = (byte) (keyframe ? 0x00 : 0x01);
        payload[3] = (byte) 0x9D;
        payload[4] = (byte) 0x01;
        payload[5] = (byte) 0x2A;
        int widthField = (width & 0x3FFF) | ((hScale & 0x03) << 14);
        int heightField = (height & 0x3FFF);
        payload[6] = (byte) (widthField & 0xFF);
        payload[7] = (byte) ((widthField >> 8) & 0xFF);
        payload[8] = (byte) (heightField & 0xFF);
        payload[9] = (byte) ((heightField >> 8) & 0xFF);
        return chunk("VP8 ", payload);
    }

    @Test
    void extendedLayoutReportsCanvasDimensions() {
        WebpMetadata metadata = WebpHeaderReader.parse(vp8xContainer(1920, 1080, 0x10), 200);
        assertEquals(1920, metadata.width());
        assertEquals(1080, metadata.height());
        assertEquals("EXTENDED", metadata.variant());
        assertTrue(metadata.alpha());
        assertFalse(metadata.animated());
        assertEquals(1920L * 1080L, metadata.pixels());
    }

    private static byte[] vp8xContainer(int width, int height, int flags) {
        return container(vp8x(width, height, flags));
    }

    @Test
    void animatedExtendedLayoutReportsSharedCanvas() {
        byte[] bytes = container(vp8x(640, 480, 0x02)); // bit 1 = animation
        WebpMetadata metadata = WebpHeaderReader.parse(bytes, bytes.length);
        assertEquals(640, metadata.width());
        assertEquals(480, metadata.height());
        assertTrue(metadata.animated());
    }

    @Test
    void losslessLayoutDecodesMinusOneDimensions() {
        byte[] bytes = container(vp8l(1, 1, false));
        WebpMetadata metadata = WebpHeaderReader.parse(bytes, bytes.length);
        assertEquals(1, metadata.width());
        assertEquals(1, metadata.height());
        assertEquals("LOSSLESS", metadata.variant());

        byte[] big = container(vp8l(16384, 1200, true));
        WebpMetadata bigMeta = WebpHeaderReader.parse(big, big.length);
        assertEquals(16384, bigMeta.width());
        assertEquals(1200, bigMeta.height());
        assertTrue(bigMeta.alpha());
    }

    @Test
    void lossyLayoutReadsKeyframeHeader() {
        byte[] bytes = container(vp8lossy(800, 600));
        WebpMetadata metadata = WebpHeaderReader.parse(bytes, bytes.length);
        assertEquals(800, metadata.width());
        assertEquals(600, metadata.height());
        assertEquals("LOSSY", metadata.variant());
    }

    private static byte[] vp8lossy(int width, int height) {
        return vp8Lossy(width, height, true, 0);
    }

    @Test
    void lossyScaleFactorIsAppliedToDimensions() {
        // width field 512 with scale 1 means the real width is 256.
        byte[] bytes = container(vp8Lossy(512, 200, true, 1));
        WebpMetadata metadata = WebpHeaderReader.parse(bytes, bytes.length);
        assertEquals(256, metadata.width());
        assertEquals(200, metadata.height());
    }

    @Test
    void truncatedContainerFailsInsteadOfPseudoSucceeding() {
        byte[] full = vp8xContainer(1920, 1080, 0x00);
        // Keep the header, drop the payload: 12-byte prefix only.
        byte[] truncated = new byte[12];
        System.arraycopy(full, 0, truncated, 0, 12);
        WebpMetadataException error = assertThrows(WebpMetadataException.class,
                () -> WebpHeaderReader.parse(truncated, truncated.length));
        assertTrue(error.getMessage().contains("truncated"), error.getMessage());
    }

    @Test
    void declaredSizeLargerThanFileIsRejected() {
        byte[] bytes = container(vp8Lossy(800, 600, true, 0));
        // 20 bytes of a 34-byte container: past the "too short to be WebP"
        // check, but the header claims more payload than the file holds.
        WebpMetadataException error = assertThrows(WebpMetadataException.class,
                () -> WebpHeaderReader.parse(bytes, 20));
        assertTrue(error.getMessage().contains("bytes on disk"), error.getMessage());
    }

    @Test
    void chunkPayloadOverrunIsRejected() {
        byte[] bytes = container(vp8x(10, 10, 0x00));
        // Rewrite the RIFF length to claim a 28-byte container: the 10-byte
        // VP8X payload then starts past its end, so the chunk overruns the
        // container. That is a corrupt file, not a small image.
        bytes[4] = 20;
        bytes[5] = 0;
        bytes[6] = 0;
        bytes[7] = 0;
        WebpMetadataException error = assertThrows(WebpMetadataException.class,
                () -> WebpHeaderReader.parse(bytes, bytes.length));
        assertTrue(error.getMessage().contains("overruns"), error.getMessage());
    }

    @Test
    void nonDimensionFirstChunkIsRejected() {
        byte[] bytes = container(chunk("ICCP", new byte[]{1, 2, 3, 4}));
        WebpMetadataException error = assertThrows(WebpMetadataException.class,
                () -> WebpHeaderReader.parse(bytes, bytes.length));
        assertTrue(error.getMessage().contains("dimension header"), error.getMessage());
    }

    @Test
    void missingMagicIsRejected() {
        // RIFF + a length + something that is not WEBP.
        WebpMetadataException error = assertThrows(WebpMetadataException.class,
                () -> WebpHeaderReader.parse("RIFF    PNGP".getBytes(StandardCharsets.US_ASCII), 12));
        assertTrue(error.getMessage().contains("RIFF/WEBP"), error.getMessage());
    }

    @Test
    void losslessSignatureByteMustBePresent() {
        byte[] bytes = container(vp8l(10, 10, false));
        bytes[20] = 0x2E; // one bit off the 0x2F signature
        WebpMetadataException error = assertThrows(WebpMetadataException.class,
                () -> WebpHeaderReader.parse(bytes, bytes.length));
        assertTrue(error.getMessage().contains("signature"), error.getMessage());
    }

    @Test
    void interFrameLossyFirstChunkIsRejected() {
        byte[] bytes = container(vp8Lossy(64, 64, false, 0));
        WebpMetadataException error = assertThrows(WebpMetadataException.class,
                () -> WebpHeaderReader.parse(bytes, bytes.length));
        assertTrue(error.getMessage().contains("keyframe"), error.getMessage());
    }

    @Test
    void lossyStartCodeMustMatch() {
        byte[] bytes = container(vp8Lossy(64, 64, true, 0));
        bytes[23] = (byte) 0x9E; // corrupt the 0x9D start code
        WebpMetadataException error = assertThrows(WebpMetadataException.class,
                () -> WebpHeaderReader.parse(bytes, bytes.length));
        assertTrue(error.getMessage().contains("start code"), error.getMessage());
    }

    @Test
    void lossyZeroWidthIsRejectedNotStoredAsZero() {
        byte[] bytes = container(vp8Lossy(0, 100, true, 0));
        WebpMetadataException error = assertThrows(WebpMetadataException.class,
                () -> WebpHeaderReader.parse(bytes, bytes.length));
        assertTrue(error.getMessage().contains("non-positive"), error.getMessage());
    }

    @Test
    void readFromFileUsesTheActualFileLength() throws Exception {
        byte[] bytes = vp8xContainer(2000, 1500, 0x00);
        Path file = Files.createTempFile("aistudy-webp-header-", ".webp");
        try {
            Files.write(file, bytes);
            WebpMetadata metadata = WebpHeaderReader.read(file);
            assertEquals(2000, metadata.width());
            assertEquals(1500, metadata.height());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void prefixReadIsBoundedRegardlessOfFileLength() throws Exception {
        byte[] bytes = vp8xContainer(2000, 1500, 0x00);
        ByteArrayOutputStream padded = new ByteArrayOutputStream();
        padded.write(bytes, 0, bytes.length);
        padded.write(new byte[4096], 0, 4096); // trailing padding
        Path file = Files.createTempFile("aistudy-webp-large-", ".webp");
        try {
            Files.write(file, padded.toByteArray());
            WebpMetadata metadata = WebpHeaderReader.read(file);
            assertEquals(2000, metadata.width());
            // The declared container is 30 bytes; the reader must not have
            // needed the extra 4 KB at all.
            assertTrue(file.toFile().length() > WebpHeaderReader.PREFIX_BYTES);
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
