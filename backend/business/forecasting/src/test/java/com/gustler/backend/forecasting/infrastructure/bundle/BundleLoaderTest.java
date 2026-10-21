package com.gustler.backend.forecasting.infrastructure.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.gustler.backend.forecasting.domain.model.SeatForecastDesignMatrix;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

/**
 * 잘못된 계수 묶음을 검사가 실제로 거절하는지 확인한다.
 *
 * <p>이 티켓에서 중요한 검증 대상이다. 잘못된 계수 묶음으로 생성한 예보는 값은 정상처럼 보이지만
 * 의미가 다르다. 화면이나 로그로 구분할 수 없으므로 검사에서 거절되는지 테스트로 보장해야 한다.
 */
class BundleLoaderTest {
    private static final List<String> EIGHT_ROUTES =
        List.of("1650", "3330", "9007", "9300", "6011", "3000", "5600", "3500");

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
    void 계산_버전_40자는_허용하고_41자는_거절한다() {
        // when
        BundleFiles accepted = DummyBundle.valid()
            .put("featureContractVersion", "v".repeat(40)).writeTo(directory);

        // then
        assertThat(BundleLoader.load(accepted).manifest().featureContractVersion())
            .isEqualTo("v".repeat(40));

        assertRejectedBy(BundleCheck.DEPLOYMENT_IDENTIFIER_LENGTH,
            loading(DummyBundle.valid().put("featureContractVersion", "v".repeat(41))));
    }

    @Test
    void 출시_식별자_80자는_허용하고_81자는_거절한다() {
        // when
        BundleFiles accepted = DummyBundle.valid().put("releaseId", "r".repeat(80)).writeTo(directory);

        // then
        assertThat(BundleLoader.load(accepted).manifest().releaseId()).isEqualTo("r".repeat(80));

        assertRejectedBy(BundleCheck.DEPLOYMENT_IDENTIFIER_LENGTH,
            loading(DummyBundle.valid().put("releaseId", "r".repeat(81))));
    }

    @Test
    void 식별자_길이는_유니코드_문자_수로_검사한다() {
        // when
        String supplementaryCharacter = new String(Character.toChars(0x1F68C));
        String acceptedVersion = supplementaryCharacter.repeat(40);
        BundleFiles accepted = DummyBundle.valid()
            .put("featureContractVersion", acceptedVersion).writeTo(directory);

        // then
        assertThat(BundleLoader.load(accepted).manifest().featureContractVersion())
            .isEqualTo(acceptedVersion);

        assertRejectedBy(BundleCheck.DEPLOYMENT_IDENTIFIER_LENGTH,
            loading(DummyBundle.valid().put("featureContractVersion", supplementaryCharacter.repeat(41))));
    }

    @Test
    void 학습_종료_시각을_읽을_수_없으면_로딩에서_거절한다() {
        // when
        Runnable loadAction = loading(DummyBundle.valid().put("dataThrough", "2026-08-30 14:59:56"));
        Throwable loadFailure = catchThrowable(loadAction::run);

        // then
        assertRejectedBy(BundleCheck.DATA_THROUGH, loadFailure);
    }

    @Test
    void 설명_파일이_symlink_면_거절한다() throws IOException {
        // given
        BundleFiles files = DummyBundle.valid().writeTo(directory);
        Path moved = directory.resolve("real-manifest.json");
        Files.move(files.manifest(), moved);
        Files.createSymbolicLink(files.manifest(), moved);

        // when
        Runnable loadAction2 = () -> BundleLoader.load(files);
        Throwable loadFailure2 = catchThrowable(loadAction2::run);

        // then
        assertRejectedBy(BundleCheck.MANIFEST_IS_REGULAR_FILE, loadFailure2);
    }

