package com.aistudy.server.knowledge.category.service;

import com.aistudy.server.knowledge.category.dto.CreateKnowledgeCategoryRequest;
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
 * <h3>Null/empty contract (controller maps to HTTP)</h3>
 *
 * <ul>
 *   <li>{@link #create} returns {@code null} when the parent space is
 *       not owned OR the parent category is invalid → 404.</li>
 *   <li>{@link #listMine} returns {@code null} when the space is not
 *       owned → 404; empty list when owned but no categories.</li>
 *   <li>{@link #getMine} returns {@code null} → 404.</li>
 * </ul>
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

    /**
     * Creates a category inside the caller's own LearningSpace,
     * optionally as a child of an existing category of the SAME
     * space.
     *
     * @return persisted category, or {@code null} when space not
     *         owned / parent invalid (404)
     */
    @Transactional
    public KnowledgeCategory create(String ownerSubject,
                                    Long spaceId,
                                    CreateKnowledgeCategoryRequest request) {
        // 1. Space ownership (id + ownerSubject, SQL-scoped).
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }

        // 2. Parent same-space invariant (if a parent is given).
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

    /**
     * Lists all categories of the caller's own space in
     * tree-presentation order (sort_order ASC, id ASC).
     *
     * @return categories, or {@code null} when the space is not owned
     */
    public List<KnowledgeCategory> listMine(String ownerSubject, Long spaceId) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }
        return knowledgeCategoryMapper.selectBySpaceAndOwner(spaceId, ownerSubject);
    }

    /**
     * Returns ONE category of the caller's own space (owner-scoped
     * SQL; {@code null} → 404).
     */
    public KnowledgeCategory getMine(String ownerSubject, Long spaceId, Long categoryId) {
        return knowledgeCategoryMapper.selectByIdAndSpaceAndOwner(categoryId, spaceId, ownerSubject);
    }
}
