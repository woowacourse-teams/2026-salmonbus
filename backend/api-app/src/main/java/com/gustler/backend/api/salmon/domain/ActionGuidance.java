package com.gustler.backend.api.salmon.domain;

import com.gustler.backend.api.salmon.domain.ActionModel.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class ActionGuidance {
    private ActionGuidance() {
    }

    public static List<String> compose(Verdict verdict, ActionConfig config) {
        List<String> lines = new ArrayList<>();
        switch (verdict.code()) {
            case GO -> lines.addAll(goLines(verdict));
            case STAY_GAIN_BELOW_THRESHOLD -> {
                lines.add(partialPrefix(verdict) + "비교한 경로의 이득이 크지 않아 이동은 권하지 않아요.");
                lines.add(stayLine(verdict.stay(), config));
            }
            case STAY_SCAN_NOT_TRIGGERED -> {
                lines.add("지금은 이동을 권하지 않아요.");
                lines.add(stayLine(verdict.stay(), config));
            }
            case NO_MOVE_INFEASIBLE -> lines.add("허용 범위 안에서 권할 이동이 없어요.");
            case NO_MOVE_NO_CANDIDATE -> lines.add("평가할 이동 후보가 없어요.");
            case ABSTAIN_INSUFFICIENT_EVIDENCE -> lines.add("이동 후보를 판단할 근거가 부족해요.");
            case ABSTAIN_STAY_UNDEFINED -> lines.add("현재 대기 상황을 판단할 근거가 부족해요.");
        }
        return List.copyOf(lines);
    }

    private static List<String> goLines(Verdict verdict) {
        CandidateRecord chosen = verdict.candidates().stream()
            .filter(record -> verdict.chosen().filter(record.id()::equals).isPresent())
            .findFirst().orElseThrow();
        Outcome outcome = chosen.outcome();
        List<String> lines = new ArrayList<>();
        lines.add(partialPrefix(verdict) + "이동하는 경로를 안내해요.");
        if (chosen.kind() == CandidateKind.REBOARD) {
            lines.add("맞은편 정류장으로 이동해요.");
            lines.add(attemptLine(outcome.first()));
            lines.add("타면 정해진 하차 정류장에서 내려요.");
            lines.add("길을 건너 원하는 방향 정류장으로 가요.");
            lines.add("맞은편에서 약 " + minutesText(outcome.first().get(0).arrival().minutes()) + "분 뒤 차를 탔다면 지금부터 "
                + attemptLine(outcome.second()));
        } else {
            lines.add("정류장으로 걸어간 뒤 차를 시도해요.");
            lines.add(attemptLine(outcome.first()));
        }
        lines.add(basisLine(outcome.evaluation()));
        outcome.margin().ifPresent(margin -> lines.add(marginLine(margin, outcome.connection())));
        return lines;
    }

    private static String basisLine(Evaluation evaluation) {
        boolean derived = evaluation instanceof Evaluable evaluable && evaluable.basis() == TimeBasis.DERIVED;
        boolean independent = evaluation instanceof Evaluable evaluable && evaluable.assumption() == Assumption.INDEP;
        StringBuilder text = new StringBuilder("입력된 차량 기준이에요.");
        if (derived) {
            text.append(" 구간시간은 추정한 값이에요.");
        }
        if (independent) {
            text.append(" 확률은 추정이에요.");
        }
        return text.toString();
    }

    private static String marginLine(Span margin, Optional<ConnectionTag> connection) {
        String minutes = minutesText(margin.minutes());
        return switch (connection.orElse(ConnectionTag.MARGIN_ABOVE_BOUND)) {
            case MARGIN_WITHIN_BOUND -> "연결 여유는 약 " + minutes + "분이고, 오차 범위 안이에요.";
            case BOUND_UNKNOWN -> "연결 여유는 약 " + minutes + "분이고, 이 여유의 근거가 부족해요.";
            case MARGIN_ABOVE_BOUND -> "연결 여유는 약 " + minutes + "분이지만 연결을 확정할 수는 없어요.";
        };
    }

    private static String stayLine(Outcome stay, ActionConfig config) {
        List<Attempt> attempts = stay.first();
        if (attempts.isEmpty()) {
            return "입력된 차량 기준으로 " + minutesText(config.horizon().minutes())
                + "분 안에 이 정류장에서 시도할 차가 없어요.";
        }
        if (attempts.size() == 1) {
            return "이 정류장에서는 약 " + minutesText(attempts.get(0).arrival().minutes()) + "분 뒤 차를 시도할 수 있어요.";
        }
        return attemptLine(attempts);
    }

    private static String attemptLine(List<Attempt> attempts) {
        String first = "약 " + minutesText(attempts.get(0).arrival().minutes()) + "분 뒤 차를 시도해요.";
        if (attempts.size() == 1) {
            return first;
        }
        return first + " 못 타면 약 " + minutesText(attempts.get(1).arrival().minutes()) + "분 뒤 차를 시도할 수 있어요.";
    }

    private static String partialPrefix(Verdict verdict) {
        return verdict.partialComparison() ? "비교 가능한 경로 중 " : "";
    }

    private static String minutesText(double minutes) {
        String text = String.format(Locale.ROOT, "%.1f", minutes);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }
}
