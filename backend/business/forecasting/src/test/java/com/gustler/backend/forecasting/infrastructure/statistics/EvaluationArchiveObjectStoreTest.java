package com.gustler.backend.forecasting.infrastructure.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.gustler.backend.forecasting.api.evaluation.EvaluationArchivePolicy;
import com.gustler.backend.forecasting.application.evaluation.EvaluationArchiveStorage.Verification;
import com.gustler.backend.forecasting.application.statistics.StatisticsArchiveReader;
import com.gustler.backend.forecasting.application.statistics.StatisticsArchiveReader.Reference;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.Key;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.Row;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.State;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/** AWS CLI 경계 대체 시험. 실제 S3 왕복 검증과 구분한다. */
class EvaluationArchiveObjectStoreTest {
    private static final String ORIGINAL = """
        {"vehicle_observation_id":7,"target_stop_order":9,"route_version_id":3,"scoring_state":"LOST",\
        "arrival_observation_id":null,"seats_on_arrival":null,"scored_at":"2026-10-08T10:00:00+00:00",\
        "arrived_at":null,"arrival_route_version_id":null,"arrival_vehicle_id":null,"arrival_stop_order":null,\
        "arrival_running_state":null,"arrival_remaining_seats":null,"arrival_seat_unknown_reason":null,\
        "arrival_vehicle_trip_key":null,"arrival_quality_direction":null}\
        """;
    private final MemoryObjects objects = new MemoryObjects();
    private final EvaluationArchiveBatch batch = new EvaluationArchiveBatch(UUID.randomUUID(), 3, 1,
        UUID.randomUUID(), Instant.now().plusSeconds(300), State.RESERVED, 1, null);
    @TempDir
    Path directory;

    @Test
    void 원본의_모든_열과_빈값을_보존하고_다시_받은_내용을_확인한다() throws Exception {
        // given
        EvaluationArchiveObjectStore store = store(1024 * 1024);

        // when
        Verification verified = store.storeAndVerify(batch, List.of(row(ORIGINAL)));

        // then
        assertThat(objects.values).hasSize(2);
        String copied = new String(objects.values.get(prefix() + "evaluations.jsonl"), StandardCharsets.UTF_8);
        assertThat(copied).isEqualTo(ORIGINAL + "\n");
        assertThat(JsonMapper.builder().build().readTree(copied).size()).isEqualTo(16);
        assertThat(verified.manifestSha256()).isEqualTo(
            EvaluationArchiveFiles.digest(objects.values.get(prefix() + "manifest.json")));
        assertThat(objects.actions).containsExactly("put:evaluations.jsonl", "get:evaluations.jsonl",
            "put:manifest.json", "get:manifest.json");
        assertDirectoryEmpty();
    }

    @Test
    void 같은_묶음을_재시도하면_저장된_내용을_검증하고_같은_결과를_반환한다() {
        // given
        Verification first = store(1024 * 1024).storeAndVerify(batch, List.of(row(ORIGINAL)));

        // when
        Verification repeated = store(1024 * 1024).storeAndVerify(batch, List.of(row(ORIGINAL)));

        // then
        assertThat(repeated).isEqualTo(first);
        assertThat(objects.values).hasSize(2);
    }

    @Test
    void 자료만_저장하고_중단된_작업도_원본을_덮어쓰지_않고_이어간다() {
        // given
        objects.failManifest = true;
        assertThatThrownBy(() -> store(1024 * 1024).storeAndVerify(batch, List.of(row(ORIGINAL))))
            .isInstanceOf(IllegalStateException.class);
        assertThat(objects.values).hasSize(1);
        byte[] first = objects.values.get(prefix() + "evaluations.jsonl").clone();
        objects.failManifest = false;

        // when
        store(1024 * 1024).storeAndVerify(batch, List.of(row(ORIGINAL)));

        // then
        assertThat(objects.values.get(prefix() + "evaluations.jsonl")).isEqualTo(first);
        assertThat(objects.values).hasSize(2);
    }

    @Test
    void 이미_저장된_객체가_다르면_성공으로_기록하지_않는다() throws Exception {
        // given
        byte[] different = "다른 원본".getBytes(StandardCharsets.UTF_8);
        objects.values.put(prefix() + "evaluations.jsonl", different);

        // when
        Throwable caughtFailure = catchThrowable(
            () -> store(1024 * 1024).storeAndVerify(batch, List.of(row(ORIGINAL))));

        // then
        assertThat(caughtFailure)
            .isNotNull()
            .isInstanceOf(IllegalStateException.class);
        assertThat(objects.values.get(prefix() + "evaluations.jsonl")).isEqualTo(different);
        assertThat(objects.values).doesNotContainKey(prefix() + "manifest.json");
        assertDirectoryEmpty();
    }

