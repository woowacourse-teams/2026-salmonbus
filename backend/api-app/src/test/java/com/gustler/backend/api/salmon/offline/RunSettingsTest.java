package com.gustler.backend.api.salmon.offline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.api.salmon.application.ArrivalStopSource.CoverageRule;
import com.gustler.backend.api.salmon.domain.ActionConfig;
import com.gustler.backend.api.salmon.offline.RunSettings.SettingSet;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunSettingsTest {
    private static final String SET = """
        {"name": "base-15", "horizonMinutes": 15, "coverageRule": "SECOND_BEYOND_WINDOW", "scanThreshold": 0,
         "gainThreshold": 1.0E-9, "alpha": "1.2", "maxWalkMinutes": 15, "maxDetour": 0, "resolutionSeconds": 1,
         "epochBudget": 1, "kindOrder": ["WALK", "REBOARD"]%s}
        """;

    @TempDir
    Path folder;

    @Test
    void 설정_묶음의_값을_판정_설정으로_옮긴다() throws Exception {
        SettingSet set = RunSettings.read(fileOf("{\"sets\": [" + SET.formatted("") + "]}")).sets().get(0);

        ActionConfig config = set.actionConfig();

        assertThat(set.coverageRule()).isEqualTo(CoverageRule.SECOND_BEYOND_WINDOW);
        assertThat(config.horizon().millis()).isEqualTo(900_000);
        assertThat(config.maxWalk().millis()).isEqualTo(900_000);
        assertThat(config.resolution().millis()).isEqualTo(1_000);
        assertThat(config.alpha()).isEqualByComparingTo("1.2");
        assertThat(set.values()).containsEntry("alpha", "1.2").containsEntry("coverageRule", "SECOND_BEYOND_WINDOW");
    }

    @Test
    void 값이_빠졌거나_모르는_이름이_있는_설정은_읽지_않는다() throws Exception {
        Path missing = fileOf("{\"sets\": [" + SET.formatted("").replace("\"horizonMinutes\": 15, ", "") + "]}");
        Path unknown = fileOf("{\"sets\": [" + SET.formatted(", \"horizon\": 20") + "]}");
        Path empty = fileOf("{\"sets\": []}");

        assertThatThrownBy(() -> RunSettings.read(missing)).hasMessageContaining("빠진 값");
        assertThatThrownBy(() -> RunSettings.read(unknown)).hasMessageContaining("horizon");
        assertThatThrownBy(() -> RunSettings.read(empty)).hasMessageContaining("sets");
    }

    private Path fileOf(String content) throws Exception {
        return Files.writeString(Files.createTempFile(folder, "settings", ".json"), content);
    }
}
