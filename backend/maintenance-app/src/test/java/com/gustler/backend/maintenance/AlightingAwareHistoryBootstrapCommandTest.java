package com.gustler.backend.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.forecasting.api.alighting.BootstrapAlightingAwareHistory;
import com.gustler.backend.forecasting.api.model.LoadConfiguredModel;
import com.gustler.backend.forecasting.application.deployment.BundleActivation;
import com.gustler.backend.forecasting.application.publication.ForecastBatchWriter;
import com.gustler.backend.maintenance.configuration.AlightingAwareHistoryBootstrapCommandConfiguration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.config.ScheduledTaskHolder;

class AlightingAwareHistoryBootstrapCommandTest {
    @TempDir
    Path directory;

    @Test
    void 옵션을_공개_API로_전달하고_모델을_활성화하지_않는다() {
        AlightingAwareHistoryBootstrapCommand.Options options = AlightingAwareHistoryBootstrapCommand.parse(arguments("batch-limit", "25"));
        AtomicReference<BootstrapAlightingAwareHistory.Request> received = new AtomicReference<BootstrapAlightingAwareHistory.Request>();
        Object actual = AlightingAwareHistoryBootstrapCommand.invoke(options, request -> {
            received.set(request);
            return new BootstrapAlightingAwareHistory.Result(request.routeVersionId(), request.sourceOriginBatchId(),
                request.throughBatchId(), 7, request.checkpointDirectory(), "b".repeat(64));
        });
        assertThat(received.get().routeVersionId()).isEqualTo(12);
        assertThat(received.get().sourceOriginBatchId()).isEqualTo(34);
        assertThat(received.get().throughBatchId()).isEqualTo(56);
        assertThat(received.get().batchLimit()).isEqualTo(25);
        assertThat(received.get().maximumHistoryRows()).isEqualTo(250000);
        assertThat(((Map<?, ?>) actual).get("activated")).isEqualTo(false);
        assertThat(CanonicalJson.stringOf(actual)).contains("\"databaseReadOnly\":true",
            "\"forecastsPublished\":false");
    }

    @Test
    void 잘못된_범위는_설정과_DB를_열기_전에_거부한다() {
        for (String value : new String[] { "0", "1001", "secret-invalid" }) {
            assertThatThrownBy(() -> MaintenanceApplication.run(arguments("batch-limit", value)))
                .isInstanceOfSatisfying(MaintenanceException.class,
                    failure -> assertThat(failure.code()).isEqualTo("INVALID_ARGUMENT_BATCH_LIMIT"));
        }
        assertThatThrownBy(() -> MaintenanceApplication.run(arguments("activate", "true")))
            .hasMessage("UNKNOWN_COMMAND_ARGUMENT");
        assertThatThrownBy(() -> MaintenanceApplication.run(arguments("route-version", "0")))
            .hasMessage("INVALID_ARGUMENT_ROUTE_VERSION");
        assertThatThrownBy(() -> MaintenanceApplication.run(arguments("maximum-history-rows", "2147483647")))
            .hasMessage("INVALID_ARGUMENT_MAXIMUM_HISTORY_ROWS");
        assertThatThrownBy(
            () -> MaintenanceApplication.run(CliArguments.parse(new String[] { "alighting-history-bootstrap" })))
            .hasMessage("MISSING_ARGUMENT_CONFIG");
    }

    @Test
    void 준비_구성은_스케줄러와_모델_발행_빈을_만들지_않는다() {
        DatabaseEnvironment environment = new DatabaseEnvironment(DatabaseEnvironment.TargetKind.LOCAL,
            "jdbc:postgresql://127.0.0.1:1/unreachable", "unused", "unused", "test");
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DatabaseEnvironment.class, () -> environment);
            context.register(AlightingAwareHistoryBootstrapCommandConfiguration.class);
            context.refresh();
            assertThat(context.getBeansOfType(BootstrapAlightingAwareHistory.class)).hasSize(1);
            assertThat(context.getBeansOfType(LoadConfiguredModel.class)).isEmpty();
            assertThat(context.getBeansOfType(BundleActivation.class)).isEmpty();
            assertThat(context.getBeansOfType(ForecastBatchWriter.class)).isEmpty();
            assertThat(context.getBeansOfType(ScheduledTaskHolder.class)).isEmpty();
        }
    }

    private CliArguments arguments(String override, String value) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("config", directory.resolve("missing.properties").toString());
        values.put("directory", directory.resolve("offline-bundle").toString());
        values.put("route-version", "12");
        values.put("source-origin-batch", "34");
        values.put("through-batch", "56");
        values.put("checkpoint-directory", directory.resolve("checkpoints").toString());
        values.put("batch-limit", "100");
        values.put("maximum-history-rows", "250000");
        values.put(override, value);
        List<String> args = new ArrayList<>();
        args.add("alighting-history-bootstrap");
        values.forEach((name, item) -> {
            args.add("--" + name);
            args.add(item);
        });
        return CliArguments.parse(args.toArray(String[]::new));
    }
}
