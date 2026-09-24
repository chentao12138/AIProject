package com.aistudy.server.exam.controller;

import com.aistudy.server.exam.dto.ExamStatisticsDto.*;
import com.aistudy.server.exam.service.ExamStatisticsService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.List;

@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/statistics")
@SecurityRequirement(name = "bearerAuth")
public class ExamStatisticsController {

    private final ExamStatisticsService examStatisticsService;

    public ExamStatisticsController(ExamStatisticsService examStatisticsService) {
        this.examStatisticsService = examStatisticsService;
    }

    @GetMapping(value = "/overview", produces = MediaType.APPLICATION_JSON_VALUE)
    public SpaceOverview overview(@PathVariable Long spaceId,
                                 Authentication authentication) {
        return examStatisticsService.overview(spaceId, authentication);
    }

    @GetMapping(value = "/time-series", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<TimeSeriesPoint> timeSeries(@PathVariable Long spaceId,
                                            @RequestParam(name = "bucket", required = false, defaultValue = "day") String bucket,
                                            Authentication authentication) {
        return examStatisticsService.timeSeries(spaceId, authentication, bucket);
    }

    @GetMapping(value = "/kp-practice-trend", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<KpPracticeTrend> kpPracticeTrend(@PathVariable Long spaceId,
                                                 Authentication authentication) {
        return examStatisticsService.kpPracticeTrend(spaceId, authentication);
    }

    @GetMapping(value = "/exam-trend", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ExamTrend> examTrend(@PathVariable Long spaceId,
                                     Authentication authentication) {
        return examStatisticsService.examTrend(spaceId, authentication);
    }

    @GetMapping(value = "/review-backlog-trend", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ReviewBacklogTrend> reviewBacklogTrend(@PathVariable Long spaceId,
                                                       Authentication authentication) {
        return examStatisticsService.reviewBacklogTrend(spaceId, authentication);
    }

    @GetMapping(value = "/mastery-movement", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<MasteryMovement> masteryMovement(@PathVariable Long spaceId,
                                                 Authentication authentication) {
        return examStatisticsService.masteryMovement(spaceId, authentication);
    }

    @GetMapping(value = "/study-task-completion", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<StudyTaskCompletion> studyTaskCompletion(@PathVariable Long spaceId,
                                                         Authentication authentication) {
        return examStatisticsService.studyTaskCompletion(spaceId, authentication);
    }

    @GetMapping(value = "/question-type-performance", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<QuestionTypePerformance> questionTypePerformance(@PathVariable Long spaceId,
                                                                  Authentication authentication) {
        return examStatisticsService.questionTypePerformance(spaceId, authentication);
    }

    @GetMapping(value = "/global-overview", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<GlobalSpaceOverview> globalOverview(Authentication authentication) {
        Collection<? extends GrantedAuthority> auths = authentication.getAuthorities();
        boolean isAdmin = auths.stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (!isAdmin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ADMIN role required");
        }
        return examStatisticsService.globalSpaceOverview();
    }
}
