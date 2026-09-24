package com.aistudy.server.ingestion.pdf;

import com.aistudy.server.ingestion.extract.ContentExtractionService;
import com.aistudy.server.ingestion.job.service.IngestionErrorCode;
import com.aistudy.server.source.asset.entity.SourceAsset;
import com.aistudy.server.source.content.entity.ContentBlock;
import com.aistudy.server.source.content.mapper.ContentBlockMapper;
import com.aistudy.server.source.page.entity.SourcePage;
import com.aistudy.server.source.page.mapper.SourcePageMapper;
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
 * {@link ContentBlock} model. No OCR, no AI, no native binaries.
 *
 * <p>Resources are closed deterministically; temp files are cleaned up
 * in the same method that creates them.
 */
@Service
public class PdfContentExtractionService {

    private static final Logger log = LoggerFactory.getLogger(PdfContentExtractionService.class);

    private final StorageService storageService;
    private final SourcePageMapper sourcePageMapper;
    private final ContentBlockMapper contentBlockMapper;
    private final com.aistudy.server.ingestion.ocr.OcrEngine ocrEngine;
    private final long maxBytes;
    private final int maxPages;
    private final int maxExtractedChars;

    public PdfContentExtractionService(StorageService storageService,
                                       SourcePageMapper sourcePageMapper,
                                       ContentBlockMapper contentBlockMapper,
                                       com.aistudy.server.ingestion.ocr.OcrEngine ocrEngine,
                                       @Value("${aistudy.ingestion.pdf.max-bytes:100MB}") DataSize maxBytes,
                                       @Value("${aistudy.ingestion.pdf.max-pages:200}") int maxPages,
                                       @Value("${aistudy.ingestion.pdf.max-extracted-chars:2000000}") int maxExtractedChars) {
        this.storageService = storageService;
        this.sourcePageMapper = sourcePageMapper;
        this.contentBlockMapper = contentBlockMapper;
        this.ocrEngine = ocrEngine;
        this.maxBytes = maxBytes.toBytes();
        this.maxPages = Math.max(1, maxPages);
        this.maxExtractedChars = Math.max(0, maxExtractedChars);
    }

    /**
     * Text-first PDF extraction with OCR fallback for image-only pages.
     *
     * @return true when any page OCR ran with low confidence
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

            PDFTextStripper stripper = new PDFTextStripper();
            int totalChars = 0;
            boolean anyLowOcr = false;
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
                if (normalized.length() < 20 && ocrEngine != null) {
                    // Image-only / sparse text page → render + OCR fallback.
                    try {
                        java.awt.image.BufferedImage image = renderer.renderImageWithDPI(i - 1, 150);
                        File ocrTemp = new File(System.getProperty("java.io.tmpdir"),
                                "aistudy-pdf-ocr-" + System.nanoTime() + ".png");
                        try {
                            javax.imageio.ImageIO.write(image, "png", ocrTemp);
                            com.aistudy.server.ingestion.ocr.OcrEngine.OcrResult ocr =
                                    ocrEngine.ocr(ocrTemp);
                            if (ocr != null && ocr.text() != null && !ocr.text().isBlank()) {
                                normalized = ocr.text().trim();
                                method = "OCR";
                                confidence = ocr.confidence();
                                if (confidence != null && confidence < 0.7) {
                                    anyLowOcr = true;
                                }
                            } else {
                                anyLowOcr = true;
                            }
                        } finally {
                            if (!ocrTemp.delete()) {
                                ocrTemp.deleteOnExit();
                            }
                        }
                    } catch (Exception ocrEx) {
                        log.warn("PDF page {} OCR fallback failed: {}", i, ocrEx.getMessage());
                        anyLowOcr = true;
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
                sourcePage.setPageOrder(i);
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

            log.info("extracted PDF asset {} into {} pages, {} chars",
                    asset.getId(), pageCount, totalChars);
            return anyLowOcr;
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

    private static boolean isPdfHeader(byte[] header) {
        return header.length == 5 && header[0] == '%' && header[1] == 'P'
                && header[2] == 'D' && header[3] == 'F' && header[4] == '-';
    }
}
