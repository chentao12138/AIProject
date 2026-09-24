package com.aistudy.server.admin.knowledge.controller;

import com.aistudy.server.admin.bulk.service.BulkOperationService;
import com.aistudy.server.knowledge.category.entity.KnowledgeCategory;
import com.aistudy.server.knowledge.category.mapper.KnowledgeCategoryMapper;
import com.aistudy.server.knowledge.point.entity.KnowledgePoint;
import com.aistudy.server.knowledge.point.mapper.KnowledgePointMapper;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/knowledge")
@SecurityRequirement(name = "bearerAuth")
public class AdminKnowledgeController {

    private final KnowledgeCategoryMapper categoryMapper;
    private final KnowledgePointMapper pointMapper;

    public AdminKnowledgeController(KnowledgeCategoryMapper categoryMapper,
                                    KnowledgePointMapper pointMapper) {
        this.categoryMapper = categoryMapper;
        this.pointMapper = pointMapper;
    }

    public record AdminCategoryView(
            Long id, Long spaceId, Long parentId, String name,
            String description, Integer sortOrder,
            LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime deletedAt) {
        public static AdminCategoryView from(KnowledgeCategory c) {
            return new AdminCategoryView(
                    c.getId(), c.getSpaceId(), c.getParentId(), c.getName(),
                    c.getDescription(), c.getSortOrder(),
                    c.getCreatedAt(), c.getUpdatedAt(), c.getDeletedAt());
        }
    }

    @GetMapping("/categories")
    @PreAuthorize("hasRole('ADMIN')")
    public List<AdminCategoryView> listCategories(
            @RequestParam(required = false) Long spaceId) {
        List<KnowledgeCategory> categories = spaceId == null
                ? categoryMapper.selectList(null)
                : categoryMapper.selectBySpaceAndOwner(spaceId, "");
        return categories.stream().map(AdminCategoryView::from).toList();
    }

    @GetMapping("/categories/{categoryId}")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminCategoryView getCategory(@PathVariable Long categoryId) {
        KnowledgeCategory category = categoryMapper.selectByIdAdmin(categoryId);
        if (category == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "KnowledgeCategory not found");
        }
        return AdminCategoryView.from(category);
    }

    @PostMapping("/categories/{categoryId}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminCategoryView deleteCategory(@PathVariable Long categoryId) {
        KnowledgeCategory category = categoryMapper.selectByIdAdmin(categoryId);
        if (category == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "KnowledgeCategory not found");
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        categoryMapper.softDeleteByIdAndSpace(categoryId, category.getSpaceId(), now);
        category.setDeletedAt(now);
        return AdminCategoryView.from(category);
    }

    public record AdminKnowledgePointView(
            Long id, Long spaceId, Long categoryId, String title,
            String summary, String originType, String status, String difficulty,
            LocalDateTime createdAt, LocalDateTime updatedAt) {
        public static AdminKnowledgePointView from(KnowledgePoint kp) {
            return new AdminKnowledgePointView(
                    kp.getId(), kp.getSpaceId(), kp.getCategoryId(), kp.getTitle(),
                    kp.getSummary(), kp.getOriginType(), kp.getStatus(), kp.getDifficulty(),
                    kp.getCreatedAt(), kp.getUpdatedAt());
        }
    }

    @GetMapping("/points")
    @PreAuthorize("hasRole('ADMIN')")
    public List<AdminKnowledgePointView> listPoints(
            @RequestParam(required = false) Long spaceId,
            @RequestParam(required = false) String status) {
        List<KnowledgePoint> points = pointMapper.selectAllAdmin(spaceId, status);
        return points.stream().map(AdminKnowledgePointView::from).toList();
    }

    @GetMapping("/points/{kpId}")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminKnowledgePointView getPoint(@PathVariable Long kpId) {
        KnowledgePoint point = pointMapper.selectByIdAdmin(kpId);
        if (point == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "KnowledgePoint not found");
        }
        return AdminKnowledgePointView.from(point);
    }

    @PostMapping("/points/{kpId}/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminKnowledgePointView publishPoint(@PathVariable Long kpId) {
        KnowledgePoint point = pointMapper.selectByIdAdmin(kpId);
        if (point == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "KnowledgePoint not found");
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int updated = pointMapper.publishById(kpId, "PUBLISHED", now, now);
        if (updated == 0) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.CONFLICT, "KnowledgePoint state changed concurrently");
        }
        point.setStatus("PUBLISHED");
        point.setPublishedAt(now);
        point.setUpdatedAt(now);
        return AdminKnowledgePointView.from(point);
    }

    @PostMapping("/points/{kpId}/archive")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminKnowledgePointView archivePoint(@PathVariable Long kpId) {
        KnowledgePoint point = pointMapper.selectByIdAdmin(kpId);
        if (point == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "KnowledgePoint not found");
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int updated = pointMapper.archiveById(kpId, "ARCHIVED", now, now);
        if (updated == 0) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.CONFLICT, "KnowledgePoint state changed concurrently");
        }
        point.setStatus("ARCHIVED");
        point.setArchivedAt(now);
        point.setUpdatedAt(now);
        return AdminKnowledgePointView.from(point);
    }

    @PostMapping("/points/bulk-publish")
    @PreAuthorize("hasRole('ADMIN')")
    public BulkOperationService.BulkResult bulkPublishPoints(@RequestBody List<Long> kpIds) {
        return BulkOperationService.bulkPublishKnowledgePoints(pointMapper, kpIds);
    }

    @PostMapping("/points/bulk-archive")
    @PreAuthorize("hasRole('ADMIN')")
    public BulkOperationService.BulkResult bulkArchivePoints(@RequestBody List<Long> kpIds) {
        return BulkOperationService.bulkArchiveKnowledgePoints(pointMapper, kpIds);
    }
}
