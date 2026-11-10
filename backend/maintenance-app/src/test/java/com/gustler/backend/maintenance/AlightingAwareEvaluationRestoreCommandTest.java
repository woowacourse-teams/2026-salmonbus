package com.gustler.backend.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.forecasting.api.evaluation.EvaluationArchiveObjects;
import com.gustler.backend.forecasting.api.evaluation.AlightingAwareEvaluationArchive;
import com.gustler.backend.forecasting.api.model.LoadConfiguredModel;
import com.gustler.backend.forecasting.application.publication.ForecastBatchWriter;
import com.gustler.backend.maintenance.configuration.AlightingAwareEvaluationRestoreCommandConfiguration;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.config.ScheduledTaskHolder;

class AlightingAwareEvaluationRestoreCommandTest {
    @TempDir
    Path directory;
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void 정확한_결과_키로_복원만_호출한다() {
        AlightingAwareEvaluationRestoreCommand.Options options = AlightingAwareEvaluationRestoreCommand
            .parse(arguments("quality-revision", "0"));
        AtomicReference<String> key = new AtomicReference<String>();
        Object result = AlightingAwareEvaluationRestoreCommand.invoke(options, new AlightingAwareEvaluationArchive() {
            @Override
            public void advance() {
                throw new AssertionError("must not archive or purge");
            }

            @Override
            public int restore(
                final long id,
                final int stop,
                String target,
                String version,
                final long revision
            ) {
                key.set(id + ":" + stop + ":" + target + ":" + version + ":" + revision);
                return 1;
            }
        });
        assertThat(key.get()).isEqualTo("34:7:DEPARTURE_ZERO:alighting-identity-safe-v1:0");
        assertThat(CanonicalJson.stringOf(result)).contains("\"restoredRows\":1", "\"activated\":false",
            "\"forecastsPublished\":false");
    }

    @Test
    void 잘못된_키는_파일과_DB를_열기_전에_거부한다() {
        assertThatThrownBy(() -> MaintenanceApplication.run(arguments("target-kind", "FIRST_POST_STOP_ZERO")))
            .hasMessage("INVALID_ARGUMENT_TARGET_KIND");
        assertThatThrownBy(() -> MaintenanceApplication.run(arguments("observation-id", "0")))
            .hasMessage("INVALID_ARGUMENT_OBSERVATION_ID");
        assertThatThrownBy(() -> MaintenanceApplication.run(arguments("quality-revision", "-1")))
            .hasMessage("INVALID_ARGUMENT_QUALITY_REVISION");
        assertThatThrownBy(() -> MaintenanceApplication.run(arguments("activate", "true")))
            .hasMessage("UNKNOWN_COMMAND_ARGUMENT");
    }

    @Test
    void 운영_승인은_복원_명령과_DB와_원본이_일치해야_한다() throws Exception {
        LocalAlightingAwareArchive archive = LocalAlightingAwareArchive.read(privateFile("archive.json", "{}"));
        DatabaseEnvironment environment = environment(DatabaseEnvironment.TargetKind.ACADEMY);
        assertThatThrownBy(() -> AlightingAwareEvaluationRestoreCommand.authorize(arguments("quality-revision", "0"),
            environment, archive, clock)).hasMessage("MISSING_ARGUMENT_APPROVAL");
        Path approval = approval(ApprovalGate.ACADEMY_ALIGHTING_AWARE_EVALUATION_RESTORE, archive.sha256(), "test-db");
        CliArguments args = arguments("approval", approval.toString());
        AlightingAwareEvaluationRestoreCommand.authorize(args, environment, archive, clock);
        approval("OTHER_ACTION", archive.sha256(), "test-db");
        assertThatThrownBy(() -> AlightingAwareEvaluationRestoreCommand.authorize(args, environment, archive, clock))
            .hasMessage("APPROVAL_ACTION_MISMATCH");
        approval(ApprovalGate.ACADEMY_ALIGHTING_AWARE_EVALUATION_RESTORE, "0".repeat(64), "test-db");
        assertThatThrownBy(() -> AlightingAwareEvaluationRestoreCommand.authorize(args, environment, archive, clock))
            .hasMessage("APPROVAL_ARTIFACT_MISMATCH");
        approval(ApprovalGate.ACADEMY_ALIGHTING_AWARE_EVALUATION_RESTORE, archive.sha256(), "other-db");
        assertThatThrownBy(() -> AlightingAwareEvaluationRestoreCommand.authorize(args, environment, archive, clock))
            .hasMessage("APPROVAL_DATABASE_MISMATCH");
        AlightingAwareEvaluationRestoreCommand.authorize(arguments("quality-revision", "0"),
            environment(DatabaseEnvironment.TargetKind.LOCAL), archive, clock);
    }

