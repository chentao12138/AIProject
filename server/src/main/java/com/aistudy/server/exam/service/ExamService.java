package com.aistudy.server.exam.service;

import com.aistudy.server.exam.dto.ExamDto.CreateExamRequest;
import com.aistudy.server.exam.dto.ExamDto.ExamQuestionInput;
import com.aistudy.server.exam.entity.Exam;
import com.aistudy.server.exam.entity.ExamPaper;
import com.aistudy.server.exam.entity.ExamQuestion;
import com.aistudy.server.exam.mapper.ExamMapper;
import com.aistudy.server.exam.mapper.ExamPaperMapper;
import com.aistudy.server.exam.mapper.ExamQuestionMapper;
import com.aistudy.server.question.entity.Question;
import com.aistudy.server.question.mapper.QuestionMapper;
import com.aistudy.server.question.service.QuestionService;
import com.aistudy.server.question.service.QuestionSnapshotService;
import com.aistudy.server.space.service.LearningSpaceService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * BUSINESS-012 — Exam definition application service.
 *
 * <h3>Model</h3>
 *
 * <p>Creating an exam immediately materializes paper version 1
 * (status DRAFT) with the frozen question composition. Publishing
 * flips exam DRAFT → PUBLISHED and the paper DRAFT → PUBLISHED.
 * Re-publish is a true no-op. PUBLISHED papers are immutable: no
 * composition edit endpoints exist in V1, and exam attempts (013)
 * always read the paper snapshot — later question-bank edits can
 * never change a published exam.
 *
 * <h3>Validation</h3>
 *
 * <ul>
 *   <li>space owned by caller (404); every question PUBLISHED +
 *       same space (404, all-or-nothing); duplicate question → 400;
 *       score >= 1 per item; totalScore = sum of item scores.</li>
 *   <li>publish requires >= 1 question (400 — guaranteed by create
 *       validation).</li>
 * </ul>
 */
@Service
public class ExamService {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String PAPER_STATUS_DRAFT = "DRAFT";
    public static final String PAPER_STATUS_PUBLISHED = "PUBLISHED";
    public static final String EXAM_TYPE_STANDARD = "STANDARD";

    private final ExamMapper examMapper;
    private final ExamPaperMapper examPaperMapper;
    private final ExamQuestionMapper examQuestionMapper;
    private final QuestionMapper questionMapper;
    private final QuestionSnapshotService questionSnapshotService;
    private final LearningSpaceService learningSpaceService;

    public ExamService(ExamMapper examMapper,
                       ExamPaperMapper examPaperMapper,
                       ExamQuestionMapper examQuestionMapper,
                       QuestionMapper questionMapper,
                       QuestionSnapshotService questionSnapshotService,
                       LearningSpaceService learningSpaceService) {
        this.examMapper = examMapper;
        this.examPaperMapper = examPaperMapper;
        this.examQuestionMapper = examQuestionMapper;
        this.questionMapper = questionMapper;
        this.questionSnapshotService = questionSnapshotService;
        this.learningSpaceService = learningSpaceService;
    }

    /**
     * Creates a DRAFT exam + DRAFT paper v1 with the frozen
     * composition.
     *
     * @return the persisted exam, or {@code null} when the space is
     *         not owned or a question is invalid (404)
     */
    @Transactional
    public Exam create(String ownerSubject, Long spaceId, CreateExamRequest request) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }

        Set<Long> seen = new HashSet<>();
        int totalScore = 0;
        for (ExamQuestionInput input : request.questions()) {
            if (!seen.add(input.questionId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "questions must not contain duplicates: " + input.questionId());
            }
            Question question = questionMapper.selectByIdSpaceOwner(
                    input.questionId(), spaceId, ownerSubject);
            if (question == null || !QuestionService.STATUS_PUBLISHED.equals(question.getStatus())) {
                return null;
            }
            totalScore += input.score();
        }

        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);

        Exam exam = new Exam();
        exam.setSpaceId(spaceId);
        exam.setTitle(request.title());
        exam.setDescription(request.description());
        exam.setExamType(EXAM_TYPE_STANDARD);
        exam.setTimeLimitMinutes(request.durationMinutes());
        exam.setTotalScore(totalScore);
        exam.setStatus(STATUS_DRAFT);
        exam.setCreatedByUserId(ownerSubject);
        exam.setCreatedAt(now);
        exam.setUpdatedAt(now);
        examMapper.insert(exam);

        ExamPaper paper = new ExamPaper();
        paper.setSpaceId(spaceId);
        paper.setExamId(exam.getId());
        paper.setPaperVersion(1);
        paper.setStatus(PAPER_STATUS_DRAFT);
        paper.setCreatedAt(now);
        examPaperMapper.insert(paper);

        int order = 0;
        for (ExamQuestionInput input : request.questions()) {
            Question question = questionMapper.selectByIdSpaceOwner(
                    input.questionId(), spaceId, ownerSubject);
            ExamQuestion slot = new ExamQuestion();
            slot.setExamPaperId(paper.getId());
            slot.setSpaceId(spaceId);
            slot.setQuestionId(question.getId());
            slot.setSortOrder(order);
            slot.setScore(input.score());
            slot.setQuestionSnapshotJson(questionSnapshotService.buildSnapshot(question));
            slot.setCreatedAt(now);
            examQuestionMapper.insert(slot);
            order++;
        }
        return exam;
    }

    /** Lists the caller's own exams, newest first. */
    public List<Exam> listMine(String ownerSubject, Long spaceId) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }
        return examMapper.selectBySpaceOwner(spaceId, ownerSubject);
    }

    /** ONE exam of the caller's own space (404 anti-probing). */
    public Exam getMine(String ownerSubject, Long spaceId, Long examId) {
        return examMapper.selectByIdSpaceOwner(examId, spaceId, ownerSubject);
    }

    /** The paper of an exam (created at exam creation). */
    public ExamPaper paperOf(Exam exam) {
        return examPaperMapper.selectLatestByExam(exam.getSpaceId(), exam.getId());
    }

    /** The frozen composition of a paper, display order. */
    public List<ExamQuestion> questionsOf(ExamPaper paper) {
        return examQuestionMapper.selectByPaperId(paper.getSpaceId(), paper.getId());
    }

    /**
     * Publishes a DRAFT exam (exam + paper → PUBLISHED). True
     * idempotency: already-published → returned unchanged, no writes.
     *
     * @return the refreshed exam, or {@code null} → 404
     */
    @Transactional
    public Exam publish(String ownerSubject, Long spaceId, Long examId) {
        Exam exam = getMine(ownerSubject, spaceId, examId);
        if (exam == null) {
            return null;
        }
        if (STATUS_PUBLISHED.equals(exam.getStatus())) {
            return exam;
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);

        ExamPaper paper = paperOf(exam);
        int paperUpdated = 0;
        if (paper != null) {
            paperUpdated = examPaperMapper.updateStatusByIdAndSpace(
                    paper.getId(), spaceId, PAPER_STATUS_DRAFT, PAPER_STATUS_PUBLISHED, now);
        }
        if (paper == null || paperUpdated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "exam paper is not in DRAFT state");
        }

        int updated = examMapper.publishByIdAndSpace(
                examId, spaceId, STATUS_DRAFT, STATUS_PUBLISHED, now, now);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "exam state changed concurrently");
        }
        exam.setStatus(STATUS_PUBLISHED);
        exam.setPublishedAt(now);
        exam.setUpdatedAt(now);
        return exam;
    }
}
