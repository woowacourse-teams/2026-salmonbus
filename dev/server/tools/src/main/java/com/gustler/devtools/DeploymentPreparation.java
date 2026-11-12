package com.gustler.devtools;

import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** API BeforeInstall updates a changed basis while preserving the selected database. */
final class DeploymentPreparation {
    private final Layout layout;
    private final Host host;
    private final DataTransition transition;

    DeploymentPreparation(Layout layout, Host host) {
        this(layout, host, new DataTransition());
    }

    DeploymentPreparation(Layout layout, Host host, DataTransition transition) {
        this.layout = layout;
        this.host = host;
        this.transition = transition;
    }

    private Path directory() {
        return layout.data().resolve("deployment-preparation");
    }

    private Path journal() {
        return directory().resolve("current.json");
    }

    private Map<String, Path> files() {
        var values = new TreeMap<String, Path>();
        for (var name : List.of("api.env", "worker.env", ArrivalSettings.FILE,
                DatabaseTarget.FILE, DatabaseTarget.CERTIFICATE)) {
            values.put("config-" + name, layout.config().resolve(name));
        }
        for (var component : List.of("api", "worker")) {
            var name = "salmonbus-" + component + ".service";
            values.put("unit-" + name, layout.units().resolve(name));
            values.put("dropin-" + component,
                    layout.units().resolve(name + ".d/30-dev-rds.conf"));
        }
        return values;
    }

    private RevisionStore.Revision application(Path incoming, Map<String, String> environment) throws Exception {
        Checks.require(host.root(), "ROOT_REQUIRED");
        var app = RevisionStore.verify(incoming, true);
        RevisionStore.deploymentGroup(app, environment);
        return app;
    }

    Map<String, Object> prepare(Path incoming, Map<String, String> environment) throws Exception {
        var app = application(incoming, environment);
        Checks.require(app.kind().equals("api"), "DEV_PREPARATION_COMPONENT");
        var nextPath = incoming.resolve("basis");
        var next = RevisionStore.verify(nextPath, true);
        Checks.require(next.kind().equals("basis") && app.commit().equals(next.commit())
                        && app.basisDigest().equals(next.basisDigest()), "DEV_PREPARATION_BASIS");
        new Preflight(layout, host).check("prepare", Map.of());
        host.verifyInstance(host.identity());
        var previousPath = RevisionStore.selectedBasis(layout);
        var previous = RevisionStore.verify(previousPath, true);
        RevisionStore.requireReady(layout, previous);
        final boolean basisChange = !previous.basisDigest().equals(next.basisDigest());
        if (!basisChange) {
            DataTransition.requireApplication(layout, app);
            return Map.of("status", "ok", "preparationRequired", false);
        }
        var dataChange = !java.util.Objects.equals(previous.files().get("tools/data-contract.sha256"),
                next.files().get("tools/data-contract.sha256"));
        var approval = dataChange && !CompatiblePreparation.permits(previous, next)
                ? DataTransition.approval(layout, previous, next) : null;
        BasisLifecycle.preserveData(previous, next, approval);
        SettingsPreparation.requireReplaceableEnvironment(layout);
        FileOps.directory(directory(), 0700);
        Checks.require((FileOps.attribute(directory(), "mode") & 0777) == 0700,
                "DEV_PREPARATION_DIRECTORY");
        try (var lease = FileOps.lock(layout.locks().resolve("salmonbus-dev-preparation.lock"))) {
            Checks.require(!FileOps.exists(layout.root().resolve(".deploying")),
                    "DEV_DEPLOYMENT_IN_PROGRESS");
            var result = new TreeMap<String, Object>(Map.of("status", "ok", "preparationRequired", true,
                    "applicationsRemainStopped", true));
            if (FileOps.exists(journal())) {
                var existing = Json.read(FileOps.privateText(journal()).getBytes());
                if (!List.of("COMPLETE", "ROLLED_BACK").contains(existing.path("phase").asText())) {
                    Checks.require(superseded(existing, app, previous), "DEV_PREPARATION_RECOVERY_REQUIRED");
                    result.put("supersededCommit", existing.path("commit").asText());
                    result.put("supersededPhase", existing.path("phase").asText());
                }
            }
            var state = snapshot(app, previous);
            state.put("dataTransition", approval != null);
            write(state);
            try {
                stopApplications();
                new BasisLifecycle(layout, host).change(nextPath, true, approval);
                new Preflight(layout, host).check("prepare", Map.of());
                if (approval != null) {
                    approval.verify(previous, next, java.time.Instant.now());
                    installTransitionGuards(next);
                    transition.approveDatabase(layout, nextPath, approval);
                }
                state.put("phase", "PREPARED");
                write(state);
                return result;
            } catch (Exception error) {
                if (approval != null) {
                    state.put("phase", "RECOVERY_REQUIRED");
                    write(state);
                    throw new IllegalArgumentException("DEV_DATA_TRANSITION_RECOVERY_REQUIRED");
                }
                try {
                    restore(Json.read(Json.bytes(state)));
                } catch (Exception recovery) {
                    throw new IllegalArgumentException("DEV_PREPARATION_RECOVERY_REQUIRED");
                }
                throw new IllegalArgumentException("DEV_PREPARATION_FAILED");
            }
        }
    }

