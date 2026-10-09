package com.gustler.devtools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

final class Bootstrap {
    private final Layout layout;
    private final Host host;

    Bootstrap(Layout layout, Host host) {
        this.layout = layout;
        this.host = host;
    }

    static void link(Path path, String target) throws Exception {
        if (FileOps.exists(path)) {
            Checks.require(
                    Files.isSymbolicLink(path)
                            && Files.readSymbolicLink(path).toString().equals(target),
                    "DEV_BOOTSTRAP_LINK");
        } else {
            Files.createSymbolicLink(path, Path.of(target));
        }
    }

    Map<String, Object> prepare(Path incoming, String origin, final boolean apply)
            throws Exception {
        Checks.require(host.root(), "ROOT_REQUIRED");
        Environment.origin(origin);
        var metadata = RevisionStore.verify(incoming, true);
        Checks.require(metadata.kind().equals("basis"), "DEV_BASIS_REVISION");
        Checks.require(
                !FileOps.exists(layout.root().resolve("basis/operation.json")),
                "DEV_BASIS_RECOVERY_REQUIRED");
        if (FileOps.exists(layout.root().resolve("basis/current"))) {
            incoming = RevisionStore.selectedBasis(layout);
            var existing = RevisionStore.verify(incoming, true);
            Checks.require(
                    existing.sourceDigest().equals(metadata.sourceDigest()), "USE_UPDATE_BASIS");
            metadata = existing;
        }
        var identity = host.identity();
        host.verifyInstance(identity);
        var mounted = host.mounted(layout.data());
        Checks.require(
                !FileOps.exists(layout.root().resolve(".deploying")), "DEV_DEPLOYMENT_IN_PROGRESS");
        DataPreparation.requireStopped(host, "STOP_DEV_APPS_BEFORE_BOOTSTRAP");
        var info = Json.read(host.run("docker", "info", "--format", "{{json .}}").getBytes());
        Checks.require(
                info.path("DockerRootDir")
                        .asText()
                        .equals(layout.data().resolve("docker").toString()),
                "DEV_DOCKER_DATA_ROOT");
        host.run("docker", "compose", "version");
        host.run("which", "runuser", "useradd");
        var javaVersion =
                host.run(
                        Path.of("/"),
                        null,
                        Duration.ofSeconds(10),
                        List.of("/usr/bin/java", "-version"));
        Checks.require(
                javaVersion.exit() == 0 && javaVersion.stderr().contains("version \"21."),
                "JAVA_21_REQUIRED");
        if (!apply) {
            return Map.of(
                    "status",
                    "ok",
                    "mode",
                    "check",
                    "targetVerified",
                    true,
                    "applicationDeployment",
                    false);
        }
        try (var lease = FileOps.lock(layout.locks().resolve("salmonbus-dev-basis.lock"))) {
            Host.Account account;
            try {
                account = host.account();
            } catch (IllegalArgumentException error) {
                Checks.require(
                        error.getMessage().equals("DEV_SERVICE_ACCOUNT_MISSING"),
                        "DEV_SERVICE_ACCOUNT");
                host.run(
                        "useradd",
                        "--system",
                        "--user-group",
                        "--home-dir",
                        "/var/lib/salmonbus-dev",
                        "--no-create-home",
                        "--shell",
                        "/sbin/nologin",
                        "salmonbus");
                account = host.account();
            }
            Checks.require(
                    account.uid() > 0
                            && List.of("/sbin/nologin", "/usr/sbin/nologin")
                                    .contains(account.shell())
                            && Integer.parseInt(
                                            host.run("getent", "group", "salmonbus")
                                                    .strip()
                                                    .split(":")[2])
                                    == account.gid(),
                    "DEV_SERVICE_ACCOUNT");
            for (var directory :
                    List.of(
                            layout.root(),
                            layout.root().resolve("basis"),
                            layout.root().resolve("basis/releases"))) {
                FileOps.directory(directory, 0755);
            }
            var operationId =
                    "bootstrap-" + java.util.UUID.randomUUID().toString().replace("-", "");
            BasisLifecycle.claim(layout, operationId);
            try {
                var target = layout.root().resolve("basis/releases/" + metadata.releaseId());
                if (!FileOps.exists(target)) {
                    var stage = Files.createTempDirectory(target.getParent(), ".basis-");
                    try {
                        for (var name : metadata.files().keySet()) {
                            var path = stage.resolve(name);
                            Files.createDirectories(path.getParent());
                            Files.copy(incoming.resolve(name), path);
                        }
                        Files.copy(
                                incoming.resolve("revision.json"), stage.resolve("revision.json"));
                        try (var paths = Files.walk(stage)) {
                            for (var path : paths.toList()) {
                                FileOps.owner(path, 0, account.gid());
                                FileOps.mode(path, Files.isDirectory(path) ? 0755 : 0644);
                            }
                        }
                        Checks.require(
                                RevisionStore.verify(stage, true).equals(metadata),
                                "DEV_BASIS_COPY");
                        FileOps.moveNew(stage, target);
                    } finally {
                        FileOps.removeTree(stage);
                    }
                }
                Checks.require(
                        RevisionStore.verify(target, true).equals(metadata), "DEV_BASIS_COPY");
                link(layout.root().resolve("basis/current"), "releases/" + metadata.releaseId());
                link(layout.tools(), "basis/current/tools");
                link(layout.root().resolve("config"), "basis/current/config");
                FileOps.directory(layout.root().resolve("infra"), 0755);
                link(layout.root().resolve("infra/current"), "../basis/current/infra");
                for (var name : List.of("postgres", "mongodb")) {
                    FileOps.directory(layout.data().resolve(name), 0700);
                }
                new SettingsPreparation(layout)
                        .initialize(
                                target.resolve("tools"),
                                identity,
                                mounted.path("uuid").asText(),
                                origin,
                                metadata.commit(),
                                account.gid());
                var image = Json.read(target.resolve("wiremock-image.json"));
                host.run(
                                Path.of("/"),
                                null,
                                Duration.ofSeconds(180),
                                List.of(
                                        "docker",
                                        "image",
                                        "load",
                                        "--input",
                                        target.resolve("wiremock-image.tar").toString()))
                        .checked();
                var actual =
                        Json.read(
                                        host.run(
                                                        "docker",
                                                        "image",
                                                        "inspect",
                                                        image.path("tag").asText())
                                                .getBytes())
                                .get(0);
                Checks.require(
                        actual.path("Id").equals(image.path("id"))
                                && actual.path("Architecture").asText().equals("arm64"),
                        "DEV_IMAGE_IDENTITY");
                var unit = layout.units().resolve("salmonbus-dev-infra.service");
                var source = target.resolve("systemd/" + unit.getFileName());
                if (FileOps.exists(unit)) {
                    FileOps.protectedFile(unit, "DEV_INFRA_UNIT");
                    Checks.require(Files.mismatch(unit, source) == -1, "DEV_INFRA_UNIT_CHANGED");
                } else {
                    FileOps.atomicFile(unit, Files.readAllBytes(source), 0644);
                }
                host.run("systemctl", "daemon-reload");
                host.run(
                                Path.of("/"),
                                null,
                                Duration.ofSeconds(210),
                                List.of(
                                        "systemctl",
                                        "enable",
                                        "--now",
                                        unit.getFileName().toString()))
                        .checked();
                new DataPreparation(layout, host).prepare();
                RevisionStore.ready(layout, metadata);
            } finally {
                BasisLifecycle.release(layout, operationId);
            }
        }
        return Map.of(
                "status",
                "ok",
                "mode",
                "apply",
                "basisPrepared",
                true,
                "applicationDeployment",
                false);
    }
}