    @Test
    void 업로드_뒤_다시_받은_자료가_바뀌면_설명_파일을_게시하지_않는다() {
        // given
        objects.corruptDownloads = true;

        // when
        Throwable caughtFailure = catchThrowable(
            () -> store(1024 * 1024).storeAndVerify(batch, List.of(row(ORIGINAL))));

        // then
        assertThat(caughtFailure)
            .isNotNull()
            .isInstanceOf(IllegalStateException.class);
        assertThat(objects.values).doesNotContainKey(prefix() + "manifest.json");
    }

    @Test
    void 파일_한도를_넘으면_외부_저장소에_쓰지_않는다() throws Exception {
        // given
        EvaluationArchiveObjectStore store = store(10);

        // when
        Throwable caughtFailure = catchThrowable(() -> store.storeAndVerify(batch, List.of(row(ORIGINAL))));

        // then
        assertThat(caughtFailure)
            .isNotNull()
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(objects.actions).isEmpty();
        assertDirectoryEmpty();
    }

    @Test
    void 일부_열이_빠진_가공_자료를_정산_원본으로_보관하지_않는다() {
        // given
        String incomplete = ORIGINAL.replace(",\"arrival_quality_direction\":null", "");

        // when
        Throwable caughtFailure = catchThrowable(
            () -> store(1024 * 1024).storeAndVerify(batch, List.of(row(incomplete))));

        // then
        assertThat(caughtFailure)
            .isNotNull()
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(objects.actions).isEmpty();
    }

    @Test
    void 원본의_키와_예약한_키가_다르면_전송하지_않는다() {
        // given
        String changedKey = ORIGINAL.replace("\"vehicle_observation_id\":7", "\"vehicle_observation_id\":8");

        // when
        Throwable caughtFailure = catchThrowable(
            () -> store(1024 * 1024).storeAndVerify(batch, List.of(row(changedKey))));

        // then
        assertThat(caughtFailure)
            .isNotNull()
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(objects.actions).isEmpty();
    }

    @Test
    void 같은_원본_키가_반복되면_건수가_맞아도_전송하지_않는다() {
        // given
        EvaluationArchiveBatch duplicate =
            new EvaluationArchiveBatch(batch.id(), 3, 1, batch.leaseToken(), batch.leaseUntil(), State.RESERVED, 2,
                null);

        // when
        Throwable caughtFailure = catchThrowable(
            () -> store(1024 * 1024).storeAndVerify(duplicate, List.of(row(ORIGINAL), row(ORIGINAL))));

        // then
        assertThat(caughtFailure)
            .isNotNull()
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(objects.actions).isEmpty();
    }

    @Test
    void 기존_객체에_예상하지_않은_뒷부분이_있으면_같은_자료로_인정하지_않는다() {
        // given
        objects.values.put(prefix() + "evaluations.jsonl", (ORIGINAL + "\n다른 행").getBytes(StandardCharsets.UTF_8));

        // when
        Throwable caughtFailure = catchThrowable(
            () -> store(1024 * 1024).storeAndVerify(batch, List.of(row(ORIGINAL))));

        // then
        assertThat(caughtFailure)
            .isNotNull()
            .isInstanceOf(IllegalStateException.class);
        assertThat(objects.values).doesNotContainKey(prefix() + "manifest.json");
    }

    @Test
    void 작업을_이어받아_소유권이_바뀌어도_같은_자료의_설명_파일은_달라지지_않는다() {
        // given
        Verification first = store(1024 * 1024).storeAndVerify(batch, List.of(row(ORIGINAL)));
        EvaluationArchiveBatch resumed =
            new EvaluationArchiveBatch(batch.id(), batch.routeVersionId(), batch.qualityRevision(),
                UUID.randomUUID(), Instant.now().plusSeconds(600), State.RESERVED, batch.rowCount(), null);

        // when
        Verification second = store(1024 * 1024).storeAndVerify(resumed, List.of(row(ORIGINAL)));

        // then
        assertThat(second).isEqualTo(first);
    }

