package com.gustler.backend.forecasting.domain.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ForecastPublicationTest {

    private static final long SOURCE_BATCH_ID = 100;
    private static final int SOURCE_ATTEMPT_NUMBER = 2;
    private static final long ROUTE_VERSION_ID = 10;
    private static final long MODEL_DEPLOYMENT_ID = 20;
    private static final int STATISTICS_REVISION = 3;
    private static final long QUALITY_REVISION = 4;
    private static final Instant OBSERVED_AT = Instant.parse("2026-09-21T02:00:00Z");
    private static final Instant GENERATED_AT = OBSERVED_AT.plusSeconds(2);
    private static final Instant PUBLISHED_AT = GENERATED_AT.plusSeconds(1);

    @Test
    void 다른_노선_버전의_예측은_한_발행에_포함할_수_없다() {
        // given
        SeatForecast prediction = prediction(
            ROUTE_VERSION_ID + 1, MODEL_DEPLOYMENT_ID, STATISTICS_REVISION, GENERATED_AT);

        // when & then
        assertThatThrownBy(() -> publish(List.of(prediction)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 다른_모델로_계산한_예측은_한_발행에_포함할_수_없다() {
        // given
        SeatForecast prediction = prediction(
            ROUTE_VERSION_ID, MODEL_DEPLOYMENT_ID + 1, STATISTICS_REVISION, GENERATED_AT);

        // when & then
        assertThatThrownBy(() -> publish(List.of(prediction)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 다른_통계_버전으로_계산한_예측은_한_발행에_포함할_수_없다() {
        // given
        SeatForecast prediction = prediction(
            ROUTE_VERSION_ID, MODEL_DEPLOYMENT_ID, STATISTICS_REVISION + 1, GENERATED_AT);

        // when & then
        assertThatThrownBy(() -> publish(List.of(prediction)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 계산_시각이_다른_예측은_한_발행에_포함할_수_없다() {
        // given
        SeatForecast prediction = prediction(
            ROUTE_VERSION_ID, MODEL_DEPLOYMENT_ID, STATISTICS_REVISION, GENERATED_AT.plusSeconds(1));

        // when & then
        assertThatThrownBy(() -> publish(List.of(prediction)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 확률이_달라도_같은_차량_관측과_대상_정류장의_예측을_중복_발행할_수_없다() {
        // given
        SeatForecast first = prediction(
            ROUTE_VERSION_ID, MODEL_DEPLOYMENT_ID, STATISTICS_REVISION, GENERATED_AT);
        SeatForecast duplicate = new SeatForecast(
            first.vehicleObservationId(), ROUTE_VERSION_ID, first.targetStopOrder(), first.stopsToTarget(),
            MODEL_DEPLOYMENT_ID, STATISTICS_REVISION, 0.8, 0.8, 0.2, GENERATED_AT);

        // when & then
        assertThatThrownBy(() -> publish(List.of(first, duplicate)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 발행_후에는_입력_목록이나_반환된_목록으로_예측을_변경할_수_없다() {
        // given
        SeatForecast prediction = prediction(
            ROUTE_VERSION_ID, MODEL_DEPLOYMENT_ID, STATISTICS_REVISION, GENERATED_AT);
        List<SeatForecast> input = new ArrayList<>(List.of(prediction));
        ForecastPublication publication = publish(input);

        // when
        input.clear();

        // then
        assertThat(publication.predictions()).containsExactly(prediction);
        assertThat(publication.predictionCount()).isEqualTo(1);
        assertThatThrownBy(() -> publication.predictions().clear())
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 예측이_없어도_수집_시도와_계산에_사용한_버전을_발행_정보에_남긴다() {
        // when
        ForecastPublication publication = publish(List.of());

        // then
        assertThat(publication.predictions()).isEmpty();
        assertThat(publication.predictionCount()).isZero();
        assertThat(publication.sourceBatchId()).isEqualTo(SOURCE_BATCH_ID);
        assertThat(publication.sourceAttemptNumber()).isEqualTo(SOURCE_ATTEMPT_NUMBER);
        assertThat(publication.routeVersionId()).isEqualTo(ROUTE_VERSION_ID);
        assertThat(publication.modelDeploymentId()).isEqualTo(MODEL_DEPLOYMENT_ID);
        assertThat(publication.demandStatisticsRevision()).isEqualTo(STATISTICS_REVISION);
        assertThat(publication.qualityRevision()).isEqualTo(QUALITY_REVISION);
        assertThat(publication.observedAt()).isEqualTo(OBSERVED_AT);
        assertThat(publication.generatedAt()).isEqualTo(GENERATED_AT);
        assertThat(publication.publishedAt()).isEqualTo(PUBLISHED_AT);
    }

    private ForecastPublication publish(List<SeatForecast> predictions) {
        return new ForecastPublication(
            SOURCE_BATCH_ID, SOURCE_ATTEMPT_NUMBER, ROUTE_VERSION_ID, MODEL_DEPLOYMENT_ID,
            STATISTICS_REVISION, QUALITY_REVISION, OBSERVED_AT, GENERATED_AT, PUBLISHED_AT, predictions);
    }

    private SeatForecast prediction(
        final long routeVersionId,
        final long modelDeploymentId,
        final int statisticsRevision,
        Instant generatedAt
    ) {
        return new SeatForecast(
            200, routeVersionId, 4, 1, modelDeploymentId, statisticsRevision, 0.4, 0.4, 0.6, generatedAt);
    }
}
