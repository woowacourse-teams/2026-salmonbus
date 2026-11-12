package com.gustler.devtools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReplaceableEnvironmentTest {
    @TempDir Path base;
    private Layout layout;

    @BeforeEach
    void prepare() throws Exception {
        layout = new Layout(Files.createDirectory(base.resolve("root")), base.resolve("etc/salmonbus-dev"),
            Files.createDirectory(base.resolve("data")), Files.createDirectory(base.resolve("locks")),
            Files.createDirectory(base.resolve("units")));
        Files.createDirectories(layout.config());
        Files.writeString(layout.units().resolve("salmonbus-api.service"), "[Service]\n"
            + "EnvironmentFile=" + layout.config().resolve("api.env") + "\n"
            + "EnvironmentFile=" + layout.config().resolve("arrivals.env") + "\n"
            + "EnvironmentFile=" + layout.root().resolve("api/current/release.env") + "\n");
        Files.writeString(layout.units().resolve("salmonbus-worker.service"), "[Service]\n"
            + "EnvironmentFile=" + layout.config().resolve("worker.env") + "\n");
        Files.writeString(layout.units().resolve("salmonbus-dev-infra.service"), "[Service]\n"
            + "EnvironmentFile=" + layout.config().resolve("infra.env") + "\n");
    }

    private void dropIn(String unit, String text) throws Exception {
        var file = layout.units().resolve(unit + ".d/90-manual.conf");
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    @Test
    void 기반_교체가_다시_만드는_환경_파일과_설정_디렉터리_밖의_파일은_허용한다() throws Exception {
        // given
        var outside = base.resolve("etc/salmonbus-dev-route-geometry/api.env");
        dropIn("salmonbus-api.service", "[Service]\nEnvironmentFile=" + outside + "\n"
            + "EnvironmentFile=-" + base.resolve("etc/salmonbus-dev-auth/auth.env") + "\nEnvironmentFile=\n");

        // when & then
        assertThatCode(() -> SettingsPreparation.requireReplaceableEnvironment(layout)).doesNotThrowAnyException();
    }

    @Test
    void 앱_unit이_설정_디렉터리의_다른_환경_파일을_읽으면_기반_교체를_거절한다() throws Exception {
        // given
        dropIn("salmonbus-api.service", "[Service]\nEnvironmentFile="
            + layout.config().resolve("route-geometry-api.env") + "\n");

        // when & then
        assertThatThrownBy(() -> SettingsPreparation.requireReplaceableEnvironment(layout))
            .hasMessage("DEV_UNMANAGED_ENVIRONMENT_FILE");
    }

    @Test
    void 없어도_되는_환경_파일과_하위_디렉터리도_기반_교체_뒤에는_사라지므로_거절한다() throws Exception {
        // given
        dropIn("salmonbus-worker.service", "[Service]\n  EnvironmentFile = -"
            + layout.config().resolve("backups/old/worker.env") + "\n");

        // when & then
        assertThatThrownBy(() -> SettingsPreparation.requireReplaceableEnvironment(layout))
            .hasMessage("DEV_UNMANAGED_ENVIRONMENT_FILE");
    }

    @Test
    void 저장소의_unit은_기반_교체_뒤에도_남는_환경_파일만_읽는다() throws Exception {
        // given
        var units = Path.of("../systemd").toAbsolutePath().normalize();
        try (var files = Files.list(units)) {
            assertThat(files.map(file -> file.getFileName().toString()).filter(name -> name.endsWith(".service")))
                .containsExactlyInAnyOrderElementsOf(SettingsPreparation.ENVIRONMENT_UNITS);
        }
        for (var unit : SettingsPreparation.ENVIRONMENT_UNITS) {
            assertThat(Files.readString(units.resolve(unit))).contains("EnvironmentFile=" + Layout.SERVER.config() + "/");
        }
        var server = new Layout(layout.root(), Layout.SERVER.config(), layout.data(), layout.locks(), units);

        // when & then
        assertThatCode(() -> SettingsPreparation.requireReplaceableEnvironment(server)).doesNotThrowAnyException();
    }

    @Test
    void 기반_unit의_drop_in도_검사한다() throws Exception {
        // given
        dropIn("salmonbus-dev-infra.service", "[Service]\nEnvironmentFile="
            + layout.config().resolve("../salmonbus-dev/extra.env") + "\n");

        // when & then
        assertThatThrownBy(() -> SettingsPreparation.requireReplaceableEnvironment(layout))
            .hasMessage("DEV_UNMANAGED_ENVIRONMENT_FILE");
    }
}
