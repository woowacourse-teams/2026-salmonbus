package com.gustler.backend.forecasting.infrastructure.jdbc;

import static com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareIdentifiers.canonical;

import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareObservation;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwarePassEvent;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingSettlementProjection;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingSettlementProjection.Layout;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingSettlementProjection.Snapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 노선·날짜·정류장 구성별 확정 관측을 커서로 이어 읽어 관측 색인과 닫힌 통과 근거로 저장하고, 그 근거로 정산 목표를 맞춘다.
 *
 * <p>읽기는 반복 가능 읽기 스냅숏에서 잠금 없이 한다. 근거 저장과 결과 커밋은 마지막 문장에서 입력 세대 행을
 * {@code FOR UPDATE SKIP LOCKED}로 비교한다. 세대가 바뀌었거나 수집 쪽 확정이 그 행을 잡고 있으면 그 트랜잭션을 버리고
 * 다음 회차에 다시 읽는다. 정산은 세대 행을 기다리지 않고 커밋 직전에만 잡으므로 수집 확정이 정산 트랜잭션을 기다리지 않는다.
 *
 * <p>세대 행은 노선 외래 키 없이 노선·날짜 값으로만 찾는다. 그래서 수집 확정이나 이관 삭제의 트리거가 세대 행을 새로 넣어도
 * 노선 행을 잠그지 않고, 노선 행을 잡은 정산 결과 커밋·예보 커밋을 기다리지 않는다. 노선이 지워져 남은 행은 그 노선의 근거가 없어
 * 판정에 쓰이지 않고, 지난 날짜가 되면 정리가 지운다.
 *
 * <p>새로 읽은 배치나 새 근거가 없으면 근거 행을 다시 쓰지 않는다. 입력 세대만 바뀌었으면 상태는 두고 세대·범위·다음 입력
 * 시각만 고친다. 커서는 마지막으로 읽은 배치에 둔다. 저장 행 수가 0보다 큰 배치만 읽으므로 V78 커서 인덱스로 찾는다.
 */
final class JdbcSettlementProjection {
    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcSettlementProjection.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int BATCH_LIMIT = 100;
    private final JdbcClient jdbc;
    private final TransactionTemplate read;
    private final TransactionTemplate write;
    private Consumer<String> rejections = reason -> { };

    record Ref(long id, long route, LocalDate date, long generation, long version, long facts,
               Instant coverage, Instant incomplete, Instant overflow, boolean advanced) {
        boolean covers(Instant through) {
            return !coverage.isBefore(through) || (overflow != null && !overflow.isAfter(through));
        }
    }

    private record Stored(Ref ref, Instant cursor, long batch, boolean dirty) { }
    private record Batch(long id, Instant at, Integer rows) { }
    private record Input(Stored stored, long generation, List<Batch> batches,
                         List<AlightingAwareObservation> rows, Map<Long, Long> counts, byte[] state) { }

    JdbcSettlementProjection(JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.read = new TransactionTemplate(transactions);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        read.setTimeout(15);
        this.write = new TransactionTemplate(transactions);
        write.setTimeout(15);
    }

    /** 거절 사유를 받을 수신자를 등록한다. 사유는 단계와 원인을 이은 정해진 값이다. */
    void rejections(Consumer<String> listener) {
        rejections = listener;
    }

