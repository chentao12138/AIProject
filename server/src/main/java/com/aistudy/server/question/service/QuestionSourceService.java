package com.aistudy.server.question.service;

import com.aistudy.server.question.entity.Question;
import com.aistudy.server.question.entity.QuestionSource;
import com.aistudy.server.question.mapper.QuestionMapper;
import com.aistudy.server.question.mapper.QuestionSourceMapper;
import com.aistudy.server.source.content.entity.ContentBlock;
import com.aistudy.server.source.content.mapper.ContentBlockMapper;
import com.aistudy.server.space.service.LearningSpaceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class QuestionSourceService {

    private final QuestionSourceMapper questionSourceMapper;
    private final QuestionMapper questionMapper;
    private final ContentBlockMapper contentBlockMapper;
    private final LearningSpaceService learningSpaceService;

    public QuestionSourceService(QuestionSourceMapper questionSourceMapper,
                                 QuestionMapper questionMapper,
                                 ContentBlockMapper contentBlockMapper,
                                 LearningSpaceService learningSpaceService) {
        this.questionSourceMapper = questionSourceMapper;
        this.questionMapper = questionMapper;
        this.contentBlockMapper = contentBlockMapper;
        this.learningSpaceService = learningSpaceService;
    }

    public List<Long> listBlockIds(String ownerSubject, Long spaceId, Long questionId) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }
        Question question = questionMapper.selectByIdSpaceOwner(questionId, spaceId, ownerSubject);
        if (question == null) {
            return null;
        }
        return questionSourceMapper.selectBlockIdsByQuestion(questionId, spaceId, ownerSubject);
    }

    @Transactional
    public void linkContentBlocks(String ownerSubject, Long spaceId, Long questionId, List<Long> blockIds) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        }
        Question question = questionMapper.selectByIdSpaceOwner(questionId, spaceId, ownerSubject);
        if (question == null) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "question not found");
        }
        if (blockIds == null || blockIds.isEmpty()) {
            return;
        }
        List<Long> existing = questionSourceMapper.selectExistingBlockIds(questionId, blockIds);
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        for (Long blockId : blockIds) {
            if (existing.contains(blockId)) {
                continue;
            }
            ContentBlock block = contentBlockMapper.selectByIdSpaceOwner(blockId, spaceId, ownerSubject);
            if (block == null) {
                throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                        "content block not found in space: " + blockId);
            }
            QuestionSource link = new QuestionSource();
            link.setSpaceId(spaceId);
            link.setQuestionId(questionId);
            link.setContentBlockId(blockId);
            link.setRelationType("CITED");
            link.setCreatedAt(now);
            questionSourceMapper.insert(link);
        }
    }

    @Transactional
    public void unlinkContentBlock(String ownerSubject, Long spaceId, Long questionId, Long blockId) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        }
        Question question = questionMapper.selectByIdSpaceOwner(questionId, spaceId, ownerSubject);
        if (question == null) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "question not found");
        }
        int deleted = questionSourceMapper.deleteByQuestionAndBlock(questionId, blockId, spaceId);
        if (deleted == 0) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "link not found");
        }
    }
}