    @Test
    void 계수_파일이_symlink_면_거절한다() throws IOException {
        // given
        BundleFiles files = DummyBundle.valid().writeTo(directory);
        Path moved = directory.resolve("real-weights.safetensors");
        Files.move(files.weights(), moved);
        Files.createSymbolicLink(files.weights(), moved);

        // when
        Runnable loadAction3 = () -> BundleLoader.load(files);
        Throwable loadFailure3 = catchThrowable(loadAction3::run);

        // then
        assertRejectedBy(BundleCheck.WEIGHTS_IS_REGULAR_FILE, loadFailure3);
    }

    @Test
    void 설명_파일이_없으면_거절한다() {
        // when
        Runnable loadAction4 = () -> BundleLoader.load(BundleFiles.under(directory));
        Throwable loadFailure4 = catchThrowable(loadAction4::run);

        // then
        assertRejectedBy(BundleCheck.MANIFEST_IS_REGULAR_FILE, loadFailure4);
    }

    @Test
    void 설명_파일이_UTF_8_이_아니면_거절한다() throws IOException {
        // given
        DummyBundle.valid().writeTo(directory);
        Files.write(directory.resolve(BundleFiles.MANIFEST_NAME), new byte[] {(byte) 0xff, (byte) 0xfe});

        // when
        Runnable loadAction5 = () -> BundleLoader.load(BundleFiles.under(directory));
        Throwable loadFailure5 = catchThrowable(loadAction5::run);

        // then
        assertRejectedBy(BundleCheck.MANIFEST_IS_UTF8_JSON, loadFailure5);
    }

    @Test
    void 묶음이_놓인_자리가_symlink_면_거절한다() throws IOException {
        // given
        // 배포 경로의 current 처럼 디렉터리 자체가 심볼릭 링크다
        Path released = Files.createDirectory(directory.resolve("release-0001"));
        DummyBundle.valid().writeTo(released);
        Path linked = Files.createSymbolicLink(directory.resolve("current"), released);

        // when
        Runnable loadAction6 = () -> BundleLoader.load(BundleFiles.under(linked));
        Throwable loadFailure6 = catchThrowable(loadAction6::run);

        // then
        assertRejectedBy(BundleCheck.BUNDLE_DIRECTORY_IS_NOT_SYMBOLIC_LINK, loadFailure6);
    }

    @Test
    void 설명_파일에_빈칸이_들어_있으면_거절한다() throws IOException {
        // given
        // 뜻은 같은데 항목 사이에 빈칸을 넣어 다시 쓴다
        BundleFiles files = DummyBundle.valid().writeTo(directory);
        Files.write(files.manifest(),
            new String(Files.readAllBytes(files.manifest()), StandardCharsets.UTF_8)
                .replace(",\"", ", \"")
                .getBytes(StandardCharsets.UTF_8));

        // when
        Runnable loadAction7 = () -> BundleLoader.load(files);
        Throwable loadFailure7 = catchThrowable(loadAction7::run);

        // then
        assertRejectedBy(BundleCheck.MANIFEST_IS_CANONICAL_JSON, loadFailure7);
    }

    @Test
    void 설명_파일의_항목_순서가_이름순이_아니면_거절한다() throws IOException {
        // given
        // 빈칸은 그대로 두고 맨 앞 항목 하나만 맨 뒤로 옮긴다
        BundleFiles files = DummyBundle.valid().writeTo(directory);
        Files.write(files.manifest(), movedToEnd(
            read(files.manifest()),
                "\"bundleSchemaVersion\":\"%s\"".formatted(DummyBundle.BUNDLE_SCHEMA_VERSION)));

        // when
        Runnable loadAction8 = () -> BundleLoader.load(files);
        Throwable loadFailure8 = catchThrowable(loadAction8::run);

        // then
        assertRejectedBy(BundleCheck.MANIFEST_IS_CANONICAL_JSON, loadFailure8);
    }

