package com.gustler.devtools;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

final class RevisionStore {
    static final String HASH = "[0-9a-f]{64}";
    static final String APPLICATION = "salmonbus-backend-dev";
    private static final Set<String> FIELDS =
            Set.of(
                    "formatVersion",
                    "environment",
                    "kind",
                    "commit",
                    "sourceDigest",
                    "basisDigest",
                    "reviewOnly",
                    "files",
                    "releaseId");

    record Revision(
            int formatVersion,
            String environment,
            String kind,
            String commit,
            String sourceDigest,
            String basisDigest,
            boolean reviewOnly,
            Map<String, String> files,
            String releaseId) {
        Map<String, Object> unsigned() {
            return Map.of(
                    "formatVersion",
                    formatVersion,
                    "environment",
                    environment,
                    "kind",
                    kind,
                    "commit",
                    commit,
                    "sourceDigest",
                    sourceDigest,
                    "basisDigest",
                    basisDigest,
                    "reviewOnly",
                    reviewOnly,
                    "files",
                    files);
        }

        Map<String, Object> values() {
            var result = new TreeMap<>(unsigned());
            result.put("releaseId", releaseId);
            return result;
        }
    }

    private RevisionStore() {}

    static Map<String, String> inventory(Path directory) throws Exception {
        var result = new TreeMap<String, String>();
        try (var paths = Files.walk(directory)) {
            for (var path : paths.toList()) {
                Checks.require(
                        Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                                || Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS),
                        "REVISION_LINK_OR_SPECIAL_FILE");
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    Checks.require(FileOps.attribute(path, "nlink") == 1, "REVISION_HARDLINK");
                    result.put(directory.relativize(path).toString(), FileOps.sha(path));
                }
            }
        }
        return result;
    }

    static Revision seal(
            Path directory,
            String kind,
            String commit,
            String digest,
            String basis,
            final boolean review)
            throws Exception {
        Checks.require(
                !FileOps.exists(directory.resolve("revision.json")), "REVISION_ALREADY_SEALED");
        var metadata =
                new Revision(
                        1, "dev", kind, commit, digest, basis, review, inventory(directory), "");
        var value =
                new Revision(
                        1,
                        "dev",
                        kind,
                        commit,
                        digest,
                        basis,
                        review,
                        metadata.files(),
                        FileOps.sha(Json.bytes(metadata.unsigned())));
        FileOps.writeNew(directory.resolve("revision.json"), Json.bytes(value.values()), 0644);
        return value;
    }

    static Revision verify(Path directory, final boolean deployable) throws Exception {
        Checks.require(
                Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS), "REVISION_DIRECTORY");
        var manifest = directory.resolve("revision.json");
        Checks.require(
                Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)
                        && Files.size(manifest) < 2097152,
                "REVISION_MANIFEST");
        var node = Json.read(manifest);
        Checks.require(
                node.isObject()
                        && node.propertyStream()
                                .map(Map.Entry::getKey)
                                .collect(java.util.stream.Collectors.toSet())
                                .equals(FIELDS)
                        && node.path("formatVersion").isIntegralNumber()
                        && node.path("formatVersion").asInt() == 1
                        && node.path("reviewOnly").isBoolean(),
                "DEV_REVISION_METADATA");
        var value = Json.MAPPER.treeToValue(node, Revision.class);
        Checks.require(
                "dev".equals(value.environment())
                        && Set.of("basis", "api", "worker").contains(value.kind())
                        && value.commit() != null
                        && value.commit().matches("[0-9a-f]{40}"),
                "DEV_REVISION_METADATA");
        for (var hash :
                java.util.List.of(value.sourceDigest(), value.basisDigest(), value.releaseId())) {
            Checks.require(hash != null && hash.matches(HASH), "DEV_REVISION_METADATA");
        }
        Checks.require(
                FileOps.sha(Json.bytes(value.unsigned())).equals(value.releaseId()),
                "REVISION_METADATA_CHANGED");
        Checks.require(!deployable || !value.reviewOnly(), "REVIEW_REVISION_CANNOT_DEPLOY");
        Checks.require(
                value.files() != null && !value.files().isEmpty() && value.files().size() <= 100,
                "REVISION_FILES");
        var actual = inventory(directory);
        actual.remove("revision.json");
        Checks.require(actual.keySet().equals(value.files().keySet()), "REVISION_FILE_SET_CHANGED");
        for (var entry : value.files().entrySet()) {
            var path = Path.of(entry.getKey());
            Checks.require(
                    !path.isAbsolute()
                            && path.normalize().equals(path)
                            && !path.startsWith("..")
                            && !entry.getKey().contains("\\")
                            && entry.getValue() != null
                            && entry.getValue().matches(HASH),
                    "REVISION_FILE_PATH");
            Checks.require(
                    actual.get(entry.getKey()).equals(entry.getValue()), "REVISION_FILE_CHANGED");
            Checks.require(
                    entry.getKey().equals("release.env")
                            || !entry.getKey().matches(".*\\.(pem|key|p12|jks|env)$"),
                    "PRIVATE_FILE_IN_REVISION");
        }
        if (!value.kind().equals("basis")) {
            var jar = "jars/" + value.kind() + "-app.jar";
            Checks.require(value.files().containsKey(jar), "REVISION_JAR_MISSING");
            Checks.require(
                    Environment.parse(
                                    Files.readString(directory.resolve("release.env")),
                                    Environment.RELEASE_KEYS)
                            .equals(releaseEnvironment(value)),
                    "REVISION_ENV_CHANGED");
            var expected = textManifest(value);
            Checks.require(
                    Environment.parse(
                                    Files.readString(directory.resolve("release-manifest.txt")),
                                    expected.keySet())
                            .equals(expected),
                    "REVISION_ENV_CHANGED");
        }
        return value;
    }

    static Map<String, String> releaseEnvironment(Revision value) {
        return Map.of(
                "MANAGEMENT_INFO_ENV_ENABLED",
                "true",
                "INFO_COMPONENT",
                value.kind(),
                "INFO_COMMIT",
                value.commit(),
                "INFO_SOURCEDIGEST",
                value.sourceDigest(),
                "INFO_ENVIRONMENT",
                "dev");
    }

    static Map<String, String> textManifest(Revision value) {
        return Map.of(
                "component",
                value.kind(),
                "environment",
                "dev",
                "commit",
                value.commit(),
                "sourceDigest",
                value.sourceDigest(),
                "artifactSha256",
                value.files().get("jars/" + value.kind() + "-app.jar"),
                "basisDigest",
                value.basisDigest(),
                "reviewOnly",
                Boolean.toString(value.reviewOnly()));
    }

    static Path selectedBasis(Layout layout) throws Exception {
        var link = layout.root().resolve("basis/current");
        Checks.require(Files.isSymbolicLink(link), "DEV_BASIS_NOT_PREPARED");
        var directory = link.toRealPath();
        Checks.require(
                directory.getParent().equals(layout.root().resolve("basis/releases")),
                "DEV_BASIS_PATH");
        var basis = verify(directory, true);
        Checks.require(
                basis.kind().equals("basis")
                        && directory.getFileName().toString().equals(basis.releaseId()),
                "DEV_BASIS_PATH");
        return directory;
    }

    static void ready(Layout layout, Revision basis) throws Exception {
        FileOps.atomicFile(
                layout.root().resolve("basis/ready.json"),
                Json.bytes(
                        Map.of("releaseId", basis.releaseId(), "basisDigest", basis.basisDigest())),
                0600);
    }

    static void requireReady(Layout layout, Revision basis) throws Exception {
        var value =
                Json.read(
                        FileOps.privateText(layout.root().resolve("basis/ready.json")).getBytes());
        Checks.require(
                value.path("releaseId").asText().equals(basis.releaseId())
                        && value.path("basisDigest").asText().equals(basis.basisDigest()),
                "DEV_BASIS_NOT_READY");
    }

    static void routing(Revision app, Map<String, String> environment, Revision basis) {
        Checks.require(
                Set.of("api", "worker").contains(app.kind())
                        && app.environment().equals("dev")
                        && !app.reviewOnly(),
                "DEV_COMPONENT_REVISION");
        Checks.require(
                APPLICATION.equals(environment.get("APPLICATION_NAME"))
                        && (APPLICATION + "-" + app.kind())
                                .equals(environment.get("DEPLOYMENT_GROUP_NAME")),
                "DEV_DEPLOYMENT_GROUP");
        Checks.require(
                basis.kind().equals("basis")
                        && basis.environment().equals("dev")
                        && !basis.reviewOnly()
                        && basis.basisDigest().equals(app.basisDigest()),
                "DEV_BASIS_UPDATE_REQUIRED");
    }

    static Revision component(
            Path incoming, Layout layout, Host host, Map<String, String> environment)
            throws Exception {
        Checks.require(
                !FileOps.exists(layout.root().resolve("basis/operation.json")),
                "DEV_BASIS_RECOVERY_REQUIRED");
        var marker = layout.root().resolve(".deploying/owner");
        if (FileOps.exists(marker)) {
            FileOps.protectedFile(marker, "DEV_DEPLOYMENT_MARKER");
            Checks.require(
                    !Files.readString(marker).startsWith("basis "), "DEV_BASIS_UPDATE_IN_PROGRESS");
        }
        var metadata = verify(incoming, true);
        var basis = verify(selectedBasis(layout), true);
        requireReady(layout, basis);
        routing(metadata, environment, basis);
        new Preflight(layout, host).check("prepare", Map.of());
        for (var path :
                java.util.List.of(
                        layout.root(),
                        layout.root().resolve(metadata.kind()),
                        layout.root().resolve(metadata.kind() + "/releases"))) {
            if (FileOps.exists(path)) {
                Checks.require(
                        Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                                && FileOps.attribute(path, "uid") == 0
                                && (FileOps.attribute(path, "mode") & 0022) == 0,
                        "DEV_DEPLOYMENT_DIRECTORY");
            }
        }
        return metadata;
    }
}
