package com.aistudy.server.operations;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUSINESS-025 — RequestIdFilter contract.
 */
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    private FilterChain recordingChain(java.util.concurrent.atomic.AtomicReference<String> mdcDuring) {
        return (request, response) -> mdcDuring.set(MDC.get("requestId"));
    }

    @Test
    void missingHeaderGeneratesRequestIdAndSetsResponseHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/spaces");
        MockHttpServletResponse response = new MockHttpServletResponse();
        java.util.concurrent.atomic.AtomicReference<String> mdcDuring = new java.util.concurrent.atomic.AtomicReference<>();

        filter.doFilter(request, response, recordingChain(mdcDuring));

        String responseId = response.getHeader("X-Request-Id");
        assertNotNull(responseId);
        assertTrue(responseId.matches("^[A-Za-z0-9._-]{1,64}$"));
        assertEquals(responseId, mdcDuring.get());
        assertNull(MDC.get("requestId"), "MDC must be cleared after the request");
    }

    @Test
    void validIncomingHeaderIsReused() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.addHeader("X-Request-Id", "trace-abc.123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        java.util.concurrent.atomic.AtomicReference<String> mdcDuring = new java.util.concurrent.atomic.AtomicReference<>();

        filter.doFilter(request, response, recordingChain(mdcDuring));

        assertEquals("trace-abc.123", response.getHeader("X-Request-Id"));
        assertEquals("trace-abc.123", mdcDuring.get());
    }

    @Test
    void invalidIncomingHeaderIsReplaced() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.addHeader("X-Request-Id", "bad id with spaces\ninject");
        MockHttpServletResponse response = new MockHttpServletResponse();
        java.util.concurrent.atomic.AtomicReference<String> mdcDuring = new java.util.concurrent.atomic.AtomicReference<>();

        filter.doFilter(request, response, recordingChain(mdcDuring));

        String responseId = response.getHeader("X-Request-Id");
        assertNotNull(responseId);
        assertTrue(responseId.matches("^[A-Za-z0-9._-]{1,64}$"));
        assertEquals(responseId, mdcDuring.get());
    }

    @Test
    void overlongHeaderIsReplaced() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.addHeader("X-Request-Id", "a".repeat(65));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        String responseId = response.getHeader("X-Request-Id");
        assertNotNull(responseId);
        assertTrue(responseId.length() <= 64);
    }
}
