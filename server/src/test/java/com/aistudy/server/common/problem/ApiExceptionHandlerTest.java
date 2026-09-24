package com.aistudy.server.common.problem;

import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * api-guidelines.md §12: every failure leaves as problem+json with a stable
 * {@code code} and the MDC {@code requestId}, without changing the status code
 * the throwing site asked for.
 */
class ApiExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FixtureController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
        MDC.put("requestId", "req-fixed-1");
    }

    @Test
    void apiExceptionKeepsItsOwnCode() throws Exception {
        mockMvc.perform(get("/fixture/space-missing"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value(ApiErrorCodes.SPACE_NOT_FOUND))
                .andExpect(jsonPath("$.detail").value("学习空间不存在或当前不可访问。"))
                .andExpect(jsonPath("$.requestId").value("req-fixed-1"));
    }

    @Test
    void legacyResponseStatusExceptionKeepsStatusAndGetsDerivedCode() throws Exception {
        mockMvc.perform(get("/fixture/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ApiErrorCodes.CONFLICT))
                .andExpect(jsonPath("$.detail").value("Session already submitted"));
    }

    @Test
    void bodyValidationReportsFieldErrors() throws Exception {
        mockMvc.perform(post("/fixture/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiErrorCodes.VALIDATION_ERROR))
                .andExpect(jsonPath("$.errors[0].field").value("title"));
    }

    @Test
    void illegalStateNeverLeaksItsMessage() throws Exception {
        mockMvc.perform(get("/fixture/internal"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(ApiErrorCodes.INTERNAL_ERROR))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("jdbc://internal-host"))));
    }

    @RestController
    private static class FixtureController {

        @GetMapping("/fixture/space-missing")
        void spaceMissing() {
            throw new ApiException(HttpStatus.NOT_FOUND, ApiErrorCodes.SPACE_NOT_FOUND,
                    "学习空间不存在或当前不可访问。");
        }

        @GetMapping("/fixture/conflict")
        void conflict() {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Session already submitted");
        }

        @GetMapping("/fixture/internal")
        void internal() {
            throw new IllegalStateException("connection refused to jdbc://internal-host");
        }

        @PostMapping("/fixture/echo")
        void echo(@RequestBody @jakarta.validation.Valid TitleRequest request) {
        }
    }

    private record TitleRequest(@NotBlank String title) {
    }
}
