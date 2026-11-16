package com.gustler.devtools;

import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Tag("linux-root")
class DeploymentServerTest {
    @TempDir Path base;
    private Fixtures fixture;
    private String basisDigest;

    @BeforeEach
    void prepare() throws Exception {
        assertTrue(new Host().root());
        fixture = new Fixtures(base);
        fixture.settings();
        var releases = Files.createDirectories(fixture.layout.root().resolve("basis/releases"));
        FileOps.mode(releases.getParent(), 0755);
        FileOps.mode(releases, 0755);
        var initial = Files.createDirectory(releases.resolve("initial"));
        Files.writeString(initial.resolve("public.txt"), "basis fixture\n");
        basisDigest = "f".repeat(64);
        var metadata =
                RevisionStore.seal(
                        initial, "basis", Fixtures.COMMIT, basisDigest, basisDigest, false);
        var selected = releases.resolve(metadata.releaseId());
        Files.move(initial, selected);
        Files.createSymbolicLink(
                fixture.layout.root().resolve("basis/current"),
                Path.of("releases/" + metadata.releaseId()));
        RevisionStore.ready(fixture.layout, metadata);
        for (var component : java.util.List.of("api", "worker")) {
            var componentReleases =
                    Files.createDirectories(fixture.layout.root().resolve(component + "/releases"));
            FileOps.mode(componentReleases.getParent(), 0755);
            FileOps.mode(componentReleases, 0755);
        }
        Files.writeString(fixture.layout.data().resolve("keep-data"), "database sentinel\n");
    }

    private Path incoming(String component, String digest, String jar) throws Exception {
        return sealed(unsealed(component, jar), component, digest);
    }

    /** 봉인하기 전의 묶음이다. worker 묶음에는 이관 앱의 JAR과 유닛이 함께 실린다. */
    private Path unsealed(String component, String jar) throws Exception {
        var directory = Files.createTempDirectory(base, "incoming-");
        Files.createDirectory(directory.resolve("jars"));
        Files.createDirectory(directory.resolve("systemd"));
        Files.writeString(directory.resolve("jars/" + component + "-app.jar"), jar);
        Files.writeString(
                directory.resolve("systemd/salmonbus-" + component + ".service"),
                "fixture unit " + component);
        if (component.equals("worker")) {
            Files.writeString(directory.resolve(RevisionStore.ARCHIVE_JAR), "archive fixture");
            Files.writeString(directory.resolve("systemd/" + RevisionStore.ARCHIVE_UNIT), "fixture unit archive");
        }
        return directory;
    }

    private Path sealed(Path directory, String component, String digest) throws Exception {
        var value =
                new RevisionStore.Revision(
                        1,
                        "dev",
                        component,
                        Fixtures.COMMIT,
                        digest,
                        basisDigest,
                        false,
                        Map.of(
                                "jars/" + component + "-app.jar",
                                FileOps.sha(directory.resolve("jars/" + component + "-app.jar"))),
                        "");
        Files.writeString(
                directory.resolve("release.env"),
                Environment.text(RevisionStore.releaseEnvironment(value)));
        Files.writeString(
                directory.resolve("release-manifest.txt"),
                Environment.text(RevisionStore.textManifest(value)));
        RevisionStore.seal(directory, component, Fixtures.COMMIT, digest, basisDigest, false);
        return directory;
    }

    private Map<String, String> environment(String component) {
        return Map.of(
                "APPLICATION_NAME",
                RevisionStore.APPLICATION,
                "DEPLOYMENT_GROUP_NAME",
                RevisionStore.APPLICATION + "-" + component);
    }

    private void staging(String component, Path incoming) throws Exception {
        var destination = fixture.layout.root().resolve(component + "/staging");
        Files.createDirectory(destination);
        try (var paths = Files.walk(incoming)) {
            for (var path : paths.filter(p -> !p.equals(incoming)).toList()) {
                var target = destination.resolve(incoming.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectory(target);
                } else {
                    Files.copy(path, target);
                }
            }
        }
    }

    private Map<String, Object> install(String component, Path incoming) throws Exception {
        staging(component, incoming);
        return new Deployment(fixture.layout, fixture.host)
                .install(incoming, environment(component));
    }

    @Test
    void worker_묶음을_설치하면_이관_앱의_JAR과_유닛도_설치하고_켠다() throws Exception {
        // given
        Path worker = incoming("worker", "2".repeat(64), "worker initial");

        // when
        install("worker", worker);

        // then
        Path unit = fixture.layout.units().resolve("salmonbus-archive.service");
        Path jar = fixture.layout.root().resolve("worker/current/jars/archive-app.jar");
        assertSoftly(softly -> {
            softly.assertThat(unit).hasContent("fixture unit archive");
            softly.assertThat(jar).hasContent("archive fixture");
            softly.assertThat(fixture.host.calls).contains(List.of("systemctl", "enable", "salmonbus-archive.service"));
        });
    }

