package com.aistudy.server.ingestion.image;

import com.aistudy.server.ingestion.extract.ContentExtractionService;
import com.aistudy.server.ingestion.job.service.IngestionErrorCode;
import com.aistudy.server.ingestion.ocr.OcrEngine;
import com.aistudy.server.ingestion.ocr.OcrException;
import com.aistudy.server.ingestion.ocr.OcrFailurePolicy;
import com.aistudy.server.ingestion.ocr.OcrOutcome;
import com.aistudy.server.source.asset.entity.SourceAsset;
import com.aistudy.server.source.content.entity.ContentBlock;
import com.aistudy.server.source.content.mapper.ContentBlockMapper;
import com.aistudy.server.source.page.entity.SourcePage;
import com.aistudy.server.source.page.mapper.SourcePageMapper;
import com.aistudy.server.source.page.service.SourcePageService;
import com.aistudy.server.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;

/**
 * Image ingestion: PNG/JPEG/WebP metadata validation + optional OCR via
 * {@link OcrEngine}.
 *
 * <h3>What a finished asset means here (F-08 / F-28)</h3>
 *
 * <p>Two invariants drive the structure of this service:
 * <ol>
 *   <li><b>Metadata is never invented.</b> Every supported format must yield a
 *       real width/height before anything is persisted, because
 *       {@code maxWidth / maxHeight / maxPixels} are safety limits — a
 *       {@code 0} placeholder silently disables them. WebP has no JDK
 *       {@code ImageIO} reader, so it goes through {@link WebpHeaderReader}
 *       (VP8 / VP8L / VP8X canvas header). A malformed or truncated container
 *       fails with the same {@code INVALID_IMAGE} error a bad PNG throws.</li>
 *   <li><b>OCR problems are reported, not swallowed.</b> The OCR stage returns
 *       an {@link OcrOutcome}; "engine unavailable", "engine timed out",
 *       "output too large" and "no text in this image" are different answers.
 *       Under the default {@code on-failure=review} policy the page is still
 *       persisted and the source is flagged for a human (the pipeline's
 *       existing {@code OCR_LOW_CONFIDENCE} issue producer), which is the
 *       behaviour the committed integration tests describe. Under
 *       {@code on-failure=fail} the same condition throws a typed
 *       {@link ImageExtractionException} so the job lands in
 *       {@code FAILED}/{@code PARTIAL_FAILED} with a diagnosable code.</li>
 * </ol>
 *
 * <p>Page order comes from {@link SourcePageService#allocateOrderSlots}: a
 * multi-asset Source gets continuation orders (1,2,3 …) instead of every image
 * restarting at 1, and slots freed by a re-extraction are reused so a
 * human-confirmed ordering is not pushed around.
 */
