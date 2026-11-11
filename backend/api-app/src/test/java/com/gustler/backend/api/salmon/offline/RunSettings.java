package com.gustler.backend.api.salmon.offline;

import com.gustler.backend.api.salmon.application.ArrivalStopSource.CoverageRule;
import com.gustler.backend.api.salmon.domain.ActionConfig;
import com.gustler.backend.api.salmon.domain.Candidates.CandidateKind;
import com.gustler.backend.api.salmon.domain.Timing.Offset;
import com.gustler.backend.api.salmon.domain.Timing.Span;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실행기에 주는 설정 묶음 파일. 값은 전부 측정용이고 제품 값이 아니다. 묶음마다 판정을 한 번씩 돌린다.
 */
record RunSettings(String notice, List<SettingSet> sets) {
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    RunSettings {
        if (sets == null || sets.isEmpty()) {
            throw new IllegalArgumentException("설정 파일의 sets에 묶음이 하나 이상 있어야 한다");
        }
        sets = List.copyOf(sets);
        if (sets.stream().map(SettingSet::name).distinct().count() != sets.size()) {
            throw new IllegalArgumentException("설정 묶음의 name이 겹친다");
        }
    }

    static RunSettings read(Path file) throws IOException {
        return JSON.readValue(Files.readAllBytes(file), RunSettings.class);
    }

    record SettingSet(String name, Double horizonMinutes, CoverageRule coverageRule, Double scanThreshold,
                      Double gainThreshold, BigDecimal alpha, Double maxWalkMinutes, Double maxDetour,
                      Double resolutionSeconds, Integer epochBudget, List<CandidateKind> kindOrder) {
        SettingSet {
            if (name == null || !name.matches("[A-Za-z0-9._-]+")) {
                throw new IllegalArgumentException("설정 묶음의 name은 영문, 숫자, 점, 밑줄, 붙임표만 쓴다");
            }
            for (Object value : new Object[] {horizonMinutes, coverageRule, scanThreshold, gainThreshold, alpha,
                maxWalkMinutes, maxDetour, resolutionSeconds, epochBudget, kindOrder}) {
                Objects.requireNonNull(value, "설정 묶음 " + name + "에 빠진 값이 있다");
            }
            kindOrder = List.copyOf(kindOrder);
        }

        ActionConfig actionConfig() {
            return new ActionConfig(scanThreshold, gainThreshold, alpha, Offset.ofMinutes(horizonMinutes),
                Span.ofMinutes(maxWalkMinutes), maxDetour, new Span(Math.round(resolutionSeconds * 1_000)),
                epochBudget, kindOrder);
        }

        Map<String, Object> values() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("name", name);
            values.put("horizonMinutes", horizonMinutes);
            values.put("coverageRule", coverageRule.name());
            values.put("scanThreshold", scanThreshold);
            values.put("gainThreshold", gainThreshold);
            values.put("alpha", alpha.toPlainString());
            values.put("maxWalkMinutes", maxWalkMinutes);
            values.put("maxDetour", maxDetour);
            values.put("resolutionSeconds", resolutionSeconds);
            values.put("epochBudget", epochBudget);
            values.put("kindOrder", kindOrder.stream().map(Enum::name).toList());
            return values;
        }
    }
}
