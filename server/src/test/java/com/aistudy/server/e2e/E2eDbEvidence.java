package com.aistudy.server.e2e;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

/**
 * ELECTRON-CORS-001-C PART 4 — READ-ONLY database evidence.
 *
 * <p>Queries the flyway-it MySQL schema for every business record
 * created through the real HTTP API under subject
 * {@code desktop-e2e-user} (prefix {@code E2E-C-}) and prints the raw
 * rows. SELECT * only — no guessed column names; the schema itself
 * decides the shape. Nothing is modified.
 *
 * <p>Deliberately NOT a {@code *Test} class name so the full Maven
 * suite never runs it; executed only via
 * {@code -Dtest=E2eDbEvidence test}.
 */
@SpringBootTest
@ActiveProfiles("flyway-it")
public class E2eDbEvidence {

    private static final String SUBJECT = E2eBackendHarness.E2E_SUBJECT;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void printEvidence() {
        String spaceIds = "(SELECT id FROM learning_space WHERE owner_subject = ?)";
        System.out.println("=== E2E DB EVIDENCE (subject=" + SUBJECT + ") ===");

        List<Map<String, Object>> spaces = jdbcTemplate.queryForList(
                "SELECT * FROM learning_space WHERE owner_subject = ?", SUBJECT);
        System.out.println("-- learning_space rows: " + spaces.size());
        spaces.forEach(r -> System.out.println("SPACE " + r));

        List<Map<String, Object>> sources = jdbcTemplate.queryForList(
                "SELECT * FROM source WHERE space_id IN " + spaceIds, SUBJECT);
        System.out.println("-- source rows: " + sources.size());
        sources.forEach(r -> System.out.println("SOURCE " + r));

        List<Map<String, Object>> categories = jdbcTemplate.queryForList(
                "SELECT * FROM knowledge_category WHERE space_id IN " + spaceIds, SUBJECT);
        System.out.println("-- knowledge_category rows: " + categories.size());
        categories.forEach(r -> System.out.println("CATEGORY " + r));

        List<Map<String, Object>> points = jdbcTemplate.queryForList(
                "SELECT * FROM knowledge_point WHERE space_id IN " + spaceIds, SUBJECT);
        System.out.println("-- knowledge_point rows: " + points.size());
        points.forEach(r -> System.out.println("POINT " + r));

        System.out.println("=== E2E DB EVIDENCE END ===");
    }
}
