package com.aistudy.server.source.content;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-25 — the statement-level rot check for {@code ContentBlockMapper}.
 *
 * <p>{@code selectBySpaceSourceAssetOwner} filtered on
 * {@code cb.source_asset_id}, a column {@code content_block} never had (V011
 * created the table, V053 only added {@code extraction_revision_id}). MySQL
 * rejects that at EXECUTION time, so the query was a compile-clean 500 waiting
 * for its first caller — and the mapper unit tests could not see it because
 * they never open a connection.
 *
 * <p>This test closes that gap offline: it derives each table's column set from
 * the migration chain itself (CREATE TABLE plus every later {@code ADD COLUMN})
 * and then checks every {@code alias.column} reference in the mapper's SQL
 * against it. It runs in {@code mvn test} with no database, and it fails on a
 * new unknown column instead of on first use. {@code ContentBlockMapperStatementIntegrationTest}
 * is the companion that executes the same statements against real MySQL.
 *
 * <h3>Scope, stated honestly</h3>
 *
 * <p>Only qualified references are checked, because an unqualified name in a
 * single-table statement cannot be attributed to a table by regex without a
 * real SQL parser. That covers the F-25 shape exactly; the integration test is
 * what covers the rest.
 */
class ContentBlockMapperColumnGuardTest {

    private static final String MAPPER = "src/main/java/com/aistudy/server/source/content/mapper/ContentBlockMapper.java";
    private static final String MIGRATION_DIR = "src/main/resources/db/migration";

    /** Words that follow a table name in a FROM/JOIN but are not aliases. */
    private static final Set<String> NOT_AN_ALIAS = Set.of(
            "where", "on", "join", "inner", "left", "right", "set", "order", "group",
            "values", "and", "or", "using", "limit", "having", "union");

