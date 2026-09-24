package com.aistudy.server.testsupport;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deletes everything a set of test subjects owns, deriving the table list from
 * the live foreign-key graph instead of a hand-kept list.
 *
 * <p>Integration tests used to carry their own {@code DELETE FROM ...} ladder.
 * Those ladders drifted: V031-V056 added tables and foreign keys
 * ({@code review_state}, {@code extraction_revision}, {@code folder_import_*})
 * that no ladder mentioned, so {@code DELETE FROM learning_space} began failing
 * with "Cannot delete or update a parent row" and roughly 700 unrelated
 * {@code DataIntegrityViolationException}s landed on other tests. Anything newly
 * referenced is now picked up automatically.
 *
 * <p>Order is topological (descendants first) so ordinary FK enforcement works;
 * {@code source <-> extraction_revision} is mutually referenced, so FK checks are
 * disabled for the duration of one cleanup rather than pretending an order
 * exists. That is safe here because every table in the ownership subtree is
 * cleared by the same predicate in the same pass, so no orphan rows are left for
 * the next test class.
 *
 * <p>Tables without a {@code space_id} column ({@code ai_message},
 * {@code exam_diagnosis_item}, ...) are reached through the foreign key of one
 * that has it.
 */
public final class OwnedSpaceReset {

    private static final String ROOT = "learning_space";
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");

    private OwnedSpaceReset() {
    }

    public static int forSubjects(JdbcTemplate jdbc, String... ownerSubjects) {
        return forSubjects(jdbc, List.of(ownerSubjects));
    }

    /** @return rows deleted across every touched table. */
    public static int forSubjects(JdbcTemplate jdbc, Collection<String> ownerSubjects) {
        List<String> subjects = new ArrayList<>();
        for (String subject : ownerSubjects) {
            if (subject != null && !subject.isBlank()) {
                subjects.add(subject);
            }
        }
        if (subjects.isEmpty()) {
            return 0;
        }

        Graph graph = Graph.load(jdbc);
        Object[] args = subjects.toArray();
        String group = placeholders(subjects.size());
        int deleted = 0;

        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        try {
            for (String table : graph.descendantsLeafFirst()) {
                deleted += jdbc.update("DELETE FROM " + table + " WHERE "
                        + graph.predicateOf(table, group), args);
            }
            deleted += jdbc.update("DELETE FROM " + ROOT + " WHERE owner_subject IN ("
                    + group + ")", args);
        } finally {
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
        return deleted;
    }

    /** The ownership subtree under {@code learning_space}. */
    private static final class Graph {

        private record Edge(String child, String childColumn, String parent, String parentColumn) {
        }

        private final Set<String> spaceScoped;
        private final List<Edge> edges;
        /** The edge chosen to reach each child, keyed by child table. */
        private final Map<String, Edge> entryEdge = new LinkedHashMap<>();

        private Graph(Set<String> spaceScoped, List<Edge> edges) {
            this.spaceScoped = spaceScoped;
            this.edges = edges;
            discover();
        }

        static Graph load(JdbcTemplate jdbc) {
            Set<String> scoped = new HashSet<>(jdbc.queryForList(
                    "SELECT DISTINCT table_name FROM information_schema.columns"
                            + " WHERE table_schema = DATABASE() AND column_name = 'space_id'",
                    String.class));
            // KEY_COLUMN_USAGE carries child and parent sides on one row; joining
            // REFERENTIAL_CONSTRAINTS to it by constraint name alone multiplies rows,
            // because constraint names are only unique per table.
            List<Edge> found = jdbc.query(
                    "SELECT TABLE_NAME child, COLUMN_NAME child_column,"
                            + " REFERENCED_TABLE_NAME parent, REFERENCED_COLUMN_NAME parent_column"
                            + " FROM information_schema.KEY_COLUMN_USAGE"
                            + " WHERE TABLE_SCHEMA = DATABASE()"
                            + "   AND REFERENCED_TABLE_NAME IS NOT NULL",
                    (row, index) -> new Edge(row.getString("child"), row.getString("child_column"),
                            row.getString("parent"), row.getString("parent_column")));
            return new Graph(scoped, found);
        }

        /** Breadth-first walk of the "who references the space subtree" relation. */
        private void discover() {
            Set<String> reached = new LinkedHashSet<>();
            reached.add(ROOT);
            Deque<String> queue = new ArrayDeque<>();
            queue.add(ROOT);
            while (!queue.isEmpty()) {
                String parent = queue.poll();
                for (Edge edge : edges) {
                    if (!edge.parent().equals(parent) || edge.child().equals(parent)
                            || reached.contains(edge.child())) {
                        continue;
                    }
                    reached.add(edge.child());
                    entryEdge.put(edge.child(), edge);
                    queue.add(edge.child());
                }
            }
        }

        /**
         * Descendants first, deepest level ahead of shallower ones, so an ordinary
         * FK violation would be impossible even without {@code FOREIGN_KEY_CHECKS}.
         */
        List<String> descendantsLeafFirst() {
            Map<String, Integer> depth = new HashMap<>();
            entryEdge.keySet().forEach(table -> depth.put(table, depthOf(table, depth)));
            List<String> tables = new ArrayList<>(entryEdge.keySet());
            tables.sort((left, right) -> Integer.compare(depth.get(right), depth.get(left)));
            return tables;
        }

        private int depthOf(String table, Map<String, Integer> memo) {
            Edge edge = entryEdge.get(table);
            if (edge == null || edge.parent().equals(ROOT)) {
                return 1;
            }
            int computed = 1 + depthOf(edge.parent(), memo);
            memo.put(table, computed);
            return computed;
        }

        /** Predicate matching {@code table}'s rows owned by a subject group. */
        String predicateOf(String table, String group) {
            requireSafe(table);
            if (spaceScoped.contains(table)) {
                return "space_id IN (SELECT id FROM " + ROOT + " WHERE owner_subject IN ("
                        + group + "))";
            }
            Edge edge = entryEdge.get(table);
            if (edge == null) {
                throw new IllegalStateException("No ownership path reaches table " + table);
            }
            requireSafe(edge.childColumn());
            requireSafe(edge.parentColumn());
            return edge.childColumn() + " IN (SELECT " + edge.parentColumn() + " FROM "
                    + edge.parent() + " WHERE " + predicateOf(edge.parent(), group) + ")";
        }

        private static void requireSafe(String identifier) {
            if (!SAFE_IDENTIFIER.matcher(identifier).matches()) {
                throw new IllegalStateException(
                        "Unexpected identifier from information_schema: " + identifier);
            }
        }
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }
}
