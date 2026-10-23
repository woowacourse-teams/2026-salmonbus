package com.gustler.backend.forecasting.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

/** 공개 예보 입력의 배열 보호와 record 재구성 계약을 검증한다. */
class SeatDistributionInputContractTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 생성_원본을_바꿔도_검증한_입력과_실제_예보가_유지된다(final boolean hasOutcomes) {
        // given
        double[] features = {1.0};
        SeatDistributionInput input = inputOf(features, outcomesFor(hasOutcomes));
        SeatDistributionPredictor predictor = predictor();
        SeatForecastResult before = predictor.predict(input);

        // when
        features[0] = Double.NaN;
        SeatForecastResult actual = predictor.predict(input);

        // then
        assertSoftly(softly -> {
            softly.assertThat(input.featureVector()).containsExactly(1.0);
            softly.assertThat(actual).isEqualTo(before);
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 반환_배열을_바꿔도_검증한_입력과_실제_예보가_유지된다(final boolean hasOutcomes) {
        // given
        SeatDistributionInput input = inputOf(new double[] {1.0}, outcomesFor(hasOutcomes));
        SeatDistributionPredictor predictor = predictor();
        SeatForecastResult before = predictor.predict(input);
        double[] returned = input.featureVector();

        // when
        returned[0] = Double.NaN;
        SeatForecastResult actual = predictor.predict(input);

        // then
        assertSoftly(softly -> {
            softly.assertThat(input.featureVector()).containsExactly(1.0);
            softly.assertThat(input.featureVector()).isNotSameAs(returned);
            softly.assertThat(actual).isEqualTo(before);
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 접근자로_재구성한_입력은_동등성과_해시와_문자열을_유지한다(final boolean hasOutcomes) {
        // given
        SeatDistributionInput input = inputOf(new double[] {1.0}, outcomesFor(hasOutcomes));

        // when
        SeatDistributionInput copy = copyOf(input);

        // then
        assertSoftly(softly -> {
            softly.assertThat(input).isEqualTo(copy);
            softly.assertThat(copy).isEqualTo(input);
            softly.assertThat(input.hashCode()).isEqualTo(copy.hashCode());
            softly.assertThat(input.toString()).isEqualTo(copy.toString());
        });
    }

    @Test
    void 동등한_입력은_해시_컬렉션에서_같은_키로_조회된다() {
        // given
        SeatDistributionInput input = inputOf(new double[] {1.0}, null);
        Map<SeatDistributionInput, String> entries = new HashMap<>();
        Set<SeatDistributionInput> unique = new HashSet<>();
        entries.put(input, "stored");
        unique.add(input);

        // when
        SeatDistributionInput copy = copyOf(input);
        final boolean added = unique.add(copy);
        String actual = entries.get(copy);

        // then
        assertSoftly(softly -> {
            softly.assertThat(added).isFalse();
            softly.assertThat(unique).hasSize(1);
            softly.assertThat(actual).isEqualTo("stored");
        });
    }

    @Test
    void 모든_컴포넌트가_동등성에_참여하고_다른_타입과_null은_동등하지_않다() {
        // given
        SeatDistributionInput input = inputOf(new double[] {1.0}, null);
        List<SeatDistributionInput> variants = List.of(
            inputOf(new double[] {2.0}, null),
            new SeatDistributionInput(new double[] {1.0}, "1650", 4, 20, 44, null),
            new SeatDistributionInput(new double[] {1.0}, "3330", 5, 20, 44, null),
            new SeatDistributionInput(new double[] {1.0}, "3330", 4, 21, 44, null),
            new SeatDistributionInput(new double[] {1.0}, "3330", 4, 20, 45, null),
            inputOf(new double[] {1.0}, new SameDayFullOutcomes(100, 90, 0.1)));

        // when
        final boolean equalsNull = input.equals(null);
        final boolean equalsOtherType = input.equals("3330");

        // then
        assertSoftly(softly -> {
            softly.assertThat(input).isEqualTo(input);
            softly.assertThat(equalsNull).isFalse();
            softly.assertThat(equalsOtherType).isFalse();
            softly.assertThat(variants).allSatisfy(other -> {
                assertThat(input).isNotEqualTo(other);
                assertThat(other).isNotEqualTo(input);
            });
        });
    }

    @Test
    void 특징의_양의_0과_음의_0은_double_값_계약대로_구별한다() {
        // given
        SeatDistributionInput positive = inputOf(new double[] {0.0}, null);
        SeatDistributionInput negative = inputOf(new double[] {-0.0}, null);

        // when
        final boolean equal = positive.equals(negative);

        // then
        assertThat(equal).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 직렬화_왕복은_특징_내용과_당일_집계의_null_의미를_유지한다(final boolean hasOutcomes) {
        // given
        ObjectMapper mapper = new ObjectMapper();
        SeatDistributionInput input = inputOf(new double[] {1.0}, outcomesFor(hasOutcomes));

        // when
        String json = mapper.writeValueAsString(input);
        SeatDistributionInput actual = mapper.readValue(json, SeatDistributionInput.class);

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual).isEqualTo(input);
            softly.assertThat(actual.featureVector()).containsExactly(1.0);
            softly.assertThat(actual.sameDayOutcomes()).isEqualTo(input.sameDayOutcomes());
            softly.assertThat(actual.toString()).isEqualTo(input.toString());
        });
    }

    @ParameterizedTest
    @MethodSource("invalidFeatures")
    void 특징의_null_빈값_비유한값을_기존과_같이_거절한다(double[] features) {
        // when
        Throwable actual = catchThrowable(() -> inputOf(features, null));

        // then
        assertThat(actual).isExactlyInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " "})
    void 노선의_null과_공백을_기존과_같이_거절한다(String route) {
        // when
        Throwable actual = catchThrowable(() ->
            new SeatDistributionInput(new double[] {1.0}, route, 4, 20, 44, null));

        // then
        assertThat(actual).isExactlyInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<Arguments> invalidFeatures() {
        return Stream.of(
            Arguments.of((Object) null),
            Arguments.of((Object) new double[0]),
            Arguments.of((Object) new double[] {Double.NaN}),
            Arguments.of((Object) new double[] {Double.POSITIVE_INFINITY}),
            Arguments.of((Object) new double[] {Double.NEGATIVE_INFINITY}));
    }

    private static SeatDistributionInput copyOf(SeatDistributionInput input) {
        return new SeatDistributionInput(input.featureVector(), input.modelRoute(), input.stopsAhead(),
            input.currentSeats(), input.capacity(), input.sameDayOutcomes());
    }

    private static SeatDistributionInput inputOf(double[] features, SameDayFullOutcomes outcomes) {
        return new SeatDistributionInput(features, "3330", 4, 20, 44, outcomes);
    }

    private static SameDayFullOutcomes outcomesFor(final boolean hasOutcomes) {
        if (hasOutcomes) {
            return new SameDayFullOutcomes(100, 90, 0.1);
        }
        return null;
    }

    private static SeatDistributionPredictor predictor() {
        return new SeatDistributionPredictor(
            CoefficientsFixture.lookupOf(CoefficientsFixture.horizon(0.2, 0.05, 0.8, 0.2, 0.5)),
            CoefficientsFixture.RELATIVE_EDGES);
    }
}
