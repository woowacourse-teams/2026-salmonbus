package com.gustler.backend.worker.station;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.gustler.backend.stations.api.ImportStationSeed;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;

class StationSeedRuntimeConfigurationTest {
    private final ImportStationSeed importer = mock(ImportStationSeed.class);
    private final ApplicationRunner runner = new StationSeedRuntimeConfiguration().stationSeedLoader(
        importer,
        Path.of("stations.csv"),
        "UTF-8"
    );

    @Test
    void 명부_설치에_실패해도_worker_기동을_멈추지_않는다() {
        // given
        given(importer.importFile(any(), any())).willThrow(new IllegalStateException("broken"));

        // when & then
        assertThatCode(() -> runner.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
    }

    @Test
    void 다른_인스턴스가_설치_중이면_건너뛴다() {
        // given
        given(importer.importFile(any(), any())).willReturn(Optional.empty());

        // when & then
        assertThatCode(() -> runner.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
    }
}
