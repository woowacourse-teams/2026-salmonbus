package com.gustler.backend.processor.seatdistribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import com.gustler.backend.processor.SeatForecastDesignMatrix;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * 검사가 실제로 막는지 본다.
 *
 * <p>이 티켓에서 값이 제일 큰 자리다. 어긋난 계수 묶음으로 낸 예보는 값이 정상처럼 보이고
 * 뜻만 없다. 화면에서도 로그에서도 구분되지 않아서, 막히는지를 여기서 고정해 둬야 한다.
 */
class BundleLoaderTest {

    @TempDir
    Path directory;

    @Test
    void 검사를_다_통과한_묶음은_노선과_거리로_계수를_꺼낼_수_있다() {
        // when
        CoefficientBundle actual = BundleLoader.load(DummyBundle.valid().writeTo(directory));

        // then
        assertThat(actual.at("3330", 12).featureCount()).isEqualTo(DummyBundle.FEATURE_COUNT);
    }

    @Test
    void 설명_파일이_symlink_면_거절한다() throws IOException {
        // given
        BundleFiles files = DummyBundle.valid().writeTo(directory);
        Path moved = directory.resolve("real-manifest.json");
        Files.move(files.manifest(), moved);
        Files.createSymbolicLink(files.manifest(), moved);

        // when & then
        assertRejectedBy(BundleCheck.MANIFEST_IS_REGULAR_FILE, () -> BundleLoader.load(files));
    }

    @Test
    void 계수_파일이_symlink_면_거절한다() throws IOException {
        // given
        BundleFiles files = DummyBundle.valid().writeTo(directory);
        Path moved = directory.resolve("real-weights.safetensors");
        Files.move(files.weights(), moved);
        Files.createSymbolicLink(files.weights(), moved);

        // when & then
        assertRejectedBy(BundleCheck.WEIGHTS_IS_REGULAR_FILE, () -> BundleLoader.load(files));
    }

    @Test
    void 설명_파일이_없으면_거절한다() {
        // when & then
        assertRejectedBy(
            BundleCheck.MANIFEST_IS_REGULAR_FILE,
            () -> BundleLoader.load(BundleFiles.under(directory)));
    }

    @Test
    void 설명_파일이_UTF_8_이_아니면_거절한다() throws IOException {
        // given
        DummyBundle.valid().writeTo(directory);
        Files.write(directory.resolve(BundleFiles.MANIFEST_NAME), new byte[] {(byte) 0xff, (byte) 0xfe});

        // when & then
        assertRejectedBy(
            BundleCheck.MANIFEST_IS_UTF8_JSON,
            () -> BundleLoader.load(BundleFiles.under(directory)));
    }

    @Test
    void 묶음이_놓인_자리가_symlink_면_거절한다() throws IOException {
        // given 배포 자리에 흔한 current 처럼 디렉터리 자체가 연결이다
        Path released = Files.createDirectory(directory.resolve("release-0001"));
        DummyBundle.valid().writeTo(released);
        Path linked = Files.createSymbolicLink(directory.resolve("current"), released);

        // when & then 설명 파일을 읽은 뒤 연결이 옮겨 가면 다른 계수 파일을 읽게 된다
        assertRejectedBy(
            BundleCheck.BUNDLE_DIRECTORY_IS_NOT_SYMBOLIC_LINK, () -> BundleLoader.load(BundleFiles.under(linked)));
    }

    @Test
    void 설명_파일에_빈칸이_들어_있으면_거절한다() throws IOException {
        // given 뜻은 같은데 항목 사이에 빈칸을 넣어 다시 쓴다
        BundleFiles files = DummyBundle.valid().writeTo(directory);
        Files.write(files.manifest(),
            new String(Files.readAllBytes(files.manifest()), StandardCharsets.UTF_8)
                .replace(",\"", ", \"")
                .getBytes(StandardCharsets.UTF_8));

        // when & then 빈칸 하나로 요약값이 달라져 같은 계수가 두 신원이 된다
        assertRejectedBy(BundleCheck.MANIFEST_IS_CANONICAL_JSON, () -> BundleLoader.load(files));
    }

