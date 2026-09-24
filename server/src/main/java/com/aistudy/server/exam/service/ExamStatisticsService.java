package com.aistudy.server.exam.service;

import com.aistudy.server.exam.dto.ExamStatisticsDto.ExamTrend;
import com.aistudy.server.exam.dto.ExamStatisticsDto.GlobalSpaceOverview;
import com.aistudy.server.exam.dto.ExamStatisticsDto.KpPracticeTrend;
import com.aistudy.server.exam.dto.ExamStatisticsDto.MasteryMovement;
import com.aistudy.server.exam.dto.ExamStatisticsDto.QuestionTypePerformance;
import com.aistudy.server.exam.dto.ExamStatisticsDto.ReviewBacklogTrend;
import com.aistudy.server.exam.dto.ExamStatisticsDto.SpaceOverview;
import com.aistudy.server.exam.dto.ExamStatisticsDto.StudyTaskCompletion;
import com.aistudy.server.exam.dto.ExamStatisticsDto.TimeSeriesPoint;
import com.aistudy.server.exam.mapper.ExamStatisticsMapper;
import com.aistudy.server.space.service.LearningSpaceService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ExamStatisticsService {

    private static final int DEFAULT_DAYS = 90;

    private final ExamStatisticsMapper examStatisticsMapper;
    private final LearningSpaceService learningSpaceService;

    public ExamStatisticsService(ExamStatisticsMapper examStatisticsMapper,
                                 LearningSpaceService learningSpaceService) {
        this.examStatisticsMapper = examStatisticsMapper;
        this.learningSpaceService = learningSpaceService;
    }

    public SpaceOverview overview(Long spaceId, Authentication authentication) {
        if (learningSpaceService.getMine(authentication.getName(), spaceId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LearningSpace not found");
        }
        String userSubject = authentication.getName();
        LocalDate now = LocalDate.now();
        return rawOverview(userSubject, spaceId, now.minusYears(10), now.plusDays(1));
    }

    public List<TimeSeriesPoint> timeSeries(Long spaceId, Authentication authentication,
                                            String bucket) {
        String userSubject = authentication.getName();
        LocalDate now = LocalDate.now();
        LocalDate from = now.minusDays(DEFAULT_DAYS);
        LocalDate to = now.plusDays(1);
        List<TimeSeriesPoint> daily = rawTimeSeries(userSubject, spaceId, from, to);
        if ("week".equalsIgnoreCase(bucket)) {
            return aggregateByWeek(daily);
        } else if ("month".equalsIgnoreCase(bucket)) {
            return aggregateByMonth(daily);
        }
        return daily;
    }

    public List<KpPracticeTrend> kpPracticeTrend(Long spaceId, Authentication authentication) {
        String userSubject = authentication.getName();
        LocalDate now = LocalDate.now();
        return rawKpPracticeTrend(userSubject, spaceId, now.minusDays(DEFAULT_DAYS), now.plusDays(1));
    }

    public List<ExamTrend> examTrend(Long spaceId, Authentication authentication) {
        String userSubject = authentication.getName();
        LocalDate now = LocalDate.now();
        return rawExamTrend(userSubject, spaceId, now.minusDays(DEFAULT_DAYS), now.plusDays(1));
    }

    public List<ReviewBacklogTrend> reviewBacklogTrend(Long spaceId, Authentication authentication) {
        String userSubject = authentication.getName();
        LocalDate now = LocalDate.now();
        return rawReviewBacklogTrend(userSubject, spaceId, now.minusDays(DEFAULT_DAYS), now.plusDays(1));
    }

    public List<MasteryMovement> masteryMovement(Long spaceId, Authentication authentication) {
        String userSubject = authentication.getName();
        LocalDate now = LocalDate.now();
        return rawMasteryMovement(spaceId, userSubject, now.minusDays(DEFAULT_DAYS), now.plusDays(1));
    }

    public List<StudyTaskCompletion> studyTaskCompletion(Long spaceId, Authentication authentication) {
        String userSubject = authentication.getName();
        LocalDate now = LocalDate.now();
        return rawStudyTaskCompletion(userSubject, spaceId, now.minusDays(DEFAULT_DAYS), now.plusDays(1));
    }

    public List<QuestionTypePerformance> questionTypePerformance(Long spaceId,
                                                                  Authentication authentication) {
        String userSubject = authentication.getName();
        if (learningSpaceService.getMine(userSubject, spaceId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LearningSpace not found");
        }
        return examStatisticsMapper.selectQuestionTypePerformance(spaceId, userSubject).stream()
                .map(row -> new QuestionTypePerformance(
                        (String) row.getOrDefault("questionType", "UNKNOWN"),
                        longVal(row.getOrDefault("total", 0L)),
                        longVal(row.getOrDefault("correct", 0L)),
                        ratio(longVal(row.getOrDefault("correct", 0L)), longVal(row.getOrDefault("total", 0L)))
                )).toList();
    }

    public List<GlobalSpaceOverview> globalSpaceOverview() {
        return examStatisticsMapper.selectGlobalSpaceOverview().stream()
                .map(row -> new GlobalSpaceOverview(
                        longVal(row.get("spaceId")),
                        (String) row.getOrDefault("spaceName", "Unknown"),
                        longVal(row.getOrDefault("memberCount", 0L)),
                        longVal(row.getOrDefault("examCount", 0L)),
                        longVal(row.getOrDefault("masteryCount", 0L)),
                        dblVal(row.getOrDefault("avgMasteryScore", 0.0))
                )).toList();
    }

    // ==================== raw internals ====================

    private SpaceOverview rawOverview(String userSubject, Long spaceId,
                                      LocalDate from, LocalDate to) {
        Long durationMs = examStatisticsMapper.selectLearningDurationMs(spaceId, userSubject, from, to);
        Map<String, Object> accuracy = examStatisticsMapper.selectPracticeAccuracy(spaceId, userSubject, from, to);
        Long wrongCount = examStatisticsMapper.selectWrongQuestionCount(spaceId, userSubject, userSubject, from, to);
        Long reviewCompleted = examStatisticsMapper.selectReviewCompleted(spaceId, userSubject, from, to);
        Long examCount = examStatisticsMapper.selectExamHistoryCount(spaceId, userSubject, userSubject, from, to);
        Double avgScore = examStatisticsMapper.selectAvgExamScore(spaceId, userSubject, userSubject, from, to);
        Double avgMaxScore = examStatisticsMapper.selectAvgExamMaxScore(spaceId, userSubject, userSubject, from, to);
        Map<String, Object> masteryDist = examStatisticsMapper.selectMasteryDistribution(spaceId, userSubject, userSubject);

        Double practiceAccuracy = ratio(longVal(accuracy.getOrDefault("correct_count", 0L)),
                longVal(accuracy.getOrDefault("total_count", 0L)));
        Double avgScoreRatio = (avgScore != null && avgMaxScore != null && avgMaxScore > 0)
                ? avgScore / avgMaxScore : null;

        Map<String, Long> masteryDistribution = Map.of(
                "LOW", longVal(masteryDist.getOrDefault("low", 0L)),
                "MEDIUM_LOW", longVal(masteryDist.getOrDefault("medium_low", 0L)),
                "MEDIUM_HIGH", longVal(masteryDist.getOrDefault("medium_high", 0L)),
                "HIGH", longVal(masteryDist.getOrDefault("high", 0L))
        );

        return new SpaceOverview(
                spaceId, durationMs, practiceAccuracy, wrongCount,
                reviewCompleted, examCount, avgScoreRatio, masteryDistribution);
    }

    private List<TimeSeriesPoint> rawTimeSeries(String userSubject, Long spaceId,
                                                LocalDate from, LocalDate to) {
        List<Map<String, Object>> practiceRows = examStatisticsMapper.selectPracticeDailyTrend(
                spaceId, userSubject, from, to);
        List<Map<String, Object>> examRows = examStatisticsMapper.selectExamTrend(
                spaceId, userSubject, from, to);
        List<Map<String, Object>> reviewRows = examStatisticsMapper.selectReviewCompletionTrend(
                spaceId, userSubject, from, to);
        List<Map<String, Object>> taskRows = examStatisticsMapper.selectStudyTaskCompletionTrend(
                spaceId, userSubject, from, to);

        Map<LocalDate, TimeSeriesPoint> byDate = new TreeMap<>();

        for (Map<String, Object> row : practiceRows) {
            LocalDate d = toLocalDate(row.get("bucket"));
            long practiceCount = longVal(row.getOrDefault("practice_count", 0L));
            long correctCount = longVal(row.getOrDefault("correct_count", 0L));
            byDate.put(d, new TimeSeriesPoint(d, 0L, practiceCount, correctCount, 0L, 0L, 0L, 0L));
        }
        for (Map<String, Object> row : examRows) {
            LocalDate d = toLocalDate(row.get("bucket"));
            TimeSeriesPoint existing = byDate.get(d);
            long attempts = longVal(row.getOrDefault("attempt_count", 0L));
            if (existing == null) {
                byDate.put(d, new TimeSeriesPoint(d, 0L, 0L, 0L, 0L, attempts, 0L, 0L));
            } else {
                byDate.put(d, new TimeSeriesPoint(d, existing.durationMs(), existing.practiceCount(),
                        existing.correctCount(), existing.wrongCount(), attempts,
                        existing.reviewCompleted(), existing.studyTaskCompleted()));
            }
        }
        for (Map<String, Object> row : reviewRows) {
            LocalDate d = toLocalDate(row.get("bucket"));
            TimeSeriesPoint existing = byDate.get(d);
            long completed = longVal(row.getOrDefault("completed_count", 0L));
            if (existing == null) {
                byDate.put(d, new TimeSeriesPoint(d, 0L, 0L, 0L, 0L, 0L, completed, 0L));
            } else {
                byDate.put(d, new TimeSeriesPoint(d, existing.durationMs(), existing.practiceCount(),
                        existing.correctCount(), existing.wrongCount(), existing.examCount(),
                        completed, existing.studyTaskCompleted()));
            }
        }
        for (Map<String, Object> row : taskRows) {
            LocalDate d = toLocalDate(row.get("bucket"));
            TimeSeriesPoint existing = byDate.get(d);
            long taskCompleted = longVal(row.getOrDefault("count", 0L));
            if (existing == null) {
                byDate.put(d, new TimeSeriesPoint(d, 0L, 0L, 0L, 0L, 0L, 0L, taskCompleted));
            } else {
                byDate.put(d, new TimeSeriesPoint(d, existing.durationMs(), existing.practiceCount(),
                        existing.correctCount(), existing.wrongCount(), existing.examCount(),
                        existing.reviewCompleted(), taskCompleted));
            }
        }

        return new ArrayList<>(byDate.values());
    }

    private List<KpPracticeTrend> rawKpPracticeTrend(String userSubject, Long spaceId,
                                                      LocalDate from, LocalDate to) {
        return examStatisticsMapper.selectKpPracticeTrend(spaceId, userSubject, from, to).stream()
                .map(row -> {
                    Long kpId = longVal(row.getOrDefault("knowledge_point_id", 0L));
                    String title = (String) row.getOrDefault("title", "KP-" + kpId);
                    long total = longVal(row.getOrDefault("total", 0L));
                    long correct = longVal(row.getOrDefault("correct", 0L));
                    return new KpPracticeTrend(kpId, title, total, correct,
                            total > 0 ? (double) correct / total : 0.0);
                }).toList();
    }

    private List<ExamTrend> rawExamTrend(String userSubject, Long spaceId,
                                         LocalDate from, LocalDate to) {
        return examStatisticsMapper.selectExamTrend(spaceId, userSubject, from, to).stream()
                .map(row -> new ExamTrend(
                        toLocalDate(row.get("bucket")),
                        longVal(row.getOrDefault("attempt_count", 0L)),
                        dblVal(row.getOrDefault("avg_score", 0.0)),
                        dblVal(row.getOrDefault("avg_max_score", 0.0))
                )).toList();
    }

    private List<ReviewBacklogTrend> rawReviewBacklogTrend(String userSubject, Long spaceId,
                                                           LocalDate from, LocalDate to) {
        Long pending = examStatisticsMapper.selectReviewPending(spaceId, userSubject, userSubject);
        List<Map<String, Object>> rows = examStatisticsMapper.selectReviewCompletionTrend(
                spaceId, userSubject, from, to);
        List<ReviewBacklogTrend> result = new ArrayList<>();
        long completedAccum = 0;
        long basePending = pending == null ? 0 : pending;
        for (Map<String, Object> row : rows) {
            LocalDate d = toLocalDate(row.get("bucket"));
            long completed = longVal(row.getOrDefault("completed_count", 0L));
            completedAccum += completed;
            result.add(new ReviewBacklogTrend(d, Math.max(0, basePending - completedAccum), completed));
        }
        return result;
    }

    private List<MasteryMovement> rawMasteryMovement(Long spaceId, String userSubject,
                                                     LocalDate from, LocalDate to) {
        Map<String, Object> row = examStatisticsMapper.selectMasteryMovement(spaceId, userSubject, from, to);
        return List.of(
                new MasteryMovement("LOW", longVal(row.getOrDefault("low", 0L))),
                new MasteryMovement("MEDIUM_LOW", longVal(row.getOrDefault("medium_low", 0L))),
                new MasteryMovement("MEDIUM_HIGH", longVal(row.getOrDefault("medium_high", 0L))),
                new MasteryMovement("HIGH", longVal(row.getOrDefault("high", 0L)))
        );
    }

    private List<StudyTaskCompletion> rawStudyTaskCompletion(String userSubject, Long spaceId,
                                                              LocalDate from, LocalDate to) {
        return examStatisticsMapper.selectStudyTaskCompletionTrend(spaceId, userSubject, from, to).stream()
                .map(row -> new StudyTaskCompletion(
                        toLocalDate(row.get("bucket")).toString(),
                        longVal(row.getOrDefault("count", 0L)),
                        longVal(row.getOrDefault("count", 0L))
                )).toList();
    }

    private List<TimeSeriesPoint> aggregateByWeek(List<TimeSeriesPoint> points) {
        Map<Integer, TimeSeriesPoint> byWeek = new LinkedHashMap<>();
        for (TimeSeriesPoint p : points) {
            LocalDate d = p.bucket();
            int weekOfYear = d.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear());
            int year = d.get(java.time.temporal.WeekFields.ISO.weekBasedYear());
            int key = year * 100 + weekOfYear;
            byWeek.merge(key, p, (existing, incoming) -> mergeTimeSeries(existing, incoming));
        }
        return new ArrayList<>(byWeek.values());
    }

    private List<TimeSeriesPoint> aggregateByMonth(List<TimeSeriesPoint> points) {
        Map<String, TimeSeriesPoint> byMonth = new LinkedHashMap<>();
        for (TimeSeriesPoint p : points) {
            LocalDate d = p.bucket();
            String key = d.getYear() + "-" + String.format("%02d", d.getMonthValue());
            LocalDate monthStart = LocalDate.of(d.getYear(), d.getMonth(), 1);
            TimeSeriesPoint merged = byMonth.computeIfAbsent(key, k -> emptyPoint(monthStart));
            byMonth.put(key, mergeTimeSeries(merged, p));
        }
        return new ArrayList<>(byMonth.values());
    }

    private TimeSeriesPoint emptyPoint(LocalDate d) {
        return new TimeSeriesPoint(d, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
    }

    private TimeSeriesPoint mergeTimeSeries(TimeSeriesPoint a, TimeSeriesPoint b) {
        return new TimeSeriesPoint(
                a.bucket(),
                a.durationMs() + b.durationMs(),
                a.practiceCount() + b.practiceCount(),
                a.correctCount() + b.correctCount(),
                a.wrongCount() + b.wrongCount(),
                a.examCount() + b.examCount(),
                a.reviewCompleted() + b.reviewCompleted(),
                a.studyTaskCompleted() + b.studyTaskCompleted()
        );
    }

    private LocalDate toLocalDate(Object v) {
        if (v instanceof LocalDate ld) return ld;
        if (v instanceof java.sql.Date sd) return sd.toLocalDate();
        return LocalDate.now();
    }

    private static long longVal(Object v) {
        if (v instanceof Number n) return n.longValue();
        return 0L;
    }

    private static double dblVal(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        return 0.0;
    }

    private static Double ratio(long correct, long total) {
        return total > 0 ? (double) correct / total : null;
    }
}