    Ref advance(final long route, LocalDate date, final long revision, final int limit, JsonNode snapshot, Instant through) {
        List<Integer> stops = new ArrayList<>();
        snapshot.path("realStopOrders").forEach(value -> stops.add(value.asInt()));
        Layout layout = new Layout(snapshot.path("turn").asInt(), stops);
        String encodedLayout = JSON.writeValueAsString(layout);
        final long logicalRoute = jdbc.sql("SELECT route_id FROM route_version WHERE id=:route")
            .param("route", route).query(Long.class).single();
        final long id = Objects.requireNonNull(write.execute(status -> initialize(logicalRoute, date, revision, limit, encodedLayout)));
        Input input = read.execute(status -> inputs(id, logicalRoute, date, limit, through));
        if (input == null || input.stored().dirty()) {
            return null;
        }
        Stored stored = input.stored();
        if (stored.ref().generation() == input.generation() && stored.ref().covers(through)) {
            return stored.ref();
        }
        List<Batch> selected = input.batches().stream().filter(batch -> !batch.at().isAfter(through)).limit(BATCH_LIMIT).toList();
        Instant next = input.batches().size() > selected.size() ? input.batches().get(selected.size()).at() : null;
        AlightingSettlementProjection model = input.state() == null ? null
            : new AlightingSettlementProjection(layout, limit, SettlementProjectionCodec.decode(input.state(), Snapshot.class));
        Map<Long, List<AlightingAwareObservation>> rows = input.rows().stream()
            .collect(Collectors.groupingBy(AlightingAwareObservation::batchId));
        Instant cursor = stored.cursor();
        long cursorBatch = stored.batch();
        Instant coverage = earlier(stored.ref().coverage(), cursor);
        for (Batch batch : selected) {
            if (batch.at().isAfter(cursor)) {
                coverage = cursor;
            }
            model.append(batch.at(), rows.getOrDefault(batch.id(), List.of()),
                input.counts().getOrDefault(batch.id(), 0L) != (batch.rows() == null ? 0 : batch.rows()));
            cursor = batch.at();
            cursorBatch = batch.id();
        }
        if (next == null || next.isAfter(cursor)) {
            if (model != null) {
                model.completeThrough(cursor);
            }
            coverage = later(coverage, cursor);
        }
        if (next == null || next.isAfter(through)) {
            coverage = later(coverage, through);
        }
        Snapshot state = model == null ? null : model.snapshot();
        final boolean factsChanged = model != null && (!model.passes().isEmpty()
            || !Objects.equals(state.incompleteAt(), stored.ref().incomplete())
            || !Objects.equals(state.overflowAt(), stored.ref().overflow()));
        if (selected.isEmpty() && (model == null || model.indexed().isEmpty()) && !factsChanged) {
            Ref current = new Ref(id, logicalRoute, date, input.generation(), stored.ref().version(), stored.ref().facts(),
                coverage, stored.ref().incomplete(), stored.ref().overflow(), false);
            if (stored.ref().generation() == input.generation()) {
                return current;
            }
            return write.execute(status -> catchUp(stored, current, next, status));
        }
        Ref result = new Ref(id, logicalRoute, date, input.generation(), stored.ref().version() + 1,
            stored.ref().facts() + (factsChanged ? 1 : 0), coverage, state.incompleteAt(), state.overflowAt(), true);
        Instant finalCursor = cursor;
        final long finalBatch = cursorBatch;
        return write.execute(status -> persist(stored, result, finalCursor, finalBatch, next, state, model, status));
    }

    private long initialize(final long route, LocalDate date, final long revision, final int limit, String layout) {
        jdbc.sql("INSERT INTO alighting_settlement_generation(route_id,source_date) VALUES(:route,:date) ON CONFLICT DO NOTHING")
            .param("route", route).param("date", date).update();
        String hash = digest(layout);
        jdbc.sql("""
            UPDATE alighting_settlement_projection SET active=false
            WHERE route_id=:route AND source_date=:date AND active
              AND (dirty OR (layout_hash=:hash AND (data_revision<>:revision OR observation_limit<>:limit)))
            """).param("route", route).param("date", date).param("hash", hash)
            .param("revision", revision).param("limit", limit).update();
        jdbc.sql("""
            INSERT INTO alighting_settlement_projection(route_id,source_date,layout_hash,layout,data_revision,
                observation_limit,cursor_at,coverage_at,state)
            VALUES(:route,:date,:hash,CAST(:layout AS jsonb),:revision,:limit,:start,:start,:state)
            ON CONFLICT DO NOTHING
            """).param("route", route).param("date", date).param("hash", hash).param("layout", layout)
            .param("revision", revision).param("limit", limit)
            .param("start", offset(date.atStartOfDay(SEOUL).toInstant().minusNanos(1000)))
            .param("state", SettlementProjectionCodec.encode(Snapshot.empty())).update();
        return jdbc.sql("""
            SELECT id FROM alighting_settlement_projection WHERE route_id=:route AND source_date=:date
              AND layout_hash=:hash AND data_revision=:revision AND observation_limit=:limit AND active
            """).param("route", route).param("date", date).param("hash", hash)
            .param("revision", revision).param("limit", limit).query(Long.class).single();
    }

