package com.gustler.backend.api.salmon.domain;

import com.gustler.backend.api.salmon.domain.RiskMath.Bound;
import com.gustler.backend.api.salmon.domain.RiskMath.Risk;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 대기와 이동을 같은 위험 범위로 비교해 v2 종료 상태 하나를 고른다.
 *
 * <p>강한 이동 추천은 관측 범위가 시간 창을 덮거나, 누락을 범위에 넣은 보수적 비교에서
 * 이득 하한이 역치 이상일 때만 낸다. 점추정만 넘거나 제한된 관측에서 숫자만 넘는 경우는 추천하지 않는다.
 */
public final class IntegratedJudge {
    private static final double EPS = 1e-9;

    private IntegratedJudge() {
    }

    public enum Terminal {
        GO,
        NO_MOVE_POLICY,
        NO_MOVE_NO_GAIN,
        NO_MOVE_NO_CANDIDATE,
        NO_MOVE_INFEASIBLE,
        ABSTAIN_EVIDENCE,
        ABSTAIN_UNCERTAIN_GAIN,
        INVALID_INPUT,
        WINDOW_EXPIRED
    }

    public enum Coverage { MODEL_WINDOW, RESTRICTED, UNKNOWN }

    public enum Admission { ALLOWED, VIOLATED, UNCONFIRMED }

    public enum Listing { COMPLETE, UNFINISHED }

    public record Move(String id, Admission admission, Optional<Risk> risk, boolean uncertaintyReflected,
                        double walkMinutes, int transfers, Optional<Double> inVehicleMinutes) {
        public Move {
            if (id == null || admission == null || risk == null || inVehicleMinutes == null) {
                throw new IllegalArgumentException("move must be complete");
            }
        }
    }

    public record Situation(boolean modelConsistent, boolean windowOpen, Optional<Risk> stay, Coverage coverage,
                             boolean omissionsIncluded, Listing listing, double gainThreshold, double eta,
                             List<Move> moves) {
        public Situation {
            if (stay == null || coverage == null || listing == null || moves == null) {
                throw new IllegalArgumentException("situation must be complete");
            }
            moves = List.copyOf(moves);
        }

        /** 대기 위험이 없어 비교를 열 수 없는 상황이다. 역치 숫자는 비교에 쓰이지 않는다. */
        public static Situation missingStay() {
            return new Situation(true, true, Optional.empty(), Coverage.UNKNOWN, false, Listing.COMPLETE, 1, 0,
                List.of());
        }

        public static Situation contradiction() {
            return new Situation(false, true, Optional.empty(), Coverage.UNKNOWN, false, Listing.COMPLETE, 1, 0,
                List.of());
        }
    }

    public record Recommendation(Terminal terminal, Optional<String> chosenId, Optional<Bound> delta, boolean partial) {
        public Recommendation {
            if (terminal == null || chosenId == null || delta == null) {
                throw new IllegalArgumentException("recommendation must be complete");
            }
            boolean chosen = terminal == Terminal.GO;
            if (chosen != chosenId.isPresent() || chosen != delta.isPresent() || (!chosen && partial)) {
                throw new IllegalArgumentException("only GO carries a chosen move");
            }
        }
    }

