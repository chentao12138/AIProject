package com.aistudy.server.ingestion.image;

import com.aistudy.server.ingestion.extract.ContentExtractionService;
import com.aistudy.server.ingestion.job.service.IngestionErrorCode;
import com.aistudy.server.ingestion.ocr.OcrEngine;
import com.aistudy.server.ingestion.ocr.OcrException;
import com.aistudy.server.source.asset.entity.SourceAsset;
import com.aistudy.server.source.content.entity.ContentBlock;
import com.aistudy.server.source.content.mapper.ContentBlockMapper;
import com.aistudy.server.source.page.entity.SourcePage;
import com.aistudy.server.source.page.mapper.SourcePageMapper;
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
 * Image ingestion: PNG/JPEG/WebP validation + optional Chinese OCR via
 * {@link OcrEngine}. Low OCR confidence is reported to the job pipeline
 * as an IngestionIssue producer flag.
 */
@Service
public class ImageContentExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ImageContentExtractionService.class);
    private static final String PAGE_TYPE_IMAGE = "IMAGE";

    private final StorageService storageService;
    private final SourcePageMapper sourcePageMapper;
    private final ContentBlockMapper contentBlockMapper;
    private final OcrEngine ocrEngine;
    private final long maxBytes;
    private final int maxWidth;
    private final int maxHeight;
    private final long maxPixels;

    public ImageContentExtractionService(StorageService storageService,
                                         SourcePageMapper sourcePageMapper,
                                         ContentBlockMapper contentBlockMapper,
                                         OcrEngine ocrEngine,
                                         @Value("${aistudy.ingestion.image.max-bytes:50MB}") DataSize maxBytes,
                                         @Value("${aistudy.ingestion.image.max-width:10000}") int maxWidth,
                                         @Value("${aistudy.ingestion.image.max-height:10000}") int maxHeight,
                                         @Value("${aistudy.ingestion.image.max-pixels:100000000}") long maxPixels) {
        this.storageService = storageService;
        this.sourcePageMapper = sourcePageMapper;
        this.contentBlockMapper = contentBlockMapper;
        this.ocrEngine = ocrEngine;
        this.maxBytes = maxBytes.toBytes();
        this.maxWidth = Math.max(1, maxWidth);
        this.maxHeight = Math.max(1, maxHeight);
        this.maxPixels = Math.max(1, maxPixels);
    }

    /** @return true when OCR ran with low confidence */
    public boolean extractAndPersist(SourceAsset asset) {
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
        int width = 0;
        int height = 0;
        String ocrText = null;
        Double ocrConfidence = null;
        String ocrEngineName = null;
        boolean lowConfidence = false;

        try {
            long totalBytes = 0;
            byte[] buf = new byte[8192];
            try (InputStream in = storageService.load(asset.getStorageKey());
                 OutputStream out = new FileOutputStream(temp)) {
                byte[] header = new byte[12];
                int headerRead = in.read(header);
                if (headerRead < 4 || !matchesImageMagic(mimeType, header, headerRead)) {
                    throw new ImageExtractionException(IngestionErrorCode.INVALID_IMAGE,
                            "uploaded asset is not a valid image");
                }
                out.write(header, 0, headerRead);
                totalBytes += headerRead;
                int n;
                while ((n = in.read(buf)) != -1) {
                    totalBytes += n;
                    if (totalBytes > maxBytes) {
                        throw new ImageExtractionException(IngestionErrorCode.IMAGE_TOO_LARGE,
                                "image exceeds the configured byte limit of " + maxBytes);
                    }
                    out.write(buf, 0, n);
                }
            } catch (ImageExtractionException e) {
                throw e;
            } catch (Exception e) {
                throw new ImageExtractionException(IngestionErrorCode.IMAGE_PARSE_FAILED,
                        "failed to read image asset");
            }

            try (ImageInputStream iis = ImageIO.createImageInputStream(temp)) {
                if (iis == null) {
                    throw new ImageExtractionException(IngestionErrorCode.INVALID_IMAGE,
                            "image format not recognized");
                }
                Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
                if (!readers.hasNext()) {
                    // WebP may lack a JDK ImageIO reader; keep size/magic validation.
                    if (isWebp(mimeType, temp)) {
                        width = 0;
                        height = 0;
                    } else {
                        throw new ImageExtractionException(IngestionErrorCode.INVALID_IMAGE,
                                "image format not recognized");
                    }
                } else {
                    ImageReader reader = readers.next();
                    try {
                        reader.setInput(iis, false, true);
                        width = reader.getWidth(0);
                        height = reader.getHeight(0);
                        if (width <= 0 || height <= 0) {
                            throw new ImageExtractionException(IngestionErrorCode.INVALID_IMAGE,
                                    "image has invalid dimensions");
                        }
                        if (width > maxWidth || height > maxHeight) {
                            throw new ImageExtractionException(
                                    IngestionErrorCode.IMAGE_DIMENSION_LIMIT_EXCEEDED,
                                    "image dimensions exceed the configured limit of "
                                            + maxWidth + "x" + maxHeight);
                        }
                        long pixels = (long) width * (long) height;
                        if (pixels > maxPixels) {
                            throw new ImageExtractionException(
                                    IngestionErrorCode.IMAGE_PIXEL_LIMIT_EXCEEDED,
                                    "image pixel count exceeds the configured limit of " + maxPixels);
                        }
                    } finally {
                        reader.dispose();
                    }
                }
            } catch (ImageExtractionException e) {
                throw e;
            } catch (Exception e) {
                throw new ImageExtractionException(IngestionErrorCode.IMAGE_PARSE_FAILED,
                        "failed to parse image metadata");
            }

            if (ocrEngine != null && temp.exists()) {
                try {
                    OcrEngine.OcrResult result = ocrEngine.ocr(temp);
                    if (result != null) {
                        ocrText = result.text() == null ? null : result.text().trim();
                        ocrConfidence = result.confidence();
                        ocrEngineName = result.engine();
                        if (ocrText != null && ocrText.length() > 2000000) {
                            ocrText = ocrText.substring(0, 2000000);
                        }
                        if (ocrConfidence != null && ocrConfidence < 0.7) {
                            lowConfidence = true;
                        }
                    }
                } catch (OcrException e) {
                    log.warn("OCR failed for asset {}: {}", asset.getId(), e.getMessage());
                    lowConfidence = true;
                }
            }
        } finally {
            if (!temp.delete()) {
                temp.deleteOnExit();
            }
        }

        contentBlockMapper.deleteBySpaceSourceAsset(asset.getSpaceId(), asset.getSourceId(), asset.getId());
        sourcePageMapper.deleteBySpaceSourceAsset(asset.getSpaceId(), asset.getSourceId(), asset.getId());

        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        SourcePage sourcePage = new SourcePage();
        sourcePage.setSpaceId(asset.getSpaceId());
        sourcePage.setSourceId(asset.getSourceId());
        sourcePage.setSourceAssetId(asset.getId());
        sourcePage.setSourcePageNumber(1);
        sourcePage.setPageOrder(1);
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
                    + (ocrEngineName == null ? "unknown" : ocrEngineName) + "\"}");
            cb.setLocatorJson("{\"page\":1,\"method\":\"OCR\"}");
            cb.setCreatedAt(now);
            cb.setUpdatedAt(now);
            contentBlockMapper.insert(cb);
        }

        log.info("extracted image asset {} ({}x{}), ocrChars={}",
                asset.getId(), width, height, ocrText == null ? 0 : ocrText.length());
        return lowConfidence;
    }

    private static boolean isWebp(String mime, File temp) {
        return mime != null && mime.contains("webp") && temp.exists();
    }

    private static boolean isSupportedMime(String mime) {
        return mime != null && (mime.equals("image/png")
                || mime.equals("image/jpeg")
                || mime.equals("image/webp")
                || mime.equals("application/octet-stream"));
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
