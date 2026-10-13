package com.aistudy.server.ingestion.pdf;

import com.aistudy.server.ingestion.extract.ContentExtractionService;
import com.aistudy.server.ingestion.job.service.IngestionErrorCode;
import com.aistudy.server.source.asset.entity.SourceAsset;
import com.aistudy.server.source.content.entity.ContentBlock;
import com.aistudy.server.source.content.mapper.ContentBlockMapper;
import com.aistudy.server.source.page.entity.SourcePage;
import com.aistudy.server.source.page.mapper.SourcePageMapper;
import com.aistudy.server.source.page.service.SourcePageService;
import com.aistudy.server.storage.StorageService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * BUSINESS-020 — deterministic PDF text extraction.
 *
 * <p>Uses Apache PDFBox to extract text page-by-page and persists
 * derived content through the existing {@link SourcePage} and
 * {@link ContentBlock} model. No AI, no native binaries; OCR is only
 * consulted through {@link com.aistudy.server.ingestion.ocr.OcrEngine} for
 * pages that carry no usable text layer.
 *
 * <p>Resources are closed deterministically; temp files are cleaned up
 * in the same method that creates them.
 *
 * <h3>OCR fallback semantics (F-28)</h3>
 *
 * <p>A page with fewer than {@value #SPARSE_TEXT_THRESHOLD} characters is
 * treated as image-only and gets an OCR pass. The result of that pass is now
 * an explicit {@link com.aistudy.server.ingestion.ocr.OcrOutcome} instead of a
 * silent "keep whatever text we had":
 * <ul>
 *   <li>no OCR deployed &rarr; {@code SKIPPED_NO_ENGINE}; a page with some text
 *       still publishes normally, a page with none is flagged for review.</li>
 *   <li>OCR answered &rarr; text + outcome; low or unknown confidence flags the
 *       source for review, and unknown confidence is stored as SQL NULL rather
 *       than as a confident value.</li>
 *   <li>OCR failed &rarr; {@code on-failure=review} keeps the run alive and
 *       flags review; {@code on-failure=fail} throws a typed
 *       {@link PdfExtractionException} so the job reports
 *       FAILED/PARTIAL_FAILED with the stable OCR code in the message.</li>
 * </ul>
 *
 * <h3>Page order (F-31)</h3>
 *
 * <p>{@code source_page_number} / {@code printed_page_number} stay the
 * PDF-internal 1-based page, while {@code page_order} comes from
 * {@link SourcePageService#allocateOrderSlots}, so a Source holding several
 * assets gets one continuous reading order instead of every asset restarting at
 * 1. Slots freed by re-extracting this same asset are reused, so a
 * human-confirmed order is not pushed around.
 */
@Service
public class PdfContentExtractionService {

    private static final Logger log = LoggerFactory.getLogger(PdfContentExtractionService.class);

    /**
     * A page whose text layer is shorter than this is considered image-only.
     * Bounded on purpose: it decides whether a native OCR run is worth the
     * cost, and a threshold that grew with the document would make the
     * behaviour unpredictable.
     */
    private static final int SPARSE_TEXT_THRESHOLD = 20;

    private final StorageService storageService;
    private final SourcePageMapper sourcePageMapper;
    private final ContentBlockMapper contentBlockMapper;
    private final SourcePageService sourcePageService;
    private final com.aistudy.server.ingestion.ocr.OcrEngine ocrEngine;
    private final com.aistudy.server.ingestion.ocr.OcrFailurePolicy ocrFailurePolicy;
    private final long maxBytes;
    private final int maxPages;
    private final int maxExtractedChars;
    private final double confidenceThreshold;

    public PdfContentExtractionService(StorageService storageService,
                                       SourcePageMapper sourcePageMapper,
                                       ContentBlockMapper contentBlockMapper,
                                       SourcePageService sourcePageService,
                                       com.aistudy.server.ingestion.ocr.OcrEngine ocrEngine,
                                       com.aistudy.server.ingestion.ocr.OcrFailurePolicy ocrFailurePolicy,
                                       @Value("${aistudy.ingestion.pdf.max-bytes:100MB}") DataSize maxBytes,
                                       @Value("${aistudy.ingestion.pdf.max-pages:200}") int maxPages,
                                       @Value("${aistudy.ingestion.pdf.max-extracted-chars:2000000}") int maxExtractedChars,
                                       @Value("${aistudy.ocr.confidence-threshold:0.7}") double confidenceThreshold) {
        this.storageService = storageService;
        this.sourcePageMapper = sourcePageMapper;
        this.contentBlockMapper = contentBlockMapper;
        this.sourcePageService = sourcePageService;
        this.ocrEngine = ocrEngine;
        this.ocrFailurePolicy = ocrFailurePolicy == null
                ? com.aistudy.server.ingestion.ocr.OcrFailurePolicy.REVIEW : ocrFailurePolicy;
        this.maxBytes = maxBytes.toBytes();
        this.maxPages = Math.max(1, maxPages);
        this.maxExtractedChars = Math.max(0, maxExtractedChars);
        this.confidenceThreshold = confidenceThreshold >= 0 && confidenceThreshold <= 1
                ? confidenceThreshold : 0.7;
    }

    /**
     * Text-first PDF extraction with OCR fallback for image-only pages.
     *
     * @param asset PDF asset to extract and persist
     * @return true when any page must go to human review: low or unknown OCR
     *         confidence, no text found by a deployed engine, or an OCR failure
     *         tolerated by the {@code review} policy
     * @throws PdfExtractionException for a rejected PDF, or — under
     *         {@code on-failure=fail} — for an OCR failure
     */
    public boolean extractAndPersist(SourceAsset asset) {
        if (asset.getSizeBytes() > maxBytes) {
            throw new PdfExtractionException(IngestionErrorCode.PDF_TEXT_LIMIT_EXCEEDED,
                    "PDF exceeds the configured byte limit of " + maxBytes);
        }

        File temp = new File(System.getProperty("java.io.tmpdir"),
                "aistudy-pdf-" + System.nanoTime() + ".pdf");
        temp.deleteOnExit();
        long totalBytes = 0;
        byte[] buf = new byte[8192];
        try (InputStream in = storageService.load(asset.getStorageKey());
             OutputStream out = new FileOutputStream(temp)) {
            byte[] header = new byte[5];
            int headerRead = in.read(header);
            if (headerRead != 5 || !isPdfHeader(header)) {
                throw new PdfExtractionException(IngestionErrorCode.INVALID_PDF,
                        "Uploaded asset is not a valid PDF");
            }
            out.write(header);
            totalBytes += headerRead;

            int n;
            while ((n = in.read(buf)) != -1) {
                totalBytes += n;
                if (totalBytes > maxBytes) {
                    throw new PdfExtractionException(IngestionErrorCode.PDF_TEXT_LIMIT_EXCEEDED,
                            "PDF exceeds the configured byte limit of " + maxBytes);
                }
                out.write(buf, 0, n);
            }
        } catch (PdfExtractionException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfExtractionException(IngestionErrorCode.INVALID_PDF,
                    "Failed to read PDF asset");
        }

        try (PDDocument document = Loader.loadPDF(temp, IOUtils.createTempFileOnlyStreamCache())) {
            if (document.isEncrypted()) {
                throw new PdfExtractionException(IngestionErrorCode.PDF_ENCRYPTED,
                        "PDF is encrypted");
            }

            int pageCount = document.getNumberOfPages();
            if (pageCount > maxPages) {
                throw new PdfExtractionException(IngestionErrorCode.PDF_PAGE_LIMIT_EXCEEDED,
                        "PDF exceeds the configured page limit of " + maxPages);
            }

            contentBlockMapper.deleteBySpaceSourceAsset(asset.getSpaceId(), asset.getSourceId(), asset.getId());
            sourcePageMapper.deleteBySpaceSourceAsset(asset.getSpaceId(), asset.getSourceId(), asset.getId());

            // F-31: allocate Source-wide slots for this asset's pages. Done
            // after the delete so re-extracting one asset reuses its own slots
            // instead of appending itself to the end of the reading order.
            int[] orderSlots = sourcePageService.allocateOrderSlots(
                    asset.getSpaceId(), asset.getSourceId(), pageCount);

            PDFTextStripper stripper = new PDFTextStripper();
            int totalChars = 0;
            boolean needsReview = false;
            LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
            org.apache.pdfbox.rendering.PDFRenderer renderer =
                    new org.apache.pdfbox.rendering.PDFRenderer(document);
            for (int i = 1; i <= pageCount; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String pageText = stripper.getText(document);
                if (pageText == null) {
                    pageText = "";
                }
                String normalized = pageText.replace("\r\n", "\n").replace('\r', '\n').trim();
                String method = "PDF_TEXT";
                Double confidence = null;
                if (normalized.length() < SPARSE_TEXT_THRESHOLD && ocrEngine != null) {
                    // Image-only / sparse text page → render + OCR fallback.
                    OcrStage stage;
                    try {
                        java.awt.image.BufferedImage image = renderer.renderImageWithDPI(i - 1, 150);
                        File ocrTemp = new File(System.getProperty("java.io.tmpdir"),
                                "aistudy-pdf-ocr-" + System.nanoTime() + ".png");
                        try {
                            javax.imageio.ImageIO.write(image, "png", ocrTemp);
                            stage = runOcrFallback(asset, i, ocrTemp, normalized);
                        } finally {
                            if (!ocrTemp.delete()) {
                                ocrTemp.deleteOnExit();
                            }
                        }
                    } catch (PdfExtractionException e) {
                        throw e;
                    } catch (Exception ocrEx) {
                        // Rendering itself failed: that is not an OCR verdict,
                        // so keep the old lenient behaviour but say so loudly.
                        log.warn("PDF page {} of asset {} could not be rendered for OCR: {}",
                                i, asset.getId(), ocrEx.getClass().getSimpleName());
                        stage = new OcrStage(com.aistudy.server.ingestion.ocr.OcrOutcome.PROCESS_FAILED,
                                normalized, null, "PDF_RENDER");
                    }
                    if (stage.method() != null) {
                        method = stage.method();
                    }
                    if (stage.text() != null && !stage.text().isBlank()) {
                        normalized = stage.text();
                    }
                    confidence = stage.confidence();
                    if (stage.outcome().needsReview()) {
                        needsReview = true;
                    }
                }
                if (totalChars + normalized.length() > maxExtractedChars) {
                    throw new PdfExtractionException(IngestionErrorCode.PDF_TEXT_LIMIT_EXCEEDED,
                            "PDF exceeds the configured extracted character limit of " + maxExtractedChars);
                }
                totalChars += normalized.length();

                SourcePage sourcePage = new SourcePage();
                sourcePage.setSpaceId(asset.getSpaceId());
                sourcePage.setSourceId(asset.getSourceId());
                sourcePage.setSourceAssetId(asset.getId());
                sourcePage.setSourcePageNumber(i);
                sourcePage.setPageOrder(orderSlots[i - 1]);
                sourcePage.setPrintedPageNumber(i);
                sourcePage.setPageType("OCR".equals(method)
                        ? ContentExtractionService.PAGE_TYPE_IMAGE
                        : ContentExtractionService.PAGE_TYPE_BODY);
                sourcePage.setOrderConfidence(null);
                sourcePage.setOrderStatus(ContentExtractionService.ORDER_STATUS_AUTO);
                sourcePage.setExtractedText(normalized);
                sourcePage.setExtractionConfidence(confidence);
                sourcePage.setCreatedAt(now);
                sourcePage.setUpdatedAt(now);
                sourcePageMapper.insert(sourcePage);

                List<ContentExtractionService.ParsedBlock> blocks =
                        ContentExtractionService.parsePageText(normalized).blocks();
                for (int j = 0; j < blocks.size(); j++) {
                    ContentExtractionService.ParsedBlock block = blocks.get(j);
                    ContentBlock cb = new ContentBlock();
                    cb.setSpaceId(asset.getSpaceId());
                    cb.setSourceId(asset.getSourceId());
                    cb.setSourcePageId(sourcePage.getId());
                    cb.setSourceOutlineNodeId(null);
                    cb.setBlockType(block.type());
                    cb.setSortOrder(j);
                    cb.setNormalizedText(block.text());
                    cb.setStructuredDataJson("{\"method\":\"" + method + "\"}");
                    cb.setLocatorJson("{\"page\":" + i
                            + ",\"lineStart\":" + block.lineStart()
                            + ",\"lineEnd\":" + block.lineEnd() + "}");
                    cb.setCreatedAt(now);
                    cb.setUpdatedAt(now);
                    contentBlockMapper.insert(cb);
                }
            }

            log.info("extracted PDF asset {} into {} pages, {} chars, review={}",
                    asset.getId(), pageCount, totalChars, needsReview);
            return needsReview;
        } catch (PdfExtractionException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfExtractionException(IngestionErrorCode.PDF_PARSE_FAILED,
                    "PDF parsing failed");
        } finally {
            if (!temp.delete()) {
                temp.deleteOnExit();
            }
        }
    }

    /**
     * Outcome of the per-page OCR fallback, already mapped onto the shared
     * vocabulary so the loop only has to ask "does this need a human?".
     *
     * @param outcome     bounded OCR outcome, never null
     * @param text        text to store (the page's own text layer when OCR had
     *                    nothing to add), never null
     * @param confidence  {@code 0.0..1.0} or null when unknown
     * @param method      {@code OCR} when the engine supplied the text, else
     *                    null meaning "keep PDF_TEXT"
     */
    private record OcrStage(com.aistudy.server.ingestion.ocr.OcrOutcome outcome,
                            String text,
                            Double confidence,
                            String method) {
    }

    /**
     * Runs the OCR fallback for one sparse page (F-28).
     *
     * @param asset      asset being extracted, for the log identity only
     * @param pageNumber PDF-internal page number, for the log only
     * @param ocrTemp    rendered PNG handed to the engine
     * @param pageText   text layer already read from the page
     * @return the stage result, never null; the page keeps {@code pageText}
     *         whenever OCR cannot improve on it
     * @throws PdfExtractionException only under {@code on-failure=fail}, carrying
     *         the closest existing {@link IngestionErrorCode} plus the stable
     *         OCR code as a message prefix
     */
    private OcrStage runOcrFallback(SourceAsset asset, int pageNumber, File ocrTemp, String pageText) {
        if (!ocrEngine.available()) {
            // OCR is simply not part of this deployment: a page with a text
            // layer is fine as is, a page with none is a human-review case,
            // not a failure a retry could fix.
            com.aistudy.server.ingestion.ocr.OcrOutcome outcome = pageText.isBlank()
                    ? com.aistudy.server.ingestion.ocr.OcrOutcome.NO_TEXT
                    : com.aistudy.server.ingestion.ocr.OcrOutcome.SKIPPED_NO_ENGINE;
            return new OcrStage(outcome, pageText, null, null);
        }
        try {
            com.aistudy.server.ingestion.ocr.OcrEngine.OcrResult ocr = ocrEngine.ocr(ocrTemp);
            String text = ocr.text() == null ? "" : ocr.text().trim();
            Double confidence = Double.isNaN(ocr.confidence()) ? null : ocr.confidence();
            com.aistudy.server.ingestion.ocr.OcrOutcome outcome = ocr.outcome();
            if (confidence != null && confidence < confidenceThreshold
                    && outcome != com.aistudy.server.ingestion.ocr.OcrOutcome.NO_TEXT) {
                outcome = com.aistudy.server.ingestion.ocr.OcrOutcome.LOW_CONFIDENCE;
            }
            if (text.isBlank()) {
                return new OcrStage(outcome, pageText, confidence, null);
            }
            return new OcrStage(outcome, text, confidence, "OCR");
        } catch (com.aistudy.server.ingestion.ocr.OcrException e) {
            if (ocrFailurePolicy == com.aistudy.server.ingestion.ocr.OcrFailurePolicy.FAIL) {
                throw new PdfExtractionException(IngestionErrorCode.PDF_PARSE_FAILED,
                        e.stableCode() + ": " + safe(e.getMessage()));
            }
            log.warn("PDF page {} of asset {} OCR fallback failed ({}); page keeps its text layer "
                    + "and the source goes to review", pageNumber, asset.getId(), e.stableCode());
            return new OcrStage(e.outcome(), pageText, null, null);
        }
    }

    private static String safe(String message) {
        if (message == null) {
            return "unspecified OCR failure";
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private static boolean isPdfHeader(byte[] header) {
        return header.length == 5 && header[0] == '%' && header[1] == 'P'
                && header[2] == 'D' && header[3] == 'F' && header[4] == '-';
    }
}
