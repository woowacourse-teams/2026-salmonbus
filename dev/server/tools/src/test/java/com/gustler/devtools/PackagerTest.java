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