    @Test
    void 실제_도착_근거가_있는_정산도_숫자와_문자열을_변형하지_않는다() {
        // given
        String settled = ORIGINAL.replace("\"LOST\"", "\"SETTLED\"")
            .replace("\"arrival_observation_id\":null", "\"arrival_observation_id\":15")
            .replace("\"seats_on_arrival\":null", "\"seats_on_arrival\":0")
            .replace("\"arrived_at\":null", "\"arrived_at\":\"2026-10-08T09:59:00.123456+00:00\"")
            .replace("\"arrival_route_version_id\":null", "\"arrival_route_version_id\":3")
            .replace("\"arrival_vehicle_id\":null", "\"arrival_vehicle_id\":\"서울 차량\"")
            .replace("\"arrival_stop_order\":null", "\"arrival_stop_order\":9")
            .replace("\"arrival_running_state\":null", "\"arrival_running_state\":2")
            .replace("\"arrival_remaining_seats\":null", "\"arrival_remaining_seats\":0")
            .replace("\"arrival_vehicle_trip_key\":null", "\"arrival_vehicle_trip_key\":\"편도 식별자\"")
            .replace("\"arrival_quality_direction\":null", "\"arrival_quality_direction\":1");

        // when
        store(1024 * 1024).storeAndVerify(batch, List.of(row(settled)));

        // then
        assertThat(new String(objects.values.get(prefix() + "evaluations.jsonl"), StandardCharsets.UTF_8))
            .isEqualTo(settled + "\n");
    }

    @Test
    void 품질_검사로_제외된_정산도_파일에_보관하고_같은_내용으로_다시_읽는다() throws Exception {
        // given
        String excluded = ORIGINAL.replace("\"LOST\"", "\"QUALITY_EXCLUDED\"");
        EvaluationArchiveObjectStore storage = store(1024 * 1024);

        // when
        Verification saved = storage.storeAndVerify(batch, List.of(row(excluded)));
        EvaluationArchiveBatch verified =
            new EvaluationArchiveBatch(batch.id(), batch.routeVersionId(), batch.qualityRevision(),
                batch.leaseToken(), batch.leaseUntil(), State.VERIFIED, batch.rowCount(), saved.manifestSha256());
        List<Row> restored = storage.readOriginal(verified);

        // then
        assertThat(restored).containsExactly(row(excluded));
        assertDirectoryEmpty();
    }

    @Test
    void 아직_정산하지_않은_대기는_완료_이력으로_보관하지_않는다() {
        // given
        String pending = ORIGINAL.replace("\"LOST\"", "\"PENDING\"");

        // when
        Throwable caughtFailure = catchThrowable(() -> store(1024 * 1024).storeAndVerify(batch, List.of(row(pending))));

        // then
        assertThat(caughtFailure)
            .isNotNull()
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(objects.actions).isEmpty();
    }

    private EvaluationArchiveObjectStore store(final long maxBytes) {
        return new EvaluationArchiveObjectStore(directory, maxBytes, objects::run);
    }

    @Test
    void 저장_당시_검증_기록으로_다시_읽어_같은_정산을_반환한다() throws Exception {
        // given
        Verification stored = store(1024 * 1024).storeAndVerify(batch, List.of(row(ORIGINAL)));
        Reference reference = new Reference(
            batch.id(), batch.routeVersionId(), batch.qualityRevision(), batch.rowCount(), stored.manifestSha256());

        // when
        List<StatisticsArchiveReader.Row> rows = store(1024 * 1024).read(reference);

        // then
        assertThat(rows).containsExactly(new StatisticsArchiveReader.Row(
            new StatisticsArchiveReader.Key(7, 9),
            ORIGINAL, EvaluationArchiveFiles.digest(ORIGINAL.getBytes(StandardCharsets.UTF_8))));
        assertDirectoryEmpty();
    }

    @Test
    void 삭제와_복원을_위한_원본도_저장한_모든_열과_검증값을_그대로_반환한다() throws Exception {
        // given
        Verification saved = store(1024 * 1024).storeAndVerify(batch, List.of(row(ORIGINAL)));
        EvaluationArchiveBatch verified =
            new EvaluationArchiveBatch(batch.id(), batch.routeVersionId(), batch.qualityRevision(),
                batch.leaseToken(), batch.leaseUntil(), State.VERIFIED, batch.rowCount(), saved.manifestSha256());

        // when
        List<Row> rows = store(1024 * 1024).readOriginal(verified);

        // then
        assertThat(rows).containsExactly(row(ORIGINAL));
        assertDirectoryEmpty();
    }