    /**
     * 다른 커밋의 배포가 basis를 바꾼 뒤 끝맺지 못한 기록인지 확인한다.
     *
     * <p>이 기록으로 되돌리는 것은 같은 커밋의 hook뿐이라 다음 커밋의 준비를 막을 이유가 없다. 현재 basis가 기록의
     * 직전 basis와 다를 때, 즉 교체가 끝난 뒤에만 해당한다. DB 전환 기록과 준비 중에 멈춘 기록은 계속 막는다.
     * 넘긴 기록의 커밋과 단계는 준비 결과의 {@code supersededCommit}·{@code supersededPhase}에 남긴다.
     */
    private static boolean superseded(JsonNode state, RevisionStore.Revision app, RevisionStore.Revision basis) {
        return state.path("dataTransition").isBoolean() && !state.path("dataTransition").asBoolean()
                && List.of("PREPARED", "API_READY").contains(state.path("phase").asText())
                && !app.commit().equals(state.path("commit").asText())
                && !basis.releaseId().equals(state.path("basisReleaseId").asText());
    }

    private Map<String, Object> snapshot(RevisionStore.Revision app, RevisionStore.Revision basis) throws Exception {
        var state = new TreeMap<String, Object>();
        var id = UUID.randomUUID().toString();
        var backup = directory().resolve(id);
        FileOps.directory(backup, 0700);
        var present = new TreeMap<String, Boolean>();
        for (var entry : files().entrySet()) {
            final boolean exists = FileOps.exists(entry.getValue());
            present.put(entry.getKey(), exists);
            if (exists) {
                FileOps.protectedFile(entry.getValue(), "DEV_PREPARATION_FILE");
                FileOps.writeNew(backup.resolve(entry.getKey()), Files.readAllBytes(entry.getValue()), 0600);
            }
        }
        state.put("files", present);
        state.put("backupId", id);
        state.put("commit", app.commit());
        state.put("basisReleaseId", basis.releaseId());
        state.put("phase", "PREPARING");
        for (var component : List.of("api", "worker")) {
            var base = layout.root().resolve(component);
            for (var link : List.of("current", "previous")) {
                var target = Deployment.current(base.resolve(link), base.resolve("releases"));
                state.put(component + "-" + link, target == null ? "" : target.getFileName().toString());
            }
            var active = host.run(Path.of("/"), null, Duration.ofSeconds(15),
                    List.of("systemctl", "is-active", "salmonbus-" + component));
            state.put(component + "-active", active.exit() == 0 && active.stdout().strip().equals("active"));
        }
        return state;
    }

    private Path transitionGuard(String component) {
        return layout.units().resolve("salmonbus-" + component + ".service.d/40-data-transition.conf");
    }

