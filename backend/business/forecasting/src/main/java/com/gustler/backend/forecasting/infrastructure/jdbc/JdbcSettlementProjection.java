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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class JdbcSettlementProjection {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int BATCH_LIMIT = 100;
    private final JdbcClient jdbc;
    private final TransactionTemplate read;
    private final TransactionTemplate write;

    record Ref(long id, long route, LocalDate date, long generation, long version, long facts,
               Instant coverage, Instant incomplete, Instant overflow, boolean advanced) {
        boolean covers(Instant through) {
            return !coverage.isBefore(through) || (overflow != null && !overflow.isAfter(through));
        }
    }

    private record Stored(Ref ref, Instant cursor, long batch, Instant next, boolean dirty, byte[] state) { }
    private record Batch(long id, Instant at, Integer rows) { }
    private record Input(Stored stored, long generation, List<Batch> batches,
                         List<AlightingAwareObservation> rows, Map<Long, Long> counts) { }

    JdbcSettlementProjection(JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.read = new TransactionTemplate(transactions);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        read.setTimeout(15);
        this.write = new TransactionTemplate(transactions);
        write.setTimeout(15);
    }

    Ref advance(long route, LocalDate date, long revision, int limit, JsonNode snapshot, Instant through) {
        List<Integer> stops = new ArrayList<>();
        snapshot.path("realStopOrders").forEach(value -> stops.add(value.asInt()));
        Layout layout = new Layout(snapshot.path("turn").asInt(), stops);
        String encodedLayout = JSON.writeValueAsString(layout);
        long logicalRoute = jdbc.sql("SELECT route_id FROM route_version WHERE id=:route")
            .param("route", route).query(Long.class).single();
        long id = Objects.requireNonNull(write.execute(status -> initialize(logicalRoute, date, revision, limit, encodedLayout)));
        Input input = read.execute(status -> inputs(id, logicalRoute, date, limit, through));
        if (input == null || input.stored().dirty()) {
            return null;
        }
        Stored stored = input.stored();
        if (stored.ref().generation() == input.generation() && stored.ref().covers(through)) {
            return stored.ref();
        }
        AlightingSettlementProjection model = new AlightingSettlementProjection(layout, limit,
            SettlementProjectionCodec.decode(stored.state(), Snapshot.class));
        Map<Long, List<AlightingAwareObservation>> rows = input.rows().stream()
            .collect(Collectors.groupingBy(AlightingAwareObservation::batchId));
        List<Batch> selected = input.batches().stream().filter(batch -> !batch.at().isAfter(through)).limit(BATCH_LIMIT).toList();
        Instant cursor = stored.cursor();
        long cursorBatch = stored.batch();
        Instant coverage = stored.ref().coverage();
        for (Batch batch : selected) {
            if (batch.at().isAfter(cursor)) {
                coverage = cursor;
            }
            model.append(batch.at(), rows.getOrDefault(batch.id(), List.of()),
                input.counts().getOrDefault(batch.id(), 0L) != (batch.rows() == null ? 0 : batch.rows()));
            cursor = batch.at();
            cursorBatch = batch.id();
        }
        Instant next = input.batches().size() > selected.size() ? input.batches().get(selected.size()).at() : null;
        if (next == null || next.isAfter(through)) {
            model.completeThrough(through);
            coverage = through.isAfter(coverage) ? through : coverage;
            if (through.isAfter(cursor)) {
                cursor = through;
                cursorBatch = Long.MAX_VALUE;
            }
        } else if (next.isAfter(cursor)) {
            model.completeThrough(cursor);
            coverage = cursor;
        }
        Snapshot state = model.snapshot();
        long facts = stored.ref().facts() + (!model.passes().isEmpty()
            || !Objects.equals(state.incompleteAt(), stored.ref().incomplete())
            || !Objects.equals(state.overflowAt(), stored.ref().overflow()) ? 1 : 0);
        Ref result = new Ref(id, logicalRoute, date, input.generation(), stored.ref().version() + 1,
            facts, coverage, state.incompleteAt(), state.overflowAt(), true);
        Instant finalCursor = cursor;
        long finalBatch = cursorBatch;
        return write.execute(status -> persist(stored, result, finalCursor, finalBatch, next, state, model) ? result : null);
    }

    private long initialize(long route, LocalDate date, long revision, int limit, String layout) {
        jdbc.sql("INSERT INTO alighting_settlement_generation(route_id,source_date) VALUES(:route,:date) ON CONFLICT DO NOTHING")
            .param("route", route).param("date", date).update();
        generation(route, date, true);
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

    private Input inputs(long id, long route, LocalDate date, int limit, Instant through) {
        long gen = generation(route, date, false);
        Stored stored = jdbc.sql("SELECT * FROM alighting_settlement_projection WHERE id=:id").param("id", id)
            .query((rs, n) -> new Stored(new Ref(id, route, date, rs.getLong("input_generation"), rs.getLong("version"),
                rs.getLong("facts_version"), instant(rs.getObject("coverage_at", OffsetDateTime.class)),
                instant(rs.getObject("incomplete_at", OffsetDateTime.class)), instant(rs.getObject("overflow_at", OffsetDateTime.class)), false),
                instant(rs.getObject("cursor_at", OffsetDateTime.class)), rs.getLong("cursor_batch_id"),
                instant(rs.getObject("next_input_at", OffsetDateTime.class)), rs.getBoolean("dirty") || !rs.getBoolean("active"),
                rs.getBytes("state"))).single();
        if (stored.dirty() || ((stored.ref().generation() == gen
            || (stored.ref().overflow() != null && !stored.ref().overflow().isAfter(through))) && stored.ref().covers(through))) {
            return new Input(stored, gen, List.of(), List.of(), Map.of());
        }
        List<Batch> batches = jdbc.sql("""
            SELECT b.id,b.input_confirmed_at,b.stored_rows FROM route_version v
            CROSS JOIN LATERAL (
                SELECT id,input_confirmed_at,stored_rows FROM observation_batch
                WHERE route_version_id=v.id AND ingestion_origin='LIVE' AND outcome='SUCCESS_ROWS'
                  AND input_confirmed_at IS NOT NULL
                  AND input_confirmed_at BETWEEN response_received_at AND response_received_at+interval '90 seconds'
                  AND input_confirmed_at>=:start AND input_confirmed_at<=:end
                  AND (input_confirmed_at,id)>(:cursor,:batch)
                ORDER BY input_confirmed_at,id LIMIT :limit
            ) b WHERE v.route_id=:route ORDER BY b.input_confirmed_at,b.id LIMIT :limit
            """).param("route", route).param("start", offset(date.atStartOfDay(SEOUL).toInstant()))
            .param("end", offset(date.plusDays(1).atStartOfDay(SEOUL).toInstant().plusSeconds(7200))).param("cursor", offset(stored.cursor())).param("batch", stored.batch())
            .param("limit", BATCH_LIMIT + 1)
            .query((rs, n) -> new Batch(rs.getLong("id"), rs.getObject("input_confirmed_at", OffsetDateTime.class).toInstant(),
                rs.getObject("stored_rows", Integer.class))).list();
        List<Long> ids = batches.stream().filter(batch -> !batch.at().isAfter(through)).limit(BATCH_LIMIT).map(Batch::id).toList();
        if (ids.isEmpty()) {
            return new Input(stored, gen, batches, List.of(), Map.of());
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
        return new Input(stored, gen, batches, observations, counts);
    }

    private boolean persist(Stored stored, Ref result, Instant cursor, long batch, Instant next,
                            Snapshot state, AlightingSettlementProjection model) {
        if (generation(result.route(), result.date(), true) != result.generation()) {
            return false;
        }
        int changed = jdbc.sql("""
            UPDATE alighting_settlement_projection SET version=:nextVersion,input_generation=:generation,
                facts_version=:facts,cursor_at=:cursor,cursor_batch_id=:batch,coverage_at=:coverage,next_input_at=:next,
                observation_count=:count,incomplete_at=:incomplete,overflow_at=:overflow,state=:state,updated_at=clock_timestamp()
            WHERE id=:id AND version=:version AND active AND NOT dirty
            """).param("nextVersion", result.version()).param("generation", result.generation()).param("facts", result.facts())
            .param("cursor", offset(cursor)).param("batch", batch).param("coverage", offset(result.coverage()))
            .param("next", offset(next)).param("count", state.observations()).param("incomplete", offset(result.incomplete()))
            .param("overflow", offset(result.overflow())).param("state", SettlementProjectionCodec.encode(state))
            .param("id", result.id()).param("version", stored.ref().version()).update();
        if (changed == 0) {
            return false;
        }
        jdbc.sql("""
            INSERT INTO alighting_settlement_observation(projection_id,observation_id,chain_id,stop_order,available_at,known_seats)
            SELECT :id,x.id,x.chain,x.stop,x."availableAt",x."knownSeats" FROM jsonb_to_recordset(CAST(:rows AS jsonb))
                AS x(id bigint,chain bigint,stop integer,"availableAt" timestamptz,"knownSeats" boolean)
            """).param("id", result.id()).param("rows", JSON.writeValueAsString(model.indexed())).update();
        List<PersistedPass> passes = model.passes().stream().map(pass -> {
            AlightingAwarePassEvent event = pass.event();
            return new PersistedPass(event.tripId(), event.stopOrder(), pass.chain(), event.confirmedAt(),
                event.departure().receivedAt().atZone(SEOUL).toLocalDate(), event.departure().observationId(),
                java.util.Base64.getEncoder().encodeToString(SettlementProjectionCodec.encode(event)));
        }).toList();
        jdbc.sql("""
            INSERT INTO alighting_settlement_pass(projection_id,trip_id,stop_order,chain_id,confirmed_at,
                departure_date,departure_observation_id,payload)
            SELECT :id,x.trip,x.stop,x.chain,x.at,x.date,x.observation,decode(x.payload,'base64')
            FROM jsonb_to_recordset(CAST(:rows AS jsonb)) AS x(trip bigint,stop integer,chain bigint,
                at timestamptz,date date,observation bigint,payload text)
            """).param("id", result.id()).param("rows", JSON.writeValueAsString(passes)).update();
        return true;
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

    boolean validate(List<Ref> refs) {
        for (Ref ref : refs.stream().distinct().sorted(java.util.Comparator.comparingLong(Ref::route).thenComparing(Ref::date)).toList()) {
            if (generation(ref.route(), ref.date(), true) != ref.generation()) {
                return false;
            }
            if (!jdbc.sql("SELECT EXISTS(SELECT 1 FROM alighting_settlement_projection WHERE id=:id AND active AND NOT dirty AND version=:version)")
                .param("id", ref.id()).param("version", ref.version()).query(Boolean.class).single()) {
                return false;
            }
        }
        return true;
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
            """).param("requests", JSON.writeValueAsString(java.util.stream.IntStream.range(0, unique.size())
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

    private long generation(long route, LocalDate date, boolean lock) {
        return jdbc.sql("SELECT generation FROM alighting_settlement_generation WHERE route_id=:route AND source_date=:date"
            + (lock ? " FOR UPDATE" : "")).param("route", route).param("date", date).query(Long.class).single();
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
