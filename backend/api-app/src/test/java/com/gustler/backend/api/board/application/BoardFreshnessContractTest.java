package com.gustler.backend.api.board.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;

/**
 * 보드가 예보에 요구하는 것을 소비자 쪽에서 고정한다.
 *
 * <p>예보는 관측이 오래된 판에 예보를 붙이지 않고, 보드는 오래된 판을 보여주지 않는다. 두 결정은
 * 각자의 것이지만 관계는 한 방향이다. 예보 창이 보드 창보다 짧으면 그 사이 판이 영영 발행되지 않아,
 * 발행이 밀렸다 돌아올 때 보드가 집을 최신 발행이 없어 503이 나간다.
 *
 * <p>두 앱은 다른 프로세스라 한쪽만 바뀌어도 기동은 따로 성공한다. 그래서 worker 의 실행 JAR 에
 * 실린 설정을 읽어 빌드 시점에 대조한다. 환경변수로 주는 경로는 worker 가 기동에서 막는다.
 */
class BoardFreshnessContractTest {

    private static final String WORKER_JAR = "worker.boot.jar";
    private static final String WORKER_CONFIG = "BOOT-INF/classes/application.yml";
    private static final String STALENESS = "forecast.staleness";

    @Test
    void 예보_신선도_창은_보드가_약속한_창보다_짧지_않다() {
        // when
        Duration forecastWindow = DurationStyle.detectAndParse(workerStaleness());

        // then
        assertThat(forecastWindow)
            .as("예보 창이 보드 창보다 짧으면 그 사이 판이 발행되지 않는다")
            .isGreaterThanOrEqualTo(BoardFreshnessPolicy.FRESHNESS_WINDOW);
    }

    @Test
    void 예보_신선도_창을_설정_파일_밖에서_정하지_않는다() {
        // then
        assertThat(workerStaleness())
            .as("자리표시자를 두면 환경변수가 이 검사를 지나친다")
            .doesNotContain("${");
    }

    private static String workerStaleness() {
        Path jarPath = Path.of(System.getProperty(WORKER_JAR, ""));
        assertThat(Files.isRegularFile(jarPath))
            .as("worker 의 bootJar 를 못 찾았다(-D%s). 이 테스트는 ./gradlew test 로 돌린다", WORKER_JAR)
            .isTrue();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry(WORKER_CONFIG);
            assertThat(entry).as("worker 실행 JAR 에 %s 가 없다", WORKER_CONFIG).isNotNull();
            try (InputStream configuration = jar.getInputStream(entry)) {
                List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
                    .load("worker", new ByteArrayResource(configuration.readAllBytes()));
                Object value = loaded.getFirst().getProperty(STALENESS);
                assertThat(value).as("worker 설정에 %s 가 없다", STALENESS).isNotNull();
                return value.toString();
            }
        } catch (IOException e) {
            throw new IllegalStateException("worker 실행 JAR 을 읽지 못했다: " + jarPath, e);
        }
    }
}
