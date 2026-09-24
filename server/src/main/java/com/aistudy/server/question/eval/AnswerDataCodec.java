package com.aistudy.server.question.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BUSINESS-008 — server-internal codec for {@code question.answer_data_json}.
 *
 * <p>Answer data is a small deterministic JSON document written ONLY
 * by the server ({@code answer_data_json} TEXT column). Its shape is
 * question-type specific:
 *
 * <pre>
 *   SINGLE_CHOICE    {"correctOptionKey":"A"}
 *   MULTIPLE_CHOICE  {"correctOptionKeys":["A","C"]}
 *   TRUE_FALSE       {"correctBoolean":true}
 *   SHORT_ANSWER     {"referenceAnswer":"..."}
 *   FILL_BLANK       {"referenceAnswer":"..."}
 *   ORDERING         {"correctOrder":[3,1,2]}
 *   MATCHING         {"matchesJson":"[{\"left\":\"A\",\"right\":\"1\"}]"}
 * </pre>
 *
 * <p>The same shape is embedded into practice/exam question snapshots
 * so grading stays stable even when the live question is later edited.
 *
 * <p>This data NEVER leaves the server through practice/exam question
 * views (api-guidelines.md §9). Only authoring responses expose it.
 */
public final class AnswerDataCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AnswerDataCodec() {
    }

    /** Builds the canonical JSON for one answer-data shape. */
    public static String buildAnswerDataJson(String correctOptionKey,
                                              List<String> correctOptionKeys,
                                              Boolean correctBoolean,
                                              String referenceAnswer) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (correctOptionKey != null) {
            map.put("correctOptionKey", correctOptionKey);
        }
        if (correctOptionKeys != null) {
            map.put("correctOptionKeys", correctOptionKeys);
        }
        if (correctBoolean != null) {
            map.put("correctBoolean", correctBoolean);
        }
        if (referenceAnswer != null) {
            map.put("referenceAnswer", referenceAnswer);
        }
        return write(map);
    }

    /** FILL_BLANK: stores the reference answer text (comma-delimited per blank). */
    public static String buildFillBlankAnswerData(String referenceAnswer) {
        return buildAnswerDataJson(null, null, null, referenceAnswer);
    }

    /** ORDERING: stores the correct sequence of item identifiers. */
    public static String buildOrderingAnswerData(List<Integer> correctOrder) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (correctOrder != null) {
            map.put("correctOrder", correctOrder);
        }
        return write(map);
    }

    /** MATCHING: stores the correct left→right pairs as a JSON array string. */
    public static String buildMatchingAnswerData(String matchesJson) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (matchesJson != null) {
            map.put("matchesJson", matchesJson);
        }
        return write(map);
    }

    /** Parses a stored answer-data document. */
    public static AnswerData parse(String answerDataJson) {
        try {
            Map<?, ?> map = MAPPER.readValue(answerDataJson, Map.class);
            Object keys = map.get("correctOptionKeys");
            @SuppressWarnings("unchecked")
            List<String> optionKeys = keys == null ? null : ((List<?>) keys).stream()
                    .map(String::valueOf)
                    .toList();
            List<Integer> order = parseIntegerList(map.get("correctOrder"));
            return new AnswerData(
                    map.get("correctOptionKey") == null ? null : String.valueOf(map.get("correctOptionKey")),
                    optionKeys,
                    map.get("correctBoolean") == null ? null : Boolean.valueOf(String.valueOf(map.get("correctBoolean"))),
                    map.get("referenceAnswer") == null ? null : String.valueOf(map.get("referenceAnswer")),
                    order,
                    map.get("matchesJson") == null ? null : String.valueOf(map.get("matchesJson"))
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt answer_data_json: " + answerDataJson, e);
        }
    }

    private static List<Integer> parseIntegerList(Object value) {
        if (value == null) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            List<?> raw = (List<?>) value;
            return raw.stream().map(o -> Integer.valueOf(String.valueOf(o))).toList();
        } catch (Exception e) {
            return null;
        }
    }

    /** Builds a practice/exam question snapshot document (server-side). */
    public static String buildSnapshotJson(String questionType,
                                           String stem,
                                           String explanation,
                                           List<Map<String, Object>> options,
                                           String answerDataJson) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("questionType", questionType);
        map.put("stem", stem);
        map.put("explanation", explanation);
        map.put("options", options == null ? List.of() : options);
        map.put("answerData", parse(answerDataJson));
        return write(map);
    }

    /**
     * Parses a stored question snapshot into the safe view parts
     * (type / stem / options) plus the server-internal answer data.
     */
    public static SnapshotView parseSnapshot(String snapshotJson) {
        try {
            Map<?, ?> map = MAPPER.readValue(snapshotJson, Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> options = map.get("options") == null
                    ? List.of()
                    : ((List<?>) map.get("options")).stream()
                            .map(o -> (Map<String, Object>) o)
                            .toList();
            Object answerDataObj = map.get("answerData");
            String answerDataJson = answerDataObj == null
                    ? null
                    : MAPPER.writeValueAsString(answerDataObj);
            return new SnapshotView(
                    String.valueOf(map.get("questionType")),
                    String.valueOf(map.get("stem")),
                    map.get("explanation") == null ? null : String.valueOf(map.get("explanation")),
                    options,
                    answerDataJson == null ? null : parse(answerDataJson)
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt question snapshot: " + snapshotJson, e);
        }
    }

    private static String write(Map<String, Object> map) {
        try {
            return MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize answer data", e);
        }
    }

    /**
     * Parsed answer-data view. Null fields mean "absent" for that type.
     */
    public record AnswerData(String correctOptionKey,
                             List<String> correctOptionKeys,
                             Boolean correctBoolean,
                             String referenceAnswer,
                             List<Integer> correctOrder,
                             String matchesJson) {

        /** Returns true when this answer data has enough structure to grade. */
        public boolean hasCorrectAnswer() {
            return correctOptionKey != null
                    || (correctOptionKeys != null && !correctOptionKeys.isEmpty())
                    || correctBoolean != null
                    || referenceAnswer != null
                    || (correctOrder != null && !correctOrder.isEmpty())
                    || matchesJson != null;
        }
    }

    /**
     * Parsed snapshot view: safe display parts plus the server-internal
     * answer data used for grading. {@code options} maps carry
     * optionKey / content / sortOrder (never correctness flags).
     */
    public record SnapshotView(String questionType,
                               String stem,
                               String explanation,
                               List<Map<String, Object>> options,
                               AnswerData answerData) {
    }

    /** Builds the server-written answer payload JSON from typed fields. */
    public static String buildAnswerPayloadJson(List<String> selectedOptionKeys,
                                                 Boolean booleanAnswer,
                                                 String textAnswer) {
        return buildAnswerPayloadJson(selectedOptionKeys, booleanAnswer, textAnswer, null, null);
    }

    /** Builds the server-written answer payload JSON including ORDERING/MATCHING. */
    public static String buildAnswerPayloadJson(List<String> selectedOptionKeys,
                                                 Boolean booleanAnswer,
                                                 String textAnswer,
                                                 List<Integer> orderingAnswer,
                                                 String matchingAnswer) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (selectedOptionKeys != null) {
            map.put("selectedOptionKeys", selectedOptionKeys);
        }
        if (booleanAnswer != null) {
            map.put("booleanAnswer", booleanAnswer);
        }
        if (textAnswer != null) {
            map.put("textAnswer", textAnswer);
        }
        if (orderingAnswer != null) {
            map.put("orderingAnswer", orderingAnswer);
        }
        if (matchingAnswer != null) {
            map.put("matchingAnswer", matchingAnswer);
        }
        return write(map);
    }

    /** Parses a stored answer payload back into typed fields. */
    public static AnswerPayloadView parseAnswerPayload(String answerPayloadJson) {
        try {
            Map<?, ?> map = MAPPER.readValue(answerPayloadJson, Map.class);
            Object keys = map.get("selectedOptionKeys");
            @SuppressWarnings("unchecked")
            List<String> optionKeys = keys == null ? null : ((List<?>) keys).stream()
                    .map(String::valueOf)
                    .toList();
            List<Integer> ordering = parseIntegerList(map.get("orderingAnswer"));
            String matching = map.get("matchingAnswer") == null ? null : String.valueOf(map.get("matchingAnswer"));
            return new AnswerPayloadView(
                    optionKeys,
                    map.get("booleanAnswer") == null ? null
                            : Boolean.valueOf(String.valueOf(map.get("booleanAnswer"))),
                    map.get("textAnswer") == null ? null : String.valueOf(map.get("textAnswer")),
                    ordering,
                    matching
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt answer payload: " + answerPayloadJson, e);
        }
    }

    /** Parsed answer payload view. */
    public record AnswerPayloadView(List<String> selectedOptionKeys,
                                    Boolean booleanAnswer,
                                    String textAnswer,
                                    List<Integer> orderingAnswer,
                                    String matchingAnswer) {
    }
}
