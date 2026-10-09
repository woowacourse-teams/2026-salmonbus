package com.gustler.devtools;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Map;
import java.util.Set;

final class Preflight {
    private final Layout layout;
    private final Host host;

    Preflight(Layout layout, Host host) {
        this.layout = layout;
        this.host = host;
    }

    Map<String, Map<String, String>> check(String component, Map<String, String> effective)
            throws Exception {
        Checks.require(host.root(), "ROOT_REQUIRED");
        var directory = layout.config();
        Checks.require(
                Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                        && FileOps.attribute(directory, "uid") == 0
                        && FileOps.attribute(directory, "gid") == host.account().gid()
                        && (FileOps.attribute(directory, "mode") & 0022) == 0,
                "CONFIG_DIRECTORY");
        var marker =
                Json.read(FileOps.privateText(directory.resolve("environment.json")).getBytes());
        Environment.identity(marker, host.identity());
        Checks.require(
                host.mounted(layout.data()).path("uuid").equals(marker.path("dataVolumeUuid")),
                "DEV_DATA_MOUNT");
        var hashes = marker.path("configSha256");
        Checks.require(
                hashes.isObject()
                        && hashes.propertyStream()
                                .map(Map.Entry::getKey)
                                .collect(java.util.stream.Collectors.toSet())
                                .equals(Set.of("api.yml", "worker.yml", "mongodb.yml")),
                "DEV_CONFIG");
        for (var entry : hashes.properties()) {
            var file = directory.resolve(entry.getKey());
            FileOps.protectedFile(file, "DEV_CONFIG");
            Checks.require(FileOps.sha(file).equals(entry.getValue().asText()), "DEV_CONFIG");
        }
        var settings = Environment.load(directory);
        switch (component) {
            case "prepare" -> {}
            case "infra" -> Environment.effective(settings.get("infra"), effective);
            case "api", "worker" -> {
                Checks.require(
                        !FileOps.exists(layout.root().resolve("basis/operation.json")),
                        "DEV_BASIS_RECOVERY_REQUIRED");
                var basis = RevisionStore.verify(RevisionStore.selectedBasis(layout), true);
                RevisionStore.requireReady(layout, basis);
                var app =
                        RevisionStore.verify(
                                layout.root().resolve(component + "/current").toRealPath(), true);
                Checks.require(
                        app.basisDigest().equals(basis.basisDigest()), "DEV_BASIS_UPDATE_REQUIRED");
                var file = layout.root().resolve(component + "/current/release.env");
                FileOps.protectedFile(file, "DEV_RELEASE_ENV");
                var release = Environment.parse(Files.readString(file), Environment.RELEASE_KEYS);
                Checks.require(
                        component.equals(release.get("INFO_COMPONENT"))
                                && "dev".equals(release.get("INFO_ENVIRONMENT"))
                                && "true".equals(release.get("MANAGEMENT_INFO_ENV_ENABLED"))
                                && release.get("INFO_COMMIT").matches("[0-9a-f]{40}")
                                && release.get("INFO_SOURCEDIGEST").matches("[0-9a-f]{64}"),
                        "DEV_RELEASE_ENV");
                Environment.effective(
                        Environment.combined(settings.get(component), release), effective);
            }
            default -> throw new IllegalArgumentException("COMPONENT");
        }
        if (component.equals("worker")) {
            for (var name : Set.of("manifest.json", "weights.safetensors")) {
                Checks.require(
                        Files.isRegularFile(
                                layout.data().resolve("models/reference-20261002/" + name)),
                        "DEV_MODEL_REQUIRED");
            }
        }
        return settings;
    }
}
