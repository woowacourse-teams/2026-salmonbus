package com.gustler.devtools;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class BuildSourceTest {
    @TempDir Path repo;
    private Map<String, String> environment;
    private final Host noGit =
            new Host() {
                @Override
                Result run(
                        Path cwd, Map<String, String> env, Duration timeout, List<String> command) {
                    throw new AssertionError("Git must not be called for a source ZIP");
                }
            };

    @BeforeEach
    void prepare() {
        environment =
                new HashMap<>(
                        Map.of(
                                "CODEBUILD_PROJECT_ARN",
                                "arn:aws:codebuild:ap-northeast-2:000000000000:project/salmonbus-backend-dev-build",
                                "CODEBUILD_INITIATOR",
                                "codepipeline/salmonbus-backend-dev-cd",
                                "CODEBUILD_SRC_DIR",
                                repo.toString(),
                                "CODEBUILD_RESOLVED_SOURCE_VERSION",
                                "a".repeat(40)));
    }

    @Test
    void usesPipelineRevisionWithoutInventingGitHistory() throws Exception {
        assertEquals("a".repeat(40), BuildSource.commit(repo, noGit, environment, true, false));
        assertFalse(Files.exists(repo.resolve(".git")));
    }

    @Test
    void rejectsLocalZipWithoutCodebuildContext() {
        assertThrows(
                IllegalArgumentException.class,
                () -> BuildSource.commit(repo, noGit, environment, false, false));
    }

    @Test
    void rejectsProductionProjectPipelineAndWrongSourceDirectory() {
        for (var changed :
                List.of(
                        Map.of(
                                "CODEBUILD_PROJECT_ARN",
                                environment
                                        .get("CODEBUILD_PROJECT_ARN")
                                        .replace("-dev-build", "-build")),
                        Map.of("CODEBUILD_INITIATOR", "codepipeline/salmonbus-backend-cd"),
                        Map.of("CODEBUILD_SRC_DIR", "/"))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            BuildSource.commit(
                                    repo,
                                    noGit,
                                    Environment.combined(environment, changed),
                                    true,
                                    false));
        }
    }

    @Test
    void rejectsUnresolvedRevisionAndCodebuildReviewBypass() {
        for (var revision : List.of("", "dev", "a".repeat(39), "a".repeat(40) + "\n")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            BuildSource.commit(
                                    repo,
                                    noGit,
                                    Environment.combined(
                                            environment,
                                            Map.of("CODEBUILD_RESOLVED_SOURCE_VERSION", revision)),
                                    true,
                                    false));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> BuildSource.commit(repo, noGit, environment, true, true));
    }

    @Test
    void rejectsDirtyCheckoutAndCommitMismatch() throws Exception {
        Files.writeString(repo.resolve(".git"), "gitdir: fixture\n");
        var dirty =
                new Host() {
                    @Override
                    Result run(
                            Path cwd,
                            Map<String, String> env,
                            Duration timeout,
                            List<String> command) {
                        return new Result(
                                0,
                                command.contains("rev-parse")
                                        ? "a".repeat(40)
                                        : " M dev/server/api.yml\n",
                                "");
                    }
                };
        assertEquals(
                "COMMIT_REQUIRED_FOR_DEPLOYABLE_REVISION",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> BuildSource.commit(repo, dirty, environment, true, false))
                        .getMessage());
        environment.put("CODEBUILD_RESOLVED_SOURCE_VERSION", "b".repeat(40));
        assertEquals(
                "CODEBUILD_COMMIT_MISMATCH",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> BuildSource.commit(repo, dirty, environment, true, false))
                        .getMessage());
    }

    @Test
    void failedBuildCannotCreateArtifacts() {
        var output = repo.resolve("revision");
        environment.put("CODEBUILD_BUILD_SUCCEEDING", "0");
        var args =
                new String[] {
                    "package",
                    "--repo",
                    repo.toString(),
                    "--codebuild",
                    "--wiremock-archive",
                    "unused.tar",
                    "--output",
                    output.toString()
                };
        assertEquals(
                "CODEBUILD_FAILED",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> DevTools.execute(args, Layout.SERVER, noGit, environment))
                        .getMessage());
        assertFalse(Files.exists(output));
    }
}
