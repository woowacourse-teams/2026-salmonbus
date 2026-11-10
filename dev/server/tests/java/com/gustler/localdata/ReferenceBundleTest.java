package com.gustler.localdata;

import static org.assertj.core.api.Assertions.*;

import com.gustler.backend.forecasting.infrastructure.bundle.FileModelBundleLoader;
import com.gustler.backend.forecasting.domain.model.RouteDirection;
import com.gustler.backend.forecasting.domain.model.RouteStop;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReferenceBundleTest {
    @TempDir Path root;

    @Test
    void 합성_번들을_별도_경로에_보존하고_지원하는_여덟_노선의_정류장_기준을_확인한다() throws Exception {
        // given
        Path directory = root.resolve("alighting-dev-v1");
        Path data = Path.of(System.getProperty("dev.data.directory"));

        // when
        ReferenceBundle.prepare(Path.of(System.getProperty("dev.data.directory")).resolve("model-reference"), directory);
        ReferenceBundle.prepare(Path.of(System.getProperty("dev.data.directory")).resolve("model-reference"), directory);
        var release = new FileModelBundleLoader().filesUnder(directory.toString()).load();
        var routes = Stream.concat(RouteDataset.read(data.resolve("routes.json")).routes().stream(),
            RouteDataset.readCatalog(data.resolve("catalog-routes.json")).routes().stream())
            .filter(route -> release.scope().coversSourceRoute(route.routeId())).toList();

        // then
        assertThat(release.bundleDigest()).isEqualTo(ReferenceBundle.DIGEST);
        assertThat(routes).hasSize(8);
        long version = 1;
        for (var route : routes) {
            long id = version++;
            var stops = route.stops().stream().map(stop -> new RouteStop(id, stop.sequence(), stop.stationId(),
                stop.boardingAllowed(), RouteDirection.valueOf(stop.direction()))).toList();
            assertThat(release.model().supportsRoute(new RouteStops(id, route.routeId(), stops, route.displayName())))
                .as(route.displayName()).isTrue();
        }
    }

    @Test
    void 기존_후보_파일이_변경되면_덮어쓰지_않는다() throws Exception {
        // given
        Path directory = root.resolve("alighting-dev-v1");
        ReferenceBundle.prepare(Path.of(System.getProperty("dev.data.directory")).resolve("model-reference"), directory);
        Files.writeString(directory.resolve("manifest.json"), "changed");

        // when
        var error = catchThrowable(() -> ReferenceBundle.prepare(Path.of(System.getProperty("dev.data.directory")).resolve("model-reference"), directory));

        // then
        assertThat(error).hasMessage("MODEL_FIXTURE_CHANGED");
        assertThat(Files.readString(directory.resolve("manifest.json"))).isEqualTo("changed");
    }
}
