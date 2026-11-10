package com.gustler.backend.api.salmon.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.salmon.application.SalmonActionService.SalmonActionAnswer;
import com.gustler.backend.api.salmon.domain.IntegratedJudge.Coverage;
import com.gustler.backend.api.salmon.domain.IntegratedJudge.Listing;
import com.gustler.backend.api.salmon.domain.IntegratedJudge.Situation;
import com.gustler.backend.api.salmon.domain.IntegratedJudge.Terminal;
import com.gustler.backend.api.salmon.domain.RiskMath.Risk;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SalmonActionServiceTest {
    @Test
    void 꺼져_있으면_상황을_읽지_않는다() {
        SalmonActionCase cases = (routeId, stationId, stopSequence) -> {
            throw new AssertionError("disabled query must not read a situation");
        };

        SalmonActionAnswer answer = new SalmonActionService(false, cases).query("204000057", "205000231", 5);

        assertThat(answer).isInstanceOf(SalmonActionAnswer.Off.class);
    }

    @Test
    void 정류장_형식이_아니면_상황을_읽지_않는다() {
        SalmonActionCase cases = (routeId, stationId, stopSequence) -> {
            throw new AssertionError("bad shape must not read a situation");
        };

        SalmonActionAnswer answer = new SalmonActionService(true, cases).query("204000057", "231", 1);

        assertThat(answer).isEqualTo(new SalmonActionAnswer.Decided(Terminal.INVALID_INPUT,
            "요청 값이 서로 맞지 않아 판정하지 않았어요."));
    }

    @Test
    void 켜져_있으면_읽어_온_상황의_문장을_반환한다() {
        Situation lowStay = new Situation(true, true, Optional.of(new Risk(0.3, 0.3)), Coverage.UNKNOWN, false,
            Listing.COMPLETE, 0.5, 0, List.of());
        SalmonActionService service = new SalmonActionService(true, (routeId, stationId, stopSequence) -> lowStay);

        SalmonActionAnswer answer = service.query("204000057", "205000231", 5);

        assertThat(answer).isEqualTo(new SalmonActionAnswer.Decided(Terminal.NO_MOVE_POLICY,
            "지금 정보에서는 기다려도 실패 위험이 낮아 이동을 찾지 않아요."));
    }
}
