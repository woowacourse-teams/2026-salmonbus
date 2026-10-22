package com.gustler.devtools;

import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class Fixtures {
    static final String ORIGIN = "https://dev.example.test";
    static final String COMMIT = "a".repeat(40);
    static final Map<String, String> LOGIN =
            Map.ofEntries(
                    Map.entry("AUTH_ENABLED", "true"),
                    Map.entry("AUTH_PUBLIC_BASE_URL", "https://dev.example.test"),
                    Map.entry("AUTH_TOKEN_SECRET", "1".repeat(64)),
                    Map.entry("AUTH_REFRESH_TOKEN_SECRET", "2".repeat(64)),
                    Map.entry("AUTH_KAKAO_ENABLED", "true"),
                    Map.entry("AUTH_KAKAO_CLIENT_ID", "kakao-client"),
                    Map.entry("AUTH_KAKAO_CLIENT_SECRET", "kakao-secret"),
                    Map.entry("AUTH_KAKAO_ADMIN_KEY", "kakao-admin"),
                    Map.entry("AUTH_APPLE_ENABLED", "true"),
                    Map.entry("AUTH_APPLE_CLIENT_ID", "test.salmonbus.dev"),
                    Map.entry("AUTH_APPLE_TEAM_ID", "TEAM000000"),
                    Map.entry("AUTH_APPLE_KEY_ID", "KEY0000000"),
                    Map.entry(
                            "AUTH_APPLE_PRIVATE_KEY_PATH",
                            "/etc/salmonbus-dev/apple/AuthKey_KEY0000000.p8"));
    final Layout layout;
    final Path source;
    final FakeHost host = new FakeHost();

    Fixtures(Path base) throws Exception {
        var etc = Files.createDirectory(base.resolve("etc"));
        layout =
                new Layout(
                        Files.createDirectory(base.resolve("root")),
                        etc.resolve("salmonbus-dev"),
                        Files.createDirectory(base.resolve("data")),
                        Files.createDirectory(base.resolve("locks")),
                        Files.createDirectory(base.resolve("units")));
        for (var directory : List.of(etc, layout.root(), layout.locks(), layout.units())) {
            FileOps.mode(directory, 0755);
        }
        FileOps.mode(layout.data(), 0700);
        source = Files.createDirectories(base.resolve("source/tools"));
        Files.createDirectory(source.resolveSibling("config"));
        Files.writeString(source.resolve("api.yml"), "server:\n  port: 8080\n");
        Files.writeString(
                source.resolve("worker.yml"), "gbis:\n  base-url: http://127.0.0.1:18080\n");
        Files.writeString(
                source.resolveSibling("config").resolve("mongodb.yml"),
                "storage:\n  dbPath: /data/db\n");
        host.data = layout.data();
    }

    String settings() throws Exception {
        return new SettingsPreparation(layout)
                .initialize(
                        source,
                        host.identity(),
                        "test-volume",
                        ORIGIN,
                        COMMIT,
                        host.account().gid());
    }

    static class FakeHost extends Host {
        Path data;
        String state = "inactive";
        final List<List<String>> calls = new ArrayList<>();

        @Override
        boolean root() {
            return true;
        }

        @Override
        Account account() {
            return new Account(65534, 65534, "/sbin/nologin");
        }

        @Override
        JsonNode identity() {
            return Json.read(
                    Json.bytes(
                            Map.of(
                                    "instanceId",
                                    "test-dev-instance",
                                    "accountId",
                                    "test-account",
                                    "region",
                                    "ap-northeast-2",
                                    "instanceType",
                                    "t4g.small")));
        }

        @Override
        JsonNode mounted(Path path) {
            return Json.read(Json.bytes(Map.of("uuid", "test-volume", "fstype", "xfs")));
        }

        @Override
        void verifyInstance(JsonNode identity) {}

        @Override
        Result run(
                Path cwd, Map<String, String> environment, Duration timeout, List<String> command) {
            calls.add(command);
            if (command.getFirst().equals("systemctl")) {
                return new Result(0, command.contains("show") ? state + "\n" : "", "");
            }
            if (command.equals(List.of("docker", "info", "--format", "{{json .}}"))) {
                return new Result(
                        0,
                        Json.MAPPER.writeValueAsString(
                                Map.of("DockerRootDir", data.resolve("docker").toString())),
                        "");
            }
            if (command.equals(List.of("docker", "compose", "version"))
                    || command.getFirst().equals("which")) {
                return new Result(0, "/fixture/tool\n", "");
            }
            if (command.equals(List.of("/usr/bin/java", "-version"))) {
                return new Result(0, "", "openjdk version \"21.0.12\"\n");
            }
            throw new AssertionError("Unexpected command: " + command.getFirst());
        }
    }
}