    private Input inputs(final long id, final long route, LocalDate date, final int limit, Instant through) {
        final long gen = jdbc.sql("SELECT generation FROM alighting_settlement_generation WHERE route_id=:route AND source_date=:date")
            .param("route", route).param("date", date).query(Long.class).single();
        Stored stored = jdbc.sql("""
            SELECT input_generation,version,facts_version,coverage_at,incomplete_at,overflow_at,cursor_at,cursor_batch_id,
                dirty,active
            FROM alighting_settlement_projection WHERE id=:id
            """).param("id", id)
            .query((rs, n) -> new Stored(new Ref(id, route, date, rs.getLong("input_generation"), rs.getLong("version"),
                rs.getLong("facts_version"), instant(rs.getObject("coverage_at", OffsetDateTime.class)),
                instant(rs.getObject("incomplete_at", OffsetDateTime.class)), instant(rs.getObject("overflow_at", OffsetDateTime.class)), false),
                instant(rs.getObject("cursor_at", OffsetDateTime.class)), rs.getLong("cursor_batch_id"),
                rs.getBoolean("dirty") || !rs.getBoolean("active"))).single();
        if (stored.dirty() || ((stored.ref().generation() == gen
            || (stored.ref().overflow() != null && !stored.ref().overflow().isAfter(through))) && stored.ref().covers(through))) {
            return new Input(stored, gen, List.of(), List.of(), Map.of(), null);
        }
        List<Batch> batches = jdbc.sql("""
            SELECT b.id,b.input_confirmed_at,b.stored_rows FROM route_version v
            CROSS JOIN LATERAL (
                SELECT id,input_confirmed_at,stored_rows FROM observation_batch
                WHERE route_version_id=v.id AND ingestion_origin='LIVE' AND outcome='SUCCESS_ROWS' AND stored_rows>0
                  AND input_confirmed_at IS NOT NULL
                  AND input_confirmed_at BETWEEN response_received_at AND response_received_at+interval '90 seconds'
                  AND input_confirmed_at>=:start AND input_confirmed_at<=:end
                  AND (input_confirmed_at,id)>(:cursor,:batch)
                ORDER BY input_confirmed_at,id LIMIT :limit
            ) b WHERE v.route_id=:route ORDER BY b.input_confirmed_at,b.id LIMIT :limit
            """).param("route", route).param("start", offset(date.atStartOfDay(SEOUL).toInstant()))
            .param("end", offset(date.plusDays(1).atStartOfDay(SEOUL).toInstant().plusSeconds(7200)))
            .param("cursor", offset(stored.cursor())).param("batch", stored.batch())
            .param("limit", BATCH_LIMIT + 1)
            .query((rs, n) -> new Batch(rs.getLong("id"), rs.getObject("input_confirmed_at", OffsetDateTime.class).toInstant(),
                rs.getObject("stored_rows", Integer.class))).list();
        List<Long> ids = batches.stream().filter(batch -> !batch.at().isAfter(through)).limit(BATCH_LIMIT).map(Batch::id).toList();
        if (ids.isEmpty() && !stored.ref().coverage().isBefore(stored.cursor())) {
            return new Input(stored, gen, batches, List.of(), Map.of(), null);
        }
        byte[] state = jdbc.sql("SELECT state FROM alighting_settlement_projection WHERE id=:id").param("id", id)
            .query(byte[].class).single();
        if (ids.isEmpty()) {
            return new Input(stored, gen, batches, List.of(), Map.of(), state);
        }
        Map<Long, Long> counts = jdbc.sql("""
            SELECT observation_batch_id,count(*) AS n FROM vehicle_observation
            WHERE observation_batch_id IN (:ids) GROUP BY observation_batch_id
            """).param("ids", ids).query((rs, n) -> Map.entry(rs.getLong("observation_batch_id"), rs.getLong("n")))
            .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        List<AlightingAwareObservation> observations = jdbc.sql("""
            WITH raw AS (
                SELECT o.*,b.input_confirmed_at,b.response_received_at,
                    count(*) OVER(PARTITION BY o.observation_batch_id,o.vehicle_id) AS identity_rows
                FROM vehicle_observation o JOIN observation_batch b ON b.id=o.observation_batch_id
                WHERE o.observation_batch_id IN (:ids) AND b.stored_rows>0 AND o.vehicle_id IS NOT NULL
            ) SELECT * FROM raw WHERE identity_rows=1 ORDER BY input_confirmed_at,observation_batch_id,id LIMIT :limit
            """).param("ids", ids).param("limit", limit + 1)
            .query((rs, n) -> {
                Integer seats = rs.getObject("remaining_seats", Integer.class);
                if (rs.getString("seat_unknown_reason") != null || (seats != null && (seats < 0 || seats > 70))) {
                    seats = null;
                }
                return new AlightingAwareObservation(rs.getLong("id"), rs.getLong("observation_batch_id"), 0,
                    rs.getObject("input_confirmed_at", OffsetDateTime.class).toInstant(), rs.getString("vehicle_id"),
                    rs.getInt("passed_stop_order"), rs.getInt("stop_order"), rs.getInt("running_state"), seats,
                    rs.getInt("crowd_level"), rs.getLong("route_version_id"),
                    rs.getObject("response_received_at", OffsetDateTime.class).toInstant(), false);
            }).list();
        return new Input(stored, gen, batches, observations, counts, state);
    }

