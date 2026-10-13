package com.aistudy.server.ingestion.image;

import com.aistudy.server.ingestion.job.service.IngestionErrorCode;
import com.aistudy.server.ingestion.ocr.OcrEngine;
import com.aistudy.server.ingestion.ocr.OcrException;
import com.aistudy.server.ingestion.ocr.OcrFailurePolicy;
import com.aistudy.server.ingestion.ocr.OcrOutcome;
import com.aistudy.server.source.asset.entity.SourceAsset;
import com.aistudy.server.source.content.mapper.ContentBlockMapper;
import com.aistudy.server.source.page.entity.SourcePage;
import com.aistudy.server.source.page.mapper.SourcePageMapper;
import com.aistudy.server.source.page.service.SourcePageService;
import com.aistudy.server.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.util.unit.DataSize;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-08 / F-28 unit tests for the image extraction gates and outcome mapping,
 * with every collaborator mocked: no database, no OCR binary, no filesystem
 * outside the JVM temp dir.
 *
 * <p>The two behaviours that used to be impossible to assert are the ones
 * covered here: a WebP must be measured before the size limits are applied
 * (previously it reported 0x0 and passed every guard), and an OCR failure must
 * be an explicit outcome rather than a silent "no text".
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImageContentExtractionServiceOutcomeTest {

    @Mock
    private StorageService storageService;
    @Mock
    private SourcePageMapper sourcePageMapper;
    @Mock
    private ContentBlockMapper contentBlockMapper;
    @Mock
    private SourcePageService sourcePageService;
    @Mock
    private OcrEngine ocrEngine;

    private ImageContentExtractionService service(OcrFailurePolicy policy) {
        return new ImageContentExtractionService(storageService, sourcePageMapper, contentBlockMapper,
                sourcePageService, ocrEngine, policy,
                DataSize.ofMegabytes(1), 16, 16, 64, 0.7);
    }

    @BeforeEach
    void stubAllocator() {
        when(sourcePageService.allocateOrderSlots(anyLong(), anyLong(), anyInt()))
                .thenReturn(new int[]{7});
        when(ocrEngine.available()).thenReturn(false);
    }

    private SourceAsset asset(String mime, byte[] bytes) {
        SourceAsset asset = new SourceAsset();
        asset.setId(11L);
        asset.setSpaceId(1L);
        asset.setSourceId(2L);
        asset.setMimeType(mime);
        asset.setStorageKey("key/ignored");
        asset.setSizeBytes((long) bytes.length);
        when(storageService.load(anyString())).thenReturn(new ByteArrayInputStream(bytes));
        return asset;
    }

    private static byte[] png(int width, int height) throws IOException {
        java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** Hand-assembled VP8X container: the JDK has no WebP encoder. */
    private static byte[] webp(int width, int height) {
        byte[] payload = new byte[10];
        payload[4] = (byte) (width - 1);
        payload[5] = (byte) ((width - 1) >> 8);
        payload[6] = (byte) ((width - 1) >> 16);
        payload[7] = (byte) (height - 1);
        payload[8] = (byte) ((height - 1) >> 8);
        payload[9] = (byte) ((height - 1) >> 16);
        byte[] chunk = new byte[8 + payload.length];
        System.arraycopy("VP8X".getBytes(StandardCharsets.US_ASCII), 0, chunk, 0, 4);
        chunk[4] = (byte) payload.length;
        System.arraycopy(payload, 0, chunk, 8, payload.length);
        int container = 4 + chunk.length;
        byte[] out = new byte[12 + chunk.length];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, out, 0, 4);
        out[4] = (byte) container;
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, out, 8, 4);
        System.arraycopy(chunk, 0, out, 12, chunk.length);
        return out;
    }

    @Test
    void pngWithoutOcrDeploysNoTextAndNeedsNoReview() throws Exception {
        when(ocrEngine.available()).thenReturn(false);
        ImageExtractionResult result = service(OcrFailurePolicy.REVIEW)
                .extractAndPersistDetailed(asset("image/png", png(8, 8)));

        assertEquals("image/png", result.format(), "a JDK reader handled the stream");
        assertEquals(8, result.width());
        assertEquals(8, result.height());
        assertEquals(OcrOutcome.SKIPPED_NO_ENGINE, result.ocrOutcome());
        assertFalse(result.needsReview());
        assertEquals(7, result.pageOrder(), "the Source-wide slot comes from the allocator");

        ArgumentCaptor<SourcePage> captor = ArgumentCaptor.forClass(SourcePage.class);
        verify(sourcePageMapper).insert(captor.capture());
        assertNull(captor.getValue().getExtractedText(),
                "an image page must not fabricate text when OCR is not deployed");
        assertNull(captor.getValue().getExtractionConfidence());
        assertEquals(7, captor.getValue().getPageOrder());
        verify(contentBlockMapper, never()).insert(any(com.aistudy.server.source.content.entity.ContentBlock.class));
    }

    @Test
    void webpIsMeasuredBeforeTheSizeGuardsApply() throws Exception {
        // 40x40 is inside the byte limit but past the configured 16x16 ceiling:
        // with the old 0x0 placeholder this asset would have been accepted.
        ImageExtractionException error = assertThrows(ImageExtractionException.class,
                () -> service(OcrFailurePolicy.REVIEW).extractAndPersistDetailed(webpAsset("image/webp", 40, 40)));
        assertEquals(IngestionErrorCode.IMAGE_DIMENSION_LIMIT_EXCEEDED, error.errorCode());
        verify(sourcePageMapper, never()).insert(any(SourcePage.class));
    }

    private SourceAsset webpAsset(String mime, int width, int height) {
        return asset(mime, webp(width, height));
    }

    @Test
    void webpPixelLimitAppliesToWebpToo() throws Exception {
        // 8x8 = 64 pixels is exactly the ceiling, 9x8 = 72 is over it while both
        // stay inside max-width/max-height.
        ImageExtractionException error = assertThrows(ImageExtractionException.class,
                () -> service(OcrFailurePolicy.REVIEW).extractAndPersistDetailed(webpAsset("image/webp", 9, 8)));
        assertEquals(IngestionErrorCode.IMAGE_PIXEL_LIMIT_EXCEEDED, error.errorCode());
    }

    @Test
    void webpWithinLimitsProducesRealDimensions() throws Exception {
        // 7x8 = 56 pixels: inside both the 16x16 edge and the 64 pixel ceiling.
        ImageExtractionResult result = service(OcrFailurePolicy.REVIEW)
                .extractAndPersistDetailed(webpAsset("image/webp", 7, 8));
        assertEquals("WEBP", result.format());
        assertEquals(7, result.width());
        assertEquals(8, result.height());
    }

    @Test
    void truncatedWebpFailsInsteadOfPseudoSucceeding() {
        byte[] bytes = webp(12, 10);
        byte[] cut = new byte[12]; // header only: declares more than it holds
        System.arraycopy(bytes, 0, cut, 0, 12);
        ImageExtractionException error = assertThrows(ImageExtractionException.class,
                () -> service(OcrFailurePolicy.REVIEW).extractAndPersistDetailed(asset("image/webp", cut)));
        assertEquals(IngestionErrorCode.INVALID_IMAGE, error.errorCode());
        assertTrue(error.safeMessage().contains("WebP"), error.safeMessage());
        verify(sourcePageMapper, never()).insert(any(SourcePage.class));
    }

    @Test
    void ocrTimeoutUnderReviewPolicyStillPersistsPageAndFlagsReview() throws Exception {
        when(ocrEngine.available()).thenReturn(true);
        when(ocrEngine.ocr(any())).thenThrow(new OcrException(OcrOutcome.TIMEOUT,
                "OCR timed out after 120s and was terminated", null, null));
        when(ocrEngine.name()).thenReturn("PaddleOCR");

        ImageExtractionResult result = service(OcrFailurePolicy.REVIEW)
                .extractAndPersistDetailed(asset("image/png", png(8, 8)));

        assertEquals(OcrOutcome.TIMEOUT, result.ocrOutcome());
        assertTrue(result.needsReview(), "a failed OCR pass is not \"no text\"");
        verify(sourcePageMapper).insert(any(SourcePage.class));
    }

    @Test
    void ocrTimeoutUnderFailPolicyFailsTheAssetWithItsStableCode() throws Exception {
        when(ocrEngine.available()).thenReturn(true);
        when(ocrEngine.ocr(any())).thenThrow(new OcrException(OcrOutcome.TIMEOUT,
                "OCR timed out after 120s and was terminated", null, null));

        ImageExtractionException error = assertThrows(ImageExtractionException.class,
                () -> service(OcrFailurePolicy.FAIL).extractAndPersistDetailed(asset("image/png", png(8, 8))));
        assertEquals(IngestionErrorCode.IMAGE_PARSE_FAILED, error.errorCode());
        assertTrue(error.safeMessage().startsWith("OCR_TIMEOUT"), error.safeMessage());
        verify(sourcePageMapper, never()).insert(any(SourcePage.class));
    }

    @Test
    void unknownConfidenceIsStoredAsNullNotAsFullConfidence() throws Exception {
        when(ocrEngine.available()).thenReturn(true);
        when(ocrEngine.ocr(any())).thenReturn(new OcrEngine.OcrResult(
                "识别文字", OcrEngine.CONFIDENCE_UNKNOWN, "PaddleOCR", null));

        ImageExtractionResult result = service(OcrFailurePolicy.REVIEW)
                .extractAndPersistDetailed(asset("image/png", png(8, 8)));

        assertTrue(result.needsReview());
        assertEquals(OcrOutcome.CONFIDENCE_UNKNOWN, result.ocrOutcome());
        assertNull(result.confidence());

        ArgumentCaptor<SourcePage> captor = ArgumentCaptor.forClass(SourcePage.class);
        verify(sourcePageMapper).insert(captor.capture());
        assertNull(captor.getValue().getExtractionConfidence(),
                "unknown confidence must be SQL NULL, never a fabricated 1.0");
        assertEquals("识别文字", captor.getValue().getExtractedText());
    }

    @Test
    void lowConfidenceTextIsKeptAndFlagged() throws Exception {
        when(ocrEngine.available()).thenReturn(true);
        when(ocrEngine.ocr(any())).thenReturn(new OcrEngine.OcrResult(
                "some text", 0.2, "PaddleOCR", null));

        ImageExtractionResult result = service(OcrFailurePolicy.REVIEW)
                .extractAndPersistDetailed(asset("image/png", png(8, 8)));

        assertEquals(OcrOutcome.LOW_CONFIDENCE, result.ocrOutcome());
        assertTrue(result.needsReview());
        assertEquals(0.2, result.confidence());
    }

    @Test
    void blankOcrAnswerIsNoTextAndNotAFailure() throws Exception {
        when(ocrEngine.available()).thenReturn(true);
        when(ocrEngine.ocr(any())).thenReturn(new OcrEngine.OcrResult("   ", 0.9, "PaddleOCR", null));

        ImageExtractionResult result = service(OcrFailurePolicy.REVIEW)
                .extractAndPersistDetailed(asset("image/png", png(8, 8)));

        assertEquals(OcrOutcome.NO_TEXT, result.ocrOutcome());
        assertTrue(result.needsReview(), "an empty image page needs a human, not a retry");
        assertEquals(0, result.textChars());
        verify(contentBlockMapper, never()).insert(any(com.aistudy.server.source.content.entity.ContentBlock.class));
    }

    @Test
    void legacyBooleanStillReportsReviewNeed() throws Exception {
        when(ocrEngine.available()).thenReturn(true);
        when(ocrEngine.ocr(any())).thenThrow(new OcrException(OcrOutcome.UNAVAILABLE,
                "OCR executable is not available", null, null));

        assertTrue(service(OcrFailurePolicy.REVIEW).extractAndPersist(asset("image/png", png(8, 8))),
                "the pipeline's boolean contract stays intact");
    }

    @Test
    void badPngMagicIsRejectedWithTheSameSemanticsAsBefore() {
        ImageExtractionException error = assertThrows(ImageExtractionException.class,
                () -> service(OcrFailurePolicy.REVIEW)
                        .extractAndPersistDetailed(asset("image/png", new byte[]{1, 2, 3, 4, 5, 6, 7, 8})));
        assertEquals(IngestionErrorCode.INVALID_IMAGE, error.errorCode());
    }

    @Test
    void octetStreamWebpIsAcceptedByMagicNotByMimeWishfulThinking() throws Exception {
        // A desktop upload with a generic MIME type still gets measured, because
        // the container is what decides the reader.
        ImageExtractionResult result = service(OcrFailurePolicy.REVIEW)
                .extractAndPersistDetailed(asset("application/octet-stream", webp(6, 5)));
        assertEquals("WEBP", result.format());
        assertEquals(6, result.width());
        assertEquals(5, result.height());
        assertFalse(result.needsReview());
    }
}
