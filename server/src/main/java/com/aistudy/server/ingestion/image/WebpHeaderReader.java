package com.aistudy.server.ingestion.image;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads the real canvas size out of a WebP container header (F-08).
 *
 * <p>The JDK ships no WebP {@code ImageIO} reader, so the previous build
 * treated "magic bytes say WEBP" as "parsed", stored {@code width=0 /
 * height=0} and carried on into OCR. That skipped every
 * {@code maxWidth / maxHeight / maxPixels} guard (a {@code 0} never exceeds a
 * limit) and published metadata that was false. This reader replaces that
 * shortcut with the container parse defined by the WebP lossless/lossy/extended
 * chunk layouts, so the size limits apply to WebP exactly as they do to PNG
 * and JPEG, and a truncated or self-inconsistent RIFF fails the asset.
 *
 * <h3>Which header is read (documented on purpose)</h3>
 *
 * <p>This is a header parse, not a pixel decode: it reads at most
 * {@value #PREFIX_BYTES} bytes and never allocates anything proportional to the
 * file. The dimensions it returns are the authoritative canvas dimensions,
 * because the WebP container stores them unambiguously in the first chunk:
 * <ul>
 *   <li>{@code VP8X} (extended: alpha, animation, colour profiles) — 10-byte
 *       payload, canvas width/height as 24-bit little-endian "minus one"
 *       fields at payload offsets 4 and 7. For an animated WebP this is the
 *       shared canvas of every frame, which is the size the size-limits care
 *       about.</li>
 *   <li>{@code VP8L} (lossless) — {@code 0x2F} signature byte then a 32-bit
 *       bit pack: width-1 in bits 0..13, height-1 in bits 14..27, alpha in
 *       bit 28.</li>
 *   <li>{@code VP8 } (lossy keyframe) — 3-byte frame tag (bit 0 = keyframe
 *       flag), the {@code 0x9D 0x01 0x2A} start code, then 14-bit width and
 *       14-bit height, each with a 2-bit scale factor that is applied
 *       (dimensions are shifted right by the scale).</li>
 * </ul>
 *
 * <p>Per the RIFF/WebP layout the first chunk of a WebP file MUST be one of
 * those three, so a file that starts with anything else is rejected rather
 * than scanned; the JDK-style "keep looking for a chunk that happens to carry
 * a size" heuristic would accept containers that no decoder can render.
 *
 * <h3>Failure modes</h3>
 *
 * <p>Everything here throws {@link WebpMetadataException}, which the extraction
 * service maps to the same {@code INVALID_IMAGE} job error a bad PNG throws:
 * missing/short RIFF or WEBP tag, a declared container size larger than the
 * file (truncated), a chunk payload that overruns the container, a chunk
 * header that does not fit in the prefix, a non-keyframe or wrong-start-code
 * lossy frame header, a missing {@code 0x2F} lossless signature, a
 * non-positive dimension, or a first chunk that is not a dimension chunk.
 * An I/O problem reading the prefix throws {@link IOException} so the caller
 * can keep its own {@code IMAGE_PARSE_FAILED} semantics.
 */
public final class WebpHeaderReader {

    /**
     * Prefix ceiling read from disk. Comfortably more than the largest
     * dimension header (RIFF 12 + chunk header 8 + VP8X payload 10 = 30 bytes)
     * while staying far away from "load the whole upload".
     */
    public static final int PREFIX_BYTES = 64;

    private static final int MIN_CONTAINER_BYTES = 20;

    private WebpHeaderReader() {
    }

    /**
     * Reads the first {@link #PREFIX_BYTES} bytes of {@code file} and parses
     * them together with the real file length.
     *
     * @param file local WebP candidate written by the extraction stage
     * @return the parsed canvas metadata, never null
     * @throws IOException when the file cannot be read
     * @throws WebpMetadataException when the container cannot be trusted
     */
    public static WebpMetadata read(Path file) throws IOException {
        long length = Files.size(file);
        byte[] prefix = new byte[(int) Math.min(length, PREFIX_BYTES)];
        try (InputStream in = Files.newInputStream(file)) {
            int read = 0;
            while (read < prefix.length) {
                int n = in.read(prefix, read, prefix.length - read);
                if (n <= 0) {
                    break;
                }
                read += n;
            }
            if (read < prefix.length) {
                prefix = java.util.Arrays.copyOf(prefix, read);
            }
        }
        return parse(prefix, length);
    }

    /**
     * Pure parse entry point (unit-testable without a filesystem).
     *
     * @param prefix      the first up-to-{@value #PREFIX_BYTES} bytes of the file
     * @param fileLength  actual byte length of the file on disk; used to detect
     *                    a RIFF container that claims more data than exists
     * @return parsed canvas metadata
     * @throws WebpMetadataException for any malformed / truncated container
     */
    public static WebpMetadata parse(byte[] prefix, long fileLength) {
        if (prefix == null || prefix.length < 12 || fileLength < 12) {
            throw new WebpMetadataException("file is too short to be a WebP container");
        }
        if (!fourCc(prefix, 0, "RIFF") || !fourCc(prefix, 8, "WEBP")) {
            throw new WebpMetadataException("RIFF/WEBP tag is missing");
        }
        if (fileLength < MIN_CONTAINER_BYTES) {
            throw new WebpMetadataException("WebP container is truncated");
        }
        long declaredPayload = Integer.toUnsignedLong(le32(prefix, 4));
        // Some encoders emit 0 (or all-ones) for a streamed/unknown container
        // length; those are the only cases the declared-size check is skipped.
        boolean sizeDeclared = declaredPayload != 0L && declaredPayload != 0xFFFFFFFFL;
        long containerLength = sizeDeclared ? declaredPayload + 8L : fileLength;
        if (sizeDeclared) {
            if (containerLength < MIN_CONTAINER_BYTES) {
                throw new WebpMetadataException("WebP container declares a negative or tiny size");
            }
            if (fileLength < containerLength) {
                throw new WebpMetadataException("WebP container is truncated: "
                        + fileLength + " bytes on disk, header declares " + containerLength);
            }
        }

        int offset = 12;
        if (offset + 8 > prefix.length) {
            throw new WebpMetadataException("WebP has no readable first chunk header");
        }
        String fourCc = chunkId(prefix, offset);
        long chunkSize = Integer.toUnsignedLong(le32(prefix, offset + 4));
        int dataStart = offset + 8;
        // Declared-chunk-vs-container bound only: the payload is normally far
        // larger than the parsed prefix, so a chunk that "does not fit in the
        // prefix" is fine as long as the dimension bytes it needs are there.
        if (dataStart + chunkSize > containerLength) {
            throw new WebpMetadataException("WebP first chunk of " + chunkSize
                    + " bytes overruns the " + containerLength + "-byte container");
        }
        switch (fourCc) {
            case "VP8X":
                return parseExtended(prefix, dataStart, chunkSize);
            case "VP8L":
                return parseLossless(prefix, dataStart, chunkSize);
            case "VP8 ":
                return parseLossy(prefix, dataStart, chunkSize);
            default:
                throw new WebpMetadataException(
                        "WebP has no dimension header (VP8/VP8L/VP8X) as its first chunk");
        }
    }

    /**
     * @param prefix    file prefix
     * @param dataStart payload offset
     * @param chunkSize declared payload size
     * @return canvas metadata for an extended (VP8X) container
     */
    private static WebpMetadata parseExtended(byte[] prefix, int dataStart, long chunkSize) {
        if (chunkSize < 10) {
            throw new WebpMetadataException("VP8X chunk is shorter than its 10-byte header");
        }
        if (dataStart + 10 > prefix.length) {
            throw new WebpMetadataException("VP8X canvas header is truncated");
        }
        int flags = prefix[dataStart] & 0xFF;
        int width = le24(prefix, dataStart + 4) + 1;
        int height = le24(prefix, dataStart + 7) + 1;
        return new WebpMetadata(width, height, "EXTENDED",
                (flags & 0x02) != 0, (flags & 0x10) != 0);
    }

    /**
     * @param prefix    file prefix
     * @param dataStart payload offset
     * @param chunkSize declared payload size
     * @return metadata for a lossless (VP8L) image
     */
    private static WebpMetadata parseLossless(byte[] prefix, int dataStart, long chunkSize) {
        if (chunkSize < 5 || dataStart + 5 > prefix.length) {
            throw new WebpMetadataException("VP8L chunk is shorter than its signature and size pack");
        }
        if ((prefix[dataStart] & 0xFF) != 0x2F) {
            throw new WebpMetadataException("VP8L chunk is missing the 0x2F signature byte");
        }
        long bits = Integer.toUnsignedLong(le32(prefix, dataStart + 1));
        int width = (int) (bits & 0x3FFFL) + 1;
        int height = (int) ((bits >>> 14) & 0x3FFFL) + 1;
        boolean alpha = ((bits >>> 28) & 0x1L) == 1L;
        return new WebpMetadata(width, height, "LOSSLESS", false, alpha);
    }

    /**
     * @param prefix    file prefix
     * @param dataStart payload offset
     * @param chunkSize declared payload size
     * @return metadata for the first frame of a lossy (VP8) bitstream
     */
    private static WebpMetadata parseLossy(byte[] prefix, int dataStart, long chunkSize) {
        if (chunkSize < 10 || dataStart + 10 > prefix.length) {
            throw new WebpMetadataException("VP8 chunk is shorter than its frame header");
        }
        long frameTag = (prefix[dataStart] & 0xFFL)
                | ((prefix[dataStart + 1] & 0xFFL) << 8)
                | ((prefix[dataStart + 2] & 0xFFL) << 16);
        // Bit 0 of the frame tag is the inter-frame flag: 0 means keyframe,
        // and only a keyframe carries the canvas size.
        if ((frameTag & 0x1L) != 0L) {
            throw new WebpMetadataException("VP8 chunk does not start with a keyframe");
        }
        if ((prefix[dataStart + 3] & 0xFF) != 0x9D
                || (prefix[dataStart + 4] & 0xFF) != 0x01
                || (prefix[dataStart + 5] & 0xFF) != 0x2A) {
            throw new WebpMetadataException("VP8 frame header is missing the 0x9D012A start code");
        }
        int widthRaw = (prefix[dataStart + 6] & 0xFF) | ((prefix[dataStart + 7] & 0xFF) << 8);
        int heightRaw = (prefix[dataStart + 8] & 0xFF) | ((prefix[dataStart + 9] & 0xFF) << 8);
        int width = (widthRaw & 0x3FFF) >> ((widthRaw >>> 14) & 0x03);
        int height = (heightRaw & 0x3FFF) >> ((heightRaw >>> 14) & 0x03);
        return new WebpMetadata(width, height, "LOSSY", false, false);
    }

    private static boolean fourCc(byte[] data, int offset, String expected) {
        for (int i = 0; i < 4; i++) {
            if (data[offset + i] != (byte) expected.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static String chunkId(byte[] data, int offset) {
        return new String(data, offset, 4, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static int le32(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private static int le24(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8) | ((data[offset + 2] & 0xFF) << 16);
    }
}
