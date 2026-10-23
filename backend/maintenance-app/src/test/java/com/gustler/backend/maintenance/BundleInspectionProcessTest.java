package com.gustler.backend.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** DB 컨테이너 없이 실제 정비 JAR의 검수 진입점을 검증한다. */
class BundleInspectionProcessTest {
    @TempDir
    Path directory;
    private int sequence;

    @Test
    void 번들_검수는_DB_설정_없이_실행된다() throws Exception {
        // when
        Path bundle = directory.resolve("bundle");
        BundleInspectionFixture.write(bundle);
        Result result = execute(bundle);

        // then
        assertThat(result.exitCode()).isZero();
        JsonNode json = JsonMapper.builder().build().readTree(result.stdout());
        assertThat(json.get("status").stringValue()).isEqualTo("succeeded");
        assertThat(json.get("result").get("validation").stringValue()).isEqualTo("PASSED");
        assertThat(json.get("result").get("activated").booleanValue()).isFalse();
        assertThat(json.get("result").get("accuracyEvaluated").booleanValue()).isFalse();
        assertThat(json.get("result").get("routes").size()).isEqualTo(8);
    }

    @Test
    void 부적합_번들_검수는_실패_종료한다() throws Exception {
        // when
        Path bundle = directory.resolve("bundle");
        BundleInspectionFixture.write(bundle);
        Path weights = bundle.resolve("weights.safetensors");
        byte[] changed = Files.readAllBytes(weights);
        changed[changed.length - 1] ^= 1;
        Files.write(weights, changed);
        Result result = execute(bundle);

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(result.exitCode()).isEqualTo(1);
            softly.assertThat(result.stdout()).isBlank();
        }
        JsonNode json = JsonMapper.builder().build().readTree(result.stderr());
        assertThat(json.get("code").stringValue()).isEqualTo("BUNDLE_REJECTED_WEIGHTS_DIGEST");
    }

    private Result execute(Path bundle) throws Exception {
        String jar = System.getProperty("maintenance.boot.jar");
        assertThat(jar).isNotBlank();
        List<String> command = new ArrayList<>(List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-XX:ActiveProcessorCount=1", "-Xmx192m", "-jar", jar,
            "bundle-check", "--directory", bundle.toString()));
        Path stdout = directory.resolve("check-" + (++sequence) + ".out");
        Path stderr = directory.resolve("check-" + sequence + ".err");
        ProcessBuilder builder = new ProcessBuilder(command)
            .redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        builder.environment().keySet().removeIf(name -> name.startsWith("DB_")
            || name.startsWith("SPRING_") || name.startsWith("GBIS_") || name.startsWith("MODEL_")
            || Set.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS").contains(name));
        Process process = builder.start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("검수 JAR은 30초 안에 종료해야 한다").isTrue();
            return new Result(process.exitValue(), Files.readString(stdout).strip(), Files.readString(stderr).strip());
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    private record Result(int exitCode, String stdout, String stderr) {
    }
}
