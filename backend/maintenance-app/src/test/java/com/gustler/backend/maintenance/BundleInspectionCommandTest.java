package com.gustler.backend.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.forecasting.api.model.InspectModelBundle;
import com.gustler.backend.forecasting.application.deployment.BundleActivation;
import com.gustler.backend.forecasting.application.deployment.LoadedModelRegistry;
import com.gustler.backend.forecasting.domain.deployment.ModelDeploymentRepository;
import com.gustler.backend.maintenance.configuration.BundleInspectionCommandConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class BundleInspectionCommandTest {
    @TempDir
    Path directory;

    @Test
    void 검수_구성에_DB와_활성화_빈이_없다() {
        // when
        try (AnnotationConfigApplicationContext context =
            new AnnotationConfigApplicationContext(BundleInspectionCommandConfiguration.class)) {

            // then
            assertThat(context.getBeansOfType(InspectModelBundle.class)).hasSize(1);
            assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
            assertThat(context.getBeansOfType(ModelDeploymentRepository.class)).isEmpty();
            assertThat(context.getBeansOfType(BundleActivation.class)).isEmpty();
            assertThat(context.getBeansOfType(LoadedModelRegistry.class)).isEmpty();
        }
    }

    @Test
    void 검수는_번들_파일을_변경하지_않는다() throws Exception {
        // when
        BundleInspectionFixture.write(directory);
        byte[] manifest = Files.readAllBytes(directory.resolve("manifest.json"));
        byte[] weights = Files.readAllBytes(directory.resolve("weights.safetensors"));
        Object actual = MaintenanceApplication.run(CliArguments.parse(new String[] {
            "bundle-check", "--directory", directory.toString()}));

        // then
        assertThat(actual).isInstanceOf(Map.class);
        Map<?, ?> result = (Map<?, ?>) actual;
        assertThat(result.get("validation")).isEqualTo("PASSED");
        assertThat(result.get("activated")).isEqualTo(false);
        assertThat(result.get("accuracyEvaluated")).isEqualTo(false);
        assertThat(Files.readAllBytes(directory.resolve("manifest.json"))).isEqualTo(manifest);
        assertThat(Files.readAllBytes(directory.resolve("weights.safetensors"))).isEqualTo(weights);
        try (Stream<Path> files = Files.list(directory)) {
            assertThat(files.map(p -> p.getFileName().toString()).toList())
                .containsExactlyInAnyOrder("manifest.json", "weights.safetensors");
        }
    }
}
