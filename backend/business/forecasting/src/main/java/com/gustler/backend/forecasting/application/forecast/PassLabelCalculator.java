package com.gustler.backend.forecasting.application.forecast;

import com.gustler.backend.forecasting.api.forecast.PassClosed;
import tools.jackson.databind.JsonNode;

/**
 * 통과 하나의 정답을 계산한다.
 *
 * <p>구현은 확정 입력 기준 정답 계산({@code AlightingAwarePassLabel.afterSeats})을 그대로 불러야 한다. 판정 순서는
 * 시간 칸 계수 없음, 직전 통과 없음, 앞차 간격, 정류장 계수 없음, 정원 근거, 정원 초과 관측, 계수 길이, 정산 완료다. 계수나 관측이
 * 계산식의 전제를 어기면 {@link IllegalArgumentException}을 던질 수 있다.
 */
public interface PassLabelCalculator {

    /**
     * 정답을 계산한다.
     *
     * @param pass 정답을 낼 통과
     * @param previous 같은 정류장, 같은 출발 날짜에서 먼저 닫힌 직전 통과. 없으면 비어 있다
     * @param evidence 예보 당시의 정산 근거. 공유 근거에 정원({@code capacity})과 목표 정류장의 계수({@code alighting.stops})를
     *                 합친 구조다
     * @return 계산 결과
     */
    PassLabelCalculation calculate(PassClosed pass, PassClosed previous, JsonNode evidence);

    /**
     * 정답 계산 결과다. 정산을 완료하면 값, 계수 해시가 있고 사유가 없다. 지원 안 함이면 사유만 있다.
     *
     * @param settled 정산을 완료하면 참
     * @param value 정답 값
     * @param reason 지원 안 함 사유
     * @param parameterHash 계산에 쓴 계수의 SHA-256 16진수
     */
    record PassLabelCalculation(
        boolean settled,
        Double value,
        String reason,
        String parameterHash
    ) {

        public PassLabelCalculation {
            if (settled != (value != null) || settled == (reason != null)) {
                throw new IllegalArgumentException("정산에 성공하면 값만, 지원하지 않으면 사유만 있어야 한다");
            }
        }

        /**
         * 지원 안 함 결과를 만든다.
         *
         * @param reason 사유
         * @return 결과
         */
        public static PassLabelCalculation unsupported(String reason) {
            return new PassLabelCalculation(
                    false,
                    null,
                    reason,
                    null
            );
        }
    }
}
