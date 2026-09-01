package com.gustler.backend.processor.seatdistribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * 계수 파일을 믿고 읽지 않는지 본다.
 *
 * <p>머리말이 적은 구간을 그대로 믿으면 파일 밖을 읽거나, 크기와 안 맞는 바이트를 계수로 읽는다.
 * 뒤엣것이 더 나쁘다. 예외가 안 나고 아무 수나 계수가 된다.
 */
class SafetensorsFileTest {

    @Test
    void 실수_배열을_적힌_크기대로_읽는다() {
        // given
        byte[] given = new SafetensorsWriter()
            .with("anchor_coefficients", TensorDataType.FLOAT64, new int[] {2, 2},
                new double[] {0.5, 1.5, 2.5, 3.5})
            .toBytes();

        // when
        Tensor actual = SafetensorsFile.of(given).get("anchor_coefficients");

        // then
        assertThat(actual.rowAt(1)).containsExactly(2.5, 3.5);
    }

    @Test
    void 학습_여부_표시_배열을_적힌_크기대로_읽는다() {
        // given
        byte[] given = new SafetensorsWriter()
            .with("bin_fitted", TensorDataType.UINT8, new int[] {3}, new double[] {1, 0, 1})
            .toBytes();

        // when
        Tensor actual = SafetensorsFile.of(given).get("bin_fitted");

        // then
        assertThat(actual.valueAt(1)).isZero();
    }

    @Test
    void 머리말에_없는_배열을_찾으면_거절한다() {
        // given
        byte[] given = new SafetensorsWriter()
            .with("bin_fitted", TensorDataType.UINT8, new int[] {1}, new double[] {1})
            .toBytes();

        // when & then
        assertThatThrownBy(() -> SafetensorsFile.of(given).get("hurdle_coefficients"))
            .isInstanceOf(BundleRejectedException.class);
    }

    @Test
    void 파일이_머리말_길이도_못_담을_만큼_짧으면_거절한다() {
        // when & then
        assertThatThrownBy(() -> SafetensorsFile.of(new byte[] {1, 2, 3}))
            .isInstanceOf(BundleRejectedException.class);
    }

    @Test
    void 머리말_길이가_파일_크기를_넘으면_거절한다() {
        // given 머리말이 실제보다 길다고 적힌 파일이다
        byte[] given = withHeaderLength(headerOf("F64", 1, 8), 8, 4096);

        // when & then
        assertThatThrownBy(() -> SafetensorsFile.of(given))
            .isInstanceOf(BundleRejectedException.class);
    }

    @Test
    void 값_구간_길이가_크기와_자료형에_안_맞으면_거절한다() {
        // given 실수 하나는 8바이트인데 구간이 4바이트로 적혀 있다
        byte[] given = fileOf(headerOf("F64", 1, 4), 8);

        // when & then
        assertThatThrownBy(() -> SafetensorsFile.of(given))
            .isInstanceOf(BundleRejectedException.class)
            .hasMessageContaining("값 구간 길이");
    }

    @Test
    void 값_구간이_파일_밖을_가리키면_거절한다() {
        // given 구간은 8바이트라고 적혀 있는데 값이 하나도 안 붙어 있다
        byte[] given = fileOf(headerOf("F64", 1, 8), 0);

        // when & then
        assertThatThrownBy(() -> SafetensorsFile.of(given))
            .isInstanceOf(BundleRejectedException.class)
            .hasMessageContaining("파일 밖");
    }

    @Test
    void 머리말이_JSON_이_아니면_거절한다() {
        // given
        byte[] given = fileOf("계수가 여기 있어요".getBytes(StandardCharsets.UTF_8), 8);

        // when & then
        assertThatThrownBy(() -> SafetensorsFile.of(given))
            .isInstanceOf(BundleRejectedException.class);
    }

    @Test
    void 배열의_크기에_0이_있으면_거절한다() {
        // given
        byte[] given = fileOf(
            "{\"hurdle_coefficients\":{\"dtype\":\"F64\",\"shape\":[0],\"data_offsets\":[0,0]}}"
                .getBytes(StandardCharsets.UTF_8), 0);

        // when & then
        assertThatThrownBy(() -> SafetensorsFile.of(given))
            .isInstanceOf(BundleRejectedException.class);
    }

    @Test
    void 머리말이_자기_정보를_담은_자리는_배열로_안_읽는다() {
        // given safetensors 는 __metadata__ 에 배열이 아닌 것을 담는다
        byte[] given = fileOf(
            ("{\"__metadata__\":{\"format\":\"pt\"},"
                + "\"bin_fitted\":{\"dtype\":\"U8\",\"shape\":[1],\"data_offsets\":[0,1]}}")
                .getBytes(StandardCharsets.UTF_8), 1);

        // when
        SafetensorsFile actual = SafetensorsFile.of(given);

        // then
        assertThat(actual.names()).containsExactly("bin_fitted");
    }

    private static byte[] headerOf(
        String dataType,
        final int length,
        final int valueBytes
    ) {
        return ("{\"hurdle_coefficients\":{\"dtype\":\"%s\",\"shape\":[%d],\"data_offsets\":[0,%d]}}"
            .formatted(dataType, length, valueBytes)).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] fileOf(
        byte[] header,
        final int valueByteCount
    ) {
        return withHeaderLength(header, valueByteCount, header.length);
    }

    private static byte[] withHeaderLength(
        byte[] header,
        final int valueByteCount,
        final long declaredHeaderLength
    ) {
        ByteBuffer file = ByteBuffer.allocate(8 + header.length + valueByteCount)
            .order(ByteOrder.LITTLE_ENDIAN);
        file.putLong(declaredHeaderLength);
        file.put(header);
        return file.array();
    }
}
