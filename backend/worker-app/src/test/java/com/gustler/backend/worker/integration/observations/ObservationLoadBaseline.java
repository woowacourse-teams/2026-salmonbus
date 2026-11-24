package com.gustler.backend.worker.integration.observations;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 수집 응답별 저장 결과의 고정 기대값이다(테스트 자원 {@code baseline/observation-load.txt}). 값은 기준 구현(JPA 가 관측 한 줄마다
 * INSERT 한 문장을 내던 관측 저장)이 같은 응답에 남긴 것이다. 그 구현은 저장소에 남아 있지 않아 파일을 다시 만들 수 없으므로,
 * 기대값을 쓰는 테스트가 응답과 노선·정류소·배치를 준비하는 값을 바꾸면 기대값과 견줄 수 없다.
 *
 * <p>{@code #}로 시작하는 줄은 설명이다. 나머지 줄은 첫 공백 앞이 종류이고 뒤가 내용이다.
 * <ul>
 *   <li>{@code scenario 이름}: 다음 {@code scenario} 줄 전까지가 그 응답의 기대값이다</li>
 *   <li>{@code observation JSON}: 저장된 관측 한 줄의 모든 열. 상류 행 번호 순서다</li>
 *   <li>{@code batch JSON}: 배치 줄의 모든 열</li>
 *   <li>{@code capacity JSON}: 응답에 나온 차량의 정원 기록 한 줄. 차량 순서다</li>
 *   <li>{@code stored 값 …}: 좌석 품질 훅이 받은 입력. 배치, 노선 판본, 저장 가능한 양수 좌석 존재 여부다</li>
 *   <li>{@code delivered 값 …}: 전달 훅이 받은 입력. 배치, 노선 판본, 변경 종류만 전달한다. 관측 목록은 포함하지 않는다</li>
 * </ul>
 *
 * <p>실행마다 달라지는 값은 이렇게 적는다. JSON 은 PostgreSQL 이 {@code jsonb}를 글자로 바꾼 그대로이고 시각은 UTC 로 읽는다.
 * <ul>
 *   <li>DB 가 매기는 배치 번호와 노선 판본 번호는 이 테스트가 준비한 것과 같으면 {@code "batch"}, {@code "routeVersion"}으로 적는다</li>
 *   <li>DB 가 매기는 관측 번호는 그 관측의 상류 행 번호로 적는다. 관측 줄에서는 {@code id}를 빼고, 정원 기록의 근거 관측은
 *       {@code evidence_source_row}로 적는다</li>
 *   <li>DB 시계로 채우는 정원 기록의 {@code updated_at}과, 관측·배치 줄을 넣은 날의 한국 날짜가 들어가는 {@code source_date}는
 *       뺀다</li>
 * </ul>
 *
 * <p>관측·배치·정원 표에 열을 더하면 그 열이 모든 줄에 나타나고, 수집 주기·정규화 판본을 올리면 배치 줄의 그 값이 달라진다.
 * 그때는 나머지 값이 그대로인지 확인하고 달라진 값만 고친다.
 */
final class ObservationLoadBaseline {
    private static final String PATH = "/baseline/observation-load.txt";

    private final Map<String, List<String>> linesByScenario = new LinkedHashMap<>();

    private ObservationLoadBaseline() {
    }

    static ObservationLoadBaseline read() {
        ObservationLoadBaseline baseline = new ObservationLoadBaseline();
        try (InputStream stream = ObservationLoadBaseline.class.getResourceAsStream(PATH)) {
            if (stream == null) {
                throw new IllegalStateException("기대값 파일이 없다: " + PATH);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            List<String> current = null;
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                current = baseline.accept(line, current);
            }
            return baseline;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** 응답 하나의 기대값을 파일에 적힌 줄 순서로 돌려준다. */
    List<String> linesOf(String scenario) {
        List<String> lines = linesByScenario.get(scenario);
        if (lines == null) {
            throw new IllegalStateException("기대값이 없는 응답이다: " + scenario);
        }
        return lines;
    }

    private List<String> accept(String line, List<String> current) {
        if (line.isBlank() || line.startsWith("#")) {
            return current;
        }
        if (line.startsWith("scenario ")) {
            List<String> lines = new ArrayList<>();
            if (linesByScenario.put(line.substring("scenario ".length()), lines) != null) {
                throw new IllegalStateException("같은 응답이 두 번 적혀 있다: " + line);
            }
            return lines;
        }
        if (current == null) {
            throw new IllegalStateException("scenario 줄보다 앞선 기대값이다: " + line);
        }
        current.add(line);
        return current;
    }
}
