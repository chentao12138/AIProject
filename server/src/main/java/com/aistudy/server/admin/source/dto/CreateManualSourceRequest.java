package com.aistudy.server.admin.source.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/admin/sources/manual} — an ADMIN typing
 * a document by hand instead of uploading a file (checklist §2.4).
 *
 * <p>Deliberately carries no {@code spaceId} (it is a query parameter, so the
 * route states which space is written) and no {@code sourceType} or
 * {@code status}: an ADMIN_MANUAL source is always created in
 * {@code NEEDS_REVIEW}. Content is required, because a manual source without
 * a body is precisely the F-04 defect this endpoint replaces — the old path
 * accepted a title and stored nothing a learner could read.
 *
 * @param title   display title, matching V005 {@code source.title VARCHAR(255)}
 * @param content the body text; parsed into one page plus ordered content
 *                blocks, and size-checked against the shared text-ingestion
 *                limit rather than a second hand-written ceiling
 * @param format  which parser the body goes through; {@code MARKDOWN} (the
 *                default, so headings/lists/tables become distinct block types
 *                that provenance can cite) or {@code TEXT} for literal
 *                paragraphs
 */
public record CreateManualSourceRequest(
        @NotBlank(message = "title must not be blank")
        @Size(max = 255, message = "title must be at most 255 characters")
        String title,

        @NotBlank(message = "content must not be blank")
        String content,

        @Pattern(regexp = "MARKDOWN|TEXT",
                message = "format must be MARKDOWN or TEXT")
        String format
) {

    public static final String FORMAT_MARKDOWN = "MARKDOWN";
    public static final String FORMAT_TEXT = "TEXT";

    /** @return {@code true} unless {@code format} explicitly asked for TEXT */
    public boolean markdown() {
        return !FORMAT_TEXT.equals(format);
    }
}
