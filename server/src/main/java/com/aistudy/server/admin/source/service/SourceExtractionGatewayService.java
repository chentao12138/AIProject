package com.aistudy.server.admin.source.service;

import com.aistudy.server.common.problem.ApiErrorCodes;
import com.aistudy.server.common.problem.ApiException;
import com.aistudy.server.ingestion.extract.IngestionParseException;
import com.aistudy.server.ingestion.extract.TextMarkdownContentParser;
import com.aistudy.server.ingestion.issue.service.IngestionIssueService;
import com.aistudy.server.ingestion.job.service.IngestionErrorCode;
import com.aistudy.server.ingestion.job.service.IngestionRevisionPublisher;
import com.aistudy.server.ingestion.revision.entity.ExtractionRevision;
import com.aistudy.server.ingestion.revision.service.ExtractionRevisionService;
import com.aistudy.server.source.content.service.SourceExtractionGateway;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * ADMIN-lane implementation of {@link SourceExtractionGateway}.
 *
 * <p>It lives in {@code admin.source} on purpose: the Source module may not
 * import {@code ingestion} (that would add the {@code source <-> ingestion}
 * module cycle {@code ModuleBoundaryTest} rejects), while the ADMIN
 * governance lane already depends on both. Governance is also the only lane
 * allowed to reach into the extraction pipeline on someone else's behalf, so
 * putting the manual-body write here keeps the dependency direction
 * documented rather than merely tolerated.
 *
 * <p>Nothing here re-implements extraction. A typed body is handed to the
 * same {@link TextMarkdownContentParser} that ingests uploaded
 * TXT/Markdown assets, so block structure, UTF-8 byte bounding, locator
 * provenance and the configured document-size limit are identical for both
 * channels by construction — a second parser would drift on the first
 * hotfix (F-04).
 */
@Service
public class SourceExtractionGatewayService implements SourceExtractionGateway {

    /** Stable marker for pages produced from a typed body instead of a file. */
    private static final String MANUAL_ASSET_NAME = "manual-entry";

    private final TextMarkdownContentParser textMarkdownContentParser;
    private final ExtractionRevisionService extractionRevisionService;
    private final IngestionRevisionPublisher revisionPublisher;
    private final IngestionIssueService ingestionIssueService;

    public SourceExtractionGatewayService(TextMarkdownContentParser textMarkdownContentParser,
                                          ExtractionRevisionService extractionRevisionService,
                                          IngestionRevisionPublisher revisionPublisher,
                                          IngestionIssueService ingestionIssueService) {
        this.textMarkdownContentParser = textMarkdownContentParser;
        this.extractionRevisionService = extractionRevisionService;
        this.revisionPublisher = revisionPublisher;
        this.ingestionIssueService = ingestionIssueService;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Order matters: the source row must exist before the revision FK, the
     * revision must exist before extraction stamps it, and the publisher runs
     * last so a failure anywhere below never leaves
     * {@code current_extraction_revision_id} pointing at unfinished rows.
     */
    @Override
    @Transactional
    public Long createManualBodyRevision(Long spaceId, Long sourceId, String title,
                                        String content, boolean markdown, LocalDateTime at) {
        ExtractionRevision template = new ExtractionRevision();
        template.setExtractorVersion(markdown
                ? EXTRACTOR_MANUAL_MARKDOWN
                : EXTRACTOR_MANUAL_TEXT);
        ExtractionRevision revision = extractionRevisionService
                .createForVerifiedSource(spaceId, sourceId, template);
        if (revision == null || revision.getId() == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCodes.INTERNAL_ERROR,
                    "无法为手工正文创建抽取版本。");
        }

        try {
            textMarkdownContentParser.extract(
                    new TextBodyFile("file", displayName(title, markdown),
                            markdown ? "text/markdown" : "text/plain",
                            content.getBytes(StandardCharsets.UTF_8)),
                    spaceId, sourceId, null);
        } catch (IngestionParseException ex) {
            throw translate(ex);
        }

        revisionPublisher.publish(spaceId, sourceId, revision.getId(), at);
        return revision.getId();
    }

    @Override
    public int countUnresolvedBlockerIssues(Long spaceId, Long sourceId) {
        return ingestionIssueService.countUnresolvedBlockers(spaceId, sourceId);
    }

    /**
     * An ingestion content failure is a client problem on this path, not a
     * pipeline problem: there is no job to mark FAILED and no asset to retry,
     * so the typed code becomes the HTTP status the reviewer can act on.
     */
    private static ApiException translate(IngestionParseException ex) {
        if (ex.errorCode() == IngestionErrorCode.DOCUMENT_TOO_LARGE) {
            return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "SOURCE_BODY_TOO_LARGE",
                    "手工正文超出文本摄取大小限制。", ex);
        }
        return new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCodes.VALIDATION_ERROR,
                "手工正文无法解析。", ex);
    }

    /**
     * The parser picks TXT vs Markdown from the file name (and content type),
     * so the pseudo file carries the extension even though nothing is stored.
     * The title is kept only as a readable hint and stripped of anything that
     * is not a plain word character.
     */
    private static String displayName(String title, boolean markdown) {
        String slug = title == null ? "" : title
                .replaceAll("[^\\p{IsAlnum}\\p{IsHan}]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.isBlank()) {
            slug = MANUAL_ASSET_NAME;
        }
        if (slug.length() > 60) {
            slug = slug.substring(0, 60);
        }
        return slug + (markdown ? ".md" : ".txt");
    }

    /**
     * In-memory {@link MultipartFile} over an already decoded body. The
     * ingestion services speak {@code MultipartFile}, and the pipeline builds
     * the same kind of shim when it re-reads a stored asset
     * ({@code IngestionJobService.multipartFromAsset}), so reusing the
     * extractor needs no second extraction API.
     */
    private record TextBodyFile(String name, String originalFilename,
                               String contentType, byte[] raw) implements MultipartFile {

        private TextBodyFile {
            raw = raw.clone();
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getOriginalFilename() {
            return originalFilename;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public boolean isEmpty() {
            return raw.length == 0;
        }

        @Override
        public long getSize() {
            return raw.length;
        }

        @Override
        public byte[] getBytes() {
            return raw.clone();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(raw);
        }

        @Override
        public void transferTo(File dest) throws IOException {
            try (OutputStream out = new FileOutputStream(dest)) {
                out.write(raw);
            }
        }

        @Override
        public boolean equals(Object other) {
            return this == other;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }
}