    @Test
    void 승인한_바이트를_보존하고_DB에_기록된_원본만_전달한다() throws Exception {
        Path file = privateFile("archive.json", "{\"original\":true}");
        LocalAlightingAwareArchive archive = LocalAlightingAwareArchive.read(file);
        Files.writeString(file, "replaced-after-approval");
        Path destination = directory.resolve("download.json");
        archive.download("techcourse-project-2026", "salmonbus-be/alighting-evaluation-archive/v1/"
            + archive.sha256() + ".json", destination, 1024);
        assertThat(Files.readString(destination)).isEqualTo("{\"original\":true}");
        archive.download("techcourse-project-2026", "salmonbus-be/les-evaluation-archive/v1/"
            + archive.sha256() + ".json", destination, 1024);
        assertThat(Files.readString(destination)).isEqualTo("{\"original\":true}");
        assertThatThrownBy(() -> archive.download("techcourse-project-2026", "wrong.json", destination, 1024))
            .hasMessage("ALIGHTING_AWARE_ARCHIVE_DATABASE_HASH_MISMATCH");
        assertThatThrownBy(() -> archive.putIfAbsent("bucket", "key", file, archive.sha256()))
            .hasMessage("ALIGHTING_AWARE_RESTORE_UPLOAD_FORBIDDEN");
        Path link = directory.resolve("link.json");
        Files.createSymbolicLink(link, file);
        assertThatThrownBy(() -> LocalAlightingAwareArchive.read(link)).hasMessage("PRIVATE_FILE_INVALID");
        Path large = privateFile("large.json", "{}");
        try (RandomAccessFile random = new RandomAccessFile(large.toFile(), "rw")) {
            random.setLength(16 * 1024 * 1024 + 1);
        }
        assertThatThrownBy(() -> LocalAlightingAwareArchive.read(large)).hasMessage("ALIGHTING_AWARE_ARCHIVE_SIZE_INVALID");
    }

    @Test
    void 복원_구성은_스케줄러와_발행을_시작하지_않는다() throws Exception {
        LocalAlightingAwareArchive archive = LocalAlightingAwareArchive.read(privateFile("archive.json", "{}"));
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DatabaseEnvironment.class, () -> environment(DatabaseEnvironment.TargetKind.LOCAL));
            context.registerBean(EvaluationArchiveObjects.class, () -> archive);
            context.register(AlightingAwareEvaluationRestoreCommandConfiguration.class);
            context.refresh();
            assertThat(context.getBeansOfType(AlightingAwareEvaluationArchive.class)).hasSize(1);
            assertThat(context.getBeansOfType(LoadConfiguredModel.class)).isEmpty();
            assertThat(context.getBeansOfType(ForecastBatchWriter.class)).isEmpty();
            assertThat(context.getBeansOfType(ScheduledTaskHolder.class)).isEmpty();
        }
    }

    private DatabaseEnvironment environment(DatabaseEnvironment.TargetKind kind) {
        return new DatabaseEnvironment(kind, "jdbc:postgresql://127.0.0.1:1/unreachable", "unused", "unused",
            "test-db");
    }

    private Path approval(String action, String artifact, String database) throws Exception {
        return privateFile("approval.json",
            CanonicalJson.stringOf(Map.of("schemaVersion", "salmonbus-migration-approval-v1",
                "action", action, "artifactSha256", artifact, "databaseIdentitySha256", database,
                "expiresAt", "2026-11-04T01:00:00Z")));
    }

    private Path privateFile(String name, String content) throws Exception {
        Path file = directory.resolve(name);
        Files.writeString(file, content);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        return file;
    }

    private CliArguments arguments(String override, String value) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("config", directory.resolve("missing.properties").toString());
        values.put("archive-file", directory.resolve("missing.json").toString());
        values.put("observation-id", "34");
        values.put("target-stop-order", "7");
        values.put("target-kind", "DEPARTURE_ZERO");
        values.put("label-version", "alighting-identity-safe-v1");
        values.put("quality-revision", "0");
        values.put(override, value);
        List<String> args = new ArrayList<>();
        args.add("alighting-evaluation-restore");
        values.forEach((name, item) -> {
            args.add("--" + name);
            args.add(item);
        });
        return CliArguments.parse(args.toArray(String[]::new));
    }
}
