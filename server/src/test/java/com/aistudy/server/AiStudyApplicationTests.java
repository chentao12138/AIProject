package com.aistudy.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class AiStudyApplicationTests {

    @Test
    void contextLoads() {
        // Verifies Spring Boot application context starts without error.
        // Uses "test" profile which excludes DataSource + MyBatis-Plus auto-config,
        // so this test does not require a running MySQL instance.
    }
}