    @Test
    void 설명_파일의_항목_순서가_이름순이_아니면_거절한다() throws IOException {
        // given 빈칸은 그대로 두고 맨 앞 항목 하나만 맨 뒤로 옮긴다
        BundleFiles files = DummyBundle.valid().writeTo(directory);
        Files.write(files.manifest(), movedToEnd(
            read(files.manifest()),
            "\"bundleSchemaVersion\":\"%s\"".formatted(DummyBundle.BUNDLE_SCHEMA_VERSION)));

        // when & then 순서만 달라도 요약값이 달라져 같은 계수가 두 신원이 된다
        assertRejectedBy(BundleCheck.MANIFEST_IS_CANONICAL_JSON, () -> BundleLoader.load(files));
    }

    @Test
    void 설명_파일_안쪽_항목의_순서가_이름순이_아니면_거절한다() throws IOException {
        // given 배열 선언 하나에서 자료형과 크기의 순서를 맞바꾼다
        BundleFiles files = DummyBundle.valid().writeTo(directory);
        Files.write(files.manifest(), read(files.manifest()).replaceFirst(
            "\\{\"dtype\":(\"[A-Z0-9]+\"),\"shape\":(\\[[0-9,]+])}",
            "{\"shape\":$2,\"dtype\":$1}").getBytes(StandardCharsets.UTF_8));

        // when & then 안쪽도 이름순이어야 같은 뜻이 한 요약값으로만 적힌다
        assertRejectedBy(BundleCheck.MANIFEST_IS_CANONICAL_JSON, () -> BundleLoader.load(files));
    }

    @Test
    void 대조_사례에_특징_벡터가_없으면_거절한다() {
        // given
        Map<String, Object> golden = new LinkedHashMap<>(DummyBundle.valid().goldenVectorFrom());
        golden.remove("featureVector");

        // when & then 안 보고 꺼내면 여기서 NullPointerException 이 나 거절을 안 거친다
        assertRejectedBy(BundleCheck.MANIFEST_FIELD_TYPE, loading(DummyBundle.valid()
            .put("goldenVector", golden)
            .put("goldenVectorDigest", "0".repeat(64))));
    }

    @Test
    void 설명_파일이_수를_문자열로_적으면_거절한다() {
        // when & then 문자열을 수로 바꿔 읽어 주면 다른 뜻의 묶음이 통과한다
        assertRejectedBy(
            BundleCheck.MANIFEST_FIELD_TYPE,
            loading(DummyBundle.valid().put("horizonStops", List.of("1", "2", "3"))));
    }

    @Test
    void 설명_파일에_모르는_항목이_있으면_거절한다() {
        // when & then
        assertRejectedBy(
            BundleCheck.MANIFEST_HAS_NO_UNKNOWN_FIELD,
            loading(DummyBundle.valid().put("leadSeconds", List.of(105.022, 90.104))));
    }

    @Test
    void 계수_묶음_버전이_다르면_거절한다() {
        // when & then
        assertRejectedBy(
            BundleCheck.BUNDLE_SCHEMA_VERSION,
            loading(DummyBundle.valid().put("bundleSchemaVersion", "a18-live-bundle-v2")));
    }

    @Test
    void 모델_버전이_다르면_거절한다() {
        // when & then
        assertRejectedBy(
            BundleCheck.MODEL_VERSION,
            loading(DummyBundle.valid().put("modelVersion", "seat-distribution-a19-v1")));
    }

    @Test
    void 입력_규칙_버전이_비어_있으면_거절한다() {
        // when & then
        assertRejectedBy(
            BundleCheck.FEATURE_CONTRACT_VERSION,
            loading(DummyBundle.valid().put("featureContractVersion", " ")));
    }

    @Test
    void 노선_참조_요약값이_64자리가_아니면_거절한다() {
        // when & then
        assertRejectedBy(
            BundleCheck.ROUTE_REFERENCE,
            loading(DummyBundle.valid().put("routeReferenceDigest", "abc")));
    }

    @Test
    void 노선_순서가_뒤집히면_거절한다() {
        // when & then
        assertRejectedBy(
            BundleCheck.ROUTE_ORDER,
            loading(DummyBundle.valid().put("routes", List.of("3330", "1650"))));
    }

    @Test
    void 예보_거리가_1부터_12까지가_아니면_거절한다() {
        // when & then
        assertRejectedBy(
            BundleCheck.HORIZON_STOPS,
            loading(DummyBundle.valid().put("horizonStops", List.of(1, 2, 4, 8, 12))));
    }

    @Test
    void 입력값_이름의_순서가_바뀌면_거절한다() {
        // given 이름도 개수도 같은데 두 열의 자리만 바꾼다
        assertRejectedBy(
            BundleCheck.FEATURE_NAMES,
            loading(DummyBundle.valid().put("featureNames", swappedFeatureNames())));
    }

