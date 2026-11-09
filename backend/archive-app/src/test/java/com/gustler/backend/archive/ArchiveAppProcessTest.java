package com.gustler.backend.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

class ArchiveAppProcessTest {
    @Test
    void 실행_JAR에_수집과_예보_업무_모듈이_들어가지_않는다() throws Exception {
        try (ZipFile zip = new ZipFile(System.getProperty("archive.boot.jar"))) {
            List<String> names = zip.stream().map(e -> e.getName()).toList();
            assertSoftly(softly -> {
                softly.assertThat(names).anyMatch(n -> n.endsWith("ArchiveApplication.class"));
                softly.assertThat(names).noneMatch(
                    n -> n.contains("/worker-app-") || n.contains("/forecasting-") || n.contains("/observations-"));
                softly.assertThat(names).noneMatch(n -> n.endsWith("LegacyArchiveReader.class"));
            });
        }
    }

    @Test
    void 실제_독립_JAR는_이관이_꺼져_있으면_DB와_S3에_접속하지_않고_시작한다() throws Exception {
        Path log = Path.of("build/reports/archive-boot.log");
        Files.createDirectories(log.getParent());
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx128m", "-jar", System.getProperty("archive.boot.jar"), "--archive.enabled=false",
            "--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/unused", "--spring.datasource.username=test",
            "--spring.datasource.password=test", "--spring.main.banner-mode=off").redirectErrorStream(true)
            .redirectOutput(log.toFile()).start();
        try {
            final long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                if (Files.exists(log) && Files.readString(log).contains("Started ArchiveApplication")) {
                    break;
                }
                if (!process.isAlive()) {
                    break;
                }
                Thread.sleep(50);
            }
            String output = Files.readString(log);
            assertThat(output).contains("Started ArchiveApplication").doesNotContain("APPLICATION FAILED TO START",
                "event=operational_archive");
        } finally {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        }
    }
}