    private static final Pattern ANNOTATION = Pattern.compile("@(Select|Insert|Update|Delete)\\s*\\(");
    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern TABLE_REF = Pattern.compile(
            "(?i)\\b(?:FROM|JOIN)\\s+`?([a-z_][a-z0-9_]*)`?(?:\\s+(?!ON\\b|WHERE\\b|SET\\b|JOIN\\b|ORDER\\b|GROUP\\b|VALUES\\b|USING\\b|LIMIT\\b|UNION\\b)([a-z_][a-z0-9_]*))?");
    private static final Pattern QUALIFIED = Pattern.compile("\\b([a-z_][a-z0-9_]*)\\.([a-z_][a-z0-9_]*)\\b");
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?([a-z_][a-z0-9_]*)`?\\s*\\((.*?)\\n\\)\\s*(?:ENGINE|COMMENT|;)");
    private static final Pattern ALTER_TARGET = Pattern.compile(
            "(?i)ALTER\\s+TABLE\\s+`?([a-z_][a-z0-9_]*)`?\\s+([^;]*)");
    private static final Pattern ADDED_COLUMN = Pattern.compile(
            "(?i)\\bADD\\s+COLUMN\\s+`?([a-z_][a-z0-9_]*)`?");
    private static final Pattern COLUMN_DEFINITION = Pattern.compile(
            "(?im)^\\s*`?([a-z_][a-z0-9_]*)`?\\s+(?:[A-Z]+)(?:\\s*\\([^)]*\\))?\\s");
    private static final Set<String> NOT_A_COLUMN = Set.of(
            "primary", "unique", "key", "constraint", "index", "foreign", "check", "comment");

    @Test
    void everyQualifiedColumnInContentBlockMapperExistsInTheSchema() throws IOException {
        Map<String, Set<String>> schema = schemaFromMigrations();
        List<String> violations = new ArrayList<>();
        for (String statement : statements(Files.readString(mapperPath()))) {
            violations.addAll(qualifiedColumnViolations(statement, schema));
        }

        assertThat(violations)
                .describedAs("ContentBlockMapper names a column its table does not have; "
                        + "MySQL only reports that when the query runs.")
                .isEmpty();
    }

    /**
     * A guard that checks nothing also passes. This feeds the mapper's own
     * shapes back through the scanner, including the statement as it shipped
     * when F-25 was filed, and requires the bad one to be reported.
     */
    @Test
    void theGuardItselfCatchesTheColumnThatBrokeAtRuntime() throws IOException {
        Map<String, Set<String>> schema = schemaFromMigrations();

        assertThat(qualifiedColumnViolations(
                "SELECT cb.* FROM content_block cb "
                        + "WHERE cb.source_asset_id = 3 AND cb.space_id = 1", schema))
                .anyMatch(violation -> violation.contains("cb.source_asset_id"));

        assertThat(qualifiedColumnViolations(
                "SELECT cb.* FROM content_block cb "
                        + "JOIN source_page sp ON sp.id = cb.source_page_id "
                        + "WHERE sp.source_asset_id = 3 AND cb.normalized_text IS NOT NULL", schema))
                .isEmpty();
    }

    /** The exact F-25 regression, spelled out so a rename cannot hide it. */
    @Test
    void assetReadsJoinThroughSourcePageInsteadOfInventingABlockColumn() throws IOException {
        String text = Files.readString(mapperPath());
        List<String> statements = statements(text);

        // Checked against the SQL only: this file's own Javadoc is allowed to
        // name the column that used to be wrong.
        assertThat(statements).allSatisfy(statement -> {
            assertThat(statement).doesNotContain("cb.source_asset_id");
            assertThat(statement).doesNotContain("content_block.source_asset_id");
        });

        String assetStatement = statements.stream()
                // The DELETE shares the join; this test is about the SELECT.
                .filter(candidate -> candidate.trim().regionMatches(true, 0, "SELECT", 0, 6))
                .filter(candidate -> candidate.contains("sp.source_asset_id"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "selectBySpaceSourceAssetOwner no longer reaches the asset through source_page"));
        assertThat(assetStatement.replaceAll("\\s+", " "))
                .containsIgnoringCase("JOIN source_page sp ON sp.id = cb.source_page_id")
                .containsIgnoringCase("ORDER BY cb.sort_order")
                .containsIgnoringCase("ls.owner_subject");
    }

    // ==================== schema ====================

    /** Table name -> column names, as the applied migration chain defines them. */
    private Map<String, Set<String>> schemaFromMigrations() throws IOException {
        Map<String, Set<String>> schema = new HashMap<>();
        for (Path script : migrationScripts()) {
            String sql = withoutComments(Files.readString(script));
            Matcher created = CREATE_TABLE.matcher(sql);
            while (created.find()) {
                Set<String> columns = new LinkedHashSet<>();
                Matcher defined = COLUMN_DEFINITION.matcher(created.group(2));
                while (defined.find()) {
                    String candidate = defined.group(1).toLowerCase(Locale.ROOT);
                    if (!NOT_A_COLUMN.contains(candidate)) {
                        columns.add(candidate);
                    }
                }
                schema.put(created.group(1).toLowerCase(Locale.ROOT), columns);
            }
            Matcher altered = ALTER_TARGET.matcher(sql);
            while (altered.find()) {
                Set<String> columns = schema.computeIfAbsent(
                        altered.group(1).toLowerCase(Locale.ROOT), key -> new LinkedHashSet<>());
                Matcher added = ADDED_COLUMN.matcher(altered.group(2));
                while (added.find()) {
                    columns.add(added.group(1).toLowerCase(Locale.ROOT));
                }
            }
        }
        // Sanity: a guard that silently found no schema would pass everything.
        assertThat(schema).containsKeys("content_block", "source_page", "learning_space");
        assertThat(schema.get("content_block")).contains("extraction_revision_id", "normalized_text");
        assertThat(schema.get("content_block")).doesNotContain("source_asset_id");
        return schema;
    }

    private List<Path> migrationScripts() throws IOException {
        Path root = projectRelative(MIGRATION_DIR);
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> scripts = new ArrayList<>();
            paths.filter(path -> path.getFileName().toString().matches("V\\d+__.*\\.sql"))
                    .forEach(scripts::add);
            scripts.sort(Comparator.comparingLong(ContentBlockMapperColumnGuardTest::version));
            return scripts;
        }
    }

    private static long version(Path file) {
        String name = file.getFileName().toString();
        return Long.parseLong(name.substring(1, name.indexOf("__")));
    }

    // ==================== mapper SQL ====================

    /**
     * Every {@code alias.column} in one statement that resolves to a table this
     * migration chain knows, reported if that table has no such column.
     *
     * <p>Unknown prefixes (a subquery's {@code t}, a function) are skipped:
     * attributing them needs a parser, and a false positive here would push the
     * next developer to weaken the guard rather than fix the SQL.
     */
    private List<String> qualifiedColumnViolations(String statement,
                                                   Map<String, Set<String>> schema) {
        List<String> violations = new ArrayList<>();
        Map<String, String> aliases = aliasesOf(statement);
        Matcher reference = QUALIFIED.matcher(statement);
        while (reference.find()) {
            String table = aliases.get(reference.group(1).toLowerCase(Locale.ROOT));
            String column = reference.group(2).toLowerCase(Locale.ROOT);
            if (table == null || column.equals("*")) {
                continue;
            }
            Set<String> columns = schema.get(table);
            if (columns == null) {
                continue;
            }
            if (!columns.contains(column)) {
                violations.add(reference.group(1) + "." + column + " (table " + table
                        + ") in: " + flatten(statement));
            }
        }
        return violations;
    }

    private Path mapperPath() {
        return projectRelative(MAPPER);
    }

    /** The text of each {@code @Select}/{@code @Update} argument, literals joined. */
    private List<String> statements(String text) {
        List<String> statements = new ArrayList<>();
        Matcher annotation = ANNOTATION.matcher(text);
        while (annotation.find()) {
            String argument = readBalanced(text, annotation.end() - 1);
            if (argument != null) {
                StringBuilder sql = new StringBuilder();
                Matcher literal = LITERAL.matcher(argument);
                while (literal.find()) {
                    sql.append(literal.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
                }
                // The method name rides along on the same declaration; drop
                // everything from the closing string onwards is not possible
                // with regex, so keep only up to the first ')' of the signature
                // by joining literals, which already excludes the declaration.
                statements.add(sql.toString());
            }
        }
        return statements;
    }

    /** alias (or a bare table name, which is its own qualifier) -> table. */
    private Map<String, String> aliasesOf(String statement) {
        Map<String, String> aliases = new HashMap<>();
        Matcher reference = TABLE_REF.matcher(statement);
        while (reference.find()) {
            String table = reference.group(1).toLowerCase(Locale.ROOT);
            String alias = reference.group(2);
            aliases.putIfAbsent(table, table);
            if (alias != null && !NOT_AN_ALIAS.contains(alias.toLowerCase(Locale.ROOT))) {
                aliases.putIfAbsent(alias.toLowerCase(Locale.ROOT), table);
            }
        }
        return aliases;
    }

    private String readBalanced(String text, int openIndex) {
        int depth = 0;
        boolean inLiteral = false;
        for (int index = openIndex; index < text.length(); index++) {
            char current = text.charAt(index);
            if (inLiteral) {
                if (current == '\\') {
                    index++;
                } else if (current == '"') {
                    inLiteral = false;
                }
                continue;
            }
            if (current == '"') {
                inLiteral = true;
            } else if (current == '(') {
                depth++;
            } else if (current == ')' && --depth == 0) {
                return text.substring(openIndex + 1, index);
            }
        }
        return null;
    }

    private String withoutComments(String sql) {
        return LINE_COMMENT.matcher(sql).replaceAll("");
    }

    private static final Pattern LINE_COMMENT = Pattern.compile("(?m)--[^\\n]*$");

    private String flatten(String statement) {
        String flat = statement.replaceAll("\\s+", " ").trim();
        return flat.length() <= 160 ? flat : flat.substring(0, 157) + "...";
    }

    private Path projectRelative(String relative) {
        Path start = Path.of("").toAbsolutePath();
        while (start != null) {
            Path candidate = start.resolve("server").resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
            candidate = start.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
            start = start.getParent();
        }
        throw new IllegalStateException("No " + relative + " under " + Path.of("").toAbsolutePath());
    }
}
