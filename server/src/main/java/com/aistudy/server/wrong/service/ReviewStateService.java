package com.aistudy.server.wrong.service;

import com.aistudy.server.wrong.entity.ReviewState;
import com.aistudy.server.wrong.mapper.ReviewStateMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * BUSINESS-017 — spaced-repetition state management for review targets.
 *
 * <p>Wraps {@link ReviewStateMapper} and applies the SM-2-compatible
 * policy from {@link ReviewSchedulePolicy} whenever a review task is
 * completed. MANUAL tasks (reason=MANUAL) never update the state.
 */
@Service
public class ReviewStateService {

    private static final int DEFAULT_POLICY_VERSION = 1;

    private final ReviewStateMapper reviewStateMapper;

    public ReviewStateService(ReviewStateMapper reviewStateMapper) {
        this.reviewStateMapper = reviewStateMapper;
    }

    /**
     * Returns the current state for a target, or null when absent.
     */
    public ReviewState getState(String ownerSubject, Long spaceId,
                                String targetType, Long targetId) {
        return reviewStateMapper.selectByUserSpaceTarget(
                ownerSubject, spaceId, targetType, targetId);
    }

    /** Overrides the nextDueAt of an existing state (used by MANUAL triggers). */
    @Transactional
    public ReviewState overrideDue(String ownerSubject, Long spaceId,
                                   String targetType, Long targetId,
                                   LocalDateTime newDueAt) {
        ReviewState existing = getState(ownerSubject, spaceId, targetType, targetId);
        if (existing == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int updated = reviewStateMapper.upsert(
                existing.getId(),
                spaceId, ownerSubject, targetType, targetId,
                existing.getEaseFactor(), existing.getIntervalDays(),
                existing.getRepetitions(), existing.getPolicyVersion(),
                newDueAt, existing.getCreatedAt(), now);
        if (updated == 0) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "review state changed concurrently");
        }
        existing.setNextDueAt(newDueAt);
        existing.setUpdatedAt(now);
        return existing;
    }

    /**
     * Called after a review task completion to update the spaced-repetition state.
     * MANUAL tasks (reason=MANUAL) leave the state untouched.
     *
     * @return the updated ReviewState (or null when absent + quality < 3)
     */
    @Transactional
    public ReviewState applyCompletion(String ownerSubject, Long spaceId,
                                       String targetType, Long targetId,
                                       int quality, String reason) {
        if (ReviewSchedulePolicy.REASON_MANUAL.equals(reason)) {
            return getState(ownerSubject, spaceId, targetType, targetId);
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        ReviewState existing = getState(ownerSubject, spaceId, targetType, targetId);

        ReviewSchedulePolicy.Sm2Result result;
        if (existing == null) {
            result = ReviewSchedulePolicy.sm2Initial(quality);
        } else {
            result = ReviewSchedulePolicy.sm2Advance(
                    quality, existing.getRepetitions(), existing.getEaseFactor(),
                    existing.getIntervalDays() != null ? existing.getIntervalDays() : 1);
        }

        int newRepetitions = result.repetitions();
        LocalDateTime nextDue = result.intervalDays() <= 0
                ? now.plusDays(1)
                : now.plusDays(result.intervalDays());

        if (existing == null) {
            ReviewState state = new ReviewState();
            state.setUserSubject(ownerSubject);
            state.setSpaceId(spaceId);
            state.setTargetType(targetType);
            state.setTargetId(targetId);
            state.setEaseFactor(result.easeFactor());
            state.setIntervalDays(result.intervalDays());
            state.setRepetitions(newRepetitions);
            state.setPolicyVersion(DEFAULT_POLICY_VERSION);
            state.setNextDueAt(nextDue);
            state.setCreatedAt(now);
            state.setUpdatedAt(now);
            reviewStateMapper.upsert(
                    null,
                    spaceId, ownerSubject, targetType, targetId,
                    result.easeFactor(), result.intervalDays(), newRepetitions,
                    DEFAULT_POLICY_VERSION, nextDue, now, now);
            return state;
        }

        int updated = reviewStateMapper.upsert(
                existing.getId(),
                spaceId, ownerSubject, targetType, targetId,
                result.easeFactor(), result.intervalDays(), newRepetitions,
                DEFAULT_POLICY_VERSION, nextDue, existing.getCreatedAt(), now);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "review state changed concurrently");
        }
        existing.setEaseFactor(result.easeFactor());
        existing.setIntervalDays(result.intervalDays());
        existing.setRepetitions(newRepetitions);
        existing.setNextDueAt(nextDue);
        existing.setUpdatedAt(now);
        return existing;
    }
}
