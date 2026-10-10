package com.gustler.backend.forecasting.infrastructure.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** AWS CLI 경계 대체 시험. 실제 S3 왕복 검증과 구분한다. */
class EvaluationArchiveObjectStoreTest {
    @TempDir Path directory;
    private final MemoryObjects objects = new MemoryObjects();
    private final EvaluationArchiveBatch batch = new EvaluationArchiveBatch(UUID.randomUUID(), 3, 1,
        UUID.randomUUID(), Instant.now().plusSeconds(300), State.RESERVED, 1, null);
    private static final String ORIGINAL = """
        {"vehicle_observation_id":7,"target_stop_order":9,"route_version_id":3,"scoring_state":"LOST",\
        "arrival_observation_id":null,"seats_on_arrival":null,"scored_at":"2026-10-08T10:00:00+00:00",\
        "arrived_at":null,"arrival_route_version_id":null,"arrival_vehicle_id":null,"arrival_stop_order":null,\
        "arrival_running_state":null,"arrival_remaining_seats":null,"arrival_seat_unknown_reason":null,\
        "arrival_vehicle_trip_key":null,"arrival_quality_direction":null}\
        """;

    @Test
    void 원본의_모든_열과_빈값을_보존하고_다시_받은_내용을_확인한다() throws Exception {
        // given
        var store = store(1024 * 1024);

        // when
        var verified = store.storeAndVerify(batch, List.of(row(ORIGINAL)));

        // then
        assertThat(objects.values).hasSize(2);
        String copied = new String(objects.values.get(prefix() + "evaluations.jsonl"), StandardCharsets.UTF_8);
        assertThat(copied).isEqualTo(ORIGINAL + "\n");
        assertThat(JsonMapper.builder().build().readTree(copied).size()).isEqualTo(16);
        assertThat(verified.manifestSha256()).isEqualTo(EvaluationArchiveFiles.digest(objects.values.get(prefix() + "manifest.json")));
        assertThat(objects.actions).containsExactly("put:evaluations.jsonl", "get:evaluations.jsonl", "put:manifest.json", "get:manifest.json");
        assertDirectoryEmpty();
    }

    @Test
    void 같은_묶음을_재시도하면_저장된_내용을_검증하고_같은_결과를_반환한다() {
        // given
        var first = store(1024 * 1024).storeAndVerify(batch, List.of(row(ORIGINAL)));

        // when
        var repeated = store(1024 * 1024).storeAndVerify(batch, List.of(row(ORIGINAL)));

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

        // when & then
        assertThatThrownBy(() -> store(1024 * 1024).storeAndVerify(batch, List.of(row(ORIGINAL))))
            .isInstanceOf(IllegalStateException.class);
        assertThat(objects.values.get(prefix() + "evaluations.jsonl")).isEqualTo(different);
        assertThat(objects.values).doesNotContainKey(prefix() + "manifest.json");
        assertDirectoryEmpty();
    }

    @Test
    void 업로드_뒤_다시_받은_자료가_바뀌면_설명_파일을_게시하지_않는다() {
        // given
        objects.corruptDownloads = true;

        // when & then
        assertThatThrownBy(() -> store(1024 * 1024).storeAndVerify(batch, List.of(row(ORIGINAL))))
            .isInstanceOf(IllegalStateException.class);
        assertThat(objects.values).doesNotContainKey(prefix() + "manifest.json");
    }

