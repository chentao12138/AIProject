package com.aistudy.server.exam.service;

import com.aistudy.server.exam.dto.ExamDto.CreateExamRequest;
import com.aistudy.server.exam.dto.ExamDto.ExamQuestionInput;
import com.aistudy.server.exam.dto.UpdateExamRequest;
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

@Service
public class ExamService {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    public static final String PAPER_STATUS_DRAFT = "DRAFT";
    public static final String PAPER_STATUS_PUBLISHED = "PUBLISHED";

    public static final String EXAM_TYPE_STANDARD = "STANDARD";
    public static final String EXAM_TYPE_CHAPTER = "CHAPTER";
    public static final String EXAM_TYPE_SPECIAL = "SPECIAL";
    public static final String EXAM_TYPE_STAGE = "STAGE";
    public static final String EXAM_TYPE_MOCK = "MOCK";
    public static final String EXAM_TYPE_CUSTOM = "CUSTOM";

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
        exam.setExamType(request.examType() != null ? request.examType() : EXAM_TYPE_STANDARD);
        exam.setTimeLimitMinutes(request.timeLimitMinutes());
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

    public List<Exam> listMine(String ownerSubject, Long spaceId) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }
        return examMapper.selectBySpaceOwner(spaceId, ownerSubject);
    }

    public Exam getMine(String ownerSubject, Long spaceId, Long examId) {
        return examMapper.selectByIdSpaceOwner(examId, spaceId, ownerSubject);
    }

    /** Latest paper by version (PUBLISHED or DRAFT, highest version wins). */
    public ExamPaper paperOf(Exam exam) {
        return examPaperMapper.selectLatestByExam(exam.getSpaceId(), exam.getId());
    }

    /** Working paper: DRAFT if exists (any version), else latest PUBLISHED. */
    public ExamPaper workingPaperOf(Exam exam) {
        return examPaperMapper.selectLatestWorkingPaper(exam.getSpaceId(), exam.getId());
    }

    /** The fixed composition of a paper, in display order. */
    public List<ExamQuestion> questionsOf(ExamPaper paper) {
        return examQuestionMapper.selectByPaperId(paper.getSpaceId(), paper.getId());
    }

    @Transactional
    public Exam update(String ownerSubject, Long spaceId, Long examId, UpdateExamRequest request) {
        Exam existing = getMine(ownerSubject, spaceId, examId);
        if (existing == null || STATUS_ARCHIVED.equals(existing.getStatus())) {
            return null;
        }
        if (!STATUS_DRAFT.equals(existing.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot update a published or archived exam");
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = examMapper.updateDetailsByIdAndSpace(
                examId, spaceId,
                request.title() != null ? request.title() : existing.getTitle(),
                request.description() != null ? request.description() : existing.getDescription(),
                request.timeLimitMinutes() != null ? request.timeLimitMinutes() : existing.getTimeLimitMinutes(),
                now);
        if (updated == 0) {
            return null;
        }
        existing.setTitle(request.title() != null ? request.title() : existing.getTitle());
        existing.setDescription(request.description() != null ? request.description() : existing.getDescription());
        existing.setTimeLimitMinutes(request.timeLimitMinutes() != null ? request.timeLimitMinutes() : existing.getTimeLimitMinutes());
        existing.setUpdatedAt(now);
        return existing;
    }

    @Transactional
    public Exam archive(String ownerSubject, Long spaceId, Long examId) {
        Exam existing = getMine(ownerSubject, spaceId, examId);
        if (existing == null || STATUS_ARCHIVED.equals(existing.getStatus())) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = examMapper.archiveByIdAndSpace(examId, spaceId, STATUS_ARCHIVED, now, now);
        if (updated == 0) {
            return null;
        }
        existing.setStatus(STATUS_ARCHIVED);
        existing.setArchivedAt(now);
        existing.setUpdatedAt(now);
        return existing;
    }

    @Transactional
    public Exam publish(String ownerSubject, Long spaceId, Long examId) {
        Exam exam = getMine(ownerSubject, spaceId, examId);
        if (exam == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);

        if (STATUS_DRAFT.equals(exam.getStatus())) {
            ExamPaper paper = paperOf(exam);
            if (paper == null || !PAPER_STATUS_DRAFT.equals(paper.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "exam paper is not in DRAFT state");
            }
            int paperUpdated = examPaperMapper.updateStatusByIdAndSpace(
                    paper.getId(), spaceId, PAPER_STATUS_DRAFT, PAPER_STATUS_PUBLISHED, now);
            if (paperUpdated == 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "paper state changed concurrently");
            }
            int updated = examMapper.publishByIdAndSpace(
                    examId, spaceId, STATUS_DRAFT, STATUS_PUBLISHED, now, now);
            if (updated == 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "exam state changed concurrently");
            }
            exam.setStatus(STATUS_PUBLISHED);
            exam.setPublishedAt(now);
            exam.setUpdatedAt(now);
        } else if (STATUS_PUBLISHED.equals(exam.getStatus())) {
            ExamPaper draftPaper = workingPaperOf(exam);
            if (draftPaper == null || !PAPER_STATUS_DRAFT.equals(draftPaper.getStatus())) {
                return exam; // idempotent: already published, no draft to publish
            }
            int paperUpdated = examPaperMapper.updateStatusByIdAndSpace(
                    draftPaper.getId(), spaceId, PAPER_STATUS_DRAFT, PAPER_STATUS_PUBLISHED, now);
            if (paperUpdated == 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "paper state changed concurrently");
            }
            exam.setUpdatedAt(now);
        }
        return exam;
    }

    // ==================== draft paper composition ====================

    private ExamPaper ensureDraftPaper(Exam exam) {
        ExamPaper working = workingPaperOf(exam);
        if (working != null && PAPER_STATUS_DRAFT.equals(working.getStatus())) {
            return working;
        }
        ExamPaper latestPublished = paperOf(exam);
        int nextVersion = (latestPublished != null ? latestPublished.getPaperVersion() : 0) + 1;
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        ExamPaper draft = new ExamPaper();
        draft.setSpaceId(exam.getSpaceId());
        draft.setExamId(exam.getId());
        draft.setPaperVersion(nextVersion);
        draft.setStatus(PAPER_STATUS_DRAFT);
        draft.setCreatedAt(now);
        examPaperMapper.insert(draft);
        if (latestPublished != null) {
            List<ExamQuestion> existingQuestions = questionsOf(latestPublished);
            for (ExamQuestion eq : existingQuestions) {
                ExamQuestion clone = new ExamQuestion();
                clone.setExamPaperId(draft.getId());
                clone.setSpaceId(eq.getSpaceId());
                clone.setQuestionId(eq.getQuestionId());
                clone.setSortOrder(eq.getSortOrder());
                clone.setScore(eq.getScore());
                clone.setQuestionSnapshotJson(eq.getQuestionSnapshotJson());
                clone.setCreatedAt(now);
                examQuestionMapper.insert(clone);
            }
        }
        return draft;
    }

    private void recomputeTotalScore(Exam exam, ExamPaper paper) {
        List<ExamQuestion> questions = questionsOf(paper);
        int total = questions.stream().mapToInt(ExamQuestion::getScore).sum();
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        examMapper.updateTotalScoreByIdAndSpace(exam.getId(), exam.getSpaceId(), total, now);
        exam.setTotalScore(total);
    }

    @Transactional
    public ExamQuestion addQuestionToPaper(String ownerSubject, Long spaceId, Long examId,
                                           Long questionId, Integer score) {
        Exam exam = getMine(ownerSubject, spaceId, examId);
        if (exam == null) {
            return null;
        }
        ExamPaper draft = ensureDraftPaper(exam);
        Question question = questionMapper.selectByIdSpaceOwner(questionId, spaceId, ownerSubject);
        if (question == null || !QuestionService.STATUS_PUBLISHED.equals(question.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Question not found or not published");
        }
        List<ExamQuestion> existing = questionsOf(draft);
        boolean duplicate = existing.stream().anyMatch(eq -> eq.getQuestionId().equals(questionId));
        if (duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "question already in paper");
        }
        int order = existing.size();
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        ExamQuestion slot = new ExamQuestion();
        slot.setExamPaperId(draft.getId());
        slot.setSpaceId(spaceId);
        slot.setQuestionId(questionId);
        slot.setSortOrder(order);
        slot.setScore(score);
        slot.setQuestionSnapshotJson(questionSnapshotService.buildSnapshot(question));
        slot.setCreatedAt(now);
        examQuestionMapper.insert(slot);
        recomputeTotalScore(exam, draft);
        return slot;
    }

    @Transactional
    public ExamQuestion removeQuestionFromPaper(String ownerSubject, Long spaceId, Long examId,
                                                Long examQuestionId) {
        Exam exam = getMine(ownerSubject, spaceId, examId);
        if (exam == null) {
            return null;
        }
        ExamPaper draft = ensureDraftPaper(exam);
        ExamQuestion slot = examQuestionMapper.selectByIdAndPaper(
                spaceId, draft.getId(), examQuestionId);
        if (slot == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "ExamQuestion not found in draft paper");
        }
        examQuestionMapper.deleteByIdAndPaper(examQuestionId, draft.getId(), spaceId);
        recomputeTotalScore(exam, draft);
        return slot;
    }

    @Transactional
    public List<ExamQuestion> reorderQuestions(String ownerSubject, Long spaceId, Long examId,
                                               List<ExamQuestionMapper.SortSlot> slots) {
        Exam exam = getMine(ownerSubject, spaceId, examId);
        if (exam == null) {
            return null;
        }
        ExamPaper draft = ensureDraftPaper(exam);
        int updated = examQuestionMapper.updateSortOrderBatch(draft.getId(), spaceId, slots);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "paper state changed concurrently");
        }
        return questionsOf(draft);
    }

    @Transactional
    public ExamQuestion updateQuestionScore(String ownerSubject, Long spaceId, Long examId,
                                            Long examQuestionId, Integer score) {
        Exam exam = getMine(ownerSubject, spaceId, examId);
        if (exam == null) {
            return null;
        }
        ExamPaper draft = ensureDraftPaper(exam);
        ExamQuestion slot = examQuestionMapper.selectByIdAndPaper(
                spaceId, draft.getId(), examQuestionId);
        if (slot == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "ExamQuestion not found in draft paper");
        }
        int updated = examQuestionMapper.updateScoreByIdAndPaper(
                examQuestionId, draft.getId(), spaceId, score);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "paper state changed concurrently");
        }
        recomputeTotalScore(exam, draft);
        slot.setScore(score);
        return slot;
    }
}
