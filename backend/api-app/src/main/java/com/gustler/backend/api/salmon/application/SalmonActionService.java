package com.gustler.backend.api.salmon.application;

import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.salmon.domain.IntegratedJudge;
import com.gustler.backend.api.salmon.domain.IntegratedJudge.Recommendation;
import com.gustler.backend.api.salmon.domain.IntegratedJudge.Situation;
import com.gustler.backend.api.salmon.domain.IntegratedJudge.Terminal;
import com.gustler.backend.api.salmon.guidance.IntegratedGuidance;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 연어행동 조회를 종료 상태 하나로 답한다.
 *
 * <p>플래그가 꺼지면 판정하지 않는다. 켜져 있으면 전광판의 출발 만차 확률과 같은 방향 도보를 운영 값으로 비교한다.
 * 그 확률이 하나가 아니거나 차내 시간, 도착 시각이 없으면 추천하지 않는다.
 */
@Service
public class SalmonActionService {
    private static final Pattern STATION_ID = Pattern.compile("[0-9]{9}");

    private final boolean enabled;
    private final SalmonActionCase actionCase;

    public SalmonActionService(
        @Value("${salmon.action.enabled:false}") boolean enabled,
        SalmonActionCase actionCase
    ) {
        this.enabled = enabled;
        this.actionCase = actionCase;
    }

    public SalmonActionAnswer query(String routeId, String stationId, int stopSequence) {
        if (!enabled) {
            return new SalmonActionAnswer.Off();
        }
        RouteId route = new RouteId(routeId);
        Recommendation recommendation = stopSequence < 1 || stationId == null || !STATION_ID.matcher(stationId).matches()
            ? IntegratedJudge.decide(Situation.contradiction())
            : IntegratedJudge.decide(actionCase.situation(route, stationId, stopSequence));
        Terminal terminal = recommendation.terminal();
        return new SalmonActionAnswer.Decided(terminal, IntegratedGuidance.line(terminal));
    }

    /** 화면에 보낼 종료 상태다. 꺼진 조회는 문장이 없다. */
    public sealed interface SalmonActionAnswer permits SalmonActionAnswer.Off, SalmonActionAnswer.Decided {
        record Off() implements SalmonActionAnswer {
        }

        record Decided(Terminal terminal, String guidance) implements SalmonActionAnswer {
            public Decided {
                if (terminal == null || guidance == null || guidance.isBlank()) {
                    throw new IllegalArgumentException("a decision carries a terminal and a sentence");
                }
            }
        }
    }
}
