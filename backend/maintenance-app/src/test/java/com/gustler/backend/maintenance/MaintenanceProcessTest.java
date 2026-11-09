package com.gustler.backend.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MaintenanceProcessTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"quality-preview", "quality-rebuild", "quality-status", "archive-import"})
    void 폐기한_명령은_실제_JAR에서도_종료코드_1로_거절한다(String command) throws Exception {
        // given
        Path stdout = directory.resolve("stdout.txt");
        Path stderr = directory.resolve("stderr.txt");

        // when
        Process process = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-jar", System.getProperty("maintenance.boot.jar"), command)
            .redirectOutput(stdout.toFile())
            .redirectError(stderr.toFile())
            .start();

        // then
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isEqualTo(1);
            assertThat(Files.readString(stdout)).isEmpty();
            assertThat(Files.readString(stderr)).contains("UNKNOWN_COMMAND");
        } finally {
            process.destroyForcibly();
        }
    }
}