    @Test
    void 입력값_이름이_우리_설계행렬에_없는_것이면_거절한다() {
        // when & then
        assertRejectedBy(
            BundleCheck.FEATURE_NAMES,
            loading(DummyBundle.valid().put("featureNames", renamedFeatureNames())));
    }

    @Test
    void 계수_파일에_읽을_줄_모르는_배열이_있으면_거절한다() {
        // given v4-1 정본이 동결 셀 통계를 가져오지 말라고 한 자리다
        DummyBundle given = DummyBundle.valid()
            .withTensor("cell_values", TensorDataType.FLOAT64, new int[] {2, 2}, new double[4]);

        // when & then
        assertRejectedBy(BundleCheck.WEIGHTS_HAS_NO_UNKNOWN_TENSOR, loading(given));
    }

    @Test
    void 좌석_분포에_필요한_배열_하나가_없으면_거절한다() {
        // when & then
        assertRejectedBy(
            BundleCheck.REQUIRED_TENSORS_PRESENT,
            loading(DummyBundle.valid().withoutTensor(BundleTensor.ANCHOR)));
    }

    @Test
    void 중심_좌석_계수가_절편과_기울기_둘이_아니면_거절한다() {
        // given 중심 좌석 계수는 절편과 기울기 둘인데 셋으로 담는다
        DummyBundle given = DummyBundle.valid().withTensor(
            BundleTensor.ANCHOR.tensorName(), TensorDataType.FLOAT64,
            new int[] {2, 12, 3}, new double[2 * 12 * 3]);

        // when & then
        assertRejectedBy(BundleCheck.TENSOR_SHAPE_AND_DATA_TYPE, loading(given));
    }

    @Test
    void 계수를_자릿수가_모자란_형식으로_담으면_읽지_않는다() {
        // given 자릿수가 모자라 같은 입력에서 다른 확률이 나온다
        byte[] given = weightsWithDataType("F32", 4);

        // when & then
        assertThatThrownBy(() -> SafetensorsFile.of(given))
            .isInstanceOf(BundleRejectedException.class)
            .hasMessageContaining("F32");
    }

    @Test
    void 계수에_NaN_이_있으면_거절한다() {
        // when & then
        assertRejectedBy(
            BundleCheck.COEFFICIENTS_ARE_FINITE,
            loading(DummyBundle.valid().withTensorValue(BundleTensor.FULL_CHANCE, 7, Double.NaN)));
    }

    @Test
    void 계수에_Infinity_가_있으면_거절한다() {
        // when & then
        assertRejectedBy(
            BundleCheck.COEFFICIENTS_ARE_FINITE,
            loading(DummyBundle.valid()
                .withTensorValue(BundleTensor.FULL_CHANCE, 0, Double.POSITIVE_INFINITY)));
    }

    @Test
    void 학습됐는지_표시하는_값이_0도_1도_아니면_거절한다() {
        // when & then
        assertRejectedBy(
            BundleCheck.FITTED_FLAGS_ARE_ZERO_OR_ONE,
            loading(DummyBundle.valid().withTensorValue(BundleTensor.RESIDUAL_BIN_FITTED, 3, 2.0)));
    }

    @Test
    void 계수_파일_요약값이_다르면_거절한다() {
        // when & then
        assertRejectedBy(
            BundleCheck.WEIGHTS_DIGEST,
            loading(DummyBundle.valid().put("weightsDigest", "f".repeat(64))));
    }

    @Test
    void 대조_사례_요약값이_사례_내용에서_나온_값이_아니면_거절한다() {
        // given 사례는 그대로인데 요약값만 아무 수로 적어 둔다
        assertRejectedBy(
            BundleCheck.GOLDEN_VECTOR,
            loading(DummyBundle.valid().put("goldenVectorDigest", "0".repeat(64))));
    }

    @Test
    void 대조_사례를_바꾸고_요약값을_안_고치면_거절한다() {
        // given 기대 잔여석만 한 석 어긋내고 요약값은 그대로 둔다
        DummyBundle given = DummyBundle.valid();
        Map<String, Object> golden = new LinkedHashMap<>(given.goldenVectorFrom());
        final String unchanged = goldenVectorDigestOf(DummyBundle.valid());
        golden.put("expectedSeats", ((double) golden.get("expectedSeats")) + 1.0);

        // when & then
        assertRejectedBy(BundleCheck.GOLDEN_VECTOR, loading(
            given.put("goldenVector", golden).put("goldenVectorDigest", unchanged)));
    }