    @Test
    void api_묶음을_설치할_때는_이관_앱의_유닛을_건드리지_않는다() throws Exception {
        // given
        Path api = incoming("api", "1".repeat(64), "api initial");

        // when
        install("api", api);

        // then
        assertSoftly(softly -> {
            softly.assertThat(fixture.layout.units().resolve("salmonbus-archive.service")).doesNotExist();
            softly.assertThat(fixture.host.calls).noneMatch(call -> call.contains("salmonbus-archive.service"));
        });
    }

    @Test
    void 이관_앱이_빠진_worker_묶음은_설치하지_않는다() throws Exception {
        // given
        Path directory = unsealed("worker", "worker initial");
        Files.delete(directory.resolve(RevisionStore.ARCHIVE_JAR));
        Path worker = sealed(directory, "worker", "2".repeat(64));
        staging("worker", worker);

        // when
        Throwable failure = catchThrowable(
            () -> new Deployment(fixture.layout, fixture.host).install(worker, environment("worker")));

        // then
        assertSoftly(softly -> {
            softly.assertThat(failure).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("REVISION_ARCHIVE_MISSING");
            softly.assertThat(fixture.host.calls).isEmpty();
            softly.assertThat(fixture.layout.root().resolve("worker/current")).doesNotExist();
        });
    }

    @Test
    void 이관_앱의_유닛만_바뀐_worker_묶음도_재시작을_요청한다() throws Exception {
        // given
        install("worker", incoming("worker", "2".repeat(64), "first compile"));
        Path directory = unsealed("worker", "second compile");
        Files.writeString(directory.resolve("systemd/salmonbus-archive.service"), "changed unit archive");
        Path update = sealed(directory, "worker", "2".repeat(64));

        // when
        Map<String, Object> result = install("worker", update);

        // then
        assertSoftly(softly -> {
            softly.assertThat(result.get("restartRequired")).isEqualTo(true);
            softly.assertThat(fixture.layout.units().resolve("salmonbus-archive.service"))
                .hasContent("changed unit archive");
        });
    }

    @Test
    void apiUpdateDoesNotChangeWorkerAndRedeploymentRestoresPreviousApi() throws Exception {
        var api = incoming("api", "1".repeat(64), "api initial");
        var worker = incoming("worker", "2".repeat(64), "worker initial");
        install("api", api);
        install("worker", worker);
        var workerBefore = fixture.layout.root().resolve("worker/current").toRealPath();
        var apiBefore = fixture.layout.root().resolve("api/current").toRealPath();
        var update = incoming("api", "3".repeat(64), "api update");
        assertEquals(true, install("api", update).get("restartRequired"));
        assertEquals(workerBefore, fixture.layout.root().resolve("worker/current").toRealPath());
        assertEquals(apiBefore, fixture.layout.root().resolve("api/previous").toRealPath());
        assertEquals(true, install("api", api).get("restartRequired"));
        assertEquals(apiBefore, fixture.layout.root().resolve("api/current").toRealPath());
        assertTrue(
                Files.exists(
                        fixture.layout
                                .root()
                                .resolve(
                                        "api/releases/"
                                                + RevisionStore.verify(update, true).releaseId())));
        assertEquals(
                "database sentinel\n",
                Files.readString(fixture.layout.data().resolve("keep-data")));
    }

    @Test
    void workerUpdateAndRecoveryPreserveApiAndAllRevisions() throws Exception {
        var api = incoming("api", "1".repeat(64), "api initial");
        var worker = incoming("worker", "2".repeat(64), "worker initial");
        install("api", api);
        install("worker", worker);
        var apiBefore = fixture.layout.root().resolve("api/current").toRealPath();
        var previous = fixture.layout.root().resolve("worker/current").toRealPath();
        var update = incoming("worker", "3".repeat(64), "worker update");
        install("worker", update);
        install("worker", worker);
        assertEquals(apiBefore, fixture.layout.root().resolve("api/current").toRealPath());
        assertEquals(previous, fixture.layout.root().resolve("worker/current").toRealPath());
        assertTrue(
                Files.exists(
                        fixture.layout
                                .root()
                                .resolve(
                                        "worker/releases/"
                                                + RevisionStore.verify(update, true).releaseId())));
    }

    @Test
    void rebuildingIdenticalRuntimeDoesNotRequestRestart() throws Exception {
        install("api", incoming("api", "1".repeat(64), "first compile"));
        assertEquals(
                false,
                install("api", incoming("api", "1".repeat(64), "second compile"))
                        .get("restartRequired"));
        assertTrue(
                Files.readString(fixture.layout.root().resolve("api/.changed"))
                        .contains("changed=no"));
    }