    private void installTransitionGuards(RevisionStore.Revision basis) throws Exception {
        // 이전 CodeDeploy 번들의 도구로 돌아가도 전환 중인 DB에 이전 앱을 시작하지 못한다.
        var tools = layout.root().resolve("basis/releases/" + basis.releaseId() + "/tools/shared-dev-tools.jar");
        Checks.require(tools.toString().matches("/[a-zA-Z0-9/_.-]+"),
                "DEV_DATA_TRANSITION_TOOL_PATH");
        for (var component : List.of("api", "worker")) {
            var file = transitionGuard(component);
            FileOps.directory(file.getParent(), 0755);
            FileOps.atomicFile(file, ("# SAL-175 dev data transition\n[Service]\n"
                    + "ExecStartPre=+/usr/bin/java -Xms16m -Xmx64m -jar " + tools
                    + " preflight " + component + "\n").getBytes(), 0644);
        }
        host.run("systemctl", "daemon-reload");
    }

    private void removeTransitionGuards() throws Exception {
        for (var component : List.of("api", "worker")) {
            var file = transitionGuard(component);
            if (!FileOps.exists(file)) continue;
            FileOps.protectedFile(file, "DEV_DATA_TRANSITION_GUARD");
            Checks.require(Files.readString(file).startsWith("# SAL-175 dev data transition\n"),
                    "DEV_DATA_TRANSITION_GUARD");
            Files.delete(file);
        }
        host.run("systemctl", "daemon-reload");
    }

    private void write(Map<String, Object> state) throws Exception {
        FileOps.atomicFile(journal(), Json.bytes(state), 0600);
        var id = state.get("backupId").toString();
        FileOps.atomicFile(directory().resolve(id).resolve("state.json"), Json.bytes(state), 0600);
    }

    private void stopApplications() throws Exception {
        for (var component : List.of("worker", "api")) {
            // Allow systemd's 45-second worker shutdown to complete before timing out.
            host.run(Path.of("/"), null, Duration.ofSeconds(75),
                    List.of("systemctl", "stop", "salmonbus-" + component)).checked();
        }
        DataPreparation.requireStopped(host, "STOP_DEV_APPS_BEFORE_PREPARATION");
    }

    Map<String, Object> recover(Path incoming, Map<String, String> environment) throws Exception {
        var app = application(incoming, environment);
        host.verifyInstance(host.identity());
        Environment.identity(Json.read(FileOps.privateText(layout.config().resolve("environment.json")).getBytes()),
                host.identity());
        try (var lease = FileOps.lock(layout.locks().resolve("salmonbus-dev-preparation.lock"))) {
            if (!FileOps.exists(journal())) {
                return Map.of("status", "ok", "recoveryRequired", false);
            }
            var state = Json.read(FileOps.privateText(journal()).getBytes());
            if (List.of("COMPLETE", "ROLLED_BACK").contains(state.path("phase").asText())) {
                return Map.of("status", "ok", "recoveryRequired", false);
            }
            if (state.path("dataTransition").asBoolean() && !state.path("phase").asText().equals("COMPLETE")) {
                stopApplications();
                ((tools.jackson.databind.node.ObjectNode) state).put("phase", "RECOVERY_REQUIRED");
                FileOps.atomicFile(journal(), Json.bytes(state), 0600);
                throw new IllegalArgumentException("DEV_DATA_TRANSITION_RECOVERY_REQUIRED");
            }
            if (!app.commit().equals(state.path("commit").asText())
                    || !List.of("PREPARING", "PREPARED", "API_READY").contains(state.path("phase").asText())) {
                return Map.of("status", "ok", "recoveryRequired", false);
            }
            restore(state);
        }
        return Map.of("status", "ok", "recoveryRequired", true, "previousDataPreserved", true);
    }

