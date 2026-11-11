package com.gustler.backend.api.salmon.offline;

import com.gustler.backend.api.salmon.domain.Verdicts.Evaluable;
import com.gustler.backend.api.salmon.domain.Verdicts.Evaluation;
import com.gustler.backend.api.salmon.domain.Verdicts.Insufficient;
import com.gustler.backend.api.salmon.offline.RunSettings.SettingSet;
import com.gustler.backend.api.salmon.offline.StayRun.DecisionRecord;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * 입력 폴더의 과거 자료로 연어행동의 대기 판정을 돌리고 집계를 쓴다. 로컬 임시 DB만 쓰고 운영 DB에 접속하지 않는다.
 *
 * <p>인자는 입력 폴더, 설정 묶음 파일, 새 출력 폴더, (선택) 코드 커밋이다. 설정 묶음마다 출력 폴더 아래에 폴더를 만들고
 * summary.json(집계)과 decisions.csv(판정 한 건당 한 줄)를 쓴다.
 */
public final class SalmonOfflineRun {
    static final String NOTICE = "측정용 값이며 제품 값이 아님";
    static final String CANDIDATES = "후보 미연결";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SalmonOfflineRun() {
    }

    public static void main(String[] args) throws IOException, SQLException {
        if (args.length < 3 || args.length > 4) {
            throw new IllegalArgumentException("입력 폴더, 설정 파일, 새 출력 폴더가 필요하다(넷째 인자는 코드 커밋)");
        }
        run(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), args.length == 4 ? args[3] : "unknown");
    }

    static void run(Path input, Path settingsFile, Path output, String codeCommit) throws IOException, SQLException {
        if (Files.exists(output)) {
            throw new IllegalArgumentException("출력 폴더가 이미 있다. 새 경로를 준다: " + output);
        }
        RunSettings settings = RunSettings.read(settingsFile);
        try (OfflineDatabase database = new OfflineDatabase()) {
            Map<String, Integer> inputRows;
            try (Connection connection = database.loadingConnection()) {
                inputRows = InputFolder.load(connection, input);
            }
            JdbcClient jdbc = database.jdbc();
            Optional<Path> decisionFile = InputFolder.fileOf(input, InputFolder.DECISION_FILE);
            List<DecisionPoint> points = decisionFile.isPresent() ? DecisionPoint.fromFile(decisionFile.get())
                : DecisionPoint.fromSnapshots(jdbc);
            Map<String, Object> data = dataOf(jdbc, inputRows, points, decisionFile.isPresent());
            Files.createDirectories(output);
            for (SettingSet set : settings.sets()) {
                Path folder = Files.createDirectory(output.resolve(set.name()));
                Summary summary = decideAll(jdbc, set, points, folder.resolve("decisions.csv"));
                Files.writeString(folder.resolve("summary.json"), JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(reportOf(set, codeCommit, data, summary)));
            }
        }
    }

    private static Summary decideAll(JdbcClient jdbc, SettingSet set, List<DecisionPoint> points, Path rows)
        throws IOException {
        Summary summary = new Summary();
        try (StayRun run = new StayRun(jdbc, set); BufferedWriter writer = Files.newBufferedWriter(rows)) {
            writer.write("route_id,station_id,stop_sequence,decision_at,reason_code,arrival_list,entries,attempts,"
                + "stay_evaluation,stay_failure_risk,stay_assumptions,stay_gaps,entry_lookups,board,"
                + "board_vehicles,board_vehicles_with_forecast,entry_outcomes_first,entry_outcomes_last,"
                + "entry_timeliness,stay_outcome_by_first,stay_outcome_by_last,stay_outcome_by_window");
            writer.newLine();
            for (DecisionPoint point : points) {
                DecisionRecord decision = run.decide(point);
                summary.add(decision);
                writer.write(rowOf(decision));
                writer.newLine();
            }
        }
        return summary;
    }

    // 한 칸에 여러 값을 담을 때는 |로 잇는다. 값에 쉼표나 따옴표가 없어 따옴표로 감싸지 않는다.
    private static String rowOf(DecisionRecord decision) {
        Evaluation stay = decision.verdict().stay().evaluation();
        return String.join(",",
            decision.point().routeId(),
            decision.point().stationId(),
            Integer.toString(decision.point().stopSequence()),
            decision.point().at().toString(),
            decision.verdict().code().name(),
            Summary.listStateOf(decision.list()),
            Integer.toString(decision.entries().size()),
            Integer.toString(decision.verdict().stay().attempts().size()),
            Summary.evaluationOf(stay),
            stay instanceof Evaluable evaluable ? Double.toString(evaluable.value()) : "",
            stay instanceof Evaluable evaluable ? Summary.assumptionsOf(evaluable) : "",
            stay instanceof Insufficient insufficient
                ? insufficient.gaps().stream().map(Summary::gapOf).collect(Collectors.joining("|")) : "",
            decision.entries().stream()
                .map(entry -> entry.order() + (entry.attempted() ? "*" : "") + ":" + Summary.lookupOf(entry))
                .collect(Collectors.joining("|")),
            decision.board().status(),
            Integer.toString(decision.board().vehicles()),
            Integer.toString(decision.board().forecasts()),
            decision.entries().stream().map(entry -> entry.order() + ":" + entry.outcome().first().label())
                .collect(Collectors.joining("|")),
            decision.entries().stream().map(entry -> entry.order() + ":" + entry.outcome().last().label())
                .collect(Collectors.joining("|")),
            decision.entries().stream().map(entry -> entry.order() + ":" + entry.timeliness())
                .collect(Collectors.joining("|")),
            Summary.stayByFirstOf(decision),
            Summary.stayByLastOf(decision),
            Summary.stayByWindowOf(decision));
    }

    private static Map<String, Object> reportOf(SettingSet set, String codeCommit, Map<String, Object> data,
                                                Summary summary) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("notice", NOTICE);
        report.put("candidates", CANDIDATES);
        report.put("codeCommit", codeCommit);
        report.put("settings", set.values());
        report.put("settingsNotUsedInThisRun",
            List.of("gainThreshold", "alpha", "maxWalkMinutes", "maxDetour", "epochBudget", "kindOrder"));
        report.put("notes", List.of(
            "후보를 연결하지 않아 사유 코드는 ABSTAIN_STAY_UNDEFINED, STAY_SCAN_NOT_TRIGGERED, "
                + "NO_MOVE_NO_CANDIDATE만 나온다. NO_MOVE_NO_CANDIDATE는 대기 위험이 탐색 역치 이상이라 "
                + "후보를 조회했을 판정이라는 뜻이다",
            "보드는 그 묶음의 좌석 예보를 전부 읽는다. 예보 적격 뷰와 편도 제외는 쓰지 않는다",
            "도착 스냅샷은 수신 시각(received_at)이 판정 시각 이전인 것을 본다. 수신과 저장 사이의 지연은 되살리지 않는다",
            "재발행된 묶음은 발행 시각(published_at)도 재발행 시각으로 덮여 있다. 원래 발행과 재발행 사이의 판정 시각에서는 "
                + "그 묶음이 아직 발행 전으로 보여 더 앞의 묶음을 고른다",
            "입력에 route_version_first_publication이 없고 판정 구간의 발행도 없으면, 그 판본을 발행이 한 번도 없던 "
                + "판본으로 읽는다. 그런 판정은 diagnostics.boardAtDecision의 READ_UNPUBLISHED로 센다",
            "entry_lookups와 seatForecastLookups의 windowAttempts는 대기 위험 계산에 들어간 시도(창 안)만 센다",
            "판정 시각은 스냅샷의 수신 시각이다. 사용자가 실제로 판정을 받는 시각(수신 뒤 0초에서 60초 사이)과 다를 수 있다"));
        report.put("data", data);
        report.put("results", summary.results());
        report.put("outcomesNote", "판정 시각 뒤의 차량 관측에서 읽은 실제 결과다. 판정 입력에는 쓰지 않았다. "
            + "byFirstObservation은 그 정류장을 떠난 첫 관측(FIRST_POST_STOP_ZERO의 정답), byLastObservation은 다음 승차 "
            + "정류장 전의 마지막 관측(DEPARTURE_ZERO의 정답)의 남은 좌석을 본다. 1석 이상이면 SEATS_AVAILABLE, 0석이면 "
            + "FULL이다. 시도열 기준(stayByAttempts)은 마감 뒤에 닿은 차량을 만석과 같이 탈 수 없었던 차량으로 세고, 마감 안에 "
            + "닿았는지 모르는 차량은 빈자리가 있어도 모름으로 둔다. 창 기준(stayByWindow)은 첫 관측만 본다. byLastObservation은 DEPARTURE_ZERO 정답의 근사다. "
            + "제품은 모델 번들의 정차 순번을 쓰고 작은 순번 후퇴를 허용하는데, 여기서는 승차 가능 정류장을 쓰고 순번이 "
            + "되돌아가면 끊는다. failureByRisk는 차량의 결과를 전부 아는 판정만 구간에 넣고 나머지는 excludedDecisions로 센다");
        report.put("outcomes", summary.outcomes());
        report.put("diagnosticsNote", "아래 diagnostics는 판정 입력도 판정 결과도 아닌 실행기의 진단 값이다");
        report.put("diagnostics", diagnosticsOf(summary));
        return report;
    }

    private static Map<String, Object> diagnosticsOf(Summary summary) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("boardAtDecisionNote", "판정 시각에 그 노선의 보드를 읽을 수 있었는지와 그 정류장 방문에 담긴 차량 수를 "
            + "도착 목록과 무관하게 센다. 도착 목록이 비어 제품 코드가 보드를 읽지 않은 판정에서도 센다");
        report.put("boardAtDecision", summary.boardAtDecision());
        report.put("boardVehicleOutcomesNote", "판정 결과가 아닌 진단이다. 판정 시각의 보드가 그 정류장 방문에 담은, "
            + "예보가 붙은 차량 한 대를 단위로 예보 값과 실제 결과를 맞춘다. 같은 차량과 정류장이 판정 시각마다 다시 "
            + "세어지므로 독립 표본이 아니다. 실제 결과는 보드의 관측 묶음 시각 뒤에서 찾는다");
        report.put("boardVehicleOutcomes", summary.boardVehicleOutcomes());
        return report;
    }

    private static Map<String, Object> dataOf(JdbcClient jdbc, Map<String, Integer> inputRows,
                                              List<DecisionPoint> points, boolean fromDecisionFile) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("inputRows", inputRows);
        data.put("decisionSource", fromDecisionFile ? "decision.csv" : "arrival_snapshot");
        data.put("decisions", points.size());
        data.put("decisionRoutes", points.stream().map(DecisionPoint::routeId).distinct().count());
        data.put("decisionStopVisits", points.stream()
            .map(point -> List.of(point.routeId(), point.stationId(), point.stopSequence())).distinct().count());
        data.put("decisionAt", rangeOf(points.stream().map(DecisionPoint::at).min(Instant::compareTo),
            points.stream().map(DecisionPoint::at).max(Instant::compareTo)));
        data.put("arrivalSnapshotReceivedAt", rangeOf(jdbc, "SELECT min(received_at), max(received_at) "
            + "FROM arrival_snapshot"));
        data.put("observationBatchReceivedAt", rangeOf(jdbc, "SELECT min(response_received_at), "
            + "max(response_received_at) FROM observation_batch"));
        data.put("forecastPublishedAt", rangeOf(jdbc, "SELECT min(published_at), max(published_at) "
            + "FROM forecast_publication"));
        data.put("seatForecastsByProbabilityTarget", jdbc.sql("SELECT probability_target, count(*) "
            + "FROM seat_forecast GROUP BY probability_target ORDER BY probability_target")
            .query((row, index) -> Map.entry(row.getString(1), row.getLong(2))).list().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, Long::sum, LinkedHashMap::new)));
        return data;
    }

    private static Map<String, Object> rangeOf(JdbcClient jdbc, String sql) {
        return jdbc.sql(sql).query((row, index) -> rangeOf(
            Optional.ofNullable(row.getTimestamp(1)).map(Timestamp::toInstant),
            Optional.ofNullable(row.getTimestamp(2)).map(Timestamp::toInstant))).single();
    }

    private static Map<String, Object> rangeOf(Optional<Instant> from, Optional<Instant> to) {
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("from", from.map(Instant::toString).orElse(null));
        range.put("to", to.map(Instant::toString).orElse(null));
        return range;
    }
}
