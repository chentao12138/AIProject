package com.aistudy.server.question.service;

import com.aistudy.server.question.entity.Question;
import com.aistudy.server.question.entity.QuestionOption;
import com.aistudy.server.question.eval.AnswerDataCodec;
import com.aistudy.server.question.mapper.QuestionOptionMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BUSINESS-012 — shared frozen-snapshot builder.
 *
 * <p>Practice sessions (009) and exam papers (012) freeze the SAME
 * snapshot shape ({@code questionType/stem/explanation/options/
 * answerData}) so grading truth and history restore behave
 * identically across both flows. Never includes correctness flags on
 * options; answerData stays server-internal.
 */
@Service
public class QuestionSnapshotService {

    private final QuestionOptionMapper questionOptionMapper;

    public QuestionSnapshotService(QuestionOptionMapper questionOptionMapper) {
        this.questionOptionMapper = questionOptionMapper;
    }

    /** Builds the snapshot JSON for a live question. */
    public String buildSnapshot(Question question) {
        List<QuestionOption> options = questionOptionMapper.selectByQuestionId(
                question.getSpaceId(), question.getId());
        List<Map<String, Object>> optionMaps = options.stream().map(o -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("optionKey", o.getOptionKey());
            m.put("content", o.getContent());
            m.put("sortOrder", o.getSortOrder());
            return m;
        }).toList();
        return AnswerDataCodec.buildSnapshotJson(
                question.getQuestionType(),
                question.getStem(),
                question.getExplanation(),
                optionMaps,
                question.getAnswerDataJson());
    }
}
