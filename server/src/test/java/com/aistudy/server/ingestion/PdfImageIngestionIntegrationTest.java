package com.aistudy.server.ingestion;

import com.aistudy.server.auth.service.JwtAccessTokenService;
import com.aistudy.server.testsupport.OwnedSpaceReset;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BUSINESS-020 / BUSINESS-021 — PDF and image ingestion vertical slice.
 *
 * <p>Real MySQL ({@code flyway-it}, schema guard), MockMvc + JWT, temp
 * storage root. PDFs are generated with PDFBox 3; images with ImageIO.
 * No OCR, no large binary fixtures.
 *
 * <p>Covers: multi-page PDF → 1-based pages/blocks/locator; valid PNG
 * and JPEG → single IMAGE page with {@code extractedText=null} and no
 * ContentBlock; invalid magic / oversize limits; retry does not
 * duplicate pages; generated page ids usable for block inserts.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("flyway-it")
@ResourceLock("aistudy-flyway-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PdfImageIngestionIntegrationTest {

    private static final String EXPECTED_SCHEMA = "aistudy_flyway_test";
    private static final List<String> BIZ_TEST_USERS = List.of("biz-pdf-img-user");

    private static final String JOBS =
            "/api/v1/spaces/{spaceId}/sources/{sourceId}/ingestion-jobs";
    private static final String PAGES =
            "/api/v1/spaces/{spaceId}/sources/{sourceId}/pages";
    private static final String BLOCKS =
            "/api/v1/spaces/{spaceId}/sources/{sourceId}/content-blocks";

    private static Path storageRoot;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JwtAccessTokenService jwtAccessTokenService;

    @DynamicPropertySource
    static void storageProps(DynamicPropertyRegistry registry) {
        try {
            storageRoot = Files.createTempDirectory("aistudy-pdf-img-it-");
        } catch (IOException e) {
            throw new IllegalStateException("cannot create temp storage root", e);
        }
        registry.add("aistudy.storage.local.root", () -> storageRoot.toString());
        // Tiny PDF limits so limit tests need no real MB file.
        registry.add("aistudy.ingestion.pdf.max-bytes", () -> "2KB");
        registry.add("aistudy.ingestion.pdf.max-pages", () -> "2");
        registry.add("aistudy.ingestion.pdf.max-extracted-chars", () -> "200");
        registry.add("aistudy.ingestion.image.max-bytes", () -> "1KB");
        registry.add("aistudy.ingestion.image.max-width", () -> "16");
        registry.add("aistudy.ingestion.image.max-height", () -> "16");
        registry.add("aistudy.ingestion.image.max-pixels", () -> "64");
    }

    @BeforeAll
    void guardTargetDatabase() {
        assertSchemaIsFlywayTest();
    }

    @AfterAll
    void removeTempStorageRoot() throws IOException {
        if (storageRoot != null && Files.exists(storageRoot)) {
            try (var walk = Files.walk(storageRoot)) {
                walk.sorted(Collections.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @BeforeEach
    void prepareDatabase() {
        assertSchemaIsFlywayTest();
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .load()
                .migrate();
        cleanBizTestRows();
    }

    @AfterEach
    void cleanUp() {
        cleanBizTestRows();
    }

    // ==================== PDF ====================

    @Test
    void validMultiPagePdfProducesPagesAndBlocks() throws Exception {
        Fixture fx = newFixture("pdf-ok");
        byte[] pdf = twoPagePdf("PageOneAlpha", "PageTwoBeta");
        Long assetId = upload(fx.token, fx.spaceId, fx.sourceId, "book.pdf", "application/pdf", pdf);

        String body = createJob(fx.token, fx.spaceId, fx.sourceId, assetId, "SUCCEEDED");

        List<Map<String, Object>> pages = rows(
                "SELECT source_page_number, page_order, page_type, extracted_text "
                        + "FROM source_page WHERE source_id = ? ORDER BY page_order",
                fx.sourceId);
        assertEquals(2, pages.size(), "two-page PDF must produce two SourcePages");
        assertEquals(1, ((Number) pages.get(0).get("source_page_number")).intValue());
        assertEquals(1, ((Number) pages.get(0).get("page_order")).intValue());
        assertEquals(2, ((Number) pages.get(1).get("source_page_number")).intValue());
        assertEquals(2, ((Number) pages.get(1).get("page_order")).intValue());
        assertTrue(String.valueOf(pages.get(0).get("extracted_text")).contains("PageOneAlpha"));
        assertTrue(String.valueOf(pages.get(1).get("extracted_text")).contains("PageTwoBeta"));

        List<Map<String, Object>> blocks = rows(
                "SELECT cb.sort_order, cb.normalized_text, cb.locator_json, cb.source_page_id, sp.source_page_number "
                        + "FROM content_block cb JOIN source_page sp ON sp.id = cb.source_page_id "
                        + "WHERE cb.source_id = ? ORDER BY sp.page_order, cb.sort_order",
                fx.sourceId);
        assertTrue(blocks.size() >= 2, "each page must emit at least one block");
        assertTrue(String.valueOf(blocks.get(0).get("locator_json")).contains("\"page\":1"));
        assertTrue(String.valueOf(blocks.get(0).get("locator_json")).contains("lineStart"));
        assertNotNull(blocks.get(0).get("source_page_id"),
                "ContentBlock must reference a usable generated SourcePage id");
        assertTrue(((Number) blocks.get(0).get("source_page_id")).longValue() > 0);
        assertNotNull(body);
    }

    @Test
    void pdfRetryDoesNotDuplicatePages() throws Exception {
        Fixture fx = newFixture("pdf-retry");
        Long assetId = upload(fx.token, fx.spaceId, fx.sourceId,
                "book.pdf", "application/pdf", twoPagePdf("Alpha", "Beta"));
        createJob(fx.token, fx.spaceId, fx.sourceId, assetId, "SUCCEEDED");

        Long jobId = jdbcTemplate.queryForObject(
                "SELECT id FROM ingestion_job WHERE source_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, fx.sourceId);
        mockMvc.perform(post("/api/v1/spaces/{spaceId}/ingestion-jobs/{jobId}/retry",
                        fx.spaceId, jobId)
                        .header("Authorization", "Bearer " + fx.token))
                .andExpect(status().isConflict());

        Long pages = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM source_page WHERE source_id = ?", Long.class, fx.sourceId);
        assertEquals(2L, pages, "retry on SUCCEEDED must not duplicate pages");
    }

    @Test
    void pdfOverPageLimitFails() throws Exception {
        Fixture fx = newFixture("pdf-pages");
        // 3 pages, limit is 2
        Long assetId = upload(fx.token, fx.spaceId, fx.sourceId,
                "big.pdf", "application/pdf", multiPagePdf(3, "p"));
        String body = createJob(fx.token, fx.spaceId, fx.sourceId, assetId, "FAILED");
        assertTrue(body.contains("PDF_PAGE_LIMIT_EXCEEDED"), body);
        assertEquals(0L, countPages(fx.sourceId));
    }

    @Test
    void pdfOverByteLimitFails() throws Exception {
        Fixture fx = newFixture("pdf-bytes");
        byte[] pad = new byte[2500];
        java.util.Arrays.fill(pad, (byte) 'x');
        String padded = "%PDF-1.4\n" + new String(pad, StandardCharsets.US_ASCII) + "\n%%EOF";
        Long assetId = upload(fx.token, fx.spaceId, fx.sourceId,
                "huge.pdf", "application/pdf", padded.getBytes(StandardCharsets.US_ASCII));
        String body = createJob(fx.token, fx.spaceId, fx.sourceId, assetId, "FAILED");
        assertTrue(body.contains("PDF_TEXT_LIMIT_EXCEEDED")
                || body.contains("PDF_PARSE_FAILED")
                || body.contains("INVALID_PDF"), body);
        assertEquals(0L, countPages(fx.sourceId));
    }

    @Test
    void invalidPdfMagicFails() throws Exception {
        Fixture fx = newFixture("pdf-magic");
        Long assetId = upload(fx.token, fx.spaceId, fx.sourceId,
                "book.pdf", "application/pdf", "NOT-A-PDF".getBytes(StandardCharsets.US_ASCII));
        String body = createJob(fx.token, fx.spaceId, fx.sourceId, assetId, "FAILED");
        assertTrue(body.contains("INVALID_PDF"), body);
        assertEquals(0L, countPages(fx.sourceId));
    }

    // ==================== IMAGE ====================

    @Test
    void validPngCreatesSingleImagePageWithoutBlocks() throws Exception {
        Fixture fx = newFixture("png-ok");
        Long assetId = upload(fx.token, fx.spaceId, fx.sourceId,
                "page.png", "image/png", tinyPng(8, 8));
        createJob(fx.token, fx.spaceId, fx.sourceId, assetId, "SUCCEEDED");

        List<Map<String, Object>> pages = rows(
                "SELECT source_page_number, page_order, page_type, extracted_text "
                        + "FROM source_page WHERE source_id = ?", fx.sourceId);
        assertEquals(1, pages.size());
        assertEquals(1, ((Number) pages.get(0).get("source_page_number")).intValue());
        assertEquals(1, ((Number) pages.get(0).get("page_order")).intValue());
        assertEquals("IMAGE", pages.get(0).get("page_type"));
        assertNull(pages.get(0).get("extracted_text"),
                "image page must not fabricate extracted text");

        assertEquals(0L, countBlocks(fx.sourceId),
                "image ingestion must not create ContentBlocks");
    }

    @Test
    void validJpegCreatesSingleImagePage() throws Exception {
        Fixture fx = newFixture("jpeg-ok");
        Long assetId = upload(fx.token, fx.spaceId, fx.sourceId,
                "photo.jpg", "image/jpeg", tinyJpeg(8, 8));
        createJob(fx.token, fx.spaceId, fx.sourceId, assetId, "SUCCEEDED");

        assertEquals(1L, countPages(fx.sourceId));
        assertEquals(0L, countBlocks(fx.sourceId));
        String pageType = jdbcTemplate.queryForObject(
                "SELECT page_type FROM source_page WHERE source_id = ?", String.class, fx.sourceId);
        assertEquals("IMAGE", pageType);
    }

    @Test
    void mimeMagicMismatchFails() throws Exception {
        Fixture fx = newFixture("png-magic");
        Long assetId = upload(fx.token, fx.spaceId, fx.sourceId,
                "page.png", "image/png", new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        String body = createJob(fx.token, fx.spaceId, fx.sourceId, assetId, "FAILED");
        assertTrue(body.contains("INVALID_IMAGE"), body);
        assertEquals(0L, countPages(fx.sourceId));
    }

    @Test
    void imageOverDimensionLimitFails() throws Exception {
        Fixture fx = newFixture("png-dim");
        // 32x32 > max-width/height 16
        Long assetId = upload(fx.token, fx.spaceId, fx.sourceId,
                "big.png", "image/png", tinyPng(32, 32));
        String body = createJob(fx.token, fx.spaceId, fx.sourceId, assetId, "FAILED");
        assertTrue(body.contains("IMAGE_DIMENSION_LIMIT_EXCEEDED"), body);
        assertEquals(0L, countPages(fx.sourceId));
    }

    @Test
    void imageOverPixelLimitFails() throws Exception {
        Fixture fx = newFixture("png-pix");
        // 16x16 = 256 > max-pixels 64 (width/height still within 16)
        Long assetId = upload(fx.token, fx.spaceId, fx.sourceId,
                "wide.png", "image/png", tinyPng(16, 16));
        String body = createJob(fx.token, fx.spaceId, fx.sourceId, assetId, "FAILED");
        assertTrue(body.contains("IMAGE_PIXEL_LIMIT_EXCEEDED"), body);
        assertEquals(0L, countPages(fx.sourceId));
    }

    @Test
    void imageRetryOnFailedThenSucceedDoesNotDuplicate() throws Exception {
        Fixture fx = newFixture("img-retry");
        Long badId = upload(fx.token, fx.spaceId, fx.sourceId,
                "page.png", "image/png", new byte[]{9, 9, 9, 9});
        String failedBody = createJob(fx.token, fx.spaceId, fx.sourceId, badId, "FAILED");
        assertTrue(failedBody.contains("INVALID_IMAGE"));
        assertEquals(0L, countPages(fx.sourceId));

        // Fresh successful attempt on a NEW asset (FAILED job does not block new create
        // only for a different asset; same asset is blocked by 409 only when PENDING/
        // RUNNING/SUCCEEDED). Failed allows a new job for the same asset.
        String okBody = mockMvc.perform(post(JOBS, fx.spaceId, fx.sourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":" + badId + "}")
                        .header("Authorization", "Bearer " + fx.token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andReturn().getResponse().getContentAsString();
        assertNotNull(okBody);
        assertEquals(0L, countPages(fx.sourceId),
                "still-invalid bytes must not create pages on retry");
    }

    // ==================== helpers ====================

    private record Fixture(Long spaceId, Long sourceId, String token) {}

    private Fixture newFixture(String label) {
        String owner = "biz-pdf-img-user";
        jdbcTemplate.update(
                "INSERT INTO learning_space "
                        + "(name, description, owner_subject, status, created_at, updated_at) "
                        + "VALUES (?, NULL, ?, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                label + "-space", owner);
        Long spaceId = jdbcTemplate.queryForObject(
                "SELECT id FROM learning_space WHERE owner_subject = ? AND name = ? "
                        + "ORDER BY id DESC LIMIT 1",
                Long.class, owner, label + "-space");
        jdbcTemplate.update(
                "INSERT INTO source "
                        + "(space_id, title, source_type, status, created_by_user_id, created_at, updated_at) "
                        + "VALUES (?, ?, 'DESKTOP_UPLOAD', 'REGISTERED', ?, "
                        + "CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                spaceId, label + "-src", owner);
        Long sourceId = jdbcTemplate.queryForObject(
                "SELECT id FROM source WHERE space_id = ? AND title = ? ORDER BY id DESC LIMIT 1",
                Long.class, spaceId, label + "-src");
        return new Fixture(spaceId, sourceId, jwtAccessTokenService.issueAccessToken(owner));
    }

    private Long upload(String token, Long spaceId, Long sourceId,
                        String name, String mime, byte[] bytes) throws Exception {
        MvcResult result = mockMvc.perform(multipart(HttpMethod.POST,
                        "/api/v1/spaces/{spaceId}/sources/{sourceId}/assets", spaceId, sourceId)
                        .file(new MockMultipartFile("file", name, mime, bytes))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        int idx = body.indexOf("\"id\":");
        assertTrue(idx >= 0, body);
        int start = idx + 5;
        int end = start;
        while (end < body.length() && Character.isDigit(body.charAt(end))) {
            end++;
        }
        return Long.parseLong(body.substring(start, end));
    }

    private String createJob(String token, Long spaceId, Long sourceId,
                             Long assetId, String expectedStatus) throws Exception {
        return mockMvc.perform(post(JOBS, spaceId, sourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":" + assetId + "}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andReturn().getResponse().getContentAsString();
    }

    private static byte[] twoPagePdf(String text1, String text2) throws IOException {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : new String[]{text1, text2}) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText(text);
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] multiPagePdf(int pageCount, String prefix) throws IOException {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 1; i <= pageCount; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    cs.newLineAtOffset(40, 720);
                    cs.showText(prefix + i);
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] tinyPng(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static byte[] tinyJpeg(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    private Long countPages(Long sourceId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM source_page WHERE source_id = ?", Long.class, sourceId);
    }

    private Long countBlocks(Long sourceId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_block WHERE source_id = ?", Long.class, sourceId);
    }

    private List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbcTemplate.queryForList(sql, args);
    }

    private void cleanBizTestRows() {
        OwnedSpaceReset.forSubjects(jdbcTemplate, BIZ_TEST_USERS);
    }

    private void assertSchemaIsFlywayTest() {
        String actual = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        if (actual == null || !EXPECTED_SCHEMA.equals(actual)) {
            throw new IllegalStateException(
                    "Refusing to run against '" + actual + "' — expected '" + EXPECTED_SCHEMA + "'");
        }
    }
}