    @Test
    void 설명_파일_안쪽_항목의_순서가_이름순이_아니면_거절한다() throws IOException {
        // given
        // 배열 선언 하나에서 자료형과 크기의 순서를 맞바꾼다
        BundleFiles files = DummyBundle.valid().writeTo(directory);
        Files.write(files.manifest(), read(files.manifest()).replaceFirst(
            "\\{\"dtype\":(\"[A-Z0-9]+\"),\"shape\":(\\[[0-9,]+])}",
        "{\"shape\":$2,\"dtype\":$1}").getBytes(StandardCharsets.UTF_8));

        // when
        Runnable loadAction9 = () -> BundleLoader.load(files);
        Throwable loadFailure9 = catchThrowable(loadAction9::run);

        // then
        assertRejectedBy(BundleCheck.MANIFEST_IS_CANONICAL_JSON, loadFailure9);
    }

    @Test
    void 대조_사례에_특징_벡터가_없으면_거절한다() {
        // given
        Map<String, Object> golden = new LinkedHashMap<>(DummyBundle.valid().goldenVectorFrom());
        golden.remove("featureVector");

        // when
        Runnable loadAction10 = loading(DummyBundle.valid()
                .put("goldenVector", golden)
                .put("goldenVectorDigest", "0".repeat(64)));
        Throwable loadFailure10 = catchThrowable(loadAction10::run);

        // then
        assertRejectedBy(BundleCheck.MANIFEST_FIELD_TYPE, loadFailure10);
    }

    @Test
    void 설명_파일이_수를_문자열로_적으면_거절한다() {
        // when
        Runnable loadAction11 = loading(DummyBundle.valid().put("horizonStops", List.of("1", "2", "3")));
        Throwable loadFailure11 = catchThrowable(loadAction11::run);

        // then
        assertRejectedBy(BundleCheck.MANIFEST_FIELD_TYPE, loadFailure11);
    }

    @Test
    void 설명_파일에_모르는_항목이_있으면_거절한다() {
        // when
        Runnable loadAction12 = loading(DummyBundle.valid().put("leadSeconds", List.of(105.022, 90.104)));
        Throwable loadFailure12 = catchThrowable(loadAction12::run);

        // then
        assertRejectedBy(BundleCheck.MANIFEST_HAS_NO_UNKNOWN_FIELD, loadFailure12);
    }

    @Test
    void 계수_묶음_버전이_다르면_거절한다() {
        // when
        Runnable loadAction13 = loading(DummyBundle.valid().put("bundleSchemaVersion", "a18-live-bundle-v999"));
        Throwable loadFailure13 = catchThrowable(loadAction13::run);

        // then
        assertRejectedBy(BundleCheck.BUNDLE_SCHEMA_VERSION, loadFailure13);
    }

    @Test
    void 모델_버전이_다르면_거절한다() {
        // when
        Runnable loadAction14 = loading(DummyBundle.valid().put("modelVersion", "seat-distribution-a19-v1"));
        Throwable loadFailure14 = catchThrowable(loadAction14::run);

        // then
        assertRejectedBy(BundleCheck.MODEL_VERSION, loadFailure14);
    }

    @Test
    void 입력_규칙_버전이_비어_있으면_거절한다() {
        // when
        Runnable loadAction15 = loading(DummyBundle.valid().put("featureContractVersion", " "));
        Throwable loadFailure15 = catchThrowable(loadAction15::run);

        // then
        assertRejectedBy(BundleCheck.FEATURE_CONTRACT_VERSION, loadFailure15);
    }

    @Test
    void 노선_참조_요약값이_64자리가_아니면_거절한다() {
        // when
        Runnable loadAction16 = loading(DummyBundle.valid().put("routeReferenceDigest", "abc"));
        Throwable loadFailure16 = catchThrowable(loadAction16::run);

        // then
        assertRejectedBy(BundleCheck.ROUTE_REFERENCE, loadFailure16);
    }

    @Test
    void 노선_순서가_뒤집히면_거절한다() {
        // when
        Runnable loadAction17 = loading(DummyBundle.valid().put("routes", List.of("3330", "1650")));
        Throwable loadFailure17 = catchThrowable(loadAction17::run);

        // then
        assertRejectedBy(BundleCheck.ROUTE_ORDER, loadFailure17);
    }