    private Ref catchUp(Stored stored, Ref current, Instant next, TransactionStatus status) {
        Ref result = new Ref(current.id(), current.route(), current.date(), current.generation(), current.version() + 1,
            current.facts(), current.coverage(), current.incomplete(), current.overflow(), false);
        final int changed = jdbc.sql("""
            UPDATE alighting_settlement_projection SET version=:nextVersion,input_generation=:generation,
                coverage_at=:coverage,next_input_at=:next,updated_at=clock_timestamp()
            WHERE id=:id AND version=:version AND active AND NOT dirty
            """).param("nextVersion", result.version()).param("generation", result.generation())
            .param("coverage", offset(result.coverage())).param("next", offset(next))
            .param("id", result.id()).param("version", stored.ref().version()).update();
        if (changed == 0) {
            rejectAndRollback(status, "persist", "projection_changed", current, null);
            return null;
        }
        return holdsGeneration(status, "persist", current) ? result : null;
    }

    private Ref persist(Stored stored, Ref result, Instant cursor, final long batch, Instant next,
                        Snapshot state, AlightingSettlementProjection model, TransactionStatus status) {
        final int changed = jdbc.sql("""
            UPDATE alighting_settlement_projection SET version=:nextVersion,input_generation=:generation,
                facts_version=:facts,cursor_at=:cursor,cursor_batch_id=:batch,coverage_at=:coverage,next_input_at=:next,
                observation_count=:count,incomplete_at=:incomplete,overflow_at=:overflow,state=:state,updated_at=clock_timestamp()
            WHERE id=:id AND version=:version AND active AND NOT dirty
            """).param("nextVersion", result.version()).param("generation", result.generation()).param("facts", result.facts())
            .param("cursor", offset(cursor)).param("batch", batch).param("coverage", offset(result.coverage()))
            .param("next", offset(next)).param("count", state.observations()).param("incomplete", offset(result.incomplete()))
            .param("overflow", offset(result.overflow())).param("state", SettlementProjectionCodec.encode(state))
            .param("id", result.id()).param("version", stored.ref().version()).update();
        Ref expected = new Ref(result.id(), result.route(), result.date(), result.generation(), stored.ref().version(),
            stored.ref().facts(), stored.ref().coverage(), stored.ref().incomplete(), stored.ref().overflow(), false);
        if (changed == 0) {
            rejectAndRollback(status, "persist", "projection_changed", expected, null);
            return null;
        }
        if (!model.indexed().isEmpty()) {
            jdbc.sql("""
                INSERT INTO alighting_settlement_observation(projection_id,observation_id,chain_id,stop_order,available_at,known_seats)
                SELECT :id,x.id,x.chain,x.stop,x."availableAt",x."knownSeats" FROM jsonb_to_recordset(CAST(:rows AS jsonb))
                    AS x(id bigint,chain bigint,stop integer,"availableAt" timestamptz,"knownSeats" boolean)
                """).param("id", result.id()).param("rows", JSON.writeValueAsString(model.indexed())).update();
        }
        if (!model.passes().isEmpty()) {
            List<PersistedPass> passes = model.passes().stream().map(pass -> {
                AlightingAwarePassEvent event = pass.event();
                return new PersistedPass(event.tripId(), event.stopOrder(), pass.chain(), event.confirmedAt(),
                    event.departure().receivedAt().atZone(SEOUL).toLocalDate(), event.departure().observationId(),
                    Base64.getEncoder().encodeToString(SettlementProjectionCodec.encode(event)));
            }).toList();
            jdbc.sql("""
                INSERT INTO alighting_settlement_pass(projection_id,trip_id,stop_order,chain_id,confirmed_at,
                    departure_date,departure_observation_id,payload)
                SELECT :id,x.trip,x.stop,x.chain,x.at,x.date,x.observation,decode(x.payload,'base64')
                FROM jsonb_to_recordset(CAST(:rows AS jsonb)) AS x(trip bigint,stop integer,chain bigint,
                    at timestamptz,date date,observation bigint,payload text)
                """).param("id", result.id()).param("rows", JSON.writeValueAsString(passes)).update();
        }
        return holdsGeneration(status, "persist", expected) ? result : null;
    }

