package com.gustler.devtools;

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarFile;

final class Packager {
    private final Path repo;
    private final Host host;

    Packager(Path repo, Host host) {
        this.repo = repo.toAbsolutePath().normalize();
        this.host = host;
    }

    static void copy(Path source, Path destination) throws Exception {
        Checks.require(
                Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS), "PACKAGE_SOURCE_FILE");
        Files.createDirectories(destination.getParent());
        Files.copy(source, destination);
        FileOps.mode(destination, destination.toString().endsWith(".sh") ? 0755 : 0644);
    }

    String digest(List<Path> inputs) throws Exception {
        var files = new TreeMap<String, Path>();
        for (var input : inputs) {
            if (Files.isDirectory(input, LinkOption.NOFOLLOW_LINKS)) {
                try (var paths = Files.walk(input)) {
                    for (var path :
                            paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                                    .toList()) {
                        var relative = repo.relativize(path);
                        if (java.util.stream.StreamSupport.stream(relative.spliterator(), false)
                                .noneMatch(
                                        part ->
                                                List.of("build", ".gradle")
                                                        .contains(part.toString()))) {
                            files.put(relative.toString(), path);
                        }
                    }
                }
            } else {
                Checks.require(
                        Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS),
                        "PACKAGE_SOURCE_FILE");
                files.put(repo.relativize(input).toString(), input);
            }
        }
        var hash = MessageDigest.getInstance("SHA-256");
        for (var entry : files.entrySet()) {
            hash.update(
                    (entry.getKey() + "\u0000" + FileOps.sha(entry.getValue()) + "\n")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    private List<Path> basisInputs() {
        return List.of(
                repo.resolve("dev/server/tools/src/main"),
                repo.resolve("dev/server/tools/build.gradle"),
                repo.resolve("dev/server/tools/settings.gradle"),
                repo.resolve("dev/server/api.yml"),
                repo.resolve("dev/server/worker.yml"),
                repo.resolve("dev/server/compose.yaml"),
                repo.resolve("dev/server/init-chat.js"),
                repo.resolve("dev/server/wiremock.Dockerfile"),
                repo.resolve("dev/server/systemd/salmonbus-dev-infra.service"),
                repo.resolve("dev/config/mongodb.yml"),
                repo.resolve("dev/data/java"),
                repo.resolve("dev/data/routes.json"),
                repo.resolve("dev/data/catalog-routes.json"),
                repo.resolve("dev/data/model-reference"),
                repo.resolve("dev/wiremock/mappings"),
                repo.resolve("dev/wiremock-extension/src/main"),
                repo.resolve("dev/scenarios/replay.json"));
    }

    String basisContract() throws Exception {
        var inputs = new java.util.ArrayList<>(basisInputs());
        inputs.add(
                repo.resolve(
                        "backend/business/forecasting/src/main/java/com/gustler/backend/forecasting/domain/model/ForecastFeatureContract.java"));
        return digest(inputs);
    }

    String preparationContract() throws Exception {
        return digest(
                List.of(
                        repo.resolve("dev/data/java"),
                        repo.resolve(
                                "backend/business/forecasting/src/main/java/com/gustler/backend/forecasting/domain/model/ForecastFeatureContract.java")));
    }

    String basisDigest() throws Exception {
        var inputs = new java.util.ArrayList<>(basisInputs());
        inputs.add(repo.resolve("dev/server/data.init"));
        var script = "source deploy/runtime-source-inputs.sh; runtime_source_inputs worker";
        var runtimeInputs =
                host.run(
                                repo.resolve("backend"),
                                null,
                                Duration.ofSeconds(10),
                                List.of("bash", "-c", script))
                        .checked();
        for (var name : runtimeInputs.lines().toList()) {
            var relative = Path.of(name);
            Checks.require(
                    !relative.isAbsolute() && !relative.normalize().startsWith(".."),
                    "PACKAGE_SOURCE_FILE");
            var input = repo.resolve("backend").resolve(relative);
            if (Files.exists(input)) {
                inputs.add(input);
            }
        }
        return digest(inputs);
    }

    static Map<String, String> imageMetadata(Path archive, String commit) throws Exception {
        var metadata = new TreeMap<String, byte[]>();
        var names = new java.util.HashSet<String>();
        long total = 0;
        try (var input = new TarArchiveInputStream(Files.newInputStream(archive))) {
            org.apache.commons.compress.archivers.tar.TarArchiveEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                var path = Path.of(entry.getName());
                Checks.require(
                        !path.isAbsolute()
                                && !path.normalize().startsWith("..")
                                && !entry.getName().contains("\\")
                                && (entry.isFile() || entry.isDirectory())
                                && !entry.isLink()
                                && !entry.isSymbolicLink()
                                && names.add(entry.getName()),
                        "IMAGE_ARCHIVE_PATH");
                if (entry.isFile() && entry.getSize() < 2097152) {
                    total += entry.getSize();
                    Checks.require(total <= 16 * 1024 * 1024, "IMAGE_ARCHIVE_METADATA");
                    metadata.put(
                            entry.getName(), input.readNBytes(Math.toIntExact(entry.getSize())));
                }
            }
        }
        Checks.require(metadata.containsKey("manifest.json"), "IMAGE_ARCHIVE_METADATA");
        var manifest = Json.read(metadata.get("manifest.json"));
        var tag = "salmonbus-gbis-replay:" + commit;
        Checks.require(
                manifest.isArray()
                        && manifest.size() == 1
                        && manifest.get(0).path("RepoTags").isArray()
                        && manifest.get(0).path("RepoTags").size() == 1
                        && manifest.get(0).path("RepoTags").get(0).asText().equals(tag),
                "IMAGE_ARCHIVE_TAG");
        var content = metadata.get(manifest.get(0).path("Config").asText());
        Checks.require(content != null, "IMAGE_ARCHIVE_METADATA");
        var config = Json.read(content);
        Checks.require(
                config.path("architecture").asText().equals("arm64")
                        && config.path("os").asText().equals("linux"),
                "IMAGE_ARCHITECTURE");
        return Map.of("tag", tag, "id", "sha256:" + FileOps.sha(content), "architecture", "arm64");
    }

    static void checkJar(Path file, String component) throws Exception {
        try (var jar = new JarFile(file.toFile())) {
            var expected =
                    Map.of(
                                    "api",
                                    "com.gustler.backend.ApiApplication",
                                    "worker",
                                    "com.gustler.backend.worker.WorkerApplication",
                                    "data",
                                    "com.gustler.localdata.LocalData",
                                    "tools",
                                    "com.gustler.devtools.DevTools")
                            .get(component);
            var key = component.equals("tools") ? "Main-Class" : "Start-Class";
            Checks.require(
                    expected.equals(jar.getManifest().getMainAttributes().getValue(key)),
                    "JAR_MAIN_CLASS");
            Checks.require(
                    jar.stream()
                            .noneMatch(entry -> entry.getName().matches(".*\\.(pem|key|p12|jks)$")),
                    "PRIVATE_FILE_IN_JAR");
            if (List.of("api", "worker").contains(component)) {
                Checks.require(
                        jar.stream()
                                .noneMatch(
                                        entry ->
                                                entry.getName()
                                                                .startsWith(
                                                                        "BOOT-INF/classes/com/gustler/localdata/")
                                                        || entry.getName()
                                                                .contains("com/gustler/devtools/")),
                        "PREPARATION_CODE_IN_APP_JAR");
            }
        }
    }

    void imageContext(Path output) throws Exception {
        Checks.require(!FileOps.exists(output), "PACKAGE_OUTPUT_EXISTS");
        Files.createDirectories(output);
        copy(repo.resolve("dev/server/wiremock.Dockerfile"), output.resolve("Dockerfile"));
        copy(
                repo.resolve("dev/wiremock-extension/build/libs/local-gbis-replay.jar"),
                output.resolve("local-gbis-replay.jar"));
        try (var files = Files.list(repo.resolve("dev/wiremock/mappings"))) {
            for (var file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                copy(file, output.resolve("mappings/" + file.getFileName()));
            }
        }
        for (var name : List.of("routes.json", "catalog-routes.json")) {
            copy(repo.resolve("dev/data/" + name), output.resolve("data/" + name));
        }
        copy(repo.resolve("dev/scenarios/replay.json"), output.resolve("replay.json"));
    }

    void assemble(Path output, Path archive, String commit, final boolean review) throws Exception {
        Checks.require(!FileOps.exists(output), "PACKAGE_OUTPUT_EXISTS");
        var image = imageMetadata(archive, commit);
        var digest = basisDigest();
        var contract = basisContract();
        Files.createDirectories(output.toAbsolutePath().getParent());
        var stage =
                Files.createTempDirectory(
                        output.toAbsolutePath().getParent(), ".shared-dev-package-");
        try {
            var basis = stage.resolve("basis");
            var tools = repo.resolve("dev/server/tools/build/libs/shared-dev-tools.jar");
            var dataJar = repo.resolve("backend/worker-app/build/libs/shared-dev-data.jar");
            checkJar(tools, "tools");
            checkJar(dataJar, "data");
            copy(tools, basis.resolve("tools/shared-dev-tools.jar"));
            copy(dataJar, basis.resolve("tools/shared-dev-data.jar"));
            FileOps.writeNew(
                    basis.resolve("tools/data-contract.sha256"),
                    (preparationContract() + "\n").getBytes(),
                    0644);
            FileOps.writeNew(
                    basis.resolve("tools/shared-dev-data.jar.sha256"),
                    (FileOps.sha(dataJar) + "\n").getBytes(),
                    0644);
            for (var name : List.of("api.yml", "worker.yml")) {
                var source = repo.resolve("dev/server/" + name);
                Checks.require(
                        !java.util.regex.Pattern.compile(
                                        "(?m)^\\s*(password|username|url|service-key(?:-[a-z])?|mongodb-uri)\\s*:")
                                .matcher(Files.readString(source))
                                .find(),
                        "PRIVATE_CONFIG_FIELD_IN_REVISION");
                copy(source, basis.resolve("tools/" + name));
            }
            copy(repo.resolve("dev/config/mongodb.yml"), basis.resolve("config/mongodb.yml"));
            for (var name : DataPreparation.FILES) {
                copy(repo.resolve("dev/data/" + name), basis.resolve("tools/data/" + name));
            }
            for (var name : List.of("compose.yaml", "init-chat.js")) {
                copy(repo.resolve("dev/server/" + name), basis.resolve("infra/" + name));
            }
            copy(
                    repo.resolve("dev/server/systemd/salmonbus-dev-infra.service"),
                    basis.resolve("systemd/salmonbus-dev-infra.service"));
            copy(archive, basis.resolve("wiremock-image.tar"));
            FileOps.writeNew(basis.resolve("wiremock-image.json"), Json.bytes(image), 0644);
            RevisionStore.seal(basis, "basis", commit, digest, contract, review);
            for (var component : List.of("api", "worker")) {
                var directory = stage.resolve(component);
                List<Path> jars;
                try (var files =
                        Files.list(repo.resolve("backend/" + component + "-app/build/libs"))) {
                    jars =
                            files.filter(
                                            p ->
                                                    p.getFileName()
                                                                    .toString()
                                                                    .matches(
                                                                            component
                                                                                    + "-app-.*\\.jar")
                                                            && !p.toString().endsWith("-plain.jar"))
                                    .toList();
                }
                Checks.require(jars.size() == 1, "PACKAGE_APP_JAR");
                checkJar(jars.getFirst(), component);
                copy(jars.getFirst(), directory.resolve("jars/" + component + "-app.jar"));
                copy(
                        repo.resolve("dev/server/deploy/appspec-" + component + ".yml"),
                        directory.resolve("appspec.yml"));
                for (var name : List.of("common.sh", "preflight.sh", "install.sh")) {
                    copy(
                            repo.resolve("dev/server/deploy/" + name),
                            directory.resolve("scripts/" + name));
                }
                for (var name : List.of("common.sh", "start.sh", "validate.sh")) {
                    copy(
                            repo.resolve("backend/deploy/scripts/" + name),
                            directory.resolve(
                                    "scripts/"
                                            + (name.equals("common.sh")
                                                    ? "production-common.sh"
                                                    : name)));
                }
                var unit = "systemd/salmonbus-" + component + ".service";
                copy(repo.resolve("dev/server/" + unit), directory.resolve(unit));
                var runtime =
                        host.run(
                                        repo.resolve("backend"),
                                        null,
                                        Duration.ofSeconds(30),
                                        List.of(
                                                "bash",
                                                "deploy/runtime-source-inputs.sh",
                                                component))
                                .checked()
                                .strip();
                Checks.require(runtime.matches(RevisionStore.HASH), "PACKAGE_RUNTIME_DIGEST");
                var appDigest =
                        FileOps.sha(
                                (runtime
                                                + contract
                                                + digest(
                                                        List.of(
                                                                repo.resolve("dev/server/deploy"),
                                                                repo.resolve("dev/server/" + unit),
                                                                repo.resolve(
                                                                        "backend/deploy/scripts"))))
                                        .getBytes());
                var metadata =
                        new RevisionStore.Revision(
                                1,
                                "dev",
                                component,
                                commit,
                                appDigest,
                                contract,
                                review,
                                Map.of(
                                        "jars/" + component + "-app.jar",
                                        FileOps.sha(jars.getFirst())),
                                "");
                FileOps.writeNew(
                        directory.resolve("release.env"),
                        Environment.text(RevisionStore.releaseEnvironment(metadata)).getBytes(),
                        0644);
                FileOps.writeNew(
                        directory.resolve("release-manifest.txt"),
                        Environment.text(RevisionStore.textManifest(metadata)).getBytes(),
                        0644);
                RevisionStore.seal(directory, component, commit, appDigest, contract, review);
            }
            for (var kind : List.of("basis", "api", "worker")) {
                RevisionStore.verify(stage.resolve(kind), false);
            }
            FileOps.moveNew(stage, output.toAbsolutePath());
        } finally {
            FileOps.removeTree(stage);
        }
    }
}
