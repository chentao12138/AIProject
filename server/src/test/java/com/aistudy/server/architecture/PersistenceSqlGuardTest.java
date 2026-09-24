package com.aistudy.server.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two invariants that cost a real debugging session each, checked offline.
 *
 * <p>Like {@link ModuleBoundaryTest} this reads its own source tree with regex
 * instead of booting Spring, so it runs in the plain {@code test} phase without
 * a database.
 */
class PersistenceSqlGuardTest {

    /**
     * MyBatis only decodes XML entities when the statement is a {@code <script>}.
     * Elsewhere {@code &lt;} is sent to MySQL verbatim and parses as
     * {@code & , l, t} — a syntax error that only shows when the query runs,
     * which is how the exam auto-submit sweeper shipped broken.
     */
    @Test
    void annotationSqlMustNotCarryXmlEntitiesUnlessItIsAScript() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : javaSources()) {
            String text = Files.readString(file);
            if (!text.contains("&lt;") && !text.contains("&gt;") && !text.contains("&amp;")) {
                continue;
            }
            for (String statement : annotatedStatements(text)) {
                if (statement.contains("<script>")) {
                    continue;
                }
                if (statement.contains("&lt;") || statement.contains("&gt;") || statement.contains("&amp;")) {
                    violations.add(displayPath(file) + ": " + abbreviate(statement));
                }
            }
        }
        assertThat(violations)
                .describedAs("XML entities in a plain @Select/@Insert/@Update/@Delete reach MySQL "
                        + "literally. Write < and > directly, or wrap the statement in <script> "
                        + "(needed anyway for <if>/<foreach>).")
                .isEmpty();
    }

    /**
     * Flyway applies versioned migrations in order, so a second plain
     * {@code CREATE TABLE} for a table an earlier migration already created
     * kills every from-scratch build — including {@code mvn test} against a
     * fresh schema.
     */
    @Test
    void migrationsMustNotRecreateATableWithoutIfNotExists() throws IOException {
        Set<String> created = new HashSet<>();
        List<String> violations = new ArrayList<>();

        for (Path file : migrationScripts()) {
            Matcher matcher = CREATE_TABLE.matcher(withoutSqlComments(Files.readString(file)));
            while (matcher.find()) {
                boolean idempotent = matcher.group(1) != null;
                String table = matcher.group(2).toLowerCase(Locale.ROOT);
                if (!created.add(table) && !idempotent) {
                    violations.add(displayPath(file) + " recreates `" + table
                            + "` with a plain CREATE TABLE");
                }
            }
        }
        assertThat(violations)
                .describedAs("A table may be created once per chain; a deliberate re-run needs "
                        + "CREATE TABLE IF NOT EXISTS (see V050 vs V013).")
                .isEmpty();
    }

    // ==================== scanning ====================

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)CREATE\\s+TABLE\\s+(IF\\s+NOT\\s+EXISTS\\s+)?`?([A-Za-z0-9_]+)`?");

    private static final Pattern ANNOTATION = Pattern.compile("@(Select|Insert|Update|Delete)\\s*\\(");

    private static final Pattern LINE_COMMENT = Pattern.compile("(?m)--[^\\n]*$");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");

    /** Keep prose such as "-- CREATE TABLE made the chain die" out of the scan. */
    private String withoutSqlComments(String script) {
        return BLOCK_COMMENT
                .matcher(LINE_COMMENT.matcher(script).replaceAll(""))
                .replaceAll("");
    }

    private List<Path> javaSources() throws IOException {
        Path root = projectRelative("src/main/java");
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }

    private List<Path> migrationScripts() throws IOException {
        Path root = projectRelative("src/main/resources/db/migration");
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> scripts = new ArrayList<>();
            paths.filter(path -> path.getFileName().toString().matches("V\\d+__.*\\.sql"))
                    .forEach(scripts::add);
            scripts.sort(Comparator.comparingLong(PersistenceSqlGuardTest::migrationVersion));
            return scripts;
        }
    }

    private static long migrationVersion(Path file) {
        String name = file.getFileName().toString();
        return Long.parseLong(name.substring(1, name.indexOf("__")));
    }

    /**
     * The argument text of every {@code @Select}/{@code @Insert}/{@code @Update}/
     * {@code @Delete}, with the surrounding {@code "..."} quotes stripped from
     * each concatenated piece so a comparator sees the SQL MyBatis receives.
     */
    private List<String> annotatedStatements(String text) {
        List<String> statements = new ArrayList<>();
        Matcher matcher = ANNOTATION.matcher(text);
        while (matcher.find()) {
            String argument = readBalanced(text, matcher.end() - 1);
            if (argument != null) {
                statements.add(joinLiterals(argument));
            }
        }
        return statements;
    }

    /** Substring between {@code openIndex}'s "(" and its matching ")". */
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

    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    private String joinLiterals(String argument) {
        Matcher matcher = LITERAL.matcher(argument);
        StringBuilder sql = new StringBuilder();
        while (matcher.find()) {
            sql.append(matcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
        }
        return sql.toString();
    }

    private String abbreviate(String statement) {
        String flat = statement.replaceAll("\\s+", " ").trim();
        return flat.length() <= 120 ? flat : flat.substring(0, 117) + "...";
    }

    private String displayPath(Path file) {
        return Path.of("").toAbsolutePath().relativize(file).toString().replace('\\', '/');
    }

    private Path projectRelative(String relative) {
        Path start = Path.of("").toAbsolutePath();
        while (start != null) {
            Path candidate = start.resolve(relative);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            Path inServer = start.resolve("server").resolve(relative);
            if (Files.isDirectory(inServer)) {
                return inServer;
            }
            start = start.getParent();
        }
        throw new IllegalStateException("No " + relative + " under " + Path.of("").toAbsolutePath());
    }
}