    private record PersistedPass(long trip, int stop, long chain, Instant at, LocalDate date, long observation, String payload) { }

    boolean cleanup(LocalDate today) {
        return Boolean.TRUE.equals(write.execute(status -> {
            int changed = jdbc.sql("""
                UPDATE alighting_settlement_projection p SET active=false WHERE p.id IN (
                    SELECT old.id FROM alighting_settlement_projection old
                    WHERE old.active AND old.source_date<:date
                      AND EXISTS(SELECT 1 FROM alighting_settlement_queue_scan WHERE complete)
                      AND NOT EXISTS(SELECT 1 FROM alighting_settlement_batch_work q
                          WHERE q.route_id=old.route_id AND q.source_date=old.source_date)
                    ORDER BY old.id LIMIT 10 FOR UPDATE SKIP LOCKED)
                """).param("date", today.minusDays(1)).update();
            Long retired = jdbc.sql("SELECT id FROM alighting_settlement_projection WHERE NOT active ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED")
                .query(Long.class).optional().orElse(null);
            if (retired != null) {
                changed += jdbc.sql("""
                DELETE FROM alighting_settlement_observation WHERE projection_id=:id AND observation_id IN (
                    SELECT observation_id FROM alighting_settlement_observation WHERE projection_id=:id ORDER BY observation_id LIMIT 1000)
                """).param("id", retired).update();
                changed += jdbc.sql("""
                DELETE FROM alighting_settlement_pass WHERE projection_id=:id AND (trip_id,stop_order) IN (
                    SELECT trip_id,stop_order FROM alighting_settlement_pass WHERE projection_id=:id ORDER BY trip_id,stop_order LIMIT 1000)
                """).param("id", retired).update();
                changed += jdbc.sql("""
                DELETE FROM alighting_settlement_projection p WHERE id=:id
                    AND NOT EXISTS(SELECT 1 FROM alighting_settlement_observation WHERE projection_id=p.id)
                    AND NOT EXISTS(SELECT 1 FROM alighting_settlement_pass WHERE projection_id=p.id)
                """).param("id", retired).update();
            }
            changed += jdbc.sql("""
                DELETE FROM alighting_settlement_generation g WHERE (route_id,source_date) IN (
                    SELECT old.route_id,old.source_date FROM alighting_settlement_generation old WHERE old.source_date<:date
                      AND NOT EXISTS(SELECT 1 FROM alighting_settlement_projection p WHERE p.route_id=old.route_id AND p.source_date=old.source_date)
                      AND NOT EXISTS(SELECT 1 FROM alighting_settlement_batch_work q WHERE q.route_id=old.route_id AND q.source_date=old.source_date)
                    ORDER BY old.route_id,old.source_date LIMIT 10 FOR UPDATE SKIP LOCKED)
                """).param("date", today.minusDays(1)).update();
            return changed > 0;
        }));
    }

