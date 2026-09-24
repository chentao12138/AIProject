package com.aistudy.server.space.scope;

import com.aistudy.server.space.service.LearningSpaceService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link SpaceScopeInterceptor} on the space-scoped API surface.
 *
 * <p>{@code /api/v1/admin/**} is deliberately not covered: admin governance
 * reads span owners by design and is guarded by ROLE_ADMIN
 * (api-guidelines.md §3).
 */
@Configuration
public class SpaceScopeWebConfig implements WebMvcConfigurer {

    private final LearningSpaceService learningSpaceService;

    public SpaceScopeWebConfig(LearningSpaceService learningSpaceService) {
        this.learningSpaceService = learningSpaceService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SpaceScopeInterceptor(learningSpaceService))
                .addPathPatterns("/api/v1/spaces/**");
    }
}
