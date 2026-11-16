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

    @Test
    void 개발_배포물의_시작_스크립트가_누락된_운영_스크립트를_요구하지_않는다() throws Exception {
        var repo = Path.of("../../..").toAbsolutePath().normalize();
        var bundle = root.resolve("application");
        new Packager(repo, new Host()).copyApplicationScripts(bundle);
        var scripts = bundle.resolve("scripts");
        var calls = root.resolve("calls");
        Files.writeString(scripts.resolve("common.sh"), """
                set -euo pipefail
                COMPONENT=api
                UNIT=salmonbus-api
                JAR=/opt/salmonbus-dev/api/current/jars/api-app.jar
                CHANGED=/nonexistent/change-marker
                claim_deploy() { :; }
                unit_pid() { echo 123; }
                log() { :; }
                pgrep() { return 1; }
                systemctl() {
                  if [ "$1" = is-active ]; then return 1; fi
                  echo "$*" >> "$CALLS"
                }
                """);

        var process = new ProcessBuilder("bash", scripts.resolve("start-body.sh").toString())
                .redirectErrorStream(true);
        process.environment().put("CALLS", calls.toString());
        var running = process.start();
        var output = new String(running.getInputStream().readAllBytes());

        assertEquals(0, running.waitFor(), output);
        assertEquals("stop salmonbus-api\nstart salmonbus-api\n", Files.readString(calls));
    }

    @Test
    void 개발_배포_검증이_실패하면_서비스를_시작하지_않는다() throws Exception {
        var repo = Path.of("../../..").toAbsolutePath().normalize();
        var bundle = root.resolve("rejected");
        new Packager(repo, new Host()).copyApplicationScripts(bundle);
        var scripts = bundle.resolve("scripts");
        Files.writeString(scripts.resolve("common.sh"), "set -euo pipefail\nexit 42\n");

        var running = new ProcessBuilder("bash", scripts.resolve("start-body.sh").toString())
                .redirectErrorStream(true).start();
        var output = new String(running.getInputStream().readAllBytes());

        assertEquals(42, running.waitFor(), output);
    }

    private record StartRun(int exit, String calls, String output) {
    }

    /**
     * worker 묶음의 시작 스크립트를 가짜 systemctl 로 돌린다. {@code active} 는 떠 있는 유닛 이름을 빈칸으로 이은 것이고, 멈춘 유닛은
     * 거기서 빠진다. {@code failingStart} 유닛은 시작에 실패한다.
     */
    private StartRun startWorkerBundle(String changed, String active, String failingStart) throws Exception {
        Path repo = Path.of("../../..").toAbsolutePath().normalize();
        Path bundle = Files.createTempDirectory(root, "worker-");
        new Packager(repo, new Host()).copyApplicationScripts(bundle);
        Path scripts = bundle.resolve("scripts");
        Path calls = bundle.resolve("calls");
        Path marker = bundle.resolve("change-marker");
        Files.writeString(marker, "changed=" + changed + "\nreason=fixture\n");
        Files.writeString(scripts.resolve("common.sh"), """
                set -euo pipefail
                COMPONENT=worker
                UNIT=salmonbus-worker
                CURRENT=/opt/salmonbus-dev/worker/current
                JAR=$CURRENT/jars/worker-app.jar
                CHANGED="$CHANGE_MARKER"
                claim_deploy() { :; }
                unit_pid() { echo 123; }
                log() { :; }
                pgrep() { return 1; }
                systemctl() {
                  if [ "$1" = is-active ]; then
                    case " $ACTIVE " in *" $3 "*) return 0 ;; esac
                    return 1
                  fi
                  echo "$*" >> "$CALLS"
                  if [ "$1" = stop ]; then ACTIVE="${ACTIVE/$2/}"; fi
                  [ "$*" != "start $FAILING_START" ]
                }
                """);
        ProcessBuilder process = new ProcessBuilder("bash", scripts.resolve("start-body.sh").toString())
            .redirectErrorStream(true);
        process.environment().putAll(Map.of(
            "CALLS", calls.toString(),
            "CHANGE_MARKER", marker.toString(),
            "ACTIVE", active,
            "FAILING_START", failingStart));
        Process running = process.start();
        String output = new String(running.getInputStream().readAllBytes());
        final int exit = running.waitFor();
        if (Files.notExists(calls)) {
            return new StartRun(exit, "", output);
        }
        return new StartRun(exit, Files.readString(calls), output);
    }

    @Test
    void 바뀐_worker_묶음은_worker와_이관_앱을_차례로_멈췄다가_띄운다() throws Exception {
        // when
        StartRun run = startWorkerBundle("yes", "salmonbus-worker salmonbus-archive", "");

        // then
        assertEquals(0, run.exit(), run.output());
        assertEquals(
            "stop salmonbus-worker\nstart salmonbus-worker\nstop salmonbus-archive\nstart salmonbus-archive\n",
            run.calls());
    }

    @Test
    void 바뀐_것이_없고_둘_다_떠_있으면_worker도_이관_앱도_다시_띄우지_않는다() throws Exception {
        // when
        StartRun run = startWorkerBundle("no", "salmonbus-worker salmonbus-archive", "");

        // then
        assertEquals(0, run.exit(), run.output());
        assertEquals("", run.calls());
    }

    @Test
    void 바뀐_것이_없어도_이관_앱이_떠_있지_않으면_이관_앱만_띄운다() throws Exception {
        // when
        StartRun run = startWorkerBundle("no", "salmonbus-worker", "");

        // then
        assertEquals(0, run.exit(), run.output());
        assertEquals("stop salmonbus-archive\nstart salmonbus-archive\n", run.calls());
    }

    @Test
    void 이관_앱을_띄우지_못하면_worker_묶음의_시작_단계가_실패한다() throws Exception {
        // when
        StartRun run = startWorkerBundle("yes", "salmonbus-worker salmonbus-archive", "salmonbus-archive");

        // then
        assertNotEquals(0, run.exit(), run.output());
        assertEquals(
            "stop salmonbus-worker\nstart salmonbus-worker\nstop salmonbus-archive\nstart salmonbus-archive\n",
            run.calls());
    }

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