    /**
     * 준비한 근거가 아직 같은 입력 세대와 판본인지 확인한다. {@code lock}이면 세대 행을 {@code SKIP LOCKED}로 잡아
     * 커밋까지 다른 입력 변경과 겹치지 않게 하고, 잡지 못하면 거절한다. 거절 사유는 기록하고 트랜잭션 처리는 호출자가 정한다.
     */
    boolean validate(List<Ref> refs, final boolean lock) {
        for (Ref ref : refs.stream().distinct().sorted(Comparator.comparingLong(Ref::route).thenComparing(Ref::date)).toList()) {
            if (!jdbc.sql("SELECT EXISTS(SELECT 1 FROM alighting_settlement_projection WHERE id=:id AND active AND NOT dirty AND version=:version)")
                .param("id", ref.id()).param("version", ref.version()).query(Boolean.class).single()) {
                reject("commit", "projection_changed", ref, null);
                return false;
            }
            Long actual = jdbc.sql("SELECT generation FROM alighting_settlement_generation WHERE route_id=:route AND source_date=:date"
                    + (lock ? " FOR UPDATE SKIP LOCKED" : ""))
                .param("route", ref.route()).param("date", ref.date()).query(Long.class).optional().orElse(null);
            if (actual == null) {
                reject("commit", "generation_busy", ref, null);
                return false;
            }
            if (actual != ref.generation()) {
                reject("commit", "generation_changed", ref, actual);
                return false;
            }
        }
        return true;
    }

    private boolean holdsGeneration(TransactionStatus status, String stage, Ref expected) {
        Long actual = jdbc.sql("""
            SELECT generation FROM alighting_settlement_generation WHERE route_id=:route AND source_date=:date
            FOR UPDATE SKIP LOCKED
            """).param("route", expected.route()).param("date", expected.date()).query(Long.class).optional().orElse(null);
        if (actual == null) {
            rejectAndRollback(status, stage, "generation_busy", expected, null);
            return false;
        }
        if (actual != expected.generation()) {
            rejectAndRollback(status, stage, "generation_changed", expected, actual);
            return false;
        }
        return true;
    }

    private void rejectAndRollback(TransactionStatus status, String stage, String reason, Ref expected, Long actual) {
        status.setRollbackOnly();
        reject(stage, reason, expected, actual);
    }

    private void reject(String stage, String reason, Ref expected, Long actual) {
        LOGGER.info("event=alighting_settlement_rejected stage={} reason={} routeId={} sourceDate={} projectionId={}"
                + " projectionVersion={} expectedGeneration={} actualGeneration={}",
            stage, reason, expected.route(), expected.date(), expected.id(), expected.version(), expected.generation(),
            actual == null ? "-" : actual);
        try {
            rejections.accept(stage + "_" + reason);
        } catch (RuntimeException ignored) {
            return;
        }
    }

    private record RequestedRow(int ordinal, long source, int target, Instant through) { }
    record Request(long source, int target, Instant through) { }
    record Match(boolean sourcePresent, AlightingAwarePassEvent visit, AlightingAwarePassEvent previous) { }

