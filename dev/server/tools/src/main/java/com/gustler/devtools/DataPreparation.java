package com.gustler.devtools;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class DataPreparation {
    static final List<String> FILES =
            List.of(
                    "routes.json",
                    "catalog-routes.json",
                    "model-reference/manifest.json",
                    "model-reference/weights.safetensors",
                    "model-reference/route-reference.json");
    private final Layout layout;
    private final Host host;

    DataPreparation(Layout layout, Host host) {
        this.layout = layout;
        this.host = host;
    }

    static void publish(Path source, Path destination, final int group) throws Exception {
        Checks.require(
                !Files.isSymbolicLink(destination)
                        && !Files.isSymbolicLink(destination.getParent()),
                "DEV_DATA_DIRECTORY");
        Checks.require(
                !Files.isSymbolicLink(source)
                        && !Files.isSymbolicLink(source.resolve("model-reference")),
                "DEV_DATA_FILE");
        var contents = new TreeMap<String, byte[]>();
        for (var name : FILES) {
            var file = source.resolve(name);
            Checks.require(
                    Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                            && Files.size(file) <= 10485760,
                    "DEV_DATA_FILE");
            contents.put(name, Files.readAllBytes(file));
        }
        if (FileOps.exists(destination)) {
            for (var directory : List.of(destination, destination.resolve("model-reference"))) {
                Checks.require(
                        Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                                && FileOps.attribute(directory, "uid") == 0
                                && (FileOps.attribute(directory, "mode") & 0022) == 0,
                        "DEV_DATA_DIRECTORY");
            }
            for (var entry : contents.entrySet()) {
                var file = destination.resolve(entry.getKey());
                FileOps.protectedFile(file, "EXISTING_DEV_DATA_DIFFER");
                Checks.require(
                        java.util.Arrays.equals(Files.readAllBytes(file), entry.getValue()),
                        "EXISTING_DEV_DATA_DIFFER");
            }
            return;
        }
        var stage = Files.createTempDirectory(destination.getParent(), ".dev-data-");
        try {
            Files.createDirectory(stage.resolve("model-reference"));
            for (var entry : contents.entrySet()) {
                var file = stage.resolve(entry.getKey());
                FileOps.writeNew(file, entry.getValue(), 0640);
                FileOps.owner(file, 0, group);
            }
            for (var directory : List.of(stage, stage.resolve("model-reference"))) {
                FileOps.owner(directory, 0, group);
                FileOps.mode(directory, 0750);
                FileOps.sync(directory);
            }
            FileOps.moveNew(stage, destination);
        } finally {
            FileOps.removeTree(stage);
        }
    }

    static void requireStopped(Host host, String code) throws Exception {
        for (var unit : List.of("salmonbus-api.service", "salmonbus-worker.service")) {
            var state =
                    host.run("systemctl", "show", unit, "--property=ActiveState", "--value")
                            .strip();
            Checks.require(List.of("inactive", "failed").contains(state), code);
        }
    }

    Map<String, Object> prepare() throws Exception {
        Checks.require(host.root(), "ROOT_REQUIRED");
        Checks.require(
                !FileOps.exists(layout.root().resolve("basis/operation.json")),
                "DEV_BASIS_RECOVERY_REQUIRED");
        var marker = layout.root().resolve(".deploying/owner");
        if (FileOps.exists(marker)) {
            Checks.require(
                    FileOps.privateText(marker).startsWith("basis bootstrap-"),
                    "DEV_DEPLOYMENT_IN_PROGRESS");
        }
        try (var lease = FileOps.lock(layout.locks().resolve("salmonbus-dev-prepare.lock"))) {
            var settings = new Preflight(layout, host).check("prepare", Map.of());
            requireStopped(host, "STOP_DEV_APPS_BEFORE_PREPARE");
            var account = host.account();
            var jar = layout.tools().resolve("shared-dev-data.jar");
            var checksum = layout.tools().resolve("shared-dev-data.jar.sha256");
            FileOps.protectedFile(jar, "DEV_DATA_JAR");
            FileOps.protectedFile(checksum, "DEV_DATA_JAR");
            var expected = Files.readString(checksum).strip();
            Checks.require(
                    expected.matches("[0-9a-f]{64}") && FileOps.sha(jar).equals(expected),
                    "DEV_DATA_JAR");
            publish(layout.tools().resolve("data"), layout.data().resolve("data"), account.gid());
            var models = layout.data().resolve("models");
            if (!FileOps.exists(models)) {
                Files.createDirectory(models);
                FileOps.owner(models, account.uid(), account.gid());
                FileOps.mode(models, 0750);
            }
            Checks.require(
                    Files.isDirectory(models, LinkOption.NOFOLLOW_LINKS)
                            && FileOps.attribute(models, "uid") == account.uid()
                            && (FileOps.attribute(models, "mode") & 0022) == 0,
                    "DEV_MODEL_DIRECTORY");
            var environment =
                    Environment.combined(
                            settings.get("worker"),
                            Map.of("PATH", "/usr/bin:/bin", "LANG", "C.UTF-8"));
            var runuser = host.run("which", "runuser").strip();
            Checks.require(runuser.startsWith("/"), "RUNUSER_REQUIRED");
            var result =
                    host.run(
                            Path.of("/"),
                            environment,
                            Duration.ofSeconds(120),
                            List.of(
                                    runuser,
                                    "-u",
                                    "salmonbus",
                                    "--",
                                    "/usr/bin/java",
                                    "-Xms32m",
                                    "-Xmx256m",
                                    "-jar",
                                    jar.toString(),
                                    layout.data().resolve("data/routes.json").toString(),
                                    models.resolve("reference-20261002").toString(),
                                    "--shared-dev"));
            Checks.require(result.exit() == 0, "DEV_DATA_PREPARATION_FAILED");
            var value = Json.read(result.stdout().getBytes());
            Checks.require(
                    value.path("status").asText().equals("ok")
                            && List.of("prepared", "preserved")
                                    .contains(value.path("result").asText()),
                    "DEV_DATA_RESULT");
            var output = new TreeMap<String, Object>();
            for (var key :
                    List.of(
                            "status",
                            "result",
                            "routeCount",
                            "catalogRouteCount",
                            "modelRouteCount",
                            "stopCount",
                            "statisticsCellCount",
                            "featureContract",
                            "bundleDigest",
                            "modelActivated")) {
                Checks.require(value.has(key), "DEV_DATA_RESULT");
                output.put(key, value.get(key));
            }
            return output;
        }
    }
}
