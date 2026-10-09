package com.gustler.devtools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class BasisLifecycle {
    private final Layout layout;
    private final Host host;

    record Operation(String id, String from, String to, boolean active) {
        Path backup(Layout layout) {
            return layout.config().resolveSibling(".salmonbus-dev-backup-" + id);
        }

        Path stage(Layout layout) {
            return layout.config().resolveSibling(".salmonbus-dev-stage-" + id);
        }

        Path unitBackup(Layout layout) {
            return layout.root().resolve("basis/backups/" + id + "/infra.service");
        }
    }

    BasisLifecycle(Layout layout, Host host) {
        this.layout = layout;
        this.host = host;
    }

    static void claim(Layout layout, String id) throws Exception {
        var marker = layout.root().resolve(".deploying");
        Checks.require(!FileOps.exists(marker), "DEV_DEPLOYMENT_IN_PROGRESS");
        Files.createDirectory(
                marker,
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
        FileOps.writeNew(
                marker.resolve("owner"),
                ("basis " + id + " " + java.time.Instant.now().getEpochSecond() + "\n").getBytes(),
                0600);
        FileOps.sync(marker);
    }

    static void release(Layout layout, String id) throws Exception {
        var marker = layout.root().resolve(".deploying");
        if (FileOps.exists(marker)) {
            Checks.require(
                    FileOps.privateText(marker.resolve("owner")).startsWith("basis " + id + " "),
                    "DEV_DEPLOYMENT_MARKER");
            FileOps.removeTree(marker);
            FileOps.sync(layout.root());
        }
    }

    private Path journal() {
        return layout.root().resolve("basis/operation.json");
    }

    private Path target(String release) throws Exception {
        Checks.require(release != null && release.matches(RevisionStore.HASH), "DEV_BASIS_PATH");
        var path = layout.root().resolve("basis/releases/" + release);
        var metadata = RevisionStore.verify(path, true);
        Checks.require(
                metadata.kind().equals("basis") && metadata.releaseId().equals(release),
                "DEV_BASIS_PATH");
        return path;
    }

    private static void preserveData(RevisionStore.Revision previous, RevisionStore.Revision next) {
        for (var name :
                List.of(
                        "tools/data-contract.sha256",
                        "config/mongodb.yml",
                        "infra/compose.yaml",
                        "infra/init-chat.js")) {
            Checks.require(
                    previous.files().containsKey(name) && next.files().containsKey(name),
                    "DEV_DATA_CHANGE_REQUIRES_PLAN");
        }
        for (var name : DataPreparation.FILES) {
            Checks.require(
                    previous.files().containsKey("tools/data/" + name)
                            && next.files().containsKey("tools/data/" + name),
                    "DEV_DATA_CHANGE_REQUIRES_PLAN");
        }
        var names = new java.util.TreeSet<>(previous.files().keySet());
        names.addAll(next.files().keySet());
        for (var name : names) {
            if (name.startsWith("tools/data/")
                    || List.of(
                                    "tools/data-contract.sha256",
                                    "config/mongodb.yml",
                                    "infra/compose.yaml",
                                    "infra/init-chat.js")
                            .contains(name)) {
                Checks.require(
                        previous.files().containsKey(name)
                                && previous.files().get(name).equals(next.files().get(name)),
                        "DEV_DATA_CHANGE_REQUIRES_PLAN");
            }
        }
    }

    private void store(Path incoming, RevisionStore.Revision metadata, final int group)
            throws Exception {
        var target = layout.root().resolve("basis/releases/" + metadata.releaseId());
        if (FileOps.exists(target)) {
            Checks.require(RevisionStore.verify(target, true).equals(metadata), "DEV_BASIS_COPY");
            return;
        }
        var stage = Files.createTempDirectory(target.getParent(), ".basis-");
        try {
            for (var name : metadata.files().keySet()) {
                var file = stage.resolve(name);
                Files.createDirectories(file.getParent());
                Files.copy(incoming.resolve(name), file);
            }
            Files.copy(incoming.resolve("revision.json"), stage.resolve("revision.json"));
            try (var paths = Files.walk(stage)) {
                for (var path : paths.toList()) {
                    FileOps.owner(path, 0, group);
                    FileOps.mode(path, Files.isDirectory(path) ? 0755 : 0644);
                }
            }
            Checks.require(RevisionStore.verify(stage, true).equals(metadata), "DEV_BASIS_COPY");
            FileOps.moveNew(stage, target);
        } finally {
            FileOps.removeTree(stage);
        }
    }

    Map<String, Object> change(Path incoming, final boolean apply) throws Exception {
        Checks.require(host.root(), "ROOT_REQUIRED");
        var next = RevisionStore.verify(incoming, true);
        Checks.require(next.kind().equals("basis"), "DEV_BASIS_REVISION");
        var previousPath = RevisionStore.selectedBasis(layout);
        var previous = RevisionStore.verify(previousPath, true);
        RevisionStore.requireReady(layout, previous);
        Checks.require(
                !FileOps.exists(journal()) && !FileOps.exists(layout.root().resolve(".deploying")),
                "DEV_BASIS_RECOVERY_REQUIRED");
        preserveData(previous, next);
        new Preflight(layout, host).check("prepare", Map.of());
        host.verifyInstance(host.identity());
        DataPreparation.requireStopped(host, "STOP_DEV_APPS_BEFORE_BASIS_CHANGE");
        var unit = layout.units().resolve("salmonbus-dev-infra.service");
        FileOps.protectedFile(unit, "DEV_INFRA_UNIT");
        Checks.require(
                Files.mismatch(unit, previousPath.resolve("systemd/" + unit.getFileName())) == -1,
                "DEV_INFRA_UNIT_CHANGED");
        var state =
                host.run(
                                "systemctl",
                                "show",
                                unit.getFileName().toString(),
                                "--property=ActiveState",
                                "--value")
                        .strip();
        Checks.require(List.of("active", "inactive", "failed").contains(state), "DEV_INFRA_STATE");
        if (!apply) {
            return Map.of(
                    "status",
                    "ok",
                    "mode",
                    "check",
                    "dataPreserved",
                    true,
                    "alreadySelected",
                    previous.releaseId().equals(next.releaseId()));
        }
        if (previous.releaseId().equals(next.releaseId())) {
            return Map.of(
                    "status",
                    "ok",
                    "mode",
                    "apply",
                    "alreadySelected",
                    true,
                    "dataPreserved",
                    true);
        }
        try (var lease = FileOps.lock(layout.locks().resolve("salmonbus-dev-basis.lock"))) {
            Checks.require(
                    RevisionStore.verify(RevisionStore.selectedBasis(layout), true)
                            .equals(previous),
                    "DEV_BASIS_CHANGED");
            var operation =
                    new Operation(
                            UUID.randomUUID().toString().replace("-", ""),
                            previous.releaseId(),
                            next.releaseId(),
                            state.equals("active"));
            claim(layout, operation.id());
            try {
                DataPreparation.requireStopped(host, "STOP_DEV_APPS_BEFORE_BASIS_CHANGE");
                store(incoming, next, host.account().gid());
                var backupDirectory = operation.unitBackup(layout).getParent();
                FileOps.directory(backupDirectory.getParent(), 0700);
                FileOps.directory(backupDirectory, 0700);
                FileOps.writeNew(operation.unitBackup(layout), Files.readAllBytes(unit), 0600);
                new SettingsPreparation(layout)
                        .replacement(
                                target(next.releaseId()).resolve("tools"),
                                operation.stage(layout),
                                host.identity(),
                                host.mounted(layout.data()).path("uuid").asText(),
                                next.commit(),
                                host.account().gid());
                FileOps.atomicFile(journal(), Json.bytes(operation), 0600);
                loadImage(target(next.releaseId()));
                stopInfrastructure();
                FileOps.moveNew(layout.config(), operation.backup(layout));
                FileOps.moveNew(operation.stage(layout), layout.config());
                Deployment.setLink(
                        layout.root().resolve("basis/current"), target(next.releaseId()));
                FileOps.atomicFile(
                        unit,
                        Files.readAllBytes(
                                target(next.releaseId()).resolve("systemd/" + unit.getFileName())),
                        0644);
                host.run("systemctl", "daemon-reload");
                startIfNeeded(operation);
                new Preflight(layout, host).check("prepare", Map.of());
                RevisionStore.ready(layout, next);
                Deployment.setLink(layout.root().resolve("basis/previous"), previousPath);
                Files.delete(journal());
                FileOps.sync(journal().getParent());
                release(layout, operation.id());
            } catch (Exception error) {
                if (FileOps.exists(journal())) {
                    try {
                        restore(operation);
                    } catch (Exception recovery) {
                        throw new IllegalArgumentException("DEV_BASIS_RECOVERY_REQUIRED");
                    }
                } else {
                    release(layout, operation.id());
                }
                throw new IllegalArgumentException("DEV_BASIS_CHANGE_FAILED");
            }
        }
        return Map.of(
                "status",
                "ok",
                "mode",
                "apply",
                "dataPreserved",
                true,
                "applicationsRemainStopped",
                true);
    }

    private void loadImage(Path target) throws Exception {
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
                                host.run("docker", "image", "inspect", image.path("tag").asText())
                                        .getBytes())
                        .get(0);
        Checks.require(
                actual.path("Id").equals(image.path("id"))
                        && actual.path("Architecture").asText().equals("arm64"),
                "DEV_IMAGE_IDENTITY");
    }

    private void startIfNeeded(Operation operation) throws Exception {
        if (operation.active()) {
            host.run(
                            Path.of("/"),
                            null,
                            Duration.ofSeconds(210),
                            List.of("systemctl", "start", "salmonbus-dev-infra.service"))
                    .checked();
        }
    }

    private void stopInfrastructure() throws Exception {
        host.run(
                        Path.of("/"),
                        null,
                        Duration.ofSeconds(210),
                        List.of("systemctl", "stop", "salmonbus-dev-infra.service"))
                .checked();
    }

    private void restore(Operation operation) throws Exception {
        stopInfrastructure();
        if (FileOps.exists(operation.backup(layout))) {
            if (FileOps.exists(layout.config())) {
                var failed =
                        layout.config().resolveSibling(".salmonbus-dev-failed-" + operation.id());
                FileOps.moveNew(layout.config(), failed);
            }
            FileOps.moveNew(operation.backup(layout), layout.config());
        }
        var previous = target(operation.from());
        Deployment.setLink(layout.root().resolve("basis/current"), previous);
        FileOps.protectedFile(operation.unitBackup(layout), "DEV_BASIS_BACKUP");
        FileOps.atomicFile(
                layout.units().resolve("salmonbus-dev-infra.service"),
                Files.readAllBytes(operation.unitBackup(layout)),
                0644);
        host.run("systemctl", "daemon-reload");
        startIfNeeded(operation);
        new Preflight(layout, host).check("prepare", Map.of());
        RevisionStore.ready(layout, RevisionStore.verify(previous, true));
        Files.delete(journal());
        FileOps.sync(journal().getParent());
        release(layout, operation.id());
    }

    Map<String, Object> recover(final boolean apply) throws Exception {
        Checks.require(host.root(), "ROOT_REQUIRED");
        host.verifyInstance(host.identity());
        host.mounted(layout.data());
        DataPreparation.requireStopped(host, "STOP_DEV_APPS_BEFORE_BASIS_CHANGE");
        if (!FileOps.exists(journal())) {
            return recoverMarker(apply);
        }
        var value = Json.read(FileOps.privateText(journal()).getBytes());
        Checks.require(
                value.isObject() && value.size() == 4 && value.path("active").isBoolean(),
                "DEV_BASIS_JOURNAL");
        var operation = Json.MAPPER.treeToValue(value, Operation.class);
        Checks.require(operation.id().matches("[0-9a-f]{32}"), "DEV_BASIS_JOURNAL");
        target(operation.from());
        target(operation.to());
        Checks.require(
                FileOps.privateText(layout.root().resolve(".deploying/owner"))
                        .startsWith("basis " + operation.id() + " "),
                "DEV_DEPLOYMENT_MARKER");
        if (apply) {
            try (var lease = FileOps.lock(layout.locks().resolve("salmonbus-dev-basis.lock"))) {
                restore(operation);
            }
        }
        return Map.of(
                "status",
                "ok",
                "mode",
                apply ? "apply" : "check",
                "recoveryRequired",
                !apply,
                "dataPreserved",
                true);
    }

    private Map<String, Object> recoverMarker(final boolean apply) throws Exception {
        var owner = layout.root().resolve(".deploying/owner");
        if (!FileOps.exists(owner)) {
            Checks.require(
                    !FileOps.exists(layout.root().resolve(".deploying")), "DEV_DEPLOYMENT_MARKER");
            return Map.of(
                    "status", "ok", "mode", apply ? "apply" : "check", "recoveryRequired", false);
        }
        var contents = FileOps.privateText(owner);
        Checks.require(
                contents.matches("basis (bootstrap-[0-9a-f]{32}|[0-9a-f]{32}) [0-9]+\\n"),
                "DEV_DEPLOYMENT_MARKER");
        var id = contents.split(" ")[1];
        if (id.startsWith("bootstrap-")) {
            var process =
                    host.run(
                            Path.of("/"),
                            null,
                            Duration.ofSeconds(5),
                            List.of("pgrep", "-f", "[s]hared-dev-data[.]jar"));
            Checks.require(process.exit() == 1, "STOP_DEV_PREPARATION_BEFORE_RECOVERY");
        }
        if (apply) {
            try (var lease = FileOps.lock(layout.locks().resolve("salmonbus-dev-basis.lock"))) {
                Checks.require(
                        FileOps.privateText(owner).equals(contents), "DEV_DEPLOYMENT_MARKER");
                release(layout, id);
            }
        }
        return Map.of(
                "status",
                "ok",
                "mode",
                apply ? "apply" : "check",
                "recoveryRequired",
                !apply,
                "dataPreserved",
                true);
    }
}
