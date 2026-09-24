package com.aistudy.server.admin.bulk.service;

import com.aistudy.server.knowledge.point.mapper.KnowledgePointMapper;
import com.aistudy.server.question.entity.Question;
import com.aistudy.server.question.mapper.QuestionMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * §9.7 — bounded bulk operation utilities.
 *
 * <p>Max batch size is 100. Each operation validates items individually
 * and returns partial results (success/failure per item). No all-or-nothing
 * semantics — callers must handle partial results.
 */
@Service
public class BulkOperationService {

    public static final int MAX_BATCH_SIZE = 100;

    public static BulkResult bulkPublishKnowledgePoints(KnowledgePointMapper mapper, List<Long> kpIds) {
        return executeBulk(kpIds, MAX_BATCH_SIZE, id -> {
            int updated = mapper.publishById(id, "PUBLISHED",
                    LocalDateTime.now().truncatedTo(ChronoUnit.MICROS),
                    LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
            return updated > 0;
        });
    }

    public static BulkResult bulkArchiveKnowledgePoints(KnowledgePointMapper mapper, List<Long> kpIds) {
        return executeBulk(kpIds, MAX_BATCH_SIZE, id -> {
            int updated = mapper.archiveById(id, "ARCHIVED",
                    LocalDateTime.now().truncatedTo(ChronoUnit.MICROS),
                    LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
            return updated > 0;
        });
    }

    public static BulkResult bulkPublishQuestions(QuestionMapper mapper, List<Long> questionIds) {
        return executeBulk(questionIds, MAX_BATCH_SIZE, id -> {
            Question q = mapper.selectByIdAdmin(id);
            if (q == null) {
                return false;
            }
            int updated = mapper.publishByIdAndSpace(id, q.getSpaceId(), "PUBLISHED",
                    LocalDateTime.now().truncatedTo(ChronoUnit.MICROS),
                    LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
            return updated > 0;
        });
    }

    public static BulkResult bulkArchiveQuestions(QuestionMapper mapper, List<Long> questionIds) {
        return executeBulk(questionIds, MAX_BATCH_SIZE, id -> {
            Question q = mapper.selectByIdAdmin(id);
            if (q == null) {
                return false;
            }
            int updated = mapper.archiveByIdAndSpace(id, q.getSpaceId(), "ARCHIVED",
                    LocalDateTime.now().truncatedTo(ChronoUnit.MICROS),
                    LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
            return updated > 0;
        });
    }

    public static BulkResult executeBulk(List<Long> ids, int maxBatch, BulkAction action) {
        if (ids == null || ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id list must not be empty");
        }
        if (ids.size() > maxBatch) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "batch size must be at most " + maxBatch);
        }
        Set<Long> seen = new HashSet<>();
        List<String> errors = new ArrayList<>();
        int success = 0;
        for (Long id : ids) {
            if (id == null) {
                errors.add("null id");
                continue;
            }
            if (!seen.add(id)) {
                errors.add("duplicate id: " + id);
                continue;
            }
            try {
                if (action.apply(id)) {
                    success++;
                } else {
                    errors.add("not found: " + id);
                }
            } catch (Exception e) {
                errors.add("id=" + id + ": " + e.getMessage());
            }
        }
        return new BulkResult(ids.size(), success, ids.size() - success - (ids.size() - success - errors.size()), errors);
    }

    @FunctionalInterface
    public interface BulkAction {
        boolean apply(Long id);
    }

    public record BulkResult(int total, int success, int failed, List<String> errors) {
    }
}
