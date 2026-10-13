package com.aistudy.server.ingestion.image;

/**
 * Dimensions and variant flags read from a WebP container header.
 *
 * @param width   canvas width in pixels, always {@code >= 1}
 * @param height  canvas height in pixels, always {@code >= 1}
 * @param variant {@code LOSSY} (VP8), {@code LOSSLESS} (VP8L) or
 *                {@code EXTENDED} (VP8X: alpha / animation / profile chunks)
 * @param animated true when the VP8X flags advertise animation; the size
 *                 reported is the canvas shared by all frames
 * @param alpha   true when an alpha channel is advertised
 */
public record WebpMetadata(int width, int height, String variant, boolean animated, boolean alpha) {

    public WebpMetadata {
        if (width < 1 || height < 1) {
            // A container that declares 0x0 is corrupt, not "small": throwing
            // the reader's own exception type keeps the caller's single
            // INVALID_IMAGE mapping intact.
            throw new WebpMetadataException("WebP declares non-positive dimensions "
                    + width + "x" + height);
        }
    }

    /** @return pixel count as a {@code long} so a huge canvas cannot overflow */
    public long pixels() {
        return (long) width * (long) height;
    }
}
