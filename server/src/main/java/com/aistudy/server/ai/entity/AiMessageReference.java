package com.aistudy.server.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * AI-005 — one grounded reference attached to an ASSISTANT message.
 */
@TableName("ai_message_reference")
public class AiMessageReference {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long messageId;
    private Integer ordinal;
    private String referenceType;
    private Long entityId;
    /**
     * C04 — the structured route of the pointer. {@code entityId} says which
     * entity {@code referenceType} names; these say where it sits in the source
     * tree, so a page reload can rebuild a deep-link without re-resolving.
     */
    private Long sourceId;
    private Long sourcePageId;
    private Long contentBlockId;
    private String title;
    private String snippet;
    private String locator;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getMessageId() {
        return messageId;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
    }

    public Integer getOrdinal() {
        return ordinal;
    }

    public void setOrdinal(Integer ordinal) {
        this.ordinal = ordinal;
    }

    public String getReferenceType() {
        return referenceType;
    }

    public void setReferenceType(String referenceType) {
        this.referenceType = referenceType;
    }

    public Long getEntityId() {
        return entityId;
    }

    public void setEntityId(Long entityId) {
        this.entityId = entityId;
    }

    public Long getSourceId() {
        return sourceId;
    }

    public void setSourceId(Long sourceId) {
        this.sourceId = sourceId;
    }

    public Long getSourcePageId() {
        return sourcePageId;
    }

    public void setSourcePageId(Long sourcePageId) {
        this.sourcePageId = sourcePageId;
    }

    public Long getContentBlockId() {
        return contentBlockId;
    }

    public void setContentBlockId(Long contentBlockId) {
        this.contentBlockId = contentBlockId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSnippet() {
        return snippet;
    }

    public void setSnippet(String snippet) {
        this.snippet = snippet;
    }

    public String getLocator() {
        return locator;
    }

    public void setLocator(String locator) {
        this.locator = locator;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
