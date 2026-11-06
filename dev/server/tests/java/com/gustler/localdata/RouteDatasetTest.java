package com.gustler.localdata;

import static org.assertj.core.api.Assertions.*;

import com.gustler.backend.routecatalog.domain.RouteContentDigest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RouteDatasetTest {
    @TempDir Path root;

    @Test
    void 기존_파일의_검증_지문을_유지하고_저장할_노선_지문은_현재_규칙으로_계산한다() throws Exception {
        // given
        Path directory = Path.of(System.getProperty("dev.data.directory"));
        var routes = Stream.concat(RouteDataset.read(directory.resolve("routes.json")).routes().stream(),
            RouteDataset.readCatalog(directory.resolve("catalog-routes.json")).routes().stream()).toList();

        // when / then
        assertThat(routes).hasSize(41);
        for (var route : routes) {
            assertThat(route.versionDigest()).isEqualTo(RouteContentDigest.of(route.domainStops()).value())
                .isNotEqualTo(route.contentDigest());
        }
    }

    @Test
    void 파일의_이름이_변조되면_현재_노선_지문이_같아도_거절한다() throws Exception {
        // given
        Path source = Path.of(System.getProperty("dev.data.directory"), "routes.json");
        String content = Files.readString(source);
        var route = RouteDataset.read(source).routes().getFirst();
        Path changed = root.resolve("routes.json");
        Files.writeString(changed, content.replace("\"name\": \"" + route.stops().getFirst().name() + "\"",
            "\"name\": \"changed\""));
        assertThat(Files.readString(changed)).isNotEqualTo(content);

        // when / then
        assertThatThrownBy(() -> RouteDataset.read(changed)).hasMessage("ROUTE_DIGEST");
    }

    @Test
    void 파일의_검증_지문을_현재_노선_지문으로_바꿔도_거절한다() throws Exception {
        // given
        Path source = Path.of(System.getProperty("dev.data.directory"), "routes.json");
        var route = RouteDataset.read(source).routes().getFirst();
        Path changed = root.resolve("routes.json");
        Files.writeString(changed, Files.readString(source).replace(route.contentDigest(), route.versionDigest()));

        // when / then
        assertThatThrownBy(() -> RouteDataset.read(changed)).hasMessage("ROUTE_DIGEST");
    }
}