    @Test
    void 저장_완료_뒤_원본_객체가_바뀌면_재집계_입력으로_사용하지_않는다() {
        // given
        Verification stored = store(1024 * 1024).storeAndVerify(batch, List.of(row(ORIGINAL)));
        Reference reference = new Reference(
            batch.id(), batch.routeVersionId(), batch.qualityRevision(), batch.rowCount(), stored.manifestSha256());
        objects.values.get(prefix() + "evaluations.jsonl")[0] ^= 1;

        // when
        Throwable caughtFailure = catchThrowable(() -> store(1024 * 1024).read(reference));

        // then
        assertThat(caughtFailure)
            .isNotNull().isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 설명_파일이_다른_묶음으로_바뀌면_원본을_읽지_않는다() {
        // given
        Verification stored = store(1024 * 1024).storeAndVerify(batch, List.of(row(ORIGINAL)));
        Reference reference = new Reference(
            batch.id(), batch.routeVersionId(), batch.qualityRevision(), batch.rowCount(), stored.manifestSha256());
        objects.values.get(prefix() + "manifest.json")[0] ^= 1;
        objects.actions.clear();

        // when
        Throwable caughtFailure = catchThrowable(() -> store(1024 * 1024).read(reference));

        // then
        assertThat(caughtFailure)
            .isNotNull().isInstanceOf(IllegalArgumentException.class);
        assertThat(objects.actions).containsExactly("get:manifest.json");
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 1000})
    void 확대된_묶음을_파일로_검증하고_통계와_복원에서_다시_읽는다(final int size) throws Exception {
        // given
        EvaluationArchiveBatch large = new EvaluationArchiveBatch(batch.id(), 3, 1, batch.leaseToken(),
            batch.leaseUntil(), State.RESERVED, size, null);
        List<Row> rows = IntStream.rangeClosed(1, size)
            .mapToObj(index -> {
                String json = ORIGINAL.replace("\"vehicle_observation_id\":7", "\"vehicle_observation_id\":" + index);
                return new Row(new Key(index, 9), json,
                    EvaluationArchiveFiles.digest(json.getBytes(StandardCharsets.UTF_8)));
            })
            .toList();
        EvaluationArchiveObjectStore storage = store(4 * 1024 * 1024);

        // when
        Verification saved = storage.storeAndVerify(large, rows);
        EvaluationArchiveBatch verified =
            new EvaluationArchiveBatch(large.id(), 3, 1, large.leaseToken(), large.leaseUntil(),
                State.VERIFIED, size, saved.manifestSha256());
        Reference reference = new Reference(
            large.id(), 3, 1, size, saved.manifestSha256());

        // then
        assertThat(storage.readOriginal(verified)).containsExactlyElementsOf(rows);
        assertThat(storage.read(reference)).hasSize(size);
        assertDirectoryEmpty();
    }

    @Test
    void 천행을_넘는_설정과_이관_묶음은_거절한다() {
        // given
        // / when / then

        // when
        Throwable caughtFailure = catchThrowable(() -> new EvaluationArchivePolicy(true, 1001));

        // then
        assertThat(caughtFailure)
            .isNotNull()
            .isInstanceOf(IllegalArgumentException.class);

        // when
        Throwable phaseFailure1 = catchThrowable(() -> new EvaluationArchiveBatch(batch.id(), 3, 1, batch.leaseToken(),
            batch.leaseUntil(), State.RESERVED, 1001, null));

        // then
        assertThat(phaseFailure1)
            .isNotNull().isInstanceOf(IllegalArgumentException.class);
    }

    private Row row(String json) {
        return new Row(new Key(7, 9), json, EvaluationArchiveFiles.digest(json.getBytes(StandardCharsets.UTF_8)));
    }

    private String prefix() {
        return "salmonbus-be/evaluation-archive/v1/route-version=3/batch=" + batch.id() + "/";
    }

    private void assertDirectoryEmpty() throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    private static final class MemoryObjects {
        final Map<String, byte[]> values = new HashMap<>();
        final List<String> actions = new ArrayList<>();
        boolean failManifest;
        boolean corruptDownloads;

        void run(List<String> args) {
            String key = value(args, "--key");
            assertThat(value(args, "--bucket")).isEqualTo("techcourse-project-2026");
            assertThat(key).startsWith("salmonbus-be/evaluation-archive/v1/");
            try {
                if (args.contains("put-object")) {
                    actions.add("put:" + key.substring(key.lastIndexOf('/') + 1));
                    assertThat(value(args, "--if-none-match")).isEqualTo("*");
                    assertThat(value(args, "--tagging")).contains("ProjectTeam=salmonbus");
                    if (values.containsKey(key) || failManifest && key.endsWith("manifest.json")) {
                        throw new IllegalStateException("업로드 실패 주입");
                    }
                    values.put(key, Files.readAllBytes(Path.of(value(args, "--body"))));

                    return;
                }

                actions.add("get:" + key.substring(key.lastIndexOf('/') + 1));
                byte[] bytes = values.get(key);
                if (bytes == null) {
                    throw new IllegalStateException("객체 없음");
                }
                final int requested = Integer.parseInt(value(args, "--range").substring("bytes=0-".length())) + 1;
                byte[] copied = Arrays.copyOf(bytes, Math.min(requested, bytes.length));
                if (corruptDownloads) {
                    copied[0] ^= 1;
                }
                Files.write(Path.of(args.getLast()), copied);
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }

        private static String value(List<String> args, String name) {
            return args.get(args.indexOf(name) + 1);
        }
    }
}
