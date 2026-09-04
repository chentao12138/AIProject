package com.aistudy.server.spike.mysql;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * SPIKE-002 ONLY.
 *
 * <p>Proves that the packaged Spring Boot JAR, when started with
 * {@code --spring.profiles.active=it}, really builds a live MySQL DataSource and
 * can execute SQL against the real database. This class is NOT part of the
 * production business feature set — it only exists to close the SPIKE-002
 * verification loop between the IT-class green tests and the shipped JAR.
 *
 * <p>Activation contract:
 * <ul>
 *   <li>Default profile: NOT executed (Spring Boot starts without a DataSource).</li>
 *   <li>{@code test} profile: NOT executed (unit tests exclude DataSource).</li>
 *   <li>{@code it} profile: executed at startup, after Hikari + MyBatis-Plus are ready.</li>
 * </ul>
 *
 * <p>Failure contract: any assertion that cannot be proven from the database
 * throws. The exception propagates out of {@link #run} and therefore aborts the
 * Spring Boot startup — no swallowing, no "log warn and continue".
 */
@Component
@Profile("it")
public class SpikeDatabaseStartupVerifier implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SpikeDatabaseStartupVerifier.class);

    private final JdbcTemplate jdbc;

    public SpikeDatabaseStartupVerifier(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 1. SELECT 1 — connectivity / driver / credentials sanity.
        Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
        if (one == null || one != 1) {
            throw new IllegalStateException(
                    "SPIKE-002 verifier: SELECT 1 did not return 1 (got: " + one + ")");
        }

        // 2. VERSION() — must be non-null and MySQL 8.x.
        String version = jdbc.queryForObject("SELECT VERSION()", String.class);
        if (version == null || !version.startsWith("8.")) {
            throw new IllegalStateException(
                    "SPIKE-002 verifier: VERSION() is not MySQL 8.x (got: " + version + ")");
        }

        // 3. DATABASE() — must be non-null (must be attached to a real schema).
        String database = jdbc.queryForObject("SELECT DATABASE()", String.class);
        if (database == null || database.isEmpty()) {
            throw new IllegalStateException(
                    "SPIKE-002 verifier: DATABASE() returned null/empty");
        }

        // 4. character_set_database — must be exactly utf8mb4.
        String charset = jdbc.queryForObject("SELECT @@character_set_database", String.class);
        if (!"utf8mb4".equals(charset)) {
            throw new IllegalStateException(
                    "SPIKE-002 verifier: character_set_database is not utf8mb4 (got: " + charset + ")");
        }

        // 5. collation_database — must start with utf8mb4.
        String collation = jdbc.queryForObject("SELECT @@collation_database", String.class);
        if (collation == null || !collation.startsWith("utf8mb4")) {
            throw new IllegalStateException(
                    "SPIKE-002 verifier: collation_database is not utf8mb4_* (got: " + collation + ")");
        }

        // 6. spike_record — the SPIKE table must be addressable.
        //    We do NOT require any rows; a SELECT that returns COUNT(*) >= 0
        //    proves the table exists and the connection can query it.
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM spike_record", Integer.class);
        if (count == null || count < 0) {
            throw new IllegalStateException(
                    "SPIKE-002 verifier: spike_record COUNT(*) returned invalid value (got: " + count + ")");
        }

        // Single success line, no secrets.
        log.info("SPIKE_DB_VERIFY_OK database={} mysqlVersion={} charset={} collation={} spikeRecordCount={}",
                database, version, charset, collation, count);
    }
}