    @Test
    void 여덟_노선_묶음도_검사를_통과하고_새_노선의_계수를_꺼낼_수_있다() {
        // when
        CoefficientBundle actual = BundleLoader.load(DummyBundle.withRoutes(EIGHT_ROUTES).writeTo(directory));

        // then
        assertThat(actual.at("3500", 12).featureCount()).isEqualTo(DummyBundle.FEATURE_COUNT);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "234000050", "204000057", "204000070", "234000886", "233000392", "227000038", "228000184", "234000051"
    })
    void 여덟_노선_묶음을_올리면_모든_수집_노선이_예보_범위_안이다(String sourceRouteId) {
        // when
        final boolean actual = DummyBundle.withRoutes(EIGHT_ROUTES).loadAt(directory).scope()
            .coversSourceRoute(sourceRouteId);

        // then
        assertThat(actual).isTrue();
    }

    @Test
    void 스무_노선_번들은_추가_노선을_지원하고_여덟_노선_번들은_지원하지_않는다() {
        // given
        List<String> routes = List.of("1650", "3330", "9007", "9300", "6011", "3000", "5600", "3500",
            "1151", "9000", "1500-2", "8100", "1150", "G8110", "6801", "6003", "3100", "1101", "7007-1", "4000");

        // when
        LoadedBundle twenty = DummyBundle.withRoutes(routes).loadAt(directory);
        LoadedBundle eight = DummyBundle.withRoutes(EIGHT_ROUTES).loadAt(directory);

        // then
        assertThat(twenty.scope().coversSourceRoute("200000314")).isTrue();
        assertThat(eight.scope().coversSourceRoute("200000314")).isFalse();
    }

    @Test
    void 여덟_노선_묶음의_순서가_계약과_다르면_거절한다() {
        // when
        Runnable loadAction18 = loading(DummyBundle.withRoutes(
                List.of("1650", "3330", "9300", "9007", "6011", "3000", "5600", "3500")));
        Throwable loadFailure18 = catchThrowable(loadAction18::run);

        // then
        assertRejectedBy(BundleCheck.ROUTE_ORDER, loadFailure18);
    }

    @Test
    void 계약에_없는_노선_목록은_거절한다() {
        // when
        Runnable loadAction19 = loading(DummyBundle.withRoutes(List.of("1650", "3330", "9007")));
        Throwable loadFailure19 = catchThrowable(loadAction19::run);

        // then
        assertRejectedBy(BundleCheck.ROUTE_ORDER, loadFailure19);
    }

    @Test
    void 예보_거리가_1부터_12까지가_아니면_거절한다() {
        // when
        Runnable loadAction20 = loading(DummyBundle.valid().put("horizonStops", List.of(1, 2, 4, 8, 12)));
        Throwable loadFailure20 = catchThrowable(loadAction20::run);

        // then
        assertRejectedBy(BundleCheck.HORIZON_STOPS, loadFailure20);
    }

    @Test
    void 입력값_이름의_순서가_바뀌면_거절한다() {
        // given
        // 이름도 개수도 같은데 두 열의 자리만 바꾼다

        // when
        Runnable loadAction21 = loading(DummyBundle.valid().put("featureNames", swappedFeatureNames()));
        Throwable loadFailure21 = catchThrowable(loadAction21::run);

        // then
        assertRejectedBy(BundleCheck.FEATURE_NAMES, loadFailure21);
    }

    @Test
    void 입력값_이름이_우리_설계행렬에_없는_것이면_거절한다() {
        // when
        Runnable loadAction22 = loading(DummyBundle.valid().put("featureNames", renamedFeatureNames()));
        Throwable loadFailure22 = catchThrowable(loadAction22::run);

        // then
        assertRejectedBy(BundleCheck.FEATURE_NAMES, loadFailure22);
    }

    @Test
    void 계수_파일에_읽을_줄_모르는_배열이_있으면_거절한다() {
        // given
        // v4-1 정본에서 동결 셀 통계를 가져오지 못하도록 규정했다
        DummyBundle given = DummyBundle.valid()
            .withTensor("cell_values", TensorDataType.FLOAT64, new int[] {2, 2}, new double[4]);

        // when
        Runnable loadAction23 = loading(given);
        Throwable loadFailure23 = catchThrowable(loadAction23::run);

        // then
        assertRejectedBy(BundleCheck.WEIGHTS_HAS_NO_UNKNOWN_TENSOR, loadFailure23);
    }

    @Test
    void 좌석_분포에_필요한_배열_하나가_없으면_거절한다() {
        // when
        Runnable loadAction24 = loading(DummyBundle.valid().withoutTensor(BundleTensor.ANCHOR));
        Throwable loadFailure24 = catchThrowable(loadAction24::run);

        // then
        assertRejectedBy(BundleCheck.REQUIRED_TENSORS_PRESENT, loadFailure24);
    }

    @Test
    void 중심_좌석_계수가_절편과_기울기_둘이_아니면_거절한다() {
        // given
        // 중심 좌석 계수는 절편과 기울기 둘인데 셋으로 담는다
        DummyBundle given = DummyBundle.valid().withTensor(
            BundleTensor.ANCHOR.tensorName(), TensorDataType.FLOAT64,
            new int[] {2, 12, 3}, new double[2 * 12 * 3]);

        // when
        Runnable loadAction25 = loading(given);
        Throwable loadFailure25 = catchThrowable(loadAction25::run);

        // then
        assertRejectedBy(BundleCheck.TENSOR_SHAPE_AND_DATA_TYPE, loadFailure25);
    }

    @Test
    void 계수를_자릿수가_모자란_형식으로_담으면_읽지_않는다() {
        // given
        // 자릿수가 모자라 같은 입력에서 다른 확률이 나온다
        byte[] given = weightsWithDataType("F32", 4);

        // when
        Throwable caughtFailure = catchThrowable(() -> SafetensorsFile.of(given));

        // then
        assertThat(caughtFailure)
            .isNotNull()
            .isInstanceOf(BundleRejectedException.class)
            .hasMessageContaining("F32");
    }

    @Test
    void 계수에_NaN_이_있으면_거절한다() {
        // when
        Runnable loadAction26 = loading(DummyBundle.valid().withTensorValue(BundleTensor.FULL_CHANCE, 7, Double.NaN));
        Throwable loadFailure26 = catchThrowable(loadAction26::run);

        // then
        assertRejectedBy(BundleCheck.COEFFICIENTS_ARE_FINITE, loadFailure26);
    }

    @Test
    void 계수에_Infinity_가_있으면_거절한다() {
        // when
        Runnable loadAction27 = loading(DummyBundle.valid()
                .withTensorValue(BundleTensor.FULL_CHANCE, 0, Double.POSITIVE_INFINITY));
        Throwable loadFailure27 = catchThrowable(loadAction27::run);

        // then
        assertRejectedBy(BundleCheck.COEFFICIENTS_ARE_FINITE, loadFailure27);
    }

    @Test
    void 학습됐는지_표시하는_값이_0도_1도_아니면_거절한다() {
        // when
        Runnable loadAction28 = loading(DummyBundle.valid().withTensorValue(BundleTensor.RESIDUAL_BIN_FITTED, 3, 2.0));
        Throwable loadFailure28 = catchThrowable(loadAction28::run);

        // then
        assertRejectedBy(BundleCheck.FITTED_FLAGS_ARE_ZERO_OR_ONE, loadFailure28);
    }

    @Test
    void 계수_파일_요약값이_다르면_거절한다() {
        // when
        Runnable loadAction29 = loading(DummyBundle.valid().put("weightsDigest", "f".repeat(64)));
        Throwable loadFailure29 = catchThrowable(loadAction29::run);

        // then
        assertRejectedBy(BundleCheck.WEIGHTS_DIGEST, loadFailure29);
    }

    @Test
    void 대조_사례_요약값이_사례_내용에서_나온_값이_아니면_거절한다() {
        // given
        // 사례는 그대로인데 요약값만 아무 수로 적어 둔다

        // when
        Runnable loadAction30 = loading(DummyBundle.valid().put("goldenVectorDigest", "0".repeat(64)));
        Throwable loadFailure30 = catchThrowable(loadAction30::run);

        // then
        assertRejectedBy(BundleCheck.GOLDEN_VECTOR, loadFailure30);
    }

    @Test
    void 대조_사례를_바꾸고_요약값을_안_고치면_거절한다() {
        // given
        // 기대 잔여석만 한 석 어긋내고 요약값은 그대로 둔다
        DummyBundle given = DummyBundle.valid();
        Map<String, Object> golden = new LinkedHashMap<>(given.goldenVectorFrom());
        String unchanged = goldenVectorDigestOf(DummyBundle.valid());
        golden.put("expectedSeats", ((double) golden.get("expectedSeats")) + 1.0);

        // when
        Runnable loadAction31 = loading(
            given.put("goldenVector", golden).put("goldenVectorDigest", unchanged));
        Throwable loadFailure31 = catchThrowable(loadAction31::run);

        // then
        assertRejectedBy(BundleCheck.GOLDEN_VECTOR, loadFailure31);
    }

    @Test
    void 출시_식별자를_묶은_요약값이_다르면_거절한다() {
        // when
        Runnable loadAction32 = loading(DummyBundle.valid().put("identityDigest", "0".repeat(64)));
        Throwable loadFailure32 = catchThrowable(loadAction32::run);

        // then
        assertRejectedBy(BundleCheck.RELEASE_IDENTITY_DIGEST, loadFailure32);
    }

    /**
     * 계수 파일은 한 글자도 바뀌지 않고 입력 생성 규칙만 바뀐 묶음이다. 계수 요약값 검사만으로는
     * 거절되지 않는다. 식별 정보 요약값에 입력 규칙 버전도 포함되므로 이 검사에서 거절된다.
     */
    @Test
    void 계수는_그대로인데_입력_규칙_버전만_바뀌면_거절한다() {
        // given
        // 기존 묶음의 식별 정보 요약값은 유지하고 입력 규칙 버전만 바꾼다
        String unchanged = identityDigestOf(DummyBundle.valid());
        DummyBundle given = DummyBundle.valid()
            .put("featureContractVersion", "seat-feature-contract-v5")
            .put("identityDigest", unchanged);

        // when
        Runnable loadAction33 = loading(given);
        Throwable loadFailure33 = catchThrowable(loadAction33::run);

        // then
        assertRejectedBy(BundleCheck.RELEASE_IDENTITY_DIGEST, loadFailure33);
    }

    /**
     * 열 개수만 맞춰 적재하면 설계행렬과 다른 입력으로 학습한 계수가 사용된다. 값은 정상처럼
     * 보이지만 의미가 다른 예보가 생성되므로 열의 개수뿐 아니라 이름과 순서를 검사한다.
     */
    @Test
    void 입력값이_24개인_계수_묶음은_거절한다() {
        // when
        Runnable loadAction34 = loading(DummyBundle.withFeatureCount(24));
        Throwable loadFailure34 = catchThrowable(loadAction34::run);

        // then
        assertRejectedBy(BundleCheck.FEATURE_NAMES, loadFailure34);
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

    /** 의미는 유지하고 항목 하나의 위치만 옮긴다. 값을 다시 쓰지 않아 빈칸과 숫자 표기를 보존한다. */
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

    private void assertRejectedBy(
        BundleCheck check,
        Throwable failure
    ) {
        assertThat(failure).isNotNull()
            .isInstanceOf(BundleRejectedException.class)
            .hasMessageContaining(check.name());
    }
}