    @Test
    void wrongGroupAndChangedStagingAreRejectedBeforeServiceChanges() throws Exception {
        var incoming = incoming("api", "1".repeat(64), "first compile");
        staging("api", incoming);
        var deployment = new Deployment(fixture.layout, fixture.host);
        assertThrows(
                IllegalArgumentException.class,
                () -> deployment.install(incoming, environment("worker")));
        assertTrue(fixture.host.calls.isEmpty());
        Files.writeString(
                fixture.layout.root().resolve("api/staging/jars/api-app.jar"), "tampered");
        assertThrows(
                IllegalArgumentException.class,
                () -> deployment.install(incoming, environment("api")));
        assertTrue(fixture.host.calls.isEmpty());
        assertFalse(Files.exists(fixture.layout.root().resolve("api/current")));
    }

    @Test
    void wrongInstanceCannotInstallOrModifyCredentials() throws Exception {
        var incoming = incoming("worker", "1".repeat(64), "worker compile");
        staging("worker", incoming);
        var before = FileOps.sha(fixture.layout.config().resolve("worker.env"));
        var wrongHost =
                new Fixtures.FakeHost() {
                    @Override
                    tools.jackson.databind.JsonNode identity() {
                        return Json.read(
                                Json.bytes(
                                        Map.of(
                                                "instanceId",
                                                "other-instance",
                                                "accountId",
                                                "test-account")));
                    }
                };
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new Deployment(fixture.layout, wrongHost)
                                .install(incoming, environment("worker")));
        assertTrue(wrongHost.calls.isEmpty());
        assertEquals(before, FileOps.sha(fixture.layout.config().resolve("worker.env")));
        assertFalse(Files.exists(fixture.layout.root().resolve("worker/current")));
    }

    @Test
    void unmanagedCurrentLinkCannotBeReplaced() throws Exception {
        Files.createSymbolicLink(
                fixture.layout.root().resolve("api/current"), base.resolve("source"));
        var incoming = incoming("api", "1".repeat(64), "first compile");
        staging("api", incoming);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new Deployment(fixture.layout, fixture.host)
                                .install(incoming, environment("api")));
        assertTrue(fixture.host.calls.isEmpty());
        assertEquals(
                base.resolve("source").toRealPath(),
                fixture.layout.root().resolve("api/current").toRealPath());
    }

    @Test
    void defaultBootstrapIsReadOnlyAndReviewBundleStopsBeforeMetadataLookup() throws Exception {
        var bundle = Files.createDirectory(base.resolve("bundle"));
        Files.writeString(bundle.resolve("public.txt"), "fixture\n");
        RevisionStore.seal(bundle, "basis", Fixtures.COMMIT, basisDigest, basisDigest, false);
        var result =
                new Bootstrap(fixture.layout, fixture.host).prepare(bundle, Fixtures.ORIGIN, false);
        assertEquals("check", result.get("mode"));
        assertTrue(
                fixture.host.calls.stream()
                        .noneMatch(
                                call ->
                                        call.getFirst().equals("useradd")
                                                || (call.getFirst().equals("systemctl")
                                                        && call.contains("enable"))
                                                || (call.getFirst().equals("docker")
                                                        && call.contains("load"))));
        var review = Files.createDirectory(base.resolve("review"));
        Files.writeString(review.resolve("public.txt"), "fixture\n");
        RevisionStore.seal(review, "basis", Fixtures.COMMIT, basisDigest, basisDigest, true);
        var noIdentity =
                new Fixtures.FakeHost() {
                    @Override
                    tools.jackson.databind.JsonNode identity() {
                        throw new AssertionError("Must stop before metadata lookup");
                    }
                };
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new Bootstrap(fixture.layout, noIdentity)
                                .prepare(review, Fixtures.ORIGIN, false));
    }

    @Test
    void productionInstanceTagsAreRejected() {
        var response =
                Json.read(
                        Json.bytes(
                                Map.of(
                                        "Reservations",
                                        java.util.List.of(
                                                Map.of(
                                                        "Instances",
                                                        java.util.List.of(
                                                                Map.of(
                                                                        "InstanceId",
                                                                        "test-dev-instance",
                                                                        "Tags",
                                                                        java.util.List.of(
                                                                                Map.of(
                                                                                        "Key",
                                                                                        "Name",
                                                                                        "Value",
                                                                                        "salmonbus-backend"),
                                                                                Map.of(
                                                                                        "Key",
                                                                                        "Environment",
                                                                                        "Value",
                                                                                        "prod")))))))));
        assertEquals(
                "DEV_INSTANCE_TAGS",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> Host.verifyTags(fixture.host.identity(), response))
                        .getMessage());
    }
}