    @Test
    void 파일_한도를_넘으면_외부_저장소에_쓰지_않는다() throws Exception {
        // given
        var store = store(10);

        // when & then
        assertThatThrownBy(() -> store.storeAndVerify(batch, List.of(row(ORIGINAL))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(objects.actions).isEmpty();
        assertDirectoryEmpty();
    }

    @Test
    void 일부_열이_빠진_가공_자료를_정산_원본으로_보관하지_않는다() {
        // given
        String incomplete = ORIGINAL.replace(",\"arrival_quality_direction\":null", "");

        // when & then
        assertThatThrownBy(() -> store(1024 * 1024).storeAndVerify(batch, List.of(row(incomplete))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(objects.actions).isEmpty();
    }

    @Test
    void 원본의_키와_예약한_키가_다르면_전송하지_않는다() {
        // given
        String changedKey = ORIGINAL.replace("\"vehicle_observation_id\":7", "\"vehicle_observation_id\":8");

        // when & then
        assertThatThrownBy(() -> store(1024 * 1024).storeAndVerify(batch, List.of(row(changedKey))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(objects.actions).isEmpty();
    }

    @Test
    void 같은_원본_키가_반복되면_건수가_맞아도_전송하지_않는다() {
        // given
        var duplicate = new EvaluationArchiveBatch(batch.id(), 3, 1, batch.leaseToken(), batch.leaseUntil(), State.RESERVED, 2, null);

        // when & then
        assertThatThrownBy(() -> store(1024 * 1024).storeAndVerify(duplicate, List.of(row(ORIGINAL), row(ORIGINAL))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(objects.actions).isEmpty();
    }

    @Test
    void 기존_객체에_예상하지_않은_뒷부분이_있으면_같은_자료로_인정하지_않는다() {
        // given
        objects.values.put(prefix() + "evaluations.jsonl", (ORIGINAL + "\n다른 행").getBytes(StandardCharsets.UTF_8));

        // when & then
        assertThatThrownBy(() -> store(1024 * 1024).storeAndVerify(batch, List.of(row(ORIGINAL))))
            .isInstanceOf(IllegalStateException.class);
        assertThat(objects.values).doesNotContainKey(prefix() + "manifest.json");
    }

    @Test
    void 작업을_이어받아_소유권이_바뀌어도_같은_자료의_설명_파일은_달라지지_않는다() {
        // given
        var first = store(1024 * 1024).storeAndVerify(batch, List.of(row(ORIGINAL)));
        var resumed = new EvaluationArchiveBatch(batch.id(), batch.routeVersionId(), batch.qualityRevision(),
            UUID.randomUUID(), Instant.now().plusSeconds(600), State.RESERVED, batch.rowCount(), null);

        // when
        var second = store(1024 * 1024).storeAndVerify(resumed, List.of(row(ORIGINAL)));

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

    private EvaluationArchiveObjectStore store(long maxBytes) {
        return new EvaluationArchiveObjectStore(directory, maxBytes, objects::run);
    }

    @Test
    void 저장_당시_검증_기록으로_다시_읽어_같은_정산을_반환한다() throws Exception {
        // given
        var stored = store(1024 * 1024).storeAndVerify(batch, List.of(row(ORIGINAL)));
        var reference = new com.gustler.backend.forecasting.application.statistics.StatisticsArchiveReader.Reference(
            batch.id(), batch.routeVersionId(), batch.qualityRevision(), batch.rowCount(), stored.manifestSha256());

        // when
        var rows = store(1024 * 1024).read(reference);

        // then
        assertThat(rows).containsExactly(new com.gustler.backend.forecasting.application.statistics.StatisticsArchiveReader.Row(
            new com.gustler.backend.forecasting.application.statistics.StatisticsArchiveReader.Key(7, 9),
            ORIGINAL, EvaluationArchiveFiles.digest(ORIGINAL.getBytes(StandardCharsets.UTF_8))));
        assertDirectoryEmpty();
    }

    @Test
    void 삭제와_복원을_위한_원본도_저장한_모든_열과_검증값을_그대로_반환한다() throws Exception {
        // given
        var saved = store(1024 * 1024).storeAndVerify(batch, List.of(row(ORIGINAL)));
        var verified = new EvaluationArchiveBatch(batch.id(), batch.routeVersionId(), batch.qualityRevision(),
            batch.leaseToken(), batch.leaseUntil(), State.VERIFIED, batch.rowCount(), saved.manifestSha256());

        // when
        var rows = store(1024 * 1024).readOriginal(verified);

        // then
        assertThat(rows).containsExactly(row(ORIGINAL));
        assertDirectoryEmpty();
    }

    @Test
    void 저장_완료_뒤_원본_객체가_바뀌면_재집계_입력으로_사용하지_않는다() {
        // given
        var stored = store(1024 * 1024).storeAndVerify(batch, List.of(row(ORIGINAL)));
        var reference = new com.gustler.backend.forecasting.application.statistics.StatisticsArchiveReader.Reference(
            batch.id(), batch.routeVersionId(), batch.qualityRevision(), batch.rowCount(), stored.manifestSha256());
        objects.values.get(prefix() + "evaluations.jsonl")[0] ^= 1;

        // when & then
        assertThatThrownBy(() -> store(1024 * 1024).read(reference)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 설명_파일이_다른_묶음으로_바뀌면_원본을_읽지_않는다() {
        // given
        var stored = store(1024 * 1024).storeAndVerify(batch, List.of(row(ORIGINAL)));
        var reference = new com.gustler.backend.forecasting.application.statistics.StatisticsArchiveReader.Reference(
            batch.id(), batch.routeVersionId(), batch.qualityRevision(), batch.rowCount(), stored.manifestSha256());
        objects.values.get(prefix() + "manifest.json")[0] ^= 1;
        objects.actions.clear();

        // when & then
        assertThatThrownBy(() -> store(1024 * 1024).read(reference)).isInstanceOf(IllegalArgumentException.class);
        assertThat(objects.actions).containsExactly("get:manifest.json");
    }

    private Row row(String json) {
        return new Row(new Key(7, 9), json, EvaluationArchiveFiles.digest(json.getBytes(StandardCharsets.UTF_8)));
    }

    private String prefix() {
        return "salmonbus-be/evaluation-archive/v1/route-version=3/batch=" + batch.id() + "/";
    }

    private void assertDirectoryEmpty() throws IOException {
        try (var files = Files.list(directory)) {
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
                } else {
                    actions.add("get:" + key.substring(key.lastIndexOf('/') + 1));
                    byte[] bytes = values.get(key);
                    if (bytes == null) {
                        throw new IllegalStateException("객체 없음");
                    }
                    int requested = Integer.parseInt(value(args, "--range").substring("bytes=0-".length())) + 1;
                    byte[] copied = Arrays.copyOf(bytes, Math.min(requested, bytes.length));
                    if (corruptDownloads) {
                        copied[0] ^= 1;
                    }
                    Files.write(Path.of(args.getLast()), copied);
                }
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }

        private static String value(List<String> args, String name) {
            return args.get(args.indexOf(name) + 1);
        }
    }
}
