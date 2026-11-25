package com.gustler.backend.worker.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gustler.backend.forecasting.api.forecast.RunForecasts;
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

    @Test
    void 예보가_켜져_있으면_모델을_올린_뒤에_첫_줍기를_한_번_돌린다() {
        // given
        List<String> order = new ArrayList<>();
        BundleStartupLoader loader = new BundleStartupLoader(new ModelBundleProperties("/srv/model", false),
            command -> {
                order.add("load");
                return new ModelLoadResult.Reloaded("release-A");
            });
        loader.setRunForecasts(new RunForecasts() {
            @Override
            public void batchStored(final long observationBatchId, final long routeVersionId) {
                order.add("signal");
            }

            @Override
            public void pickUp() {
                order.add("pickUp");
            }
        });

        // when
        loader.loadConfiguredBundle();

        // then
        assertThat(order).containsExactly("load", "pickUp");
    }

    @ParameterizedTest
    @MethodSource("results")
    void 적재_결과마다_정해진_수준과_문장으로_남긴다(ModelLoadResult result, Level level, String message) {
        // given
        BundleStartupLoader loader = new BundleStartupLoader(new ModelBundleProperties(null, false), command -> result);

        // when
        loader.loadConfiguredBundle();

        // then
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertSoftly(softly -> {
                softly.assertThat(event.getLevel()).isEqualTo(level);
                softly.assertThat(event.getFormattedMessage()).isEqualTo(message);
            });
        });
    }

    static Stream<Arguments> results() {
        return Stream.of(
                Arguments.of(new ModelLoadResult.NotConfigured(), Level.INFO,
                "모델 번들 경로가 설정되지 않았다. 모델을 사용할 수 있을 때까지 예보를 실행하지 않는다"),
                Arguments.of(new ModelLoadResult.Rejected("심볼릭 링크다: /srv/model"), Level.ERROR,
                "모델 번들을 적재하지 못해 예보를 실행하지 않는다: 심볼릭 링크다: /srv/model"),
                Arguments.of(new ModelLoadResult.Activated(7), Level.INFO, "활성 모델 배포가 없어 모델 번들을 적재한다: 배포 7"),
                Arguments.of(new ModelLoadResult.Reloaded("release-A"), Level.INFO, "활성 배포의 모델 번들을 다시 적재했다: release-A"),
                Arguments.of(new ModelLoadResult.IdentityMismatch("release-A", "release-B"), Level.WARN,
                    "활성 배포와 모델 번들의 식별 정보가 다르다. model.bundle.promote-on-start가 꺼져 있어 "
                    + "적재하지 않는다. 활성 배포 release-A, 파일 release-B"),
                Arguments.of(new ModelLoadResult.Promoted("release-A", "release-B", 8), Level.WARN,
                "model.bundle.promote-on-start 설정에 따라 활성 모델을 전환했다. release-A에서 release-B로, 배포 8")
        );
    }
}
