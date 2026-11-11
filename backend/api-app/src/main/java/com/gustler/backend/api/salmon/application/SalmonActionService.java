package com.gustler.backend.api.salmon.application;

import com.gustler.backend.api.error.ErrorCode;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.salmon.SalmonActionException;
import com.gustler.backend.api.salmon.domain.IntegratedJudge;
import com.gustler.backend.api.salmon.domain.IntegratedJudge.Terminal;
import com.gustler.backend.api.salmon.guidance.IntegratedGuidance;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 연어행동 조회를 종료 상태 하나로 답한다.
 *
 * <p>플래그가 꺼지면 판정하지 않는다. 켜져 있으면 마감 안에 닿는 차량들의 출발 만차 확률과 같은 방향 도보를
 * 운영 값으로 비교한다. 그 차량 가운데 확률이 없는 차량이 있거나 이동 후보의 차내 시간이 없으면 추천하지 않는다.
 * 노선 ID, 정류장 ID, 순번의 형식이 틀리면 판정하지 않고 요청 오류로 답한다.
 */
@Service
public class SalmonActionService {
    private static final Pattern STATION_ID = Pattern.compile("[0-9]{9}");

    private final SalmonActionSettings settings;
    private final SalmonActionCase actionCase;

    public SalmonActionService(SalmonActionSettings settings, SalmonActionCase actionCase) {
        this.settings = settings;
        this.actionCase = actionCase;
    }

    public SalmonActionAnswer query(String routeId, String stationId, int stopSequence) {
        if (!settings.enabled()) {
            return new SalmonActionAnswer.Off();
        }
        RouteId route = new RouteId(routeId);
        if (stopSequence < 1 || stationId == null || !STATION_ID.matcher(stationId).matches()) {
            throw new SalmonActionException(ErrorCode.INVALID_REQUEST);
        }
        Terminal terminal = IntegratedJudge.decide(actionCase.situation(route, stationId, stopSequence)).terminal();
        return new SalmonActionAnswer.Decided(terminal, IntegratedGuidance.line(terminal));
    }

    /** 화면에 보낼 종료 상태다. 꺼진 조회와 판정하지 못한 상태는 문장이 없다. */
    public sealed interface SalmonActionAnswer permits SalmonActionAnswer.Off, SalmonActionAnswer.Decided {
        record Off() implements SalmonActionAnswer {
        }

        record Decided(Terminal terminal, Optional<String> guidance) implements SalmonActionAnswer {
            public Decided {
                if (terminal == null || guidance == null || guidance.filter(String::isBlank).isPresent()) {
                    throw new IllegalArgumentException("a decision carries a terminal and no blank sentence");
                }
            }
        }
    }
}
