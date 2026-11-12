package com.gustler.backend.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * 배치에서 출발하도록 바꾼 내보내기 조회가 이전 조회와 같은 행을 같은 순서와 같은 페이지 경계로 내는지 대조한다.
 *
 * <p>이전 조회는 대조 기준으로만 이 시험에 남긴다. 한 자료에 날짜 경계, 응답 없는 배치, 다른 노선, 첫 키가 같은 여러 행을 담고 페이지
 * 크기를 바꿔 커서가 묶음 가운데에 걸리게 한다. 이전 조회는 이관 조회처럼 UTC 세션에서 돌려 시각 문자열을 같게 맞춘다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ArchiveExportQueryTest {
    private static final LocalDate DATE = LocalDate.parse("2026-11-07");
    private static final List<Integer> LIMITS = List.of(1, 3, 5);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PREVIOUS_EVIDENCE = """
                WITH evidence_ids AS MATERIALIZED (
                SELECT e.arrival_observation_id AS id
                FROM observation_batch b JOIN vehicle_observation src ON src.observation_batch_id=b.id
                JOIN forecast_evaluation_result e ON e.vehicle_observation_id=src.id
                WHERE b.route_version_id=:v AND COALESCE(b.response_received_at,b.scheduled_at)>=:from
                  AND COALESCE(b.response_received_at,b.scheduled_at)<:until AND e.arrival_observation_id IS NOT NULL
                UNION
                SELECT c.arrival_observation_id
                FROM observation_batch b JOIN vehicle_observation src ON src.observation_batch_id=b.id
                JOIN forecast_evaluation_compact c ON c.vehicle_observation_id=src.id
                WHERE b.route_version_id=:v AND COALESCE(b.response_received_at,b.scheduled_at)>=:from
                  AND COALESCE(b.response_received_at,b.scheduled_at)<:until
            )
            SELECT o.id,jsonb_build_object('observation',to_jsonb(o),'batch',to_jsonb(ab))::text AS original
            FROM evidence_ids refs JOIN vehicle_observation o ON o.id=refs.id
            JOIN observation_batch ab ON ab.id=o.observation_batch_id
            WHERE o.id>:cursor ORDER BY o.id LIMIT :limit
            """;

    OperationalArchiveIntegrationTest fixture;
    JdbcClient jdbc;
    Connection session;
    JdbcClient previous;
    long model;
    long version;
    int routes;

    @BeforeAll
    void migrate() throws SQLException {
        fixture = new OperationalArchiveIntegrationTest();
        fixture.migrate();
        jdbc = fixture.jdbc;
        session = fixture.database.connection();
        previous = JdbcClient.create(new SingleConnectionDataSource(session, true));
        previous.sql("SET TIME ZONE 'UTC'").update();
    }

    @AfterAll
    void close() throws SQLException {
        session.close();
        fixture.close();
    }

    /**
     * 대상 노선의 배치 아홉 개와 다른 노선의 배치 하나를 날짜와 노선이 섞이게 만든다. 그날로 고를 배치는 응답이 그날 0시 정각인 배치, 한낮
     * 배치, 응답 없이 예약이 그날 0시 정각인 배치, 응답이 그날 마지막 순간인 배치다. 배치마다 관측 셋과 그 예보·정산·보관 기록을 단다.
     */
    @BeforeEach
    void setup() {
        jdbc.sql("TRUNCATE route,model_deployment,vehicle_capacity,vehicle_capacity_history CASCADE").update();
        model = jdbc.sql("""
            INSERT INTO model_deployment(deployment_key,release_id,model_key,model_version,bundle_digest,
                prediction_target_version,calculation_version,supported_scope_digest,data_until,state)
            VALUES(gen_random_uuid(),'test','seat','1',?,'target','calc',?,'2026-01-01','STAGED') RETURNING id
            """).params("1".repeat(64), "2".repeat(64)).query(Long.class).single();
        version = version();
        final long other = version();
        children(version, batch(version, "2026-11-06T14:00Z", "2026-11-06T14:00Z"), "prev");
        children(other, batch(other, "2026-11-07T03:00Z", "2026-11-07T03:00Z"), "other");
        children(version, batch(version, "2026-11-06T14:59Z", "2026-11-06T15:00Z"), "bus");
        children(version, batch(version, "2026-11-06T14:59Z", null), "null-prev");
        children(version, batch(version, "2026-11-07T03:00Z", "2026-11-07T03:00Z"), "bus");
        children(version, batch(version, "2026-11-06T15:00Z", null), "bus");
        children(version, batch(version, "2026-11-07T14:59Z", "2026-11-07T15:00:01Z"), "late");
        children(version, batch(version, "2026-11-07T14:00Z", "2026-11-07T14:59:59.999999Z"), "bus");
        children(version, batch(version, "2026-11-07T15:00Z", null), "null-until");
        children(version, batch(version, "2026-11-08T03:00Z", "2026-11-08T03:00Z"), "next");
        settle();
    }

    @Test
    void 원본_페이지는_이전_조회와_같은_행을_같은_순서와_커서로_낸다() {
        // given
        ArchiveRepository.Job job = job(version);
        Map<ArchiveTable, Integer> expected = new LinkedHashMap<>();
        expected.put(ArchiveTable.ALIGHTING_AWARE_RESULT, 48);
        expected.put(ArchiveTable.ALIGHTING_AWARE_ARCHIVE, 24);
        expected.put(ArchiveTable.RESULT, 24);
        expected.put(ArchiveTable.COMPACT, 12);
        expected.put(ArchiveTable.LEGACY_MEMBER, 12);
        expected.put(ArchiveTable.FORECAST, 36);
        expected.put(ArchiveTable.PUBLICATION, 4);
        expected.put(ArchiveTable.OBSERVATION, 12);
        expected.put(ArchiveTable.BATCH, 4);

        assertSoftly(softly -> {
            for (int limit : LIMITS) {
                for (ArchiveTable table : ArchiveTable.values()) {
                    // when
                    List<ArchiveRepository.Page> current = pages("[]",
                        cursor -> fixture.repository.page(job, table, cursor, limit));
                    List<ArchiveRepository.Page> before = pages("[]", cursor -> previousPage(job, table, cursor, limit));

                    // then
                    softly.assertThat(current).as("%s limit %d", table, limit).isEqualTo(before);
                    softly.assertThat(rows(current)).as("%s limit %d 행 수", table, limit).isEqualTo(expected.get(table));
                }
            }
        });
    }

    @Test
    void 도착_근거_페이지는_이전_조회와_같은_관측을_같은_순서와_커서로_낸다() {
        // given
        ArchiveRepository.Job job = job(version);

        assertSoftly(softly -> {
            for (int limit : LIMITS) {
                // when
                List<ArchiveRepository.Page> current = pages("0",
                    cursor -> fixture.repository.evidence(job, Long.parseLong(cursor), limit));
                List<ArchiveRepository.Page> before = pages("0", cursor -> previousEvidence(job, cursor, limit));

                // then
                softly.assertThat(current).as("limit %d", limit).isEqualTo(before);
                softly.assertThat(rows(current)).as("limit %d 행 수", limit).isEqualTo(18);
            }
        });
    }

    @Test
    void 차원_자료는_그_노선_날짜에_관측된_차량의_정원과_정원_이력만_담는다() {
        // given
        for (String vehicle : List.of("bus-1", "bus-2", "bus-3", "prev-1", "null-prev-1", "late-1", "null-until-1",
            "next-1", "other-1")) {
            jdbc.sql("""
                INSERT INTO vehicle_capacity(source_id,vehicle_id,capacity,evidence_at) VALUES('GBIS',?,41,'2026-11-07T00:00Z')
                """).param(vehicle).update();
        }
        jdbc.sql("""
            INSERT INTO vehicle_capacity_history(source_id,vehicle_id,evidence_at,evidence_batch_id,capacity)
            VALUES('GBIS','bus-1','2026-10-01T00:00Z',0,45)
            """).update();
        ArchiveRepository.Job job = job(version);

        // when
        List<String> dimensions = fixture.repository.dimensions(job, "[]");

        // then
        assertSoftly(softly -> {
            softly.assertThat(vehicles(dimensions, "vehicle_capacity")).containsExactlyInAnyOrder("bus-1", "bus-2",
                "bus-3");
            softly.assertThat(vehicles(dimensions, "vehicle_capacity_history")).containsExactlyInAnyOrder("bus-1",
                "bus-1", "bus-2", "bus-3");
        });
    }

    private List<String> vehicles(List<String> dimensions, String table) {
        return dimensions.stream().map(row -> JSON.readValue(row, Map.class))
            .filter(row -> row.get("table").equals(table))
            .map(row -> (String) ((Map<?, ?>) row.get("data")).get("vehicle_id")).toList();
    }

    private List<ArchiveRepository.Page> pages(String start, Function<String, ArchiveRepository.Page> read) {
        List<ArchiveRepository.Page> pages = new ArrayList<>();
        String cursor = start;
        while (pages.size() < 200) {
            ArchiveRepository.Page page = read.apply(cursor);
            pages.add(page);
            if (page.rows().isEmpty()) {
                return pages;
            }
            cursor = page.cursor();
        }
        throw new IllegalStateException("PAGE_NOT_ENDED");
    }

    private int rows(List<ArchiveRepository.Page> pages) {
        return pages.stream().mapToInt(page -> page.rows().size()).sum();
    }

    /** 이전 page() 가 만들던 SQL 이다. 대조 기준으로만 쓴다. */
    private static String previousPageSql(ArchiveTable table) {
        String deploymentId = table == ArchiveTable.FORECAST ? "t.model_deployment_id" : "NULL::bigint";
        String selection = switch (table.scope) {
        case "observation" -> "JOIN vehicle_observation o ON o.id=t.vehicle_observation_id JOIN"
            + " observation_batch b ON b.id=o.observation_batch_id";
        case "source_batch" -> "JOIN observation_batch b ON b.id=t.source_batch_id";
        case "self" -> "JOIN observation_batch b ON b.id=t.observation_batch_id";
        default -> "";
        };
        String batch = table.scope.equals("batch") ? "t" : "b";
        return "SELECT to_jsonb(t)::text AS original," + table.key() + "::text AS cursor,"
            + deploymentId + " AS model_deployment_id FROM " + table.table
            + " t " + selection + " WHERE " + batch + ".route_version_id=:v AND " + "COALESCE("
            + batch + ".response_received_at," + batch + ".scheduled_at)>=:from AND " + "COALESCE("
            + batch + ".response_received_at," + batch + ".scheduled_at)<:until AND "
            + table.afterCursor() + " ORDER BY " + table.keyOrder() + " LIMIT :limit";
    }

    private ArchiveRepository.Page previousPage(
        ArchiveRepository.Job job,
        ArchiveTable table,
        String cursor,
        final int limit
    ) {
        List<Object[]> rows = previous.sql(previousPageSql(table)).param("v", job.version()).param("from", from())
            .param("until", until()).param("cursor", cursor).param("limit", limit)
            .query((rs, n) -> new Object[] { rs.getString(1), rs.getString(2), rs.getObject(3, Long.class) }).list();
        Set<Long> deployments = table == ArchiveTable.FORECAST
            ? rows.stream().map(row -> (Long) row[2]).collect(Collectors.toSet()) : Set.of();
        return new ArchiveRepository.Page(rows.stream().map(row -> (String) row[0]).toList(),
            rows.isEmpty() ? cursor : (String) rows.getLast()[1], deployments);
    }

    private ArchiveRepository.Page previousEvidence(ArchiveRepository.Job job, String cursor, final int limit) {
        List<String[]> rows = previous.sql(PREVIOUS_EVIDENCE).param("v", job.version()).param("from", from())
            .param("until", until()).param("cursor", Long.parseLong(cursor)).param("limit", limit)
            .query((rs, n) -> new String[] { rs.getString(1), rs.getString(2) }).list();
        return new ArchiveRepository.Page(rows.stream().map(row -> row[1]).toList(),
            rows.isEmpty() ? cursor : rows.getLast()[0], Set.of());
    }

    private OffsetDateTime from() {
        return ArchiveRetention.start(DATE).atOffset(ZoneOffset.UTC);
    }

    private OffsetDateTime until() {
        return ArchiveRetention.start(DATE.plusDays(1)).atOffset(ZoneOffset.UTC);
    }

    private ArchiveRepository.Job job(final long version) {
        UUID id = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO operational_archive_job(id,route_version_id,source_date,cutoff,state,lease_token,lease_until)
            VALUES(?,?,?,?,'EXPORTING',?,clock_timestamp()+interval '5 minutes')
            """).params(id, version, DATE, until(), token).update();
        return new ArchiveRepository.Job(id, version, DATE, until().toInstant(), token, "EXPORTING");
    }

    private long version() {
        routes++;
        final long route = jdbc.sql("""
            INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
            VALUES(?,'GBIS',?,?,'A','B') RETURNING id
            """).params("r" + routes, "r" + routes, "route-" + routes).query(Long.class).single();
        final long version = jdbc.sql("""
            INSERT INTO route_version(route_id,content_digest,valid_from) VALUES(?,?,'2026-01-01') RETURNING id
            """).params(route, "0".repeat(64)).query(Long.class).single();
        jdbc.sql("""
            INSERT INTO route_stop(route_version_id,stop_order,stop_id,name,direction,boarding_allowed)
            SELECT ?,n,'s'||n,'S'||n,'UP',true FROM generate_series(1,4) n
            """).param(version).update();
        jdbc.sql("""
            INSERT INTO archive_legacy_batch(id,route_version_id,quality_revision,lease_token,lease_until,
                state,row_count,manifest_sha256,verified_at,storage_state,purged_at)
            VALUES(gen_random_uuid(),?,1,gen_random_uuid(),'-infinity','VERIFIED',1,repeat('a',64),now(),'PURGED',now())
            """).param(version).update();
        return version;
    }

    private long batch(final long version, String scheduled, String response) {
        return jdbc.sql("""
            INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,requested_at,
                response_received_at,completed_at,outcome,normalization_version,collection_strategy_version)
            VALUES(?,CAST(? AS timestamptz),1,?,CAST(? AS timestamptz),CAST(? AS timestamptz),CAST(? AS timestamptz),
                CASE WHEN ? THEN 'SUCCESS_ROWS' ELSE 'FAILED_UNREADABLE' END,'n','c') RETURNING id
            """).params(version, scheduled, UUID.randomUUID().toString(), scheduled, response, scheduled,
            response != null).query(Long.class).single();
    }

    /** 배치에 발행 하나, 관측 셋, 관측마다 예보 셋과 거기 딸린 승하차 정산·승하차 보관·옛 이관 기록·근거 없는 정산을 단다. */
    private void children(final long version, final long batch, String vehicle) {
        jdbc.sql("""
            INSERT INTO forecast_publication(source_batch_id,route_version_id,model_deployment_id,
                demand_statistics_revision,quality_revision,observed_at,generated_at,published_at,prediction_count)
            SELECT id,route_version_id,?,1,1,scheduled_at,scheduled_at,scheduled_at,9 FROM observation_batch WHERE id=?
            """).params(model, batch).update();
        jdbc.sql("""
            INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,vehicle_id,
                stop_order,stop_id,running_state,passed_stop_order,remaining_seats)
            SELECT ?,?,n,?||'-'||n,1,'s1',2,1,12 FROM generate_series(1,3) n
            """).params(batch, version, vehicle).update();
        jdbc.sql("""
            INSERT INTO seat_forecast(publication_id,vehicle_observation_id,target_stop_order,route_version_id,
                stops_to_target,model_deployment_id,demand_statistics_revision,seat_full_chance_raw,seat_full_chance,
                generated_at,quality_revision)
            SELECT p.id,o.id,s,o.route_version_id,s-1,p.model_deployment_id,1,0.2,0.3,p.generated_at,1
            FROM vehicle_observation o JOIN forecast_publication p ON p.source_batch_id=o.observation_batch_id
            CROSS JOIN generate_series(2,4) s WHERE o.observation_batch_id=?
            """).param(batch).update();
        jdbc.sql("""
            INSERT INTO forecast_evaluation_result(vehicle_observation_id,target_stop_order,route_version_id,
                scoring_state,scored_at)
            SELECT id,3,route_version_id,'SKIPPED','2026-11-07T05:00Z' FROM vehicle_observation WHERE observation_batch_id=?
            """).param(batch).update();
        jdbc.sql("""
            INSERT INTO alighting_forecast_evaluation_result(vehicle_observation_id,target_stop_order,target_kind,
                label_version,route_version_id,scoring_state,label_value,pre_observation_id,quality_revision,scored_at)
            SELECT o.id,2,'AFTER_ALIGHTING_ESTIMATE',label,o.route_version_id,'SETTLED',12.5,o.id,revision,
                '2026-11-07T05:00Z'
            FROM vehicle_observation o CROSS JOIN (VALUES('label-b'),('label-a')) AS labels(label)
            CROSS JOIN generate_series(1,2) AS revisions(revision) WHERE o.observation_batch_id=?
            """).param(batch).update();
        jdbc.sql("""
            INSERT INTO alighting_evaluation_archive(vehicle_observation_id,target_stop_order,target_kind,label_version,
                quality_revision,route_version_id,label_value,scoring_state,scored_at,object_hash,storage_state)
            SELECT o.id,3,'AFTER_ALIGHTING_ESTIMATE',label,1,o.route_version_id,12.5,'SETTLED','2026-11-07T05:00Z',
                repeat('a',64),'LIVE'
            FROM vehicle_observation o CROSS JOIN (VALUES('label-b'),('label-a')) AS labels(label)
            WHERE o.observation_batch_id=?
            """).param(batch).update();
        jdbc.sql("""
            INSERT INTO archive_legacy_member(vehicle_observation_id,target_stop_order,route_version_id,batch_id,
                original_sha256)
            SELECT o.id,4,o.route_version_id,l.id,repeat('b',64) FROM vehicle_observation o
            JOIN archive_legacy_batch l ON l.route_version_id=o.route_version_id WHERE o.observation_batch_id=?
            """).param(batch).update();
    }

    /**
     * 같은 노선에서 다음에 만든 같은 행 번호 관측을 도착으로 삼아 정산한다. 축약 정산은 홀수 행이 자기 관측을, 짝수 행이 다음 관측을 가리켜
     * 상세 정산과 겹치는 도착 근거를 만든다. 마지막 배치의 관측은 다음 관측이 없어 도착 근거가 없다.
     */
    private void settle() {
        jdbc.sql("""
            INSERT INTO forecast_evaluation_result(vehicle_observation_id,target_stop_order,route_version_id,
                scoring_state,arrival_observation_id,scored_at,arrived_at,arrival_route_version_id,arrival_stop_order,
                arrival_running_state,arrival_seat_unknown_reason,arrival_quality_direction)
            SELECT o.id,2,o.route_version_id,'SEAT_MISSING',a.id,'2026-11-07T05:00Z','2026-11-07T04:00Z',
                a.route_version_id,2,2,'UNKNOWN',0
            FROM vehicle_observation o CROSS JOIN LATERAL (
                SELECT a.id,a.route_version_id FROM vehicle_observation a
                WHERE a.route_version_id=o.route_version_id AND a.source_row_number=o.source_row_number AND a.id>o.id
                ORDER BY a.id LIMIT 1) a
            """).update();
        jdbc.sql("""
            INSERT INTO forecast_evaluation_compact(vehicle_observation_id,target_stop_order,arrival_observation_id,
                seats_on_arrival,scored_at,arrived_at,arrival_quality_direction)
            SELECT o.id,4,COALESCE(CASE WHEN o.source_row_number%2=0 THEN e.arrival_observation_id END,o.id),9,
                '2026-11-07T05:00Z','2026-11-07T04:00Z',0
            FROM vehicle_observation o
            LEFT JOIN forecast_evaluation_result e ON e.vehicle_observation_id=o.id AND e.target_stop_order=2
            """).update();
    }
}
