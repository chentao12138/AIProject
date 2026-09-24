package com.aistudy.server;

import com.aistudy.server.spike.auth.SpikeSpaceMembershipRepository;
import com.aistudy.server.testsupport.MapperMockRegistrar;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Does the real application context boot?
 *
 * <p>Runs under the {@code test} profile, which excludes DataSource and
 * MyBatis-Plus auto-configuration, so {@link MapperMockRegistrar} supplies a
 * mock per {@code @Mapper} instead of this file naming every mapper by hand —
 * a hand-kept list silently stopped covering the app as soon as mappers were
 * added without updating it, which is the one thing a smoke test must not do.
 *
 * <p>{@code contextLoads()} asserts wiring only: bean graph, configuration
 * properties, interceptor and advice registration. Persistence behaviour is
 * owned by the {@code flyway-it} tests.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MapperMockRegistrar.class)
class AiStudyApplicationTests {

    /**
     * Not a {@code @Mapper}: the SPIKE membership repository constructor-injects
     * {@code JdbcTemplate}, which this profile has no bean for.
     */
    @MockitoBean
    private SpikeSpaceMembershipRepository membershipRepository;

    @Test
    void contextLoads() {
    }
}
