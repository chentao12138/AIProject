package com.aistudy.server;

import com.aistudy.server.spike.auth.SpikeSpaceMembershipRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class AiStudyApplicationTests {

    /**
     * MICRO-08B: mock the SPIKE-only membership repository so this
     * context-boot smoke test does not need a real
     * {@code JdbcTemplate} or {@code DataSource} bean on the classpath.
     *
     * <h3>Why this mock is required</h3>
     *
     * The {@code test} profile (see {@code application-test.yml})
     * deliberately excludes {@code DataSourceAutoConfiguration} so
     * unit-oriented tests run without a database. Since MICRO-07E-A
     * wired {@code SpikeSpaceAccess} to constructor-inject
     * {@code SpikeSpaceMembershipRepository}, and that repository
     * constructor-injects {@code JdbcTemplate}, the full application
     * context fails to boot under this profile with:
     * <pre>
     *   No qualifying bean of type
     *     'org.springframework.jdbc.core.JdbcTemplate'
     * </pre>
     *
     * Replacing the repository bean with a Mockito mock via
     * {@code @MockitoBean} prevents Spring from ever calling the
     * real repository's constructor, so the missing
     * {@code JdbcTemplate} dependency is never resolved.
     *
     * <h3>Why not stub the mock in this test</h3>
     *
     * {@code contextLoads()} verifies that the Spring ApplicationContext
     * starts without error — it does not touch authorization at all.
     * Therefore we add no {@code when(...)}, {@code given(...)}, or
     * {@code verify(...)} calls. The mock exists purely to keep the
     * Spring context bootable under the {@code test} profile.
     *
     * <h3>Why not change the profile</h3>
     *
     * This test is the smallest possible "does the Spring context
     * start at all" smoke test. Switching it to a database-backed
     * profile (e.g. {@code flyway-it}) would force it to stand up a
     * real MySQL, which is the wrong test for that. The
     * repository-to-database path is owned by
     * {@code SpikeSpaceMembershipIntegrationTest} and
     * {@code SpikeSpaceAuthorizationEndToEndIntegrationTest}.
     */
    @MockitoBean
    private SpikeSpaceMembershipRepository membershipRepository;

    @Test
    void contextLoads() {
        // Verifies Spring Boot application context starts without error.
        // Uses "test" profile which excludes DataSource + MyBatis-Plus auto-config,
        // so this test does not require a running MySQL instance.
    }
}
