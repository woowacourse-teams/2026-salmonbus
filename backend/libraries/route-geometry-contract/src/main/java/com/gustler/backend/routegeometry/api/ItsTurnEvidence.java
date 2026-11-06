package com.gustler.backend.routegeometry.api;

import java.util.Objects;

/**
 * ITS 구축기준 제2026-344호 4.3.2/5.2.6의 코드 해석.
 * 코드 해석은 via 경로의 해석이나 특정 시각의 통행 허가가 아니다.
 * 원문 코드와 알려지지 않은 값도 그대로 보존한다.
 */
public record ItsTurnEvidence(
        String nodeId, String turnId, String startLink, String endLink,
        String typeCode, String operationCode) {
    public static final String CODEBOOK_VERSION = "MOLIT-2026-344:5.2.6";
    public static final String CODEBOOK_URL =
            "https://www.law.go.kr/admRulInfoR.do?admRulSeq=2100000281252";

    public ItsTurnEvidence {
        requireId(nodeId);
        requireId(startLink);
        requireId(endLink);
        if (turnId == null || turnId.isBlank()) {
            throw new IllegalArgumentException("회전제한번호가 필요하다");
        }
        Objects.requireNonNull(typeCode);
        Objects.requireNonNull(operationCode);
    }

    public enum Meaning {
        UNPROTECTED_TURN, BUS_ONLY_TURN, TURN_PROHIBITED,
        U_TURN, P_TURN, LEFT_PROHIBITED, STRAIGHT_PROHIBITED,
        RIGHT_PROHIBITED, UNKNOWN
    }

    public enum Operation { ALL_DAY, PART_TIME, UNKNOWN }

    public Meaning meaning() {
        return switch (typeCode) {
            case "001" -> Meaning.UNPROTECTED_TURN;
            case "002" -> Meaning.BUS_ONLY_TURN;
            case "003" -> Meaning.TURN_PROHIBITED;
            case "011" -> Meaning.U_TURN;
            case "012" -> Meaning.P_TURN;
            case "101" -> Meaning.LEFT_PROHIBITED;
            case "102" -> Meaning.STRAIGHT_PROHIBITED;
            case "103" -> Meaning.RIGHT_PROHIBITED;
            default -> Meaning.UNKNOWN;
        };
    }

    public Operation operation() {
        return switch (operationCode) {
            case "0" -> Operation.ALL_DAY;
            case "1" -> Operation.PART_TIME;
            default -> Operation.UNKNOWN;
        };
    }

    /** Only identifies an explicit prohibition in the row; no graph-path decision. */
    public boolean describesProhibition() {
        return switch (meaning()) {
            case TURN_PROHIBITED, LEFT_PROHIBITED, STRAIGHT_PROHIBITED, RIGHT_PROHIBITED -> true;
            default -> false;
        };
    }

    /** A partial-time code needs separate validated schedule evidence before application. */
    public boolean requiresScheduleEvidence() {
        return operation() == Operation.PART_TIME;
    }

    private static void requireId(String value) {
        if (value == null || !value.matches("[0-9]{10}")) {
            throw new IllegalArgumentException("구간단위 ITS 식별자는 10자리 문자열이어야 한다");
        }
    }
}
