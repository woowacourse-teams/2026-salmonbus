package com.gustler.backend.worker.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;

/**
 * 합성 번들 허용({@code model.bundle.allow-synthetic-fixture})을 어느 설정 파일이 켜는지 지킨다.
 * 합성 번들은 실제 관측 없이 만들어 재생·정산 검증을 거치지 않았으므로 운영과 공유 개발 서버의 worker가 받으면 안 된다.
 */
class SyntheticFixtureSettingTest {
    private static final String KEY = "model.bundle.allow-synthetic-fixture";
    private static final Path BASE = Path.of("src/main/resources/application.yml");
    private static final Path SHARED_DEV = Path.of("../../dev/server/worker.yml");
    private static final Path LOCAL = Path.of("../../dev/config/worker.yml");

    @Test
    void 기본_설정은_합성_번들을_허용하지_않고_환경변수_자리표시자를_두지_않는다() throws Exception {
        // when
        Object configured = single(BASE).getProperty(KEY);

        // then
        assertThat(configured).isEqualTo(false);
    }

    @Test
    void 공유_개발_서버_설정은_합성_번들_허용_키를_두지_않아_기본값을_따른다() throws Exception {
        // when
        MockEnvironment environment = environment(SHARED_DEV);

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(single(SHARED_DEV).containsProperty(KEY)).isFalse();
            softly.assertThat(environment.getProperty(KEY, Boolean.class)).isFalse();
        }
    }

    @Test
    void 로컬_개발_설정은_합성_번들을_허용한다() throws Exception {
        // when
        MockEnvironment environment = environment(LOCAL);

        // then
        assertThat(environment.getProperty(KEY, Boolean.class)).isTrue();
    }

    /** 기본 설정 위에 환경별 설정 파일을 얹은 환경이다. */
    private static MockEnvironment environment(Path overlay) throws Exception {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addLast(single(overlay));
        environment.getPropertySources().addLast(single(BASE));
        return environment;
    }

    private static PropertySource<?> single(Path file) throws Exception {
        assertThat(file).exists();
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
            .load(file.toString(), new FileSystemResource(file));
        assertThat(sources).hasSize(1);
        return sources.getFirst();
    }
}
