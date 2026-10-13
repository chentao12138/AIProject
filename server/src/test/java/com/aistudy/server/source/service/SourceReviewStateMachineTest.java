package com.aistudy.server.source.service;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-05 / D-06 / I-11 — the review state machine, as a plain unit test.
 *
 * <p>Deliberately database-free: the matrix is pure logic, so it runs in
 * {@code mvn test} on a laptop with no MySQL. The HTTP-level behaviour (400 on
 * a non-outcome, 409 on a skip-level move, 409 on an unresolved blocker) is
 * covered by {@code SourceReviewGateIntegrationTest}; what is pinned here is
 * the rule table itself, which is the part a reviewer can otherwise change
 * without noticing that a documented edge moved.
 */
class SourceReviewStateMachineTest {

    /** Every documented source status has a matrix row — no status is an accident. */
    @Test
    void matrixCoversEverySourceStatus() {
        assertThat(SourceService.REVIEW_TRANSITIONS.keySet()).containsExactlyInAnyOrder(
                SourceService.STATUS_REGISTERED,
                SourceService.STATUS_PROCESSING,
                SourceService.STATUS_NEEDS_REVIEW,
                SourceService.STATUS_PUBLISHED,
                SourceService.STATUS_ARCHIVED,
                SourceService.STATUS_REJECTED);
    }

    /** The review vocabulary is closed and is a subset of the status vocabulary. */
    @Test
    void reviewOutcomesAreTheClosedSubsetOfStatuses() {
        assertThat(SourceService.REVIEW_OUTCOMES).containsExactlyInAnyOrder(
                SourceService.STATUS_NEEDS_REVIEW,
                SourceService.STATUS_PUBLISHED,
                SourceService.STATUS_REJECTED);
    }

    /**
     * The reported defect: nothing extracted, nothing read, and a caller asked
     * for PUBLISHED anyway. Same for a source still inside extraction.
     */
    @Test
    void publishingIsNeverReachableWithoutAReviewStepFirst() {
        assertThat(SourceService.isLegalReviewTransition(
                SourceService.STATUS_REGISTERED, SourceService.STATUS_PUBLISHED)).isFalse();
        assertThat(SourceService.isLegalReviewTransition(
                SourceService.STATUS_PROCESSING, SourceService.STATUS_PUBLISHED)).isFalse();
    }

    @Test
    void reviewIsAlwaysTheNextStepAfterRegistrationOrProcessing() {
        assertThat(SourceService.isLegalReviewTransition(
                SourceService.STATUS_REGISTERED, SourceService.STATUS_NEEDS_REVIEW)).isTrue();
        assertThat(SourceService.isLegalReviewTransition(
                SourceService.STATUS_PROCESSING, SourceService.STATUS_NEEDS_REVIEW)).isTrue();
        assertThat(SourceService.isLegalReviewTransition(
                SourceService.STATUS_PROCESSING, SourceService.STATUS_REJECTED)).isTrue();
    }

    /** The decision point accepts both verdicts and a re-review. */
    @Test
    void needsReviewIsTheDecisionPoint() {
        assertThat(SourceService.isLegalReviewTransition(
                SourceService.STATUS_NEEDS_REVIEW, SourceService.STATUS_PUBLISHED)).isTrue();
        assertThat(SourceService.isLegalReviewTransition(
                SourceService.STATUS_NEEDS_REVIEW, SourceService.STATUS_REJECTED)).isTrue();
        assertThat(SourceService.isLegalReviewTransition(
                SourceService.STATUS_NEEDS_REVIEW, SourceService.STATUS_NEEDS_REVIEW)).isTrue();
    }

    /**
     * Documented choice: re-publishing an already published source is an
     * idempotent success, not a conflict — the review screen re-sends
     * PUBLISHED after every content correction and after a double click.
     */
    @Test
    void republishIsAnIdempotentLegalMove() {
        assertThat(SourceService.isLegalReviewTransition(
                SourceService.STATUS_PUBLISHED, SourceService.STATUS_PUBLISHED)).isTrue();
        // ... and live content can still be pulled back or refused.
        assertThat(SourceService.isLegalReviewTransition(
                SourceService.STATUS_PUBLISHED, SourceService.STATUS_REJECTED)).isTrue();
        assertThat(SourceService.isLegalReviewTransition(
                SourceService.STATUS_PUBLISHED, SourceService.STATUS_NEEDS_REVIEW)).isTrue();
    }

    /** ARCHIVED is exited through restore() only; REJECTED only through review. */
    @Test
    void archivedIsTerminalForReviewAndRejectedNeedsAFreshReview() {
        assertThat(SourceService.REVIEW_TRANSITIONS.get(SourceService.STATUS_ARCHIVED)).isEmpty();
        for (String outcome : Set.of(SourceService.STATUS_NEEDS_REVIEW,
                SourceService.STATUS_PUBLISHED, SourceService.STATUS_REJECTED)) {
            assertThat(SourceService.isLegalReviewTransition(
                    SourceService.STATUS_ARCHIVED, outcome))
                    .as("archived sources must be restored before any review")
                    .isFalse();
        }

        assertThat(SourceService.isLegalReviewTransition(
                SourceService.STATUS_REJECTED, SourceService.STATUS_NEEDS_REVIEW)).isTrue();
        assertThat(SourceService.isLegalReviewTransition(
                SourceService.STATUS_REJECTED, SourceService.STATUS_PUBLISHED))
                .as("approving straight from REJECTED would keep a reason on live content")
                .isFalse();
    }

    /**
     * Statuses that are pipeline facts or lifecycle verbs are not review
     * outcomes, and an unknown/invented value is never a legal move from
     * anywhere — the shape that stops a caller writing {@code "APPROVED"}.
     */
    @Test
    void onlyThreeValuesAreOutcomesAtAll() {
        for (String notAnOutcome : java.util.Arrays.asList(SourceService.STATUS_REGISTERED,
                SourceService.STATUS_PROCESSING, SourceService.STATUS_ARCHIVED,
                "APPROVED", "published", "", null)) {
            assertThat(SourceService.isReviewOutcome(notAnOutcome))
                    .as("%s is not a review outcome", notAnOutcome)
                    .isFalse();
            assertThat(SourceService.isLegalReviewTransition(
                    SourceService.STATUS_NEEDS_REVIEW, notAnOutcome)).isFalse();
        }
    }

    /** Unknown current status (legacy or invented row) has no legal outcome. */
    @Test
    void unknownCurrentStatusHasNoLegalOutcome() {
        assertThat(SourceService.isLegalReviewTransition("SOMETHING_ELSE",
                SourceService.STATUS_PUBLISHED)).isFalse();
        assertThat(SourceService.isLegalReviewTransition(null,
                SourceService.STATUS_REJECTED)).isFalse();
    }

    /**
     * The matrix is read by other lanes (the report documents it as the
     * contract), so it must stay unmodifiable — a stray {@code put} from a
     * request thread would rewrite the rules for every later caller.
     */
    @Test
    void matrixAndOutcomeSetAreUnmodifiable() {
        assertThat(Set.copyOf(SourceService.REVIEW_TRANSITIONS
                .get(SourceService.STATUS_NEEDS_REVIEW)))
                .isEqualTo(SourceService.REVIEW_TRANSITIONS.get(SourceService.STATUS_NEEDS_REVIEW));
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> SourceService.REVIEW_TRANSITIONS.put("NEW", Set.of()));
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> SourceService.REVIEW_OUTCOMES.add("NEW"));
    }
}
