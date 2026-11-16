package com.gustler.devtools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchivePackagingTest {
    private static final String COMMIT = "a".repeat(40);
    private static final String ARCHIVE_JAR = "backend/archive-app/build/libs/archive-app-0.0.1.jar";
    private static final String ARCHIVE_UNIT = "dev/server/systemd/salmonbus-archive.service";

    @TempDir
    Path root;

    private Path repo;
    private Path image;
    private PackagingHost host;

    @BeforeEach
    void prepare() throws Exception {
        repo = Files.createDirectory(root.resolve("repo"));
        for (String name : sourceFiles()) {
            write(name, "fixture:" + name);
        }
        for (String name : DataPreparation.FILES) {
            write("dev/data/" + name, "fixture data:" + name);
        }
        jar("dev/server/tools/build/libs/shared-dev-tools.jar", "Main-Class", "com.gustler.devtools.DevTools");
        jar("backend/worker-app/build/libs/shared-dev-data.jar", "Start-Class", "com.gustler.localdata.LocalData");
        jar("backend/api-app/build/libs/api-app-0.0.1.jar", "Start-Class", "com.gustler.backend.ApiApplication");
        jar("backend/worker-app/build/libs/worker-app-0.0.1.jar", "Start-Class",
            "com.gustler.backend.worker.WorkerApplication");
        jar(ARCHIVE_JAR, "Start-Class", "com.gustler.backend.archive.ArchiveApplication");
        image = image();
        host = new PackagingHost();
    }

    private static List<String> sourceFiles() {
        return List.of(
            "dev/server/tools/src/main/Tool.java",
            "dev/server/tools/build.gradle",
            "dev/server/tools/settings.gradle",
            "dev/server/api.yml",
            "dev/server/worker.yml",
            "dev/server/replay.json",
            "dev/server/compose.yaml",
            "dev/server/wiremock.Dockerfile",
            "dev/server/data.init",
            "dev/server/systemd/salmonbus-dev-infra.service",
            "dev/server/systemd/salmonbus-api.service",
            "dev/server/systemd/salmonbus-worker.service",
            ARCHIVE_UNIT,
            "dev/server/deploy/appspec-api.yml",
            "dev/server/deploy/appspec-worker.yml",
            "dev/server/deploy/common.sh",
            "dev/server/deploy/guard.sh",
            "dev/server/deploy/preflight.sh",
            "dev/server/deploy/preflight-body.sh",
            "dev/server/deploy/install.sh",
            "dev/server/deploy/install-body.sh",
            "dev/server/deploy/start.sh",
            "dev/server/deploy/start-body.sh",
            "dev/server/deploy/validate.sh",
            "backend/deploy/scripts/common.sh",
            "backend/deploy/scripts/validate.sh",
            "dev/data/java/Data.java",
            "dev/wiremock/mappings/location.json",
            "dev/wiremock-extension/src/main/Replay.java",
            "backend/business/forecasting/src/main/java/com/gustler/backend/forecasting/domain/model/alighting/"
                + "AlightingAwareFeatureVector.java");
    }

    private void write(String name, String content) throws Exception {
        Path file = repo.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private void jar(String name, String key, String mainClass) throws Exception {
        Path file = repo.resolve(name);
        Files.createDirectories(file.getParent());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue(key, mainClass);
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(file), manifest)) {
            jar.finish();
        }
    }

    private Path image() throws Exception {
        Path path = root.resolve("image.tar");
        Map<String, byte[]> members = Map.of(
            "config.json", Json.bytes(Map.of("architecture", "arm64", "os", "linux")),
            "manifest.json", Json.bytes(List.of(Map.of(
                "Config", "config.json",
                "RepoTags", List.of("salmonbus-gbis-replay:" + COMMIT),
                "Layers", List.of()))));
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> member : members.entrySet()) {
                TarArchiveEntry entry = new TarArchiveEntry(member.getKey());
                entry.setSize(member.getValue().length);
                tar.putArchiveEntry(entry);
                tar.write(member.getValue());
                tar.closeArchiveEntry();
            }
        }
        return path;
    }

    private Path assemble(String name) throws Exception {
        Path output = root.resolve(name);
        new Packager(repo, host).assemble(output, image, COMMIT, false);
        return output;
    }

    private static RevisionStore.Revision revision(Path output, String kind) throws Exception {
        return RevisionStore.verify(output.resolve(kind), true);
    }

    @Test
    void worker_묶음에_이관_앱의_JAR과_유닛을_함께_싣는다() throws Exception {
        // when
        Path output = assemble("revision");

        // then
        Path worker = output.resolve("worker");
        RevisionStore.Revision sealed = revision(output, "worker");
        assertSoftly(softly -> {
            softly.assertThat(sealed.files()).containsKeys("jars/archive-app.jar", "systemd/salmonbus-archive.service");
            softly.assertThat(worker.resolve("jars/archive-app.jar")).hasSameBinaryContentAs(repo.resolve(ARCHIVE_JAR));
            softly.assertThat(worker.resolve("systemd/salmonbus-archive.service"))
                .hasSameBinaryContentAs(repo.resolve(ARCHIVE_UNIT));
        });
    }

    @Test
    void api_묶음에는_이관_앱을_싣지_않는다() throws Exception {
        // when
        Path output = assemble("revision");

        // then
        assertThat(revision(output, "api").files())
            .doesNotContainKeys("jars/archive-app.jar", "systemd/salmonbus-archive.service");
    }

    @Test
    void 이관_앱의_JAR이_없으면_묶음을_만들지_않는다() throws Exception {
        // given
        Files.delete(repo.resolve(ARCHIVE_JAR));

        // when
        Throwable failure = catchThrowable(() -> assemble("revision"));

        // then
        assertSoftly(softly -> {
            softly.assertThat(failure).isInstanceOf(IllegalArgumentException.class).hasMessage("PACKAGE_APP_JAR");
            softly.assertThat(root.resolve("revision")).doesNotExist();
        });
    }

    @Test
    void 이관_앱의_유닛이_없으면_묶음을_만들지_않는다() throws Exception {
        // given
        Files.delete(repo.resolve(ARCHIVE_UNIT));

        // when
        Throwable failure = catchThrowable(() -> assemble("revision"));

        // then
        assertSoftly(softly -> {
            softly.assertThat(failure).isInstanceOf(IllegalArgumentException.class).hasMessage("PACKAGE_SOURCE_FILE");
            softly.assertThat(root.resolve("revision")).doesNotExist();
        });
    }

    @Test
    void 이관_앱의_소스가_바뀌면_worker_묶음의_지문만_바뀐다() throws Exception {
        // given
        Path before = assemble("before");
        host.runtimeDigests.put("archive", "9".repeat(64));

        // when
        Path after = assemble("after");

        // then
        assertOnlyWorkerDigestChanged(before, after);
    }

    @Test
    void 이관_앱의_유닛이_바뀌면_worker_묶음의_지문만_바뀐다() throws Exception {
        // given
        Path before = assemble("before");
        write(ARCHIVE_UNIT, "changed archive unit");

        // when
        Path after = assemble("after");

        // then
        assertOnlyWorkerDigestChanged(before, after);
    }

    private static void assertOnlyWorkerDigestChanged(Path before, Path after) throws Exception {
        String workerBefore = revision(before, "worker").sourceDigest();
        String workerAfter = revision(after, "worker").sourceDigest();
        String apiBefore = revision(before, "api").sourceDigest();
        String apiAfter = revision(after, "api").sourceDigest();
        assertSoftly(softly -> {
            softly.assertThat(workerAfter).isNotEqualTo(workerBefore);
            softly.assertThat(apiAfter).isEqualTo(apiBefore);
        });
    }

    @Test
    void 이관_앱의_JAR_자리에_다른_앱의_JAR을_두면_거절한다() throws Exception {
        // given
        jar(ARCHIVE_JAR, "Start-Class", "com.gustler.backend.worker.WorkerApplication");

        // when
        Throwable failure = catchThrowable(() -> assemble("revision"));

        // then
        assertThat(failure).isInstanceOf(IllegalArgumentException.class).hasMessage("JAR_MAIN_CLASS");
    }

    @Test
    void 이관_앱의_유닛은_worker_묶음에_실린_JAR을_서버_폴더_저장소로_띄운다() throws Exception {
        // given
        Path unit = Path.of("../systemd/salmonbus-archive.service").toAbsolutePath().normalize();

        // when
        List<String> lines = Files.readAllLines(unit);

        // then
        assertSoftly(softly -> {
            softly.assertThat(lines).contains(
                "ExecStart=/usr/bin/java -Xms32m -Xmx256m -jar "
                    + Layout.SERVER.root().resolve("worker/current").resolve(RevisionStore.ARCHIVE_JAR),
                "ExecStartPre=+/usr/bin/java -Xms16m -Xmx64m -jar "
                    + Layout.SERVER.tools().resolve("shared-dev-tools.jar") + " preflight archive",
                "EnvironmentFile=" + Layout.SERVER.config().resolve("worker.env"),
                "Environment=ARCHIVE_DIRECTORY=" + Layout.SERVER.data().resolve("archive"),
                "Environment=ARCHIVE_PARTITION_EXPORT_ENABLED=true",
                "Environment=ARCHIVE_PARTITION_EXPORT_DROP_ENABLED=true",
                "Environment=ARCHIVE_PARTITION_EXPORT_PREFIX=salmonbus/dev/lake-v3/");
            softly.assertThat(lines).noneMatch(line -> line.contains("ARCHIVE_LEGACY_EXPORT"));
        });
    }

    /** 실행 JAR의 소스 지문을 계산하는 셸 스크립트 대신 앱마다 정해 둔 지문을 낸다. */
    private static final class PackagingHost extends Host {
        private final Map<String, String> runtimeDigests = new HashMap<>(Map.of(
            "api", "1".repeat(64),
            "worker", "2".repeat(64),
            "archive", "3".repeat(64)));

        @Override
        Result run(Path cwd, Map<String, String> environment, Duration timeout, List<String> command) {
            if (command.size() == 3 && command.get(1).equals("deploy/runtime-source-inputs.sh")) {
                return new Result(0, runtimeDigests.get(command.get(2)) + "\n", "");
            }
            return new Result(0, "", "");
        }
    }
}