    @Test
    void 출시_식별자를_묶은_요약값이_다르면_거절한다() {
        // when & then
        assertRejectedBy(
            BundleCheck.RELEASE_IDENTITY_DIGEST,
            loading(DummyBundle.valid().put("identityDigest", "0".repeat(64))));
    }

    /**
     * 계수 파일은 한 글자도 안 바뀌었는데 입력을 만드는 규칙만 바뀐 묶음이다. 계수 요약값 검사만으로는
     * 안 걸린다. 신원 요약값이 그 규칙 버전까지 묶고 있어서 여기서 걸린다.
     */
    @Test
    void 계수는_그대로인데_입력_규칙_버전만_바뀌면_거절한다() {
        // given 안 바뀐 묶음의 신원 요약값을 그대로 달고 입력 규칙 버전만 바꾼다
        final String unchanged = identityDigestOf(DummyBundle.valid());
        DummyBundle given = DummyBundle.valid()
            .put("featureContractVersion", "seat-feature-contract-v5")
            .put("identityDigest", unchanged);

        // when & then
        assertRejectedBy(BundleCheck.RELEASE_IDENTITY_DIGEST, loading(given));
    }

    /**
     * 열 개수만 맞추고 올리면, 그 계수는 우리 설계행렬과 다른 자리에 학습된 것이다. 값은 정상으로
     * 보이면서 뜻이 없는 예보가 나간다. 그래서 개수가 아니라 이름과 순서를 본다.
     */
    @Test
    void 입력값이_24개인_계수_묶음은_거절한다() {
        // when & then
        assertRejectedBy(BundleCheck.FEATURE_NAMES, loading(DummyBundle.withFeatureCount(24)));
    }

    private static String goldenVectorDigestOf(
        DummyBundle bundle
    ) {
        return new ObjectMapper().readTree(new String(bundle.manifestBytes(), StandardCharsets.UTF_8))
            .get("goldenVectorDigest")
            .stringValue();
    }

    private static String identityDigestOf(
        DummyBundle bundle
    ) {
        return new ObjectMapper().readTree(new String(bundle.manifestBytes(), StandardCharsets.UTF_8))
            .get("identityDigest")
            .stringValue();
    }

    private static byte[] weightsWithDataType(
        String dataType,
        final int byteCount
    ) {
        byte[] header = ("{\"hurdle_coefficients\":{\"dtype\":\"%s\",\"shape\":[1],\"data_offsets\":[0,%d]}}"
            .formatted(dataType, byteCount)).getBytes(StandardCharsets.UTF_8);
        ByteBuffer file = ByteBuffer.allocate(8 + header.length + byteCount).order(ByteOrder.LITTLE_ENDIAN);
        file.putLong(header.length);
        file.put(header);
        return file.array();
    }

    private static List<String> swappedFeatureNames() {
        List<String> names = new ArrayList<>(SeatForecastDesignMatrix.COLUMN_NAMES);
        names.set(1, SeatForecastDesignMatrix.COLUMN_NAMES.get(2));
        names.set(2, SeatForecastDesignMatrix.COLUMN_NAMES.get(1));
        return names;
    }

    private static List<String> renamedFeatureNames() {
        List<String> names = new ArrayList<>(SeatForecastDesignMatrix.COLUMN_NAMES);
        names.set(0, "intercept");
        return names;
    }

    private static String read(
        Path path
    ) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    /** 뜻은 그대로 두고 항목 하나의 자리만 옮긴다. 값을 다시 쓰지 않아 빈칸과 수의 모양이 그대로다. */
    private static byte[] movedToEnd(
        String manifest,
        String field
    ) {
        String without = manifest.replace(field + ",", "");
        return (without.substring(0, without.length() - 1) + "," + field + "}")
            .getBytes(StandardCharsets.UTF_8);
    }

    private Runnable loading(
        DummyBundle bundle
    ) {
        return () -> BundleLoader.load(bundle.writeTo(directory));
    }

    private void assertRejectedBy(
        BundleCheck check,
        Runnable loading
    ) {
        assertThatThrownBy(loading::run)
            .isInstanceOf(BundleRejectedException.class)
            .hasMessageContaining(check.name());
    }
}
