package com.aistudy.server.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit F-30 / contract request C12 ratchet: a conflict the client has to
 * branch on carries a machine-readable {@code code}, not only an English reason.
 *
 * <p>{@code ProblemDetail} already emits {@code code}; the gap was that
 * {@code new ResponseStatusException(HttpStatus.CONFLICT, "…")} falls back to
 * the generic {@code CONFLICT}, so a UI could only match on prose. Those sites are
 * being migrated to {@code ApiException.conflict(ApiErrorCodes.X, …)}, and this
 * test is what stops the list from growing back: a new offender fails the build,
 * and a fixed one fails until it is deleted from the baseline.
 */
class ErrorCodeRatchetTest {

    private static final String BASE_PACKAGE = "com.aistudy.server";

    /**
     * Classes that still answer 409 without a dedicated code. Migrate one, then
     * delete its entry — never add one.
     */
    private static final Set<String> UNTYPED_CONFLICT_BASELINE = Set.of(
            "ai.explain.AiAnswerExplanationService",
            "ai.service.AiConversationService",
            "config.service.SystemConfigService",
            "exam.service.ExamDiagnosisService",
            "knowledge.point.service.KnowledgePointRelationService",
            "mastery.service.MasteryCalibrationConfigService",
            "source.outline.service.SourceOutlineNodeService",
            "studyplan.service.StudyPlanService"
    );

    private static final Pattern UNTYPED_CONFLICT = Pattern.compile(
            "new\\s+ResponseStatusException\\s*\\(\\s*HttpStatus\\.CONFLICT");

    @Test
    void untypedConflictThrowSitesMustNotGrow() throws IOException {
        Path mainJava = locateMainSources();
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(mainJava)) {
            files.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .forEach(path -> {
                        try {
                            String source = Files.readString(path);
                            if (UNTYPED_CONFLICT.matcher(source).find()) {
                                offenders.add(classNameOf(mainJava, path));
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }

        assertThat(new TreeSet<>(offenders))
                .withFailMessage("409 responses without a stable code must not be added; "
                        + "throw ApiException.conflict(ApiErrorCodes.X, …) instead.\nOffenders: %s",
                        offenders)
                .containsExactlyInAnyOrderElementsOf(new TreeSet<>(UNTYPED_CONFLICT_BASELINE));
    }

    /** Package-relative class name, matching the baseline convention. */
    private static String classNameOf(Path mainJava, Path file) {
        String relative = mainJava.relativize(file).toString().replace('\\', '/');
        String fullyQualified =
                relative.substring(0, relative.length() - ".java".length()).replace('/', '.');
        String prefix = BASE_PACKAGE + ".";
        return fullyQualified.startsWith(prefix)
                ? fullyQualified.substring(prefix.length())
                : fullyQualified;
    }

    /** Works from either the repository root or the {@code server/} module. */
    private static Path locateMainSources() {
        for (Path candidate = Path.of("").toAbsolutePath(); candidate != null;
             candidate = candidate.getParent()) {
            Path direct = candidate.resolve("src/main/java");
            if (Files.isDirectory(direct.resolve(BASE_PACKAGE.replace('.', '/')))) {
                return direct;
            }
            Path server = candidate.resolve("server/src/main/java");
            if (Files.isDirectory(server.resolve(BASE_PACKAGE.replace('.', '/')))) {
                return server;
            }
        }
        throw new IllegalStateException(
                "Could not locate src/main/java from " + Path.of("").toAbsolutePath());
    }
}
