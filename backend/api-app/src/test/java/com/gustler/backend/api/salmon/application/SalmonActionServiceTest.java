package com.gustler.backend.api.salmon.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.api.error.ErrorCode;
import com.gustler.backend.api.route.InvalidRouteIdException;
import com.gustler.backend.api.salmon.SalmonActionException;
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

        SalmonActionAnswer answer = new SalmonActionService(disabled(), cases).query("204000057", "205000231", 5);

        assertThat(answer).isInstanceOf(SalmonActionAnswer.Off.class);
    }

    @Test
    void 정류장_ID나_순번의_형식이_틀리면_상황을_읽지_않고_요청_오류다() {
        SalmonActionCase cases = (routeId, stationId, stopSequence) -> {
            throw new AssertionError("bad shape must not read a situation");
        };
        SalmonActionService service = new SalmonActionService(SalmonSettingsFixture.adopted(), cases);

        assertThatThrownBy(() -> service.query("204000057", "231", 1))
            .isInstanceOfSatisfying(SalmonActionException.class,
                failure -> assertThat(failure.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThatThrownBy(() -> service.query("204000057", "205000231", 0))
            .isInstanceOf(SalmonActionException.class);
        assertThatThrownBy(() -> service.query("2040", "205000231", 1))
            .isInstanceOf(InvalidRouteIdException.class);
    }

    @Test
    void 할_행동이_정해진_상태는_문장을_싣는다() {
        Situation lowStay = new Situation(true, true, Optional.of(new Risk(0.3, 0.3)), Coverage.UNKNOWN, false,
            Listing.COMPLETE, 0.5, 0, List.of());
        SalmonActionService service = new SalmonActionService(SalmonSettingsFixture.adopted(),
            (routeId, stationId, stopSequence) -> lowStay);

        assertThat(service.query("204000057", "205000231", 5)).isEqualTo(
            new SalmonActionAnswer.Decided(Terminal.NO_MOVE_POLICY, Optional.of("지금은 정류장을 옮기지 않아도 돼요.")));
    }

    @Test
    void 판정하지_못한_상태는_문장_없이_종료_상태만_싣는다() {
        SalmonActionService service = new SalmonActionService(SalmonSettingsFixture.adopted(),
            (routeId, stationId, stopSequence) -> Situation.missingStay());

        assertThat(service.query("204000057", "205000231", 5))
            .isEqualTo(new SalmonActionAnswer.Decided(Terminal.ABSTAIN_EVIDENCE, Optional.empty()));
    }

    private static SalmonActionSettings disabled() {
        SalmonActionSettings on = SalmonSettingsFixture.adopted();
        return new SalmonActionSettings(false, on.maxWalk(), on.gainThreshold(), on.horizon(), on.maxInVehicle(),
            on.eta(), on.alpha(), on.maxTransfers(), on.minConnection());
    }
}
