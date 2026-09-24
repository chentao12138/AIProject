package com.aistudy.server.config.properties;

import com.aistudy.server.ai.config.AiProperties;
import com.aistudy.server.auth.security.AuthProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Canonical registration point for typed application properties.
 */
@Configuration
@EnableConfigurationProperties({
        AuthProperties.class,
        StorageProperties.class,
        UploadProperties.class,
        CorsProperties.class,
        OperationsProperties.class,
        BootstrapAdminProperties.class,
        IngestionWorkerProperties.class,
        AiProperties.class
})
public class ApplicationPropertiesConfig {
}