@Service
public class ImageContentExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ImageContentExtractionService.class);
    private static final String PAGE_TYPE_IMAGE = "IMAGE";
    /** Ceiling for OCR text stored on one page; a larger payload is truncated. */
    private static final int MAX_OCR_TEXT_CHARS = 2_000_000;

    private final StorageService storageService;
    private final SourcePageMapper sourcePageMapper;
    private final ContentBlockMapper contentBlockMapper;
    private final SourcePageService sourcePageService;
    private final OcrEngine ocrEngine;
    private final OcrFailurePolicy ocrFailurePolicy;
    private final long maxBytes;
    private final int maxWidth;
    private final int maxHeight;
    private final long maxPixels;
    private final double confidenceThreshold;

    public ImageContentExtractionService(StorageService storageService,
                                         SourcePageMapper sourcePageMapper,
                                         ContentBlockMapper contentBlockMapper,
                                         SourcePageService sourcePageService,
                                         OcrEngine ocrEngine,
                                         OcrFailurePolicy ocrFailurePolicy,
                                         @Value("${aistudy.ingestion.image.max-bytes:50MB}") DataSize maxBytes,
                                         @Value("${aistudy.ingestion.image.max-width:10000}") int maxWidth,
                                         @Value("${aistudy.ingestion.image.max-height:10000}") int maxHeight,
                                         @Value("${aistudy.ingestion.image.max-pixels:100000000}") long maxPixels,
                                         @Value("${aistudy.ocr.confidence-threshold:0.7}") double confidenceThreshold) {
        this.storageService = storageService;
        this.sourcePageMapper = sourcePageMapper;
        this.contentBlockMapper = contentBlockMapper;
        this.sourcePageService = sourcePageService;
        this.ocrEngine = ocrEngine;
        this.ocrFailurePolicy = ocrFailurePolicy == null ? OcrFailurePolicy.REVIEW : ocrFailurePolicy;
        this.maxBytes = maxBytes.toBytes();
        this.maxWidth = Math.max(1, maxWidth);
        this.maxHeight = Math.max(1, maxHeight);
        this.maxPixels = Math.max(1, maxPixels);
        this.confidenceThreshold = confidenceThreshold >= 0 && confidenceThreshold <= 1
                ? confidenceThreshold : 0.7;
    }

    /**
     * Back-compatible pipeline entry point.
     *
     * @param asset image asset to extract and persist
     * @return true when the source must go to human review (low or unknown OCR
     *         confidence, no text found, or an OCR failure tolerated by the
     *         {@code review} policy)
     * @throws ImageExtractionException for a rejected asset, or for an OCR
     *         failure under {@code on-failure=fail}
     */
    public boolean extractAndPersist(SourceAsset asset) {
        return extractAndPersistDetailed(asset).needsReview();
    }

    /**
     * Extracts one image asset and reports the explicit outcome.
     *
     * <p>Persisted state on success: exactly one {@code IMAGE} SourcePage whose
     * {@code pageOrder} is allocated across the whole Source, plus one
     * ContentBlock when OCR produced text. On a thrown failure nothing is
     * written — the previous rows for this asset are only deleted after the
     * metadata and OCR gates have passed, so a rejected upload cannot wipe a
     * good earlier extraction.
     *
     * @param asset image asset (PNG/JPEG/WebP)
     * @return the bounded result, never null
     * @throws ImageExtractionException with {@code INVALID_IMAGE},
     *         {@code IMAGE_TOO_LARGE}, {@code IMAGE_DIMENSION_LIMIT_EXCEEDED},
     *         {@code IMAGE_PIXEL_LIMIT_EXCEEDED}, {@code IMAGE_PARSE_FAILED}
     *         or, under {@code on-failure=fail}, an OCR failure whose stable
     *         code prefixes the safe message (e.g. {@code "OCR_TIMEOUT: …"})
     */
    public ImageExtractionResult extractAndPersistDetailed(SourceAsset asset) {
        String mimeType = asset.getMimeType();
        if (!isSupportedMime(mimeType)) {
            throw new ImageExtractionException(IngestionErrorCode.INVALID_IMAGE,
                    "unsupported image MIME type: " + safeMime(mimeType));
        }
        if (asset.getSizeBytes() != null && asset.getSizeBytes() > maxBytes) {
            throw new ImageExtractionException(IngestionErrorCode.IMAGE_TOO_LARGE,
                    "image exceeds the configured byte limit of " + maxBytes);
        }

        File temp = new File(System.getProperty("java.io.tmpdir"),
                "aistudy-image-" + System.nanoTime() + ".bin");
        temp.deleteOnExit();
        int width;
        int height;
        String format;
        String ocrText = null;
        Double ocrConfidence = null;
        String ocrEngineName = null;
        OcrOutcome ocrOutcome;

        try {
            byte[] header = new byte[12];
            try (InputStream in = storageService.load(asset.getStorageKey());
                 OutputStream out = new FileOutputStream(temp)) {
                int headerRead = in.readNBytes(header, 0, header.length);
                if (headerRead < header.length) {
                    header = java.util.Arrays.copyOf(header, headerRead);
                }
                boolean webp = looksLikeWebp(header);
                if (headerRead < 4 || !(matchesImageMagic(mimeType, header, headerRead) || webp)) {
                    throw new ImageExtractionException(IngestionErrorCode.INVALID_IMAGE,
                            "uploaded asset is not a valid image");
                }
                out.write(header, 0, headerRead);
                // The byte ceiling is enforced while streaming, so a claimed
                // size that lies about the payload still stops the run.
                long totalBytes = headerRead;
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    totalBytes += n;
                    if (totalBytes > maxBytes) {
                        throw new ImageExtractionException(IngestionErrorCode.IMAGE_TOO_LARGE,
                                "image exceeds the configured byte limit of " + maxBytes);
                    }
                    out.write(buf, 0, n);
                }
                format = webp ? "WEBP" : normalizedMime(mimeType);
            } catch (ImageExtractionException e) {
                throw e;
            } catch (Exception e) {
                throw new ImageExtractionException(IngestionErrorCode.IMAGE_PARSE_FAILED,
                        "failed to read image asset");
            }

            int[] size = readDimensions(temp, format, header);
            width = size[0];
            height = size[1];
            enforceDimensionLimits(width, height);

            OcrStage ocr = runOcr(asset, temp);
            ocrText = ocr.text();
            ocrConfidence = ocr.confidence();
            ocrEngineName = ocr.engineName();
            ocrOutcome = ocr.outcome();
        } finally {
            if (!temp.delete()) {
                temp.deleteOnExit();
            }
        }

        boolean needsReview = ocrOutcome.needsReview();

        contentBlockMapper.deleteBySpaceSourceAsset(asset.getSpaceId(), asset.getSourceId(), asset.getId());
        sourcePageMapper.deleteBySpaceSourceAsset(asset.getSpaceId(), asset.getSourceId(), asset.getId());

        int pageOrder = sourcePageService.allocateOrderSlots(asset.getSpaceId(), asset.getSourceId(), 1)[0];
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        SourcePage sourcePage = new SourcePage();
        sourcePage.setSpaceId(asset.getSpaceId());
        sourcePage.setSourceId(asset.getSourceId());
        sourcePage.setSourceAssetId(asset.getId());
        // source_page_number stays the asset-internal page (an image is page 1
        // of its own asset); pageOrder is the Source-wide reading position.
        sourcePage.setSourcePageNumber(1);
        sourcePage.setPageOrder(pageOrder);
        sourcePage.setPrintedPageNumber(null);
        sourcePage.setPageType(PAGE_TYPE_IMAGE);
        sourcePage.setOrderConfidence(null);
        sourcePage.setOrderStatus(ContentExtractionService.ORDER_STATUS_AUTO);
        sourcePage.setExtractedText(ocrText);
        sourcePage.setExtractionConfidence(ocrConfidence);
        sourcePage.setCreatedAt(now);
        sourcePage.setUpdatedAt(now);
        sourcePageMapper.insert(sourcePage);

        if (ocrText != null && !ocrText.isBlank()) {
            ContentBlock cb = new ContentBlock();
            cb.setSpaceId(asset.getSpaceId());
            cb.setSourceId(asset.getSourceId());
            cb.setSourcePageId(sourcePage.getId());
            cb.setSourceOutlineNodeId(null);
            cb.setBlockType("PARAGRAPH");
            cb.setSortOrder(0);
            cb.setNormalizedText(ocrText);
            cb.setStructuredDataJson("{\"ocrEngine\":\""
                    + (ocrEngineName == null ? "unknown" : ocrEngineName)
                    + "\",\"ocrOutcome\":\"" + ocrOutcome.name() + "\"}");
            cb.setLocatorJson("{\"page\":1,\"method\":\"OCR\"}");
            cb.setCreatedAt(now);
            cb.setUpdatedAt(now);
            contentBlockMapper.insert(cb);
        }

        log.info("extracted image asset {} ({}x{} {}), pageOrder={}, ocrOutcome={}, ocrChars={}",
                asset.getId(), width, height, format, pageOrder, ocrOutcome,
                ocrText == null ? 0 : ocrText.length());
        return new ImageExtractionResult(sourcePage.getId(), pageOrder, width, height, format,
                ocrOutcome, ocrConfidence, ocrText == null ? 0 : ocrText.length(), needsReview);
    }

    /**
     * Internal carrier for one OCR stage: the bounded outcome plus the values
     * that get written to {@code source_page}.
     */
    private record OcrStage(OcrOutcome outcome, String text, Double confidence, String engineName) {
    }

    /**
     * Runs the OCR stage and maps it onto the bounded {@link OcrOutcome}
     * vocabulary (F-28).
     *
     * <ul>
     *   <li>No engine deployed ({@link OcrEngine#available()} false):
     *       {@link OcrOutcome#SKIPPED_NO_ENGINE}, no text, no review flag —
     *       this is the documented "text layer only" mode, not a failure.</li>
     *   <li>Engine answered: outcome comes from the result, with a confidence
     *       under {@code aistudy.ocr.confidence-threshold} downgraded to
     *       {@link OcrOutcome#LOW_CONFIDENCE} and an unknown confidence kept
     *       unknown (stored as SQL NULL, never as 1.0).</li>
     *   <li>Engine failed: under {@code on-failure=review} the page is still
     *       written and the source goes to review; under
     *       {@code on-failure=fail} a typed {@link ImageExtractionException}
     *       is thrown whose safe message starts with the stable OCR code.</li>
     * </ul>
     *
     * @param asset asset id is used for the log line only
     * @param temp  local image copy
     * @return the stage result, never null
     * @throws ImageExtractionException only under the {@code fail} policy
     */
    private OcrStage runOcr(SourceAsset asset, File temp) {
        if (ocrEngine == null || !ocrEngine.available()) {
            return new OcrStage(OcrOutcome.SKIPPED_NO_ENGINE, null, null,
                    ocrEngine == null ? null : ocrEngine.name());
        }
        try {
            OcrEngine.OcrResult result = ocrEngine.ocr(temp);
            String text = result.text() == null || result.text().isBlank()
                    ? null : truncate(result.text().trim());
            Double confidence = Double.isNaN(result.confidence()) ? null : result.confidence();
            OcrOutcome outcome = result.outcome();
            if (confidence != null && confidence < confidenceThreshold
                    && outcome != OcrOutcome.NO_TEXT) {
                outcome = OcrOutcome.LOW_CONFIDENCE;
            }
            if (text == null && outcome == OcrOutcome.OK) {
                outcome = OcrOutcome.NO_TEXT;
            }
            if (text != null && outcome == OcrOutcome.NO_TEXT) {
                outcome = confidence == null ? OcrOutcome.CONFIDENCE_UNKNOWN : OcrOutcome.OK;
            }
            return new OcrStage(outcome, text, confidence, result.engine());
        } catch (OcrException e) {
            OcrOutcome outcome = e.outcome();
            if (ocrFailurePolicy == OcrFailurePolicy.FAIL) {
                throw new ImageExtractionException(IngestionErrorCode.IMAGE_PARSE_FAILED,
                        e.stableCode() + ": " + safe(e.getMessage()));
            }
            log.warn("OCR stage failed for asset {}: {} (policy=review, source goes to review)",
                    asset.getId(), e.stableCode());
            return new OcrStage(outcome, null, null, ocrEngine.name());
        }
    }

    /**
     * Reads true pixel dimensions for every accepted format.
     *
     * <p>Order matters: the JDK reader is tried first (it is authoritative for
     * PNG/JPEG), and only when no reader claims the stream does a WebP header
     * get parsed. Any other unclaimed format stays the hard error it was.
     *
     * @param temp   materialised local copy of the asset
     * @param format {@code WEBP} or the normalised MIME type
     * @param header the first 12 bytes, used to recognise a WebP container
     * @return {@code {width, height}}, both {@code >= 1}
     * @throws ImageExtractionException INVALID_IMAGE for an unrecognised or
     *         malformed container, IMAGE_PARSE_FAILED for an I/O error
     */
    private int[] readDimensions(File temp, String format, byte[] header) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(temp)) {
            if (iis == null) {
                throw new ImageExtractionException(IngestionErrorCode.INVALID_IMAGE,
                        "image format not recognized");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (readers.hasNext()) {
                ImageReader reader = readers.next();
                try {
                    reader.setInput(iis, false, true);
                    return new int[]{reader.getWidth(0), reader.getHeight(0)};
                } finally {
                    reader.dispose();
                }
            }
            // No JDK reader: this is where WebP lives. Parse the container
            // header instead of pretending the file was decoded, so the
            // dimension limits below apply to WebP too.
            if ("WEBP".equals(format) || looksLikeWebp(header)) {
                WebpMetadata metadata = WebpHeaderReader.read(temp.toPath());
                return new int[]{metadata.width(), metadata.height()};
            }
            throw new ImageExtractionException(IngestionErrorCode.INVALID_IMAGE,
                    "image format not recognized");
        } catch (WebpMetadataException e) {
            throw new ImageExtractionException(IngestionErrorCode.INVALID_IMAGE,
                    "WebP container is malformed: " + safe(e.getMessage()));
        } catch (ImageExtractionException e) {
            throw e;
        } catch (Exception e) {
            throw new ImageExtractionException(IngestionErrorCode.IMAGE_PARSE_FAILED,
                    "failed to parse image metadata");
        }
    }

    /**
     * The single dimension gate shared by every format (F-08): zero/negative
     * sizes and the configured width/height/pixel ceilings all reject the asset
     * before any page is written.
     *
     * @param width  measured width
     * @param height measured height
     * @throws ImageExtractionException INVALID_IMAGE,
     *         IMAGE_DIMENSION_LIMIT_EXCEEDED or IMAGE_PIXEL_LIMIT_EXCEEDED
     */
    private void enforceDimensionLimits(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new ImageExtractionException(IngestionErrorCode.INVALID_IMAGE,
                    "image has invalid dimensions");
        }
        if (width > maxWidth || height > maxHeight) {
            throw new ImageExtractionException(
                    IngestionErrorCode.IMAGE_DIMENSION_LIMIT_EXCEEDED,
                    "image dimensions exceed the configured limit of " + maxWidth + "x" + maxHeight);
        }
        long pixels = (long) width * (long) height;
        if (pixels > maxPixels) {
            throw new ImageExtractionException(
                    IngestionErrorCode.IMAGE_PIXEL_LIMIT_EXCEEDED,
                    "image pixel count exceeds the configured limit of " + maxPixels);
        }
    }

    private static String truncate(String text) {
        return text.length() > MAX_OCR_TEXT_CHARS ? text.substring(0, MAX_OCR_TEXT_CHARS) : text;
    }

    private static String safe(String message) {
        if (message == null) {
            return "unspecified container error";
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private static boolean looksLikeWebp(byte[] header) {
        return header != null && header.length >= 12
                && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P';
    }

    private static boolean isSupportedMime(String mime) {
        return mime != null && (mime.equals("image/png")
                || mime.equals("image/jpeg")
                || mime.equals("image/webp")
                || mime.equals("application/octet-stream"));
    }

    private static String normalizedMime(String mime) {
        return mime == null ? "" : mime;
    }

    private static String safeMime(String mime) {
        return mime == null ? "null" : mime;
    }

    private static boolean matchesImageMagic(String mime, byte[] header, int headerRead) {
        if ("image/png".equals(mime)) {
            return headerRead >= 8
                    && header[0] == (byte) 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G'
                    && header[4] == 0x0D && header[5] == 0x0A && header[6] == 0x1A && header[7] == 0x0A;
        }
        if ("image/jpeg".equals(mime)) {
            return headerRead >= 2 && header[0] == (byte) 0xFF && header[1] == (byte) 0xD8;
        }
        if ("image/webp".equals(mime)) {
            return headerRead >= 12
                    && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                    && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P';
        }
        if ("application/octet-stream".equals(mime) && headerRead >= 12
                && header[0] == 'R' && header[8] == 'W') {
            return header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                    && header[9] == 'E' && header[10] == 'B' && header[11] == 'P';
        }
        return false;
    }
}
