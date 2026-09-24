package com.aistudy.server.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The ratchet that makes architecture.md §4 rules checkable.
 *
 * <p>Module boundaries in a single Maven project exist only as conventions
 * unless something reads them back. This test is that something — deliberately
 * dependency-free (regex over the source's own import statements) so it runs
 * offline and in the plain {@code test} profile, without a database.
 *
 * <p>Both rules carry an explicit legacy baseline. The point is that the list
 * only ever shrinks: a new violation fails the build, and a fixed one fails
 * until it is removed from the baseline.
 */
class ModuleBoundaryTest {

    private static final String BASE_PACKAGE = "com.aistudy.server";

    /**
     * Controller classes that still reach a Mapper directly
     * (architecture.md §4 forbids it). Move the read into the owning module's
     * service and delete the entry — never add one.
     */
    private static final Set<String> MAPPER_IN_CONTROLLER_BASELINE = Set.of(
            "admin.ai.controller.AdminAiJobController",
            "admin.bulk.controller.AdminBulkController",
            "admin.exam.controller.AdminExamController",
            "admin.knowledge.controller.AdminKnowledgeController",
            "admin.question.controller.AdminQuestionController",
            "admin.space.controller.AdminSpaceController",
            "exam.controller.ExamController",
            "operations.AdminOperationsController",
            "question.controller.VariantController"
    );

    /** Same rule for ad-hoc SQL outside the persistence layer (§14). */
    private static final Set<String> JDBC_IN_CONTROLLER_BASELINE = Set.of();

    /**
     * Top-level modules that are mutually reachable today. Measured, not
     * assumed: the pairwise cycles {@code question<->ai}, {@code question<->practice},
     * {@code mastery<->studyplan}, {@code operations<->storage} are connected
     * to each other through shared back-edges, so they form ONE blob of twelve
     * modules rather than four isolated pairs — i.e. 12 of 21 modules currently
     * have no acyclic layering at all, which is what ADR-024's "split later if
     * needed" argument depends on NOT having.
     *
     * <p>The ratchet here is "no further module may join the blob", and any
     * module that leaves it must be deleted from this string.
     */
    private static final Set<String> MODULE_CYCLE_BASELINE = Set.of(
            "ai|config|exam|knowledge|mastery|operations|practice|provenance|question|source|storage|studyplan"
    );

    private static final int CROSS_MODULE_MAPPER_IMPORT_CAP = 85;

    @Test
    void controllersMustNotReachMappersOrRawSql() throws IOException {
        List<SourceFile> sources = readSources();
        List<String> mapperViolations = new ArrayList<>();
        List<String> jdbcViolations = new ArrayList<>();

        for (SourceFile source : sources) {
            if (!source.controllerLike()) {
                continue;
            }
            if (source.imports().stream().anyMatch(imported -> imported.contains(".mapper."))) {
                mapperViolations.add(withoutBasePackage(source.fqcn()));
            }
            if (source.imports().stream().anyMatch(imported -> imported.startsWith("org.springframework.jdbc"))) {
                jdbcViolations.add(withoutBasePackage(source.fqcn()));
            }
        }

        assertThat(mapperViolations)
                .describedAs("Controllers importing a Mapper bypass the owning module's service "
                        + "(architecture.md §4). New: %s / fixed but still baselined: %s",
                        difference(mapperViolations, MAPPER_IN_CONTROLLER_BASELINE),
                        difference(MAPPER_IN_CONTROLLER_BASELINE, mapperViolations))
                .containsExactlyInAnyOrderElementsOf(MAPPER_IN_CONTROLLER_BASELINE);
        assertThat(jdbcViolations)
                .describedAs("Controllers using JdbcTemplate put persistence and state transitions "
                        + "in the transport layer (architecture.md §4, §14)")
                .containsExactlyInAnyOrderElementsOf(JDBC_IN_CONTROLLER_BASELINE);
    }

    @Test
    void moduleGraphMustNotGainCycles() throws IOException {
        Map<String, Set<String>> edges = moduleEdges(readSources());
        Set<String> cycles = stronglyConnectedModules(edges);

        Set<String> introduced = difference(cycles, MODULE_CYCLE_BASELINE);
        Set<String> resolved = difference(MODULE_CYCLE_BASELINE, cycles);
        if (!introduced.isEmpty() || !resolved.isEmpty()) {
            fail("Module cycles changed. New cycles (fix, do not baseline): " + introduced
                    + "; resolved cycles (remove from MODULE_CYCLE_BASELINE): " + resolved
                    + "; current: " + new TreeSet<>(cycles));
        }
    }

    @Test
    void crossModuleMapperImportsMustNotGrow() throws IOException {
        int count = 0;
        for (SourceFile source : readSources()) {
            String ownModule = moduleOf(source.fqcn());
            if (ownModule == null) {
                continue;
            }
            for (String imported : source.imports()) {
                if (!imported.contains(".mapper.")) {
                    continue;
                }
                String target = moduleOf(imported);
                if (target != null && !target.equals(ownModule)) {
                    count++;
                }
            }
        }
        assertThat(count)
                .describedAs("Imports of another module's Mapper (architecture.md §4: "
                        + "\"Mapper 不被其他模块任意直接调用以绕过应用服务\"). Measured value is the "
                        + "ceiling; lower it by routing a call through the owning service.")
                .isLessThanOrEqualTo(CROSS_MODULE_MAPPER_IMPORT_CAP);
    }

    // ==================== scanning ====================

    private List<SourceFile> readSources() throws IOException {
        Path root = sourceRoot();
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> javaFiles = paths
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .toList();
            List<SourceFile> parsed = new ArrayList<>(javaFiles.size());
            for (Path file : javaFiles) {
                parsed.add(parse(root, file));
            }
            return parsed;
        }
    }

    private Path sourceRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            Path direct = candidate.resolve("src/main/java");
            if (Files.isDirectory(direct)) {
                return direct;
            }
            Path server = candidate.resolve("server/src/main/java");
            if (Files.isDirectory(server)) {
                return server;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "Could not locate src/main/java from " + Path.of("").toAbsolutePath()
                        + "; ModuleBoundaryTest must run inside the server module.");
    }

    private static final Pattern IMPORT = Pattern.compile(
            "^import\\s+(?:static\\s+)?([A-Za-z0-9_.$]+)\\s*;");

    private SourceFile parse(Path root, Path file) {
        try {
            String relative = root.relativize(file).toString().replace('\\', '/');
            String withoutExt = relative.substring(0, relative.length() - ".java".length());
            String fqcn = withoutExt.replace('/', '.');
            Set<String> imports = new LinkedHashSet<>();
            for (String line : Files.readAllLines(file)) {
                Matcher matcher = IMPORT.matcher(line.trim());
                if (matcher.matches()) {
                    imports.add(matcher.group(1));
                }
            }
            String simpleName = file.getFileName().toString().replace(".java", "");
            boolean controllerLike = relative.contains("/controller/") || simpleName.endsWith("Controller");
            return new SourceFile(fqcn, simpleName, controllerLike, imports);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private Map<String, Set<String>> moduleEdges(List<SourceFile> sources) {
        Map<String, Set<String>> edges = new HashMap<>();
        for (SourceFile source : sources) {
            String from = moduleOf(source.fqcn());
            if (from == null) {
                continue;
            }
            for (String imported : source.imports()) {
                String to = moduleOf(imported);
                if (to == null || to.equals(from)) {
                    continue;
                }
                edges.computeIfAbsent(from, key -> new HashSet<>()).add(to);
            }
        }
        return edges;
    }

    /** First package segment under the base package, i.e. the module name. */
    private String moduleOf(String fqcn) {
        if (!fqcn.startsWith(BASE_PACKAGE + ".")) {
            return null;
        }
        String rest = fqcn.substring(BASE_PACKAGE.length() + 1);
        int dot = rest.indexOf('.');
        return dot < 0 ? null : rest.substring(0, dot);
    }

    /** Modules that are mutually reachable, each keyed by its sorted names. */
    private Set<String> stronglyConnectedModules(Map<String, Set<String>> edges) {
        Set<String> modules = new TreeSet<>(edges.keySet());
        edges.values().forEach(modules::addAll);

        Map<String, Set<String>> reachable = new HashMap<>();
        for (String module : modules) {
            reachable.put(module, reachableFrom(module, edges));
        }

        Set<String> claimed = new HashSet<>();
        Set<String> cycles = new TreeSet<>();
        for (String module : modules) {
            if (claimed.contains(module)) {
                continue;
            }
            Set<String> members = new TreeSet<>();
            for (String other : modules) {
                if (!other.equals(module) && reachable.get(module).contains(other)
                        && reachable.get(other).contains(module)) {
                    members.add(other);
                }
            }
            if (!members.isEmpty()) {
                members.add(module);
                claimed.addAll(members);
                cycles.add(String.join("|", members));
            }
        }
        return cycles;
    }

    private Set<String> reachableFrom(String start, Map<String, Set<String>> edges) {
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            for (String next : edges.getOrDefault(queue.poll(), Set.of())) {
                if (seen.add(next)) {
                    queue.add(next);
                }
            }
        }
        return seen;
    }

    private String withoutBasePackage(String fqcn) {
        return fqcn.startsWith(BASE_PACKAGE + ".") ? fqcn.substring(BASE_PACKAGE.length() + 1) : fqcn;
    }

    private Set<String> difference(Iterable<String> actual, Iterable<String> baseline) {
        Set<String> excluded = new HashSet<>();
        baseline.forEach(excluded::add);
        List<String> sorted = new ArrayList<>();
        actual.forEach(sorted::add);
        sorted.sort(Comparator.naturalOrder());
        Set<String> result = new LinkedHashSet<>();
        for (String value : sorted) {
            if (!excluded.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    private record SourceFile(String fqcn, String simpleName, boolean controllerLike,
                              Set<String> imports) {
    }
}
