package com.aistudy.server.testsupport;

import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.AutoConfiguration;

/**
 * Applies {@link MapperMockRegistrar} to every Spring test context that runs
 * under the {@code test} profile.
 *
 * <p>The {@code test} profile excludes DataSource and MyBatis-Plus
 * auto-configuration, so no real mapper can exist there and a mock cannot
 * shadow one. Registration is therefore scoped to that profile only: the
 * {@code flyway-it} tests must keep resolving real mappers against a real
 * database, and a globally registered mock would silently replace them.
 */
@AutoConfiguration
@Profile("test")
@Import(MapperMockRegistrar.class)
public class TestProfileMapperMockAutoConfiguration {
}
