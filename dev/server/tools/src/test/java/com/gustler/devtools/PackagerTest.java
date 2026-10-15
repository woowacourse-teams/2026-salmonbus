package com.gustler.devtools;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

class PackagerTest {
    @TempDir Path root;

    private Path archive(String architecture, String entry) throws Exception {
        var path = root.resolve("image-" + java.util.UUID.randomUUID() + ".tar");
        var config = Json.bytes(Map.of("architecture", architecture, "os", "linux"));
        var manifest =
                Json.bytes(
                        java.util.List.of(
                                Map.of(
                                        "Config",
                                        "config.json",
                                        "RepoTags",
                                        java.util.List.of(
                                                "salmonbus-gbis-replay:" + "a".repeat(40)),
                                        "Layers",
                                        java.util.List.of())));
        try (var tar = new TarArchiveOutputStream(Files.newOutputStream(path))) {
            for (var item :
                    Map.of("config.json", config, "manifest.json", manifest, entry, new byte[0])
                            .entrySet()) {
                var member = new TarArchiveEntry(item.getKey());
                member.setSize(item.getValue().length);
                tar.putArchiveEntry(member);
                tar.write(item.getValue());
                tar.closeArchiveEntry();
            }
        }
        return path;
    }

    @Test
    void sharedImageUsesTheServerReplayProfile() throws Exception {
        var repo = root.resolve("repo");
        for (var entry : Map.of(
                "dev/server/wiremock.Dockerfile", "FROM scratch\n",
                "dev/server/replay.json", "shared replay profile",
                "dev/scenarios/replay.json", "personal local profile",
                "dev/wiremock-extension/build/libs/local-gbis-replay.jar", "extension artifact",
                "dev/wiremock/mappings/location.json", "{}",
                "dev/data/routes.json", "{}",
                "dev/data/catalog-routes.json", "{}").entrySet()) {
            var file = repo.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
        }
        var output = root.resolve("image");
        new Packager(repo, new Host()).imageContext(output);
        assertEquals("shared replay profile", Files.readString(output.resolve("replay.json")));
        assertTrue(Files.exists(output.resolve("mappings/location.json")));
    }

    @Test
    void acceptsArmImageAndRejectsWrongTagArchitectureAndTraversal() throws Exception {
        var archive = archive("arm64", "layer.tar");
        assertEquals("arm64", Packager.imageMetadata(archive, "a".repeat(40)).get("architecture"));
        assertThrows(
                IllegalArgumentException.class,
                () -> Packager.imageMetadata(archive, "b".repeat(40)));
        assertThrows(
                IllegalArgumentException.class,
                () -> Packager.imageMetadata(archive("amd64", "layer.tar"), "a".repeat(40)));
        assertThrows(
                IllegalArgumentException.class,
                () -> Packager.imageMetadata(archive("arm64", "../../outside"), "a".repeat(40)));
    }

    @Test
    void imageArchiveLinksAreRejectedWithoutExtraction() throws Exception {
        var path = root.resolve("linked.tar");
        try (var tar = new TarArchiveOutputStream(Files.newOutputStream(path))) {
            var link =
                    new TarArchiveEntry(
                            "alias",
                            org.apache.commons.compress.archivers.tar.TarConstants.LF_SYMLINK);
            link.setLinkName("/outside");
            tar.putArchiveEntry(link);
            tar.closeArchiveEntry();
        }
        assertThrows(
                IllegalArgumentException.class, () -> Packager.imageMetadata(path, "a".repeat(40)));
        assertEquals(1, Files.list(root).count());
    }

    @Test
    void rejectsPreparationClassesInApplicationJar() throws Exception {
        var path = root.resolve("api.jar");
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Start-Class", "com.gustler.backend.ApiApplication");
        try (var jar = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            jar.putNextEntry(new JarEntry("BOOT-INF/classes/com/gustler/devtools/DevTools.class"));
            jar.write(new byte[] {1});
            jar.closeEntry();
        }
        assertEquals(
                "PREPARATION_CODE_IN_APP_JAR",
                assertThrows(IllegalArgumentException.class, () -> Packager.checkJar(path, "api"))
                        .getMessage());
    }

    @Test
    void embeddedBasisRetainsItsManifestAndDetectsChangedFiles() throws Exception {
        var basis = Files.createDirectory(root.resolve("basis"));
        Files.createDirectory(basis.resolve("tools"));
        Files.writeString(basis.resolve("tools/shared-dev-tools.jar"), "tool artifact");
        Files.writeString(basis.resolve("wiremock-image.tar"), "image artifact");
        var metadata = RevisionStore.seal(basis, "basis", "a".repeat(40),
                "b".repeat(64), "c".repeat(64), false);
        var api = Files.createDirectory(root.resolve("api"));
        Packager.embedBasis(basis, api);
        assertEquals(metadata, RevisionStore.verify(api.resolve("basis"), true));
        assertEquals(FileOps.sha(basis.resolve("tools/shared-dev-tools.jar")),
                FileOps.sha(api.resolve("basis/tools/shared-dev-tools.jar")));
        Files.writeString(api.resolve("basis/tools/shared-dev-tools.jar"), "changed artifact");
        assertThrows(IllegalArgumentException.class,
                () -> RevisionStore.verify(api.resolve("basis"), true));
    }

    @Test
    void malformedCliArgumentsDoNotStartCommands() {
        var host =
                new Host() {
                    @Override
                    boolean root() {
                        throw new AssertionError("Must reject arguments first");
                    }
                };
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DevTools.execute(
                                new String[] {"bootstrap", "--apply", "--apply"},
                                Layout.SERVER,
                                host,
                                Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DevTools.execute(
                                new String[] {"configure", "--unknown", "value"},
                                Layout.SERVER,
                                host,
                                Map.of()));
    }
}
