package com.gustler.backend.worker.startup;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gustler.backend.forecasting.api.model.LoadConfiguredModelCommand;
import com.gustler.backend.forecasting.api.model.ModelLoadResult;
import com.gustler.backend.worker.configuration.ModelBundleProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

class BundleStartupLoaderTest {

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(BundleStartupLoader.class);

    @BeforeEach
    void 로그_수집을_시작한다() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void 로그_수집을_종료한다() {
        logger.detachAppender(logs);
        logs.stop();
    }

    @Test
    void 설정한_계수_파일_자리와_기동_승격_스위치로_적재를_요청한다() {
        // given
        List<LoadConfiguredModelCommand> commands = new ArrayList<>();
        BundleStartupLoader loader = new BundleStartupLoader(new ModelBundleProperties("/srv/model", true),
            command -> {
                commands.add(command);
                return new ModelLoadResult.NotConfigured();
            });

        // when
        loader.loadConfiguredBundle();

        // then
        assertThat(commands).containsExactly(new LoadConfiguredModelCommand("/srv/model", true));
    }

    @ParameterizedTest
    @MethodSource("results")
    void 적재_결과마다_정해진_수준과_문장으로_남긴다(ModelLoadResult result, Level level, String message) {
        // given
        BundleStartupLoader loader = new BundleStartupLoader(new ModelBundleProperties(null, false),
            command -> result);

        // when
        loader.loadConfiguredBundle();

        // then
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(level);
            assertThat(event.getFormattedMessage()).isEqualTo(message);
        });
    }

    static Stream<Arguments> results() {
        return Stream.of(
            Arguments.of(new ModelLoadResult.NotConfigured(), Level.INFO,
                "계수 파일 자리가 안 잡혀 있다. 예보는 계수가 붙을 때까지 batch 를 안 연다"),
            Arguments.of(new ModelLoadResult.Rejected("symlink 다: /srv/model"), Level.ERROR,
                "계수 파일을 못 올렸다. 예보는 batch 를 안 연다: symlink 다: /srv/model"),
            Arguments.of(new ModelLoadResult.Activated(7), Level.INFO,
                "도는 배포가 없어 계수 파일을 올린다: 배포 7"),
            Arguments.of(new ModelLoadResult.Reloaded("release-A"), Level.INFO,
                "도는 배포의 계수를 다시 올렸다: release-A"),
            Arguments.of(new ModelLoadResult.IdentityMismatch("release-A", "release-B"), Level.WARN,
                "도는 배포와 계수 파일의 신원이 다르다. model.bundle.promote-on-start 가 꺼져 있어 "
                    + "안 올린다. 도는 배포 release-A, 파일 release-B"),
            Arguments.of(new ModelLoadResult.Promoted("release-A", "release-B", 8), Level.WARN,
                "사람이 켜 둔 스위치로 계수를 갈아 끼운다. release-A 에서 release-B 로, 배포 8"));
    }
}