    Map<Request, Match> matches(Ref ref, List<Request> requests) {
        List<Request> unique = requests.stream().distinct().toList();
        return jdbc.sql("""
            WITH requested AS (
                SELECT * FROM jsonb_to_recordset(CAST(:requests AS jsonb)) AS r(ordinal integer,source bigint,target integer,through timestamptz)
            )
            SELECT r.ordinal,s.observation_id IS NOT NULL AS source_present,
                visit.payload AS visit,previous.payload AS previous
            FROM requested r
            LEFT JOIN alighting_settlement_observation s ON s.projection_id=:id AND s.observation_id=r.source AND s.available_at<=r.through
            LEFT JOIN LATERAL (
                SELECT p.* FROM alighting_settlement_pass p
                WHERE p.projection_id=s.projection_id AND p.chain_id=s.chain_id AND p.stop_order=r.target
                    AND p.confirmed_at<=r.through
                    AND EXISTS(SELECT 1 FROM alighting_settlement_observation o
                        WHERE o.projection_id=s.projection_id AND o.chain_id=s.chain_id AND o.stop_order=r.target AND o.known_seats
                          AND o.available_at>s.available_at+interval '10 seconds'
                          AND o.available_at<=least(s.available_at+interval '2 hours',r.through))
                ORDER BY p.confirmed_at,p.departure_observation_id LIMIT 1
            ) visit ON true
            LEFT JOIN LATERAL (
                SELECT p.payload FROM alighting_settlement_pass p WHERE p.projection_id=:id AND p.stop_order=r.target
                    AND p.departure_date=visit.departure_date AND p.confirmed_at<visit.confirmed_at
                ORDER BY p.confirmed_at DESC,p.departure_observation_id LIMIT 1
            ) previous ON true
            """).param("requests", JSON.writeValueAsString(IntStream.range(0, unique.size())
                .mapToObj(i -> new RequestedRow(i, unique.get(i).source(), unique.get(i).target(), unique.get(i).through())).toList())).param("id", ref.id())
            .query((rs, n) -> Map.entry(unique.get(rs.getInt("ordinal")),
                new Match(rs.getBoolean("source_present"), pass(rs.getBytes("visit")), pass(rs.getBytes("previous")))))
            .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    JdbcAlightingAwareForecastEvaluation.Outcome evaluate(Ref ref, Instant through, JsonNode snapshot, Match match) {
        if (!canonical(snapshot.path("inputProfile").asText()).equals(AlightingAwareRouteStatistics.CONFIRMED_PROFILE)) {
            return JdbcAlightingAwareForecastEvaluation.Outcome.unsupported("INPUT_PROFILE_MISMATCH");
        }
        if (!snapshot.path("capacityPolicy").asText().equals("vehicle-capacity-snapshot-v1")) {
            return JdbcAlightingAwareForecastEvaluation.Outcome.unsupported("CAPACITY_POLICY_MISMATCH");
        }
        if (ref.overflow() != null && !ref.overflow().isAfter(through)) {
            return JdbcAlightingAwareForecastEvaluation.Outcome.unsupported("OBSERVATION_INPUT_LIMIT");
        }
        if (ref.incomplete() != null && !ref.incomplete().isAfter(through)) {
            return JdbcAlightingAwareForecastEvaluation.Outcome.unsupported("SOURCE_HISTORY_INCOMPLETE");
        }
        if (!match.sourcePresent()) {
            return JdbcAlightingAwareForecastEvaluation.Outcome.unsupported("SOURCE_NOT_IN_CONFIRMED_HISTORY");
        }
        if (match.visit() == null) {
            return null;
        }
        return ConfirmedAlightingAwareEvaluation.afterSeats(match.visit(), match.previous(), snapshot);
    }

    private static AlightingAwarePassEvent pass(byte[] payload) {
        return payload == null ? null : SettlementProjectionCodec.decode(payload, AlightingAwarePassEvent.class);
    }

    private static Instant earlier(Instant first, Instant second) {
        return first.isAfter(second) ? second : first;
    }

    private static Instant later(Instant first, Instant second) {
        return first.isBefore(second) ? second : first;
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime offset(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
