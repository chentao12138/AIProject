package com.aistudy.server.note.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * User-owned note inside a LearningSpace.
 *
 * <p>{@code userSubject} is ALWAYS the authenticated JWT subject stored in
 * {@code note.user_subject_id}. Clients never supply owner identity.
 */
@TableName("note")
public class Note {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long spaceId;
    /** JWT subject string; column name is user_subject_id (VARCHAR). */
    @TableField("user_subject_id")
    private String userSubject;
    private String title;
    private String content;
    private String contentFormat;
    private Long sourceId;
    private Long sourcePageId;
    private Long contentBlockId;
    private Long knowledgePointId;
    private Long questionId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime archivedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSpaceId() { return spaceId; }
    public void setSpaceId(Long spaceId) { this.spaceId = spaceId; }
    public String getUserSubject() { return userSubject; }
    public void setUserSubject(String userSubject) { this.userSubject = userSubject; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getContentFormat() { return contentFormat; }
    public void setContentFormat(String contentFormat) { this.contentFormat = contentFormat; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public Long getSourcePageId() { return sourcePageId; }
    public void setSourcePageId(Long sourcePageId) { this.sourcePageId = sourcePageId; }
    public Long getContentBlockId() { return contentBlockId; }
    public void setContentBlockId(Long contentBlockId) { this.contentBlockId = contentBlockId; }
    public Long getKnowledgePointId() { return knowledgePointId; }
    public void setKnowledgePointId(Long knowledgePointId) { this.knowledgePointId = knowledgePointId; }
    public Long getQuestionId() { return questionId; }
    public void setQuestionId(Long questionId) { this.questionId = questionId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getArchivedAt() { return archivedAt; }
    public void setArchivedAt(LocalDateTime archivedAt) { this.archivedAt = archivedAt; }
}