    public static Recommendation decide(Situation situation) {
        if (!situation.modelConsistent() || !legalThreshold(situation.gainThreshold()) || !legalEta(situation.eta())
            || invalid(situation.moves())) {
            return plain(Terminal.INVALID_INPUT);
        }
        if (!situation.windowOpen()) {
            return plain(Terminal.WINDOW_EXPIRED);
        }
        if (situation.stay().isEmpty()) {
            return plain(Terminal.ABSTAIN_EVIDENCE);
        }

        Risk stay = situation.stay().get();
        double threshold = situation.gainThreshold();
        if (stay.high() < threshold) {
            return plain(Terminal.NO_MOVE_POLICY);
        }

        boolean strong = situation.coverage() == Coverage.MODEL_WINDOW || situation.omissionsIncluded();
        List<Scored> qualified = new ArrayList<>();
        boolean weakNumeric = false;
        boolean uncertain = false;
        boolean unresolved = situation.listing() == Listing.UNFINISHED;
        boolean sawAllowed = false;
        boolean sawOtherThanViolated = false;
        boolean everyComparedMissesGain = true;

        for (Move move : situation.moves()) {
            if (move.admission() == Admission.VIOLATED) {
                continue;
            }
            sawOtherThanViolated = true;
            if (move.admission() == Admission.UNCONFIRMED || move.risk().isEmpty()) {
                unresolved = true;
                everyComparedMissesGain = false;
                continue;
            }
            sawAllowed = true;
            Bound gain = RiskMath.delta(stay, move.risk().get());
            boolean numericGo = gain.low() > 0 && gain.low() >= threshold - EPS;
            boolean numericMiss = gain.high() < threshold - EPS;
            if (!move.uncertaintyReflected()) {
                unresolved = true;
                everyComparedMissesGain = false;
                if (numericGo || !numericMiss) {
                    uncertain = true;
                }
                continue;
            }
            if (numericGo && strong) {
                qualified.add(new Scored(move, gain));
                everyComparedMissesGain = false;
            } else if (numericGo) {
                weakNumeric = true;
                everyComparedMissesGain = false;
            } else if (!numericMiss) {
                uncertain = true;
                everyComparedMissesGain = false;
            }
        }

        if (!qualified.isEmpty()) {
            Move chosen = pick(qualified, situation.eta());
            return new Recommendation(Terminal.GO, Optional.of(chosen.id()),
                Optional.of(RiskMath.delta(stay, chosen.risk().orElseThrow())), unresolved);
        }
        if (weakNumeric) {
            return plain(Terminal.ABSTAIN_EVIDENCE);
        }
        if (uncertain) {
            return plain(Terminal.ABSTAIN_UNCERTAIN_GAIN);
        }
        if (unresolved) {
            return plain(Terminal.ABSTAIN_EVIDENCE);
        }
        if (situation.moves().isEmpty()) {
            return plain(situation.listing() == Listing.COMPLETE
                ? Terminal.NO_MOVE_NO_CANDIDATE
                : Terminal.ABSTAIN_EVIDENCE);
        }
        if (!sawOtherThanViolated) {
            return plain(Terminal.NO_MOVE_INFEASIBLE);
        }
        if (strong && sawAllowed && everyComparedMissesGain) {
            return plain(Terminal.NO_MOVE_NO_GAIN);
        }
        return plain(Terminal.ABSTAIN_EVIDENCE);
    }

    private static Move pick(List<Scored> qualified, double eta) {
        double bestHigh = qualified.stream()
            .mapToDouble(scored -> scored.move().risk().orElseThrow().high())
            .min()
            .orElseThrow();
        return qualified.stream()
            .filter(scored -> scored.move().risk().orElseThrow().high() <= bestHigh + eta + EPS)
            .map(Scored::move)
            .min(Comparator.comparingDouble(Move::walkMinutes)
                .thenComparingInt(Move::transfers)
                .thenComparingDouble(move -> move.inVehicleMinutes().orElse(Double.POSITIVE_INFINITY))
                .thenComparing(Move::id))
            .orElseThrow();
    }

    private static boolean invalid(List<Move> moves) {
        Set<String> ids = new HashSet<>();
        for (Move move : moves) {
            if (move.id().isBlank() || !ids.add(move.id())) {
                return true;
            }
            if (!Double.isFinite(move.walkMinutes()) || move.walkMinutes() < 0 || move.transfers() < 0) {
                return true;
            }
            if (move.inVehicleMinutes().filter(minutes -> !Double.isFinite(minutes) || minutes < 0).isPresent()) {
                return true;
            }
            if (move.admission() == Admission.ALLOWED && move.inVehicleMinutes().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean legalThreshold(double threshold) {
        return Double.isFinite(threshold) && threshold > 0 && threshold <= 1;
    }

    private static boolean legalEta(double eta) {
        return Double.isFinite(eta) && eta >= 0;
    }

    private static Recommendation plain(Terminal terminal) {
        return new Recommendation(terminal, Optional.empty(), Optional.empty(), false);
    }

    private record Scored(Move move, Bound gain) {
    }
}
