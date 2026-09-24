package com.aistudy.server.space.scope;

import com.aistudy.server.common.problem.ApiErrorCodes;
import com.aistudy.server.common.problem.ApiException;
import com.aistudy.server.space.service.LearningSpaceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

/**
 * Structural floor for architecture.md §5.2: every request whose handler
 * declares {@code /api/v1/spaces/{spaceId}...} must come from a principal
 * that owns that LearningSpace, or it never reaches the handler.
 *
 * <p>Module services keep their own owner-scoped queries — this is not a
 * replacement for them but the safety net that turns "every endpoint must
 * remember to call {@link LearningSpaceService#getMine}" from a convention
 * into an enforced invariant. A space that exists but is not the caller's is
 * reported exactly like a space that does not exist: 404, no ownership hint.
 *
 * <p>The {@code spaceId} path variable is read from the mapping Spring already
 * resolved, so it cannot be influenced by query parameters or bodies
 * (api-guidelines.md §2).
 */
public class SpaceScopeInterceptor implements HandlerInterceptor {

    private static final String SPACE_ID = "spaceId";

    private final LearningSpaceService learningSpaceService;

    public SpaceScopeInterceptor(LearningSpaceService learningSpaceService) {
        this.learningSpaceService = learningSpaceService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            // The security chain owns 401 for these paths; answering 404 here
            // would break client token-refresh logic.
            return true;
        }

        Map<String, String> pathVariables = pathVariables(request);
        if (pathVariables == null) {
            return true;
        }
        Long spaceId = parseSpaceId(pathVariables.get(SPACE_ID));
        if (spaceId == null) {
            // Not a space-scoped handler, or a malformed id the handler itself
            // must reject with 400.
            return true;
        }

        if (learningSpaceService.getMine(authentication.getName(), spaceId) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, ApiErrorCodes.SPACE_NOT_FOUND,
                    "学习空间不存在或当前不可访问。");
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> pathVariables(HttpServletRequest request) {
        Object attribute = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return attribute instanceof Map<?, ?> map ? (Map<String, String>) map : null;
    }

    private Long parseSpaceId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
