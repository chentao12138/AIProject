package com.aistudy.server.space.scope;

import com.aistudy.server.common.problem.ApiErrorCodes;
import com.aistudy.server.common.problem.ApiException;
import com.aistudy.server.space.entity.LearningSpace;
import com.aistudy.server.space.service.LearningSpaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerMapping;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The §5.2 floor: a space-scoped request whose space is not the caller's must
 * never reach the handler, and must be answered exactly like a missing space.
 */
class SpaceScopeInterceptorTest {

    private final LearningSpaceService learningSpaceService = mock(LearningSpaceService.class);
    private final SpaceScopeInterceptor interceptor = new SpaceScopeInterceptor(learningSpaceService);
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void allowsTheOwnerOfThePathSpace() {
        authenticate("alice");
        when(learningSpaceService.getMine("alice", 7L)).thenReturn(new LearningSpace());

        assertThat(interceptor.preHandle(spaceRequest("7"), response, new Object()))
                .isTrue();
    }

    @Test
    void rejectsForeignSpaceAsNotFoundWithoutRevealingOwnership() {
        authenticate("bob");
        when(learningSpaceService.getMine("bob", 7L)).thenReturn(null);

        assertThatThrownBy(() -> interceptor.preHandle(spaceRequest("7"), response, new Object()))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.code()).isEqualTo(ApiErrorCodes.SPACE_NOT_FOUND);
                    assertThat(ex.detail()).doesNotContain("bob").doesNotContain("owner");
                });
    }

    @Test
    void ignoresHandlersWithoutASpacePathVariable() {
        authenticate("alice");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        verify(learningSpaceService, never()).getMine(anyString(), anyLong());
    }

    @Test
    void leavesUnauthenticatedRequestsToTheSecurityChain() {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anon",
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThat(interceptor.preHandle(spaceRequest("7"), response, new Object())).isTrue();
        verify(learningSpaceService, never()).getMine(anyString(), anyLong());
    }

    @Test
    void doesNotInventAStatusForANonNumericSpaceSegment() {
        authenticate("alice");

        // Path-variable binding/reporting stays with the handler (400), not here.
        assertThatCode(() -> interceptor.preHandle(spaceRequest("not-a-number"), response, new Object()))
                .doesNotThrowAnyException();
        verify(learningSpaceService, never()).getMine(anyString(), anyLong());
    }

    @Test
    void readsTheSpaceIdFromTheResolvedMappingOnly() {
        authenticate("alice");
        when(learningSpaceService.getMine("alice", 7L)).thenReturn(new LearningSpace());

        MockHttpServletRequest request = spaceRequest("7");
        // A query parameter claiming a different space must not be consulted.
        request.setParameter("spaceId", "999");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        verify(learningSpaceService).getMine("alice", 7L);
        verify(learningSpaceService, never()).getMine("alice", 999L);
    }

    private MockHttpServletRequest spaceRequest(String spaceId) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v1/spaces/" + spaceId + "/statistics");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("spaceId", spaceId));
        return request;
    }

    private void authenticate(String subject) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(subject, "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }
}
