package com.gustler.devtools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

final class BuildSource {
    private BuildSource() {}

    static String commit(
            Path repo,
            Host host,
            Map<String, String> environment,
            final boolean codebuild,
            final boolean review)
            throws Exception {
        String commit = null;
        if (codebuild) {
            Checks.require(!review, "CODEBUILD_REVIEW_FORBIDDEN");
            Checks.require(
                    environment
                            .getOrDefault("CODEBUILD_PROJECT_ARN", "")
                            .matches(
                                    "arn:aws:codebuild:ap-northeast-2:[0-9]{12}:project/salmonbus-backend-dev-build"),
                    "DEV_CODEBUILD_PROJECT");
            Checks.require(
                    "codepipeline/salmonbus-backend-dev-cd"
                            .equals(environment.get("CODEBUILD_INITIATOR")),
                    "DEV_CODEPIPELINE");
            var directory = environment.getOrDefault("CODEBUILD_SRC_DIR", "");
            Checks.require(
                    !directory.isEmpty()
                            && Path.of(directory).toRealPath().equals(repo.toRealPath()),
                    "CODEBUILD_SOURCE_DIRECTORY");
            commit = environment.getOrDefault("CODEBUILD_RESOLVED_SOURCE_VERSION", "");
            Checks.require(commit.matches("[0-9a-f]{40}"), "CODEBUILD_COMMIT_REQUIRED");
        }
        if (Files.exists(repo.resolve(".git"))) {
            var actual =
                    host.run(
                                    repo,
                                    null,
                                    Duration.ofSeconds(10),
                                    List.of("git", "rev-parse", "HEAD"))
                            .checked()
                            .strip();
            Checks.require(commit == null || commit.equals(actual), "CODEBUILD_COMMIT_MISMATCH");
            var dirty =
                    host.run(
                                    repo,
                                    null,
                                    Duration.ofSeconds(10),
                                    List.of(
                                            "git",
                                            "status",
                                            "--porcelain",
                                            "--untracked-files=all",
                                            "--",
                                            "backend",
                                            "dev"))
                            .checked();
            Checks.require(dirty.isBlank() || review, "COMMIT_REQUIRED_FOR_DEPLOYABLE_REVISION");
            commit = actual;
        } else {
            Checks.require(codebuild, "GIT_OR_CODEBUILD_REQUIRED");
        }
        return commit;
    }
}
