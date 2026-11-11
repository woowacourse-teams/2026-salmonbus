package com.gustler.backend.api.salmon.application;

import com.gustler.backend.api.salmon.domain.IntegratedJudge.Admission;
import com.gustler.backend.api.salmon.domain.IntegratedJudge.Coverage;
import com.gustler.backend.api.salmon.domain.IntegratedJudge.Listing;
import com.gustler.backend.api.salmon.domain.IntegratedJudge.Move;
import com.gustler.backend.api.salmon.domain.IntegratedJudge.Situation;
import com.gustler.backend.api.salmon.domain.RiskMath;
import com.gustler.backend.api.salmon.domain.RiskMath.Risk;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 출발 만차 확률과 도보를 운영 값으로 판정 상황에 담는다.
 *
 * <p>대기 위험은 마감 안에 닿는 차량이 모두 만차로 떠날 확률의 범위다. 한 대면 {@code Risk(p, p)}이고, 둘 이상이면
 * 독립으로 곱하지 않고 주변확률만으로 정해지는 범위를 쓴다. 요청 시각이 마감의 시작이라 이 조회에서 창은 열려 있다.
 *
 * <p>상한은 도착 목록에 보이는 차량의 확률로만 만든다. 가려진 차량은 도착 시각이 추정이라 상한을 낮추지 못하고
 * 하한에만 들어간다. 가려진 차량을 넣어야만 상한이 이득 역치 아래로 내려가는 경우에는 대기 위험을 확정하지 않는다.
 * 추정한 도착 시각만으로 옮기지 않아도 된다는 답이 나가지 않게 한다.
 * 이동 확률은 점추정이고 관측 범위는 모르므로 추천으로 닫지 않는다.
 */
@Component
public class SalmonActionAssessment {
    private final SalmonActionSettings settings;

    public SalmonActionAssessment(SalmonActionSettings settings) {
        this.settings = settings;
    }

    /**
     * @param listedFullProbabilities 도착 목록에 보이는 차량의 출발 만차 확률
     * @param hiddenFullProbabilities 도착을 이어 붙인 가려진 차량의 출발 만차 확률. 전광판에 확률이 없으면 빈 값
     */
    public Situation situation(List<Double> listedFullProbabilities, List<Optional<Double>> hiddenFullProbabilities,
                               List<SalmonActionWalk> walks, boolean listingComplete) {
        if (listedFullProbabilities.isEmpty()
            || !listedFullProbabilities.stream().allMatch(SalmonActionAssessment::usable)) {
            return Situation.missingStay();
        }
        Risk listed = RiskMath.everyAttemptFails(listedFullProbabilities);
        List<Optional<Double>> hidden = hiddenFullProbabilities.stream()
            .map(probability -> probability.filter(SalmonActionAssessment::usable))
            .toList();
        double threshold = settings.gainThreshold();
        boolean hiddenAloneLowersRisk = hidden.stream()
            .anyMatch(probability -> probability.isEmpty() || probability.get() < threshold);
        if (listed.high() >= threshold && hiddenAloneLowersRisk) {
            return Situation.missingStay();
        }
        List<Double> everyVehicle = new ArrayList<>(listedFullProbabilities);
        hidden.forEach(probability -> everyVehicle.add(probability.orElse(0.0)));
        Risk stay = new Risk(RiskMath.everyAttemptFails(everyVehicle).low(), listed.high());
        List<Move> moves = new ArrayList<>();
        for (SalmonActionWalk walk : walks) {
            moves.add(move(walk));
        }
        return new Situation(true, true, Optional.of(stay), Coverage.UNKNOWN, false,
            listingComplete ? Listing.COMPLETE : Listing.UNFINISHED,
            settings.gainThreshold(), settings.eta(), moves);
    }

    private Move move(SalmonActionWalk walk) {
        Optional<Double> inVehicleMinutes = walk.inVehicleMillis().map(SalmonActionAssessment::minutes);
        boolean violated = settings.walkExceeded(walk.walkMillis())
            || settings.transfersExceeded(walk.transfers())
            || walk.inVehicleMillis().filter(settings::inVehicleExceeded).isPresent()
            || settings.walkOutlastsHorizon(walk.walkMillis())
            || missedConnection(walk);
        if (violated) {
            return new Move(walk.id(), Admission.VIOLATED, Optional.empty(), false,
                minutes(walk.walkMillis()), walk.transfers(), inVehicleMinutes);
        }
        if (walk.inVehicleMillis().isEmpty()) {
            return new Move(walk.id(), Admission.UNCONFIRMED, Optional.empty(), false,
                minutes(walk.walkMillis()), walk.transfers(), Optional.empty());
        }
        return new Move(walk.id(), Admission.ALLOWED, Optional.empty(), false,
            minutes(walk.walkMillis()), walk.transfers(), inVehicleMinutes);
    }

    private boolean missedConnection(SalmonActionWalk walk) {
        return walk.connection()
            .filter(link -> !settings.connected(link.busArrivalMillis(), link.personReadyMillis()))
            .isPresent();
    }

    private static boolean usable(Double probability) {
        return probability != null && Double.isFinite(probability) && probability >= 0 && probability <= 1;
    }

    private static double minutes(long millis) {
        return millis / 60_000.0;
    }
}
