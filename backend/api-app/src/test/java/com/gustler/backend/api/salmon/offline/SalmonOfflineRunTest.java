package com.gustler.backend.api.salmon.offline;

import static com.gustler.backend.api.salmon.offline.SyntheticInput.BASE;
import static com.gustler.backend.api.salmon.offline.SyntheticInput.ROUTE;
import static com.gustler.backend.api.salmon.offline.SyntheticInput.arrival;
import static com.gustler.backend.api.salmon.offline.SyntheticInput.stationOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 합성 입력 폴더를 임시 DB에 넣고 실행기를 처음부터 끝까지 돌려 결과 파일을 확인한다. */
class SalmonOfflineRunTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String BASE_SET = """
        {"notice": "측정용 값이며 제품 값이 아님",
         "sets": [{"name": "base-15", "horizonMinutes": 15, "coverageRule": "NONE", "scanThreshold": 0,
                   "gainThreshold": 1.0E-9, "alpha": "1.0", "maxWalkMinutes": 15, "maxDetour": 0,
                   "resolutionSeconds": 1, "epochBudget": 1, "kindOrder": ["WALK", "REBOARD"]}]}
        """;

    @TempDir
    Path workspace;

    @Test
    void 도착_스냅샷마다_대기_판정을_돌려_사유_코드와_판정하지_못한_이유와_값_없음_원인을_센다() throws Exception {
        Path input = new SyntheticInput(Files.createDirectory(workspace.resolve("input")))
            // 두 시도 모두 예보가 있다. 실패 위험은 (1 - 0.7) x (1 - 0.5)이다.
            .snapshot(15, 0, arrival("bus-4", 1, 120), arrival("bus-1", 2, 300))
            .snapshotOfOtherRoute(5, arrival("bus-9", 1, 90))
            // 둘째 차는 창 밖(25분 뒤)이라 시도하지 않는다. 그 차의 예보는 없다. 실패 위험은 첫 차의 만석 확률 0.9다.
            .snapshot(10, 10, arrival("bus-2", 1, 60), arrival("bus-3", 2, 1500))
            .snapshot(18, 15, arrival("bus-3", 1, 600))
            .snapshotWithoutRoutes(5, 20)
            .snapshot(12, 25, arrival("bus-1", 1, 30), arrival("bus-7", 2, 200))
            .snapshot(3, 30, arrival("", 1, 100))
            // bus-2는 담을 수 있는 차량이지만 가까운 세 대 밖이다.
            .snapshot(15, 40, arrival("bus-1", 1, 250), arrival("bus-2", 2, 700))
            .folder();
        Path output = workspace.resolve("output");

        SalmonOfflineRun.run(input, settings(), output, "test-commit");

        JsonNode summary = JSON.readTree(Files.readAllBytes(output.resolve("base-15/summary.json")));
        assertThat(summary.path("notice").asString()).isEqualTo("측정용 값이며 제품 값이 아님");
        assertThat(summary.path("candidates").asString()).isEqualTo("후보 미연결");
        assertThat(summary.path("codeCommit").asString()).isEqualTo("test-commit");
        assertThat(summary.path("settings").path("horizonMinutes").asDouble()).isEqualTo(15.0);
        assertThat(summary.path("settings").path("coverageRule").asString()).isEqualTo("NONE");
        JsonNode data = summary.path("data");
        assertThat(data.path("decisionSource").asString()).isEqualTo("arrival_snapshot");
        assertThat(data.path("decisions").asInt()).isEqualTo(8);
        assertThat(data.path("decisionStopVisits").asInt()).isEqualTo(7);
        assertThat(data.path("inputRows").path("seat_forecast").asInt()).isEqualTo(5);
        assertThat(data.path("seatForecastsByProbabilityTarget").path("DEPARTURE_ZERO").asInt()).isEqualTo(1);
        assertThat(data.path("decisionAt").path("to").asString()).isEqualTo(BASE.plusSeconds(40).toString());

        JsonNode results = summary.path("results");
        assertThat(results.path("decisions").asInt()).isEqualTo(8);
        assertThat(results.path("decisionsWhereRouteIdsDiffer").asInt()).isEqualTo(1);
        assertThat(countsOf(results.path("reasonCodes")))
            .containsExactlyInAnyOrder("ABSTAIN_STAY_UNDEFINED=6", "NO_MOVE_NO_CANDIDATE=2");
        assertThat(countsOf(results.path("arrivalLists"))).containsExactly("QUERIED=8");
        assertThat(countsOf(results.path("entriesPerList"))).containsExactlyInAnyOrder("0=1", "1=3", "2=4");
        assertThat(countsOf(results.path("attemptsPerDecision"))).containsExactlyInAnyOrder("0=1", "1=4", "2=3");
        assertThat(countsOf(results.path("stayEvaluations")))
            .containsExactlyInAnyOrder("EVALUABLE=2", "INSUFFICIENT=6");
        assertThat(results.path("stayGaps").valueStream().map(gap -> gap.path("kind").asString() + "/"
            + gap.path("list").asString() + "/" + gap.path("reason").asString() + "=" + gap.path("count").asInt()))
            .containsExactlyInAnyOrder("LIST_NOT_COVERING_WINDOW/CURRENT/-=1", "PROBABILITY_UNKNOWN/-/-=6");
        assertThat(countsOf(results.path("stayAssumptions")))
            .containsExactlyInAnyOrder("INDEP+SEAT_FORECAST_PROXY=1", "SEAT_FORECAST_PROXY=1");
        JsonNode risk = results.path("stayFailureRisk");
        assertThat(risk.path("count").asInt()).isEqualTo(2);
        assertThat(risk.path("min").asDouble()).isCloseTo(0.15, within(1e-9));
        assertThat(risk.path("p50").asDouble()).isCloseTo(0.15, within(1e-9));
        assertThat(risk.path("max").asDouble()).isCloseTo(0.9, within(1e-9));

        JsonNode all = results.path("seatForecastLookups").path("allEntries");
        assertThat(all.path("entries").asInt()).isEqualTo(11);
        assertThat(countsOf(all.path("foundByProbabilityTarget")))
            .containsExactlyInAnyOrder("DEPARTURE_ZERO=2", "FIRST_POST_STOP_ZERO=2");
        assertThat(countsOf(all.path("notFoundByCause"))).containsExactlyInAnyOrder("BOARD_READ_FAILED=1",
            "FORECAST_UNAVAILABLE=1", "LIST_WITHOUT_VEHICLE_ID=1", "VEHICLE_NOT_ON_BOARD=4");
        assertThat(countsOf(all.path("boardReadFailedByError"))).containsExactly("ROUTE_NOT_FOUND=1");
        assertThat(countsOf(all.path("vehicleNotOnBoardByAbsence"))).containsExactlyInAnyOrder(
            "ALREADY_PASSED=1", "BEYOND_HORIZON=1", "BEYOND_NEAREST=1", "NOT_IN_BATCH=1");
        JsonNode attempts = results.path("seatForecastLookups").path("windowAttempts");
        assertThat(attempts.path("entries").asInt()).isEqualTo(10);
        assertThat(attempts.path("notFoundByCause").has("FORECAST_UNAVAILABLE")).isFalse();

        JsonNode board = summary.path("diagnostics").path("boardAtDecision");
        assertThat(countsOf(board.path("status"))).containsExactlyInAnyOrder("READ=7", "ROUTE_NOT_FOUND=1");
        assertThat(countsOf(board.path("vehiclesAtStop"))).containsExactlyInAnyOrder("1=2", "2=2", "3=3");
        assertThat(countsOf(board.path("vehiclesWithForecastAtStop")))
            .containsExactlyInAnyOrder("0=4", "1=1", "3=2");
    }

    @Test
    void 판정_시각_뒤의_차량_관측으로_시도의_실제_결과와_대기의_성공_여부를_두_기준으로_센다() throws Exception {
        Path input = new SyntheticInput(Files.createDirectory(workspace.resolve("input")))
            // bus-4는 떠난 첫 관측이 0석, 마지막 관측이 2석이다. bus-1은 둘 다 0석이다.
            .snapshot(15, 0, arrival("bus-4", 1, 120), arrival("bus-1", 2, 300))
            .snapshotOfOtherRoute(5, arrival("bus-9", 1, 90))
            .snapshot(10, 10, arrival("bus-2", 1, 60), arrival("bus-3", 2, 1500))
            .snapshot(18, 15, arrival("bus-3", 1, 600))
            .snapshotWithoutRoutes(5, 20)
            .snapshot(12, 25, arrival("bus-1", 1, 30), arrival("bus-7", 2, 200))
            .snapshot(3, 30, arrival("", 1, 100))
            .snapshot(15, 40, arrival("bus-1", 1, 250), arrival("bus-2", 2, 700))
            .folder();
        Path output = workspace.resolve("output");

        SalmonOfflineRun.run(input, settings(), output, "test-commit");

        JsonNode summary = JSON.readTree(Files.readAllBytes(output.resolve("base-15/summary.json")));
        JsonNode attempts = summary.path("outcomes").path("attempts");
        assertThat(attempts.path("units").asInt()).isEqualTo(10);
        assertThat(countsOf(attempts.path("byFirstObservation").path("results"))).containsExactlyInAnyOrder(
            "FULL=3", "SEATS_AVAILABLE=1", "UNKNOWN/ALREADY_PASSED=1", "UNKNOWN/NOT_REACHED=2",
            "UNKNOWN/NO_OBSERVATION=2", "UNKNOWN/NO_VEHICLE_ID=1");
        assertThat(countsOf(attempts.path("byLastObservation").path("results"))).contains("FULL=2",
            "SEATS_AVAILABLE=2");
        assertThat(binsOf(attempts.path("byFirstObservation").path("seatsAvailableByChance"), "seatsAvailable"))
            .containsExactly("0.0:1/1", "0.5:0/2", "0.7:0/1");
        assertThat(binsOf(attempts.path("byLastObservation").path("seatsAvailableByChance"), "seatsAvailable"))
            .containsExactly("0.0:1/1", "0.5:0/2", "0.7:1/1");
        JsonNode difference = attempts.path("firstVersusLast");
        assertThat(difference.path("unitsWithBothKnown").asInt()).isEqualTo(4);
        assertThat(difference.path("sameSeats").asInt()).isEqualTo(3);
        assertThat(difference.path("firstFullButLastHasSeats").asInt()).isEqualTo(1);
        assertThat(difference.path("meanAbsoluteSeatDifference").asDouble()).isEqualTo(0.5);

        JsonNode byAttempts = summary.path("outcomes").path("stayByAttempts");
        assertThat(countsOf(byAttempts.path("byFirstObservation")))
            .containsExactlyInAnyOrder("FAIL=1", "NO_ATTEMPT=1", "SUCCESS=1", "UNKNOWN=5");
        assertThat(countsOf(byAttempts.path("byLastObservation")))
            .containsExactlyInAnyOrder("NO_ATTEMPT=1", "SUCCESS=2", "UNKNOWN=5");
        JsonNode failureByFirst = byAttempts.path("failureByRiskByFirstObservation");
        assertThat(failureByFirst.path("decisionsWithAllVehiclesKnown").asInt()).isEqualTo(2);
        assertThat(failureByFirst.path("excludedDecisions").isEmpty()).isTrue();
        assertThat(binsOf(failureByFirst.path("bins"), "failed")).containsExactly("0.1:1/1", "0.9:0/1");
        JsonNode byWindow = summary.path("outcomes").path("stayByWindow");
        assertThat(countsOf(byWindow.path("byFirstObservation")))
            .containsExactlyInAnyOrder("FAIL=2", "NO_PASSAGE=5", "SUCCESS=1");
        assertThat(binsOf(byWindow.path("failureByRisk").path("bins"), "failed"))
            .containsExactly("0.1:1/1", "0.9:0/1");

        JsonNode pairs = summary.path("outcomes").path("firstTwoAttempts");
        assertThat(pairs.path("byFirstObservation").path("pairsWithBothKnown").asInt()).isEqualTo(1);
        assertThat(pairs.path("byFirstObservation").path("bothFull").asInt()).isEqualTo(1);
        assertThat(pairs.path("byFirstObservation").path("bothFullPredictedByProduct").asDouble())
            .isCloseTo(0.15, within(1e-9));
        assertThat(pairs.path("byLastObservation").path("bothFull").asInt()).isZero();

        JsonNode board = summary.path("diagnostics").path("boardVehicleOutcomes");
        assertThat(board.path("units").asInt()).isEqualTo(7);
        assertThat(board.path("distinctVehicleStopPairs").asInt()).isEqualTo(4);
        assertThat(countsOf(board.path("byFirstObservation").path("results")))
            .containsExactlyInAnyOrder("FULL=4", "SEATS_AVAILABLE=1", "UNKNOWN/NOT_REACHED=2");
        JsonNode nearest = board.path("nearestTwoVehicles").path("byFirstObservation");
        assertThat(nearest.path("pairsWithBothKnown").asInt()).isEqualTo(2);
        assertThat(nearest.path("bothFull").asInt()).isEqualTo(2);
        assertThat(nearest.path("bothFullPredictedByProduct").asDouble()).isCloseTo(0.3, within(1e-9));

        List<String> rows = Files.readAllLines(output.resolve("base-15/decisions.csv"));
        assertThat(rows.get(1)).endsWith(",1:FULL|2:FULL,1:SEATS_AVAILABLE|2:FULL,FAIL,SUCCESS,FAIL");
        assertThat(String.join("\n", rows)).doesNotContain("bus-");
    }

    @Test
    void 정류장을_떠난_첫_관측이_0석이면_가득_참으로_보고_다음_승차_전_마지막_좌석을_본다() throws Exception {
        Path input = new SyntheticInput(Files.createDirectory(workspace.resolve("input")))
            .snapshot(15, 0, arrival("bus-4", 1, 120))
            .folder();
        Path output = workspace.resolve("output");

        SalmonOfflineRun.run(input, settings(), output, "test-commit");

        JsonNode attempts = JSON.readTree(Files.readAllBytes(output.resolve("base-15/summary.json")))
            .path("outcomes").path("attempts");
        assertThat(countsOf(attempts.path("byFirstObservation").path("results"))).containsExactly("FULL=1");
        assertThat(countsOf(attempts.path("byLastObservation").path("results"))).containsExactly("SEATS_AVAILABLE=1");
    }

    @Test
    void 시도의_결과를_일부만_아는_판정은_위험_구간별_실패율에_넣지_않고_따로_센다() throws Exception {
        Path input = new SyntheticInput(Files.createDirectory(workspace.resolve("input")))
            // bus-4는 첫 관측 0석, 마지막 관측 2석이다. bus-5는 이 정류장에 닿는 관측이 없어 결과를 모른다.
            .snapshot(15, 0, arrival("bus-4", 1, 120), arrival("bus-5", 2, 300))
            .folder();
        Path output = workspace.resolve("output");

        SalmonOfflineRun.run(input, settings(), output, "test-commit");

        JsonNode summary = JSON.readTree(Files.readAllBytes(output.resolve("base-15/summary.json")));
        assertThat(countsOf(summary.path("results").path("stayEvaluations"))).containsExactly("EVALUABLE=1");
        JsonNode byAttempts = summary.path("outcomes").path("stayByAttempts");
        assertThat(countsOf(byAttempts.path("byFirstObservation"))).containsExactly("UNKNOWN=1");
        assertThat(countsOf(byAttempts.path("byLastObservation"))).containsExactly("SUCCESS=1");
        JsonNode byFirst = byAttempts.path("failureByRiskByFirstObservation");
        assertThat(byFirst.path("decisionsWithAllVehiclesKnown").asInt()).isZero();
        assertThat(countsOf(byFirst.path("excludedDecisions"))).containsExactly("FULL_OR_UNKNOWN_ONLY=1");
        assertThat(byFirst.path("bins").isEmpty()).isTrue();
        // 한 대에 빈자리가 있어 성공이지만, 다른 한 대를 몰라서 실패율 구간에는 넣지 않는다.
        JsonNode byLast = byAttempts.path("failureByRiskByLastObservation");
        assertThat(byLast.path("decisionsWithAllVehiclesKnown").asInt()).isZero();
        assertThat(countsOf(byLast.path("excludedDecisions"))).containsExactly("SEATS_AVAILABLE_AND_UNKNOWN=1");
        assertThat(byLast.path("bins").isEmpty()).isTrue();
    }

    @Test
    void 입력에_발행이_없어도_그_전에_발행이_있던_판본은_발행_없는_판본으로_읽지_않는다() throws Exception {
        String decision = ROUTE + "," + stationOf(15) + ",15," + BASE.plusSeconds(1);
        Path cut = new SyntheticInput(Files.createDirectory(workspace.resolve("cut")))
            .withoutPublications().decisions(decision).folder();
        Path marked = new SyntheticInput(Files.createDirectory(workspace.resolve("marked")))
            .withoutPublications().firstPublication(10, BASE.minusSeconds(86_400)).decisions(decision).folder();

        SalmonOfflineRun.run(cut, settings(), workspace.resolve("cut-output"), "test-commit");
        SalmonOfflineRun.run(marked, settings(), workspace.resolve("marked-output"), "test-commit");

        // 발행 여부를 모르는 입력은 발행 없는 판본의 보드로 읽히고, 그 판정은 READ_UNPUBLISHED로 따로 센다.
        assertThat(countsOf(boardStatusOf(workspace.resolve("cut-output")))).containsExactly("READ_UNPUBLISHED=1");
        // 제품은 발행이 있던 판본에서 최근 발행 묶음이 없으면 최근 관측 없음으로 거부한다.
        assertThat(countsOf(boardStatusOf(workspace.resolve("marked-output"))))
            .containsExactly("NO_RECENT_OBSERVATION=1");
    }

    @Test
    void 추출이_중간에_멈춘_입력_폴더는_쓰지_않는다() throws Exception {
        Path input = new SyntheticInput(Files.createDirectory(workspace.resolve("input"))).folder();
        Files.writeString(input.resolve("INCOMPLETE"), "");

        assertThatThrownBy(() -> SalmonOfflineRun.run(input, settings(), workspace.resolve("output"), "test-commit"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("INCOMPLETE");
    }

    @Test
    void 판정_한_건당_한_줄을_쓰고_차량_ID_대신_도착_순서를_적는다() throws Exception {
        Path input = new SyntheticInput(Files.createDirectory(workspace.resolve("input")))
            .snapshot(15, 0, arrival("bus-4", 1, 120), arrival("bus-1", 2, 300))
            .snapshot(18, 15, arrival("bus-3", 1, 600))
            .folder();
        Path output = workspace.resolve("output");

        SalmonOfflineRun.run(input, settings(), output, "test-commit");

        List<String> rows = Files.readAllLines(output.resolve("base-15/decisions.csv"));
        assertThat(rows).hasSize(3);
        assertThat(rows.get(1)).startsWith(ROUTE + "," + stationOf(15) + ",15," + BASE
            + ",NO_MOVE_NO_CANDIDATE,QUERIED,2,2,EVALUABLE,0.15");
        assertThat(rows.get(1)).endsWith(
            ",INDEP+SEAT_FORECAST_PROXY,,1*:FOUND/FIRST_POST_STOP_ZERO|2*:FOUND/DEPARTURE_ZERO,READ,3,3,"
            + "1:FULL|2:FULL,1:SEATS_AVAILABLE|2:FULL,FAIL,SUCCESS,FAIL");
        assertThat(rows.get(2)).endsWith(",ABSTAIN_STAY_UNDEFINED,QUERIED,1,1,INSUFFICIENT,,,"
            + "PROBABILITY_UNKNOWN/-/-,1*:VEHICLE_NOT_ON_BOARD/BEYOND_HORIZON,READ,3,0,"
            + "1:UNKNOWN/NOT_REACHED,1:UNKNOWN/NOT_REACHED,UNKNOWN,UNKNOWN,NO_PASSAGE");
        assertThat(String.join("\n", rows)).doesNotContain("bus-");
        assertThat(Files.readString(output.resolve("base-15/summary.json"))).doesNotContain("bus-");
    }

    @Test
    void 판정_자리_파일이_있으면_그_자리만_판정하고_스냅샷이_없거나_낡은_자리는_목록_없음으로_센다() throws Exception {
        Path input = new SyntheticInput(Files.createDirectory(workspace.resolve("input")))
            .snapshot(15, 0, arrival("bus-4", 1, 120), arrival("bus-1", 2, 300))
            .decisions(
                ROUTE + "," + stationOf(15) + ",15," + BASE.plusSeconds(1),
                ROUTE + "," + stationOf(15) + ",15," + BASE.plusSeconds(600),
                ROUTE + "," + stationOf(7) + ",7," + BASE.plusSeconds(1))
            .folder();
        Path output = workspace.resolve("output");

        SalmonOfflineRun.run(input, settings(), output, "test-commit");

        JsonNode summary = JSON.readTree(Files.readAllBytes(output.resolve("base-15/summary.json")));
        assertThat(summary.path("data").path("decisionSource").asString()).isEqualTo("decision.csv");
        JsonNode results = summary.path("results");
        assertThat(countsOf(results.path("reasonCodes")))
            .containsExactlyInAnyOrder("ABSTAIN_STAY_UNDEFINED=2", "NO_MOVE_NO_CANDIDATE=1");
        assertThat(countsOf(results.path("arrivalLists"))).containsExactlyInAnyOrder("QUERIED=1",
            "UNAVAILABLE/NOT_PROVIDED=1", "UNAVAILABLE/STALE=1");
        assertThat(results.path("stayGaps").valueStream().map(gap -> gap.path("kind").asString() + "/"
            + gap.path("list").asString() + "/" + gap.path("reason").asString() + "=" + gap.path("count").asInt()))
            .containsExactlyInAnyOrder("LIST_UNAVAILABLE/CURRENT/NOT_PROVIDED=1",
                "LIST_UNAVAILABLE/CURRENT/STALE=1");
        JsonNode all = results.path("seatForecastLookups").path("allEntries");
        assertThat(countsOf(all.path("foundByProbabilityTarget")))
            .containsExactlyInAnyOrder("DEPARTURE_ZERO=1", "FIRST_POST_STOP_ZERO=1");
        assertThat(all.path("notFoundByCause").isEmpty()).isTrue();
        JsonNode board = summary.path("diagnostics").path("boardAtDecision");
        assertThat(countsOf(board.path("status"))).containsExactlyInAnyOrder("NO_RECENT_OBSERVATION=1", "READ=2");
        assertThat(countsOf(board.path("vehiclesAtStop"))).containsExactlyInAnyOrder("1=1", "3=1");
        assertThat(countsOf(board.path("vehiclesWithForecastAtStop"))).containsExactlyInAnyOrder("0=1", "3=1");
    }

    @Test
    void 규격에_없는_열이_있는_입력은_적재하지_않는다() throws Exception {
        Path input = new SyntheticInput(Files.createDirectory(workspace.resolve("input"))).folder();
        Files.write(input.resolve("vehicle_observation.csv"), List.of(
            "id,observation_batch_id,route_version_id,source_row_number,stop_order,stop_id,running_state,"
                + "passed_stop_order,remaining_seats,plate_number",
            "1001,100,10,1,13," + stationOf(13) + ",2,13,20,synthetic"));

        assertThatThrownBy(() -> SalmonOfflineRun.run(input, settings(), workspace.resolve("output"), "test-commit"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("vehicle_observation.csv")
            .hasMessageContaining("plate_number");
    }

    @Test
    void 출력_폴더가_이미_있으면_덮어쓰지_않고_멈춘다() throws Exception {
        Path output = Files.createDirectory(workspace.resolve("output"));

        assertThatThrownBy(() -> SalmonOfflineRun.run(workspace.resolve("input"), settings(), output, "test-commit"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private Path settings() throws IOException {
        return Files.writeString(workspace.resolve("settings.json"), BASE_SET);
    }

    private static JsonNode boardStatusOf(Path output) throws IOException {
        return JSON.readTree(Files.readAllBytes(output.resolve("base-15/summary.json")))
            .path("diagnostics").path("boardAtDecision").path("status");
    }

    private static List<String> binsOf(JsonNode bins, String event) {
        return bins.valueStream().map(bin -> bin.path("from").asDouble() + ":" + bin.path(event).asInt() + "/"
            + bin.path("count").asInt()).toList();
    }

    private static List<String> countsOf(JsonNode counts) {
        return counts.propertyStream().map(entry -> entry.getKey() + "=" + entry.getValue().asInt()).toList();
    }
}
