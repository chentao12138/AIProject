package com.aistudy.server.knowledge.category.service;

import com.aistudy.server.knowledge.category.dto.CreateKnowledgeCategoryRequest;
import com.aistudy.server.knowledge.category.dto.UpdateKnowledgeCategoryRequest;
import com.aistudy.server.knowledge.category.entity.KnowledgeCategory;
import com.aistudy.server.knowledge.category.mapper.KnowledgeCategoryMapper;
import com.aistudy.server.space.service.LearningSpaceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * BUSINESS-003 — application service for KnowledgeCategory.
 *
 * <p>The ONLY caller of {@link KnowledgeCategoryMapper}. Parent-space
 * ownership is proven via {@link LearningSpaceService#getMine}
 * (owner-scoped id+owner query); the category read queries are
 * themselves owner-scoped SQL so no future caller can bypass the
 * boundary.
 *
 * <h3>Parent same-space invariant</h3>
 *
 * <p>When {@code parentId != null}, the parent must exist, belong to
 * the SAME {@code spaceId}, and that space must belong to the current
 * owner. The check uses the owner-scoped
 * {@link KnowledgeCategoryMapper#selectByIdAndSpaceAndOwner} — a
 * parentId pointing at another space (or another owner) returns
 * {@code null} → 404. This prevents Space A category being parented
 * to a Space B category.
 *
 * <h3>Lifecycle</h3>
 *
 * <p>Categories support rename, reparent, reorder, and soft-delete.
 * A category with children cannot be deleted (403). The same-space
 * invariant is re-verified on reparent.
 */
@Service
public class KnowledgeCategoryService {

    private final KnowledgeCategoryMapper knowledgeCategoryMapper;
    private final LearningSpaceService learningSpaceService;

    public KnowledgeCategoryService(KnowledgeCategoryMapper knowledgeCategoryMapper,
                                    LearningSpaceService learningSpaceService) {
        this.knowledgeCategoryMapper = knowledgeCategoryMapper;
        this.learningSpaceService = learningSpaceService;
    }

    @Transactional
    public KnowledgeCategory create(String ownerSubject,
                                    Long spaceId,
                                    CreateKnowledgeCategoryRequest request) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }

        if (request.parentId() != null
                && knowledgeCategoryMapper.selectByIdAndSpaceAndOwner(
                        request.parentId(), spaceId, ownerSubject) == null) {
            return null;
        }

        LocalDateTime now = LocalDateTime.now();

        KnowledgeCategory category = new KnowledgeCategory();
        category.setSpaceId(spaceId);
        category.setParentId(request.parentId());
        category.setName(request.name());
        category.setDescription(request.description());
        category.setSortOrder(request.sortOrder() != null ? request.sortOrder() : 0);
        category.setCreatedAt(now);
        category.setUpdatedAt(now);

        knowledgeCategoryMapper.insert(category);
        return category;
    }

    public List<KnowledgeCategory> listMine(String ownerSubject, Long spaceId) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }
        return knowledgeCategoryMapper.selectBySpaceAndOwner(spaceId, ownerSubject);
    }

    public KnowledgeCategory getMine(String ownerSubject, Long spaceId, Long categoryId) {
        return knowledgeCategoryMapper.selectByIdAndSpaceAndOwner(categoryId, spaceId, ownerSubject);
    }

    @Transactional
    public KnowledgeCategory update(String ownerSubject, Long spaceId, Long categoryId,
                                    UpdateKnowledgeCategoryRequest request) {
        KnowledgeCategory existing = getMine(ownerSubject, spaceId, categoryId);
        if (existing == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = knowledgeCategoryMapper.updateDetailsByIdAndSpace(
                categoryId, spaceId,
                request.name(), request.description(), request.sortOrder(), now);
        if (updated == 0) {
            return null;
        }
        existing.setName(request.name());
        existing.setDescription(request.description());
        existing.setSortOrder(request.sortOrder());
        existing.setUpdatedAt(now);
        return existing;
    }

    @Transactional
    public KnowledgeCategory reparent(String ownerSubject, Long spaceId, Long categoryId, Long newParentId) {
        KnowledgeCategory existing = getMine(ownerSubject, spaceId, categoryId);
        if (existing == null) {
            return null;
        }
        if (newParentId != null) {
            if (newParentId.equals(categoryId)) {
                return null;
            }
            KnowledgeCategory newParent = knowledgeCategoryMapper.selectByIdAndSpaceAndOwner(newParentId, spaceId, ownerSubject);
            if (newParent == null) {
                return null;
            }
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = knowledgeCategoryMapper.reparentByIdAndSpace(categoryId, spaceId, newParentId, now);
        if (updated == 0) {
            return null;
        }
        existing.setParentId(newParentId);
        existing.setUpdatedAt(now);
        return existing;
    }

    @Transactional
    public KnowledgeCategory reorder(String ownerSubject, Long spaceId, Long categoryId, Integer newSortOrder) {
        KnowledgeCategory existing = getMine(ownerSubject, spaceId, categoryId);
        if (existing == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = knowledgeCategoryMapper.updateSortOrderByIdAndSpace(categoryId, spaceId, newSortOrder, now);
        if (updated == 0) {
            return null;
        }
        existing.setSortOrder(newSortOrder);
        existing.setUpdatedAt(now);
        return existing;
    }

    @Transactional
    public void delete(String ownerSubject, Long spaceId, Long categoryId) {
        KnowledgeCategory existing = getMine(ownerSubject, spaceId, categoryId);
        if (existing == null) {
            return;
        }
        long childCount = knowledgeCategoryMapper.countByParentId(categoryId);
        if (childCount > 0) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "Cannot delete a category that still has children");
        }
        LocalDateTime now = LocalDateTime.now();
        knowledgeCategoryMapper.softDeleteByIdAndSpace(categoryId, spaceId, now);
    }
}
