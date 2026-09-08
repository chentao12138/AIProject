package com.aistudy.server.mastery.service;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * BUSINESS-014 — evidence timestamp normalization + lastEvidenceAt
 * ordering (RUNTIME-FIX-03 ROOT CAUSE A).
 *
 * <p>The evidence queries project {@code MAX(...) AS last_at} from a
 * {@code DATETIME(6)} column into a {@code Map<String, Object>}. The
 * MyBatis/JDBC stack actually in force returns a {@code LocalDateTime}
 * there, while the previous code hard-cast to {@code java.sql.Timestamp}
 * and threw {@code ClassCastException} at runtime. These tests pin both
 * accepted runtime types, the empty-projection {@code null}, the loud
 * failure for anything else, and the max(practice, exam, review)
 * semantics of {@code lastEvidenceAt} — which must not change.
 *
 * <p>Pure unit tests: no mocking, no Spring.
 */
class MasteryEvidenceTimestampTest {

    // ==================== asLocalDateTime ====================

    /** (1) empty evidence projection (no rows) → null, not an exception. */
    @Test
    void nullTimestampStaysNull() {
        assertNull(MasteryService.asLocalDateTime(null));
    }

    /** (2) the type the stack really returns — passed through unchanged. */
    @Test
    void localDateTimeIsPassedThrough() {
        LocalDateTime value = LocalDateTime.of(2026, 9, 7, 10, 11, 12, 345678);
        assertSame(value, MasteryService.asLocalDateTime(value));
        assertEquals(value, MasteryService.asLocalDateTime(value));
    }

    /** (3) Timestamp still accepted, converted without string round-trip. */
    @Test
    void sqlTimestampIsConverted() {
        LocalDateTime value = LocalDateTime.of(2026, 9, 7, 10, 11, 12, 345678);
        Timestamp timestamp = Timestamp.valueOf(value);
        assertEquals(value, MasteryService.asLocalDateTime(timestamp));
    }

    /** (4) both accepted types produce the same instant — no drift. */
    @Test
    void bothRuntimeTypesAgree() {
        LocalDateTime value = LocalDateTime.of(2025, 1, 2, 3, 4, 5, 100);
        assertEquals(MasteryService.asLocalDateTime(value),
                MasteryService.asLocalDateTime(Timestamp.valueOf(value)));
    }

    /** (5) an unexpected wrapper type fails loudly, never silently coerced. */
    @Test
    void unsupportedTypeFailsLoudly() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> MasteryService.asLocalDateTime("2026-09-07 10:11:12"));
        assertEquals("Unsupported mastery evidence timestamp type: java.lang.String",
                ex.getMessage());
    }

    // ==================== latestEvidenceAt ====================

    /** (6) all empty → null (no evidence at all). */
    @Test
    void allEmptyIsNull() {
        assertNull(MasteryService.latestEvidenceAt(null, null, null));
    }

    /** (7) max wins regardless of argument order. */
    @Test
    void latestWinsOutOfOrder() {
        LocalDateTime practice = LocalDateTime.of(2026, 9, 1, 0, 0);
        LocalDateTime exam = LocalDateTime.of(2026, 9, 3, 0, 0);
        LocalDateTime review = LocalDateTime.of(2026, 9, 2, 0, 0);
        assertEquals(exam, MasteryService.latestEvidenceAt(practice, exam, review));
        assertEquals(exam, MasteryService.latestEvidenceAt(review, practice, exam));
        assertEquals(exam, MasteryService.latestEvidenceAt(exam, review, practice));
    }

    /** (8) review completion participates in the timestamp. */
    @Test
    void reviewCompletionParticipatesInLastEvidenceAt() {
        LocalDateTime practice = LocalDateTime.of(2026, 9, 1, 0, 0);
        LocalDateTime review = LocalDateTime.of(2026, 9, 9, 0, 0);
        assertEquals(review, MasteryService.latestEvidenceAt(practice, null, review));
    }

    /** (9) an empty source never blanks out the others'. */
    @Test
    void emptySourceNeverBlanksotherSources() {
        LocalDateTime exam = LocalDateTime.of(2026, 9, 5, 0, 0);
        assertEquals(exam, MasteryService.latestEvidenceAt(null, exam, null));
        LocalDateTime practice = LocalDateTime.of(2026, 9, 4, 0, 0);
        assertEquals(practice, MasteryService.latestEvidenceAt(practice, null, null));
    }

    /** (10) the full chain: normalize each source, then take the max. */
    @Test
    void normalizedSourcesYieldSameMaxAsBefore() {
        LocalDateTime practice = LocalDateTime.of(2026, 9, 1, 12, 0);
        LocalDateTime exam = LocalDateTime.of(2026, 9, 4, 12, 0);
        LocalDateTime review = LocalDateTime.of(2026, 9, 2, 12, 0);
        LocalDateTime max = MasteryService.latestEvidenceAt(
                MasteryService.asLocalDateTime(practice),
                MasteryService.asLocalDateTime(Timestamp.valueOf(exam)),
                MasteryService.asLocalDateTime(Timestamp.valueOf(review)));
        assertEquals(exam, max);
    }
}