    private void restore(JsonNode state) throws Exception {
        var id = state.path("backupId").asText();
        Checks.require(id.matches("[a-f0-9-]{36}"), "DEV_PREPARATION_BACKUP");
        var backup = directory().resolve(id);
        stopApplications();
        for (var entry : files().entrySet()) {
            if (state.path("files").path(entry.getKey()).asBoolean()) {
                var source = backup.resolve(entry.getKey());
                var content = FileOps.privateText(source).getBytes();
                final int mode = entry.getKey().startsWith("unit-")
                                || entry.getKey().startsWith("dropin-")
                                || entry.getKey().endsWith(DatabaseTarget.CERTIFICATE) ? 0644 : 0600;
                FileOps.atomicFile(entry.getValue(), content, mode);
            } else if (FileOps.exists(entry.getValue())) {
                FileOps.protectedFile(entry.getValue(), "DEV_PREPARATION_FILE");
                Files.delete(entry.getValue());
            }
        }
        var previous = state.path("basisReleaseId").asText();
        Checks.require(previous.matches(RevisionStore.HASH), "DEV_PREPARATION_BACKUP");
        var basis = RevisionStore.verify(RevisionStore.selectedBasis(layout), true);
        if (!basis.releaseId().equals(previous)) {
            new BasisLifecycle(layout, host).revert(layout.root().resolve("basis/releases/" + previous));
        }
        for (var component : List.of("api", "worker")) {
            var base = layout.root().resolve(component);
            for (var name : List.of("current", "previous")) {
                var release = state.path(component + "-" + name).asText();
                var link = base.resolve(name);
                if (release.isEmpty()) {
                    Checks.require(!FileOps.exists(link) || Files.isSymbolicLink(link), "DEV_CURRENT_LINK");
                    Files.deleteIfExists(link);
                } else {
                    Checks.require(release.matches(RevisionStore.HASH), "DEV_PREPARATION_BACKUP");
                    var target = base.resolve("releases/" + release);
                    RevisionStore.verify(target, true);
                    Deployment.setLink(link, target);
                }
            }
        }
        host.run("systemctl", "daemon-reload");
        for (var component : List.of("api", "worker")) {
            if (state.path(component + "-active").asBoolean()) {
                host.run("systemctl", "start", "salmonbus-" + component);
            }
        }
        ((tools.jackson.databind.node.ObjectNode) state).put("phase", "ROLLED_BACK");
        FileOps.atomicFile(journal(), Json.bytes(state), 0600);
        FileOps.atomicFile(backup.resolve("state.json"), Json.bytes(state), 0600);
    }

    Map<String, Object> complete(Path incoming, Map<String, String> environment) throws Exception {
        var app = application(incoming, environment);
        DataTransition.requireApplication(layout, app);
        var basis = RevisionStore.verify(RevisionStore.selectedBasis(layout), true);
        RevisionStore.requireReady(layout, basis);
        RevisionStore.routing(app, environment, basis);
        host.verifyInstance(host.identity());
        Environment.identity(Json.read(FileOps.privateText(layout.config().resolve("environment.json")).getBytes()),
                host.identity());
        try (var lease = FileOps.lock(layout.locks().resolve("salmonbus-dev-preparation.lock"))) {
            removeManualOverride(app.kind());
            if (FileOps.exists(journal())) {
                var state = Json.read(FileOps.privateText(journal()).getBytes());
                if (app.commit().equals(state.path("commit").asText())
                        && List.of("PREPARED", "API_READY").contains(state.path("phase").asText())) {
                    ((tools.jackson.databind.node.ObjectNode) state).put("phase",
                            app.kind().equals("worker") ? "COMPLETE" : "API_READY");
                    FileOps.atomicFile(journal(), Json.bytes(state), 0600);
                    FileOps.atomicFile(directory().resolve(state.path("backupId").asText()).resolve("state.json"),
                            Json.bytes(state), 0600);
                    if (app.kind().equals("worker") && state.path("dataTransition").asBoolean()) {
                        removeTransitionGuards();
                    }
                }
            }
        }
        return Map.of("status", "ok", "component", app.kind());
    }

    private void removeManualOverride(String component) throws Exception {
        var unit = layout.units().resolve("salmonbus-" + component + ".service");
        var file = layout.units().resolve("salmonbus-" + component + ".service.d/30-dev-rds.conf");
        if (!FileOps.exists(file) || !FileOps.exists(layout.config().resolve(DatabaseTarget.FILE))) {
            return;
        }
        FileOps.protectedFile(file, "DEV_MANUAL_OVERRIDE");
        if (!Files.readString(file).startsWith("# salmonbus-dev-rds-manual dc47e6e7\n")) {
            return;
        }
        Checks.require(!Files.readString(unit).contains("--spring.datasource.url="),
                "DEV_MANUAL_OVERRIDE_STILL_REQUIRED");
        DatabaseTarget.url(layout.config());
        Files.delete(file);
        host.run("systemctl", "daemon-reload");
    }
}
