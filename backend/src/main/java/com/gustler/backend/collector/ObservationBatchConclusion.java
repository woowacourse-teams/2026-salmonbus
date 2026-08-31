package com.gustler.backend.collector;

import com.gustler.backend.collector.GbisLocationResult.DailyQuotaExceeded;
import com.gustler.backend.collector.GbisLocationResult.GatewayRejected;
import com.gustler.backend.collector.GbisLocationResult.GbisSystemError;
import com.gustler.backend.collector.GbisLocationResult.MissingRequiredParameter;
import com.gustler.backend.collector.GbisLocationResult.NoResponse;
import com.gustler.backend.collector.GbisLocationResult.NoVehicles;
import com.gustler.backend.collector.GbisLocationResult.PerSecondQuotaExceeded;
import com.gustler.backend.collector.GbisLocationResult.Success;
import com.gustler.backend.collector.GbisLocationResult.UnknownGbisResultCode;
import com.gustler.backend.collector.GbisLocationResult.UnreadableResponse;

/**
 * 상류 응답 한 갈래를 판의 결말로 옮긴 것.
 *
 * <p>GbisLocationResult 는 "상류가 뭐라고 답했나"를 나눈 것이고 결말은 "이 판이 어디까지 갔나"다.
 * 축이 달라서 RESERVED 나 UNKNOWN_AFTER_DISPATCH 는 응답 갈래에 없다. 둘을 잇는 번역이 여기 있다.
 */
public record ObservationBatchConclusion(
    ObservationBatchOutcome outcome,
    ObservationBatchFailureCode failureCode,
    Integer upstreamResultCode
) {

    public static ObservationBatchConclusion from(
        GbisLocationResult result
    ) {
        return switch (result) {
            case Success success -> succeededWith(success.buses().size());
            // 호출은 정상인데 그 순간 운행 차량이 없다. 실패가 아니고 운행이 끝났다는 뜻도 아니다.
            // 실측에서 심야에 이 응답이 되풀이된 뒤 다음 수집에서 차량이 다시 나타났다.
            case NoVehicles ignored -> new ObservationBatchConclusion(
                ObservationBatchOutcome.SUCCESS_EMPTY, null, GbisResultCode.NO_VEHICLES.code());
            case DailyQuotaExceeded ignored ->
                rejectedBy(ObservationBatchFailureCode.DAILY_QUOTA_EXCEEDED, null);
            case PerSecondQuotaExceeded ignored ->
                rejectedBy(ObservationBatchFailureCode.PER_SECOND_QUOTA_EXCEEDED, null);
            // 게이트웨이가 GBIS 앞에서 막은 것이라 GBIS 결과 코드가 없다.
            case GatewayRejected ignored ->
                rejectedBy(ObservationBatchFailureCode.UPSTREAM_ERROR, null);
            case GbisSystemError ignored ->
                rejectedBy(ObservationBatchFailureCode.UPSTREAM_ERROR, GbisResultCode.SYSTEM_FAILURE.code());
            case MissingRequiredParameter ignored ->
                rejectedBy(ObservationBatchFailureCode.UPSTREAM_ERROR, GbisResultCode.PARAMETER_MISSING.code());
            case UnknownGbisResultCode unknown ->
                rejectedBy(ObservationBatchFailureCode.UPSTREAM_ERROR, unknown.resultCode());
            case NoResponse ignored -> new ObservationBatchConclusion(
                ObservationBatchOutcome.UNKNOWN_AFTER_DISPATCH, null, null);
            // 결과 코드가 0 이었는데 본문이 없던 경우도 여기로 온다. 갈래가 코드를 안 들고 나와 0 이 남지 않는다.
            case UnreadableResponse ignored -> new ObservationBatchConclusion(
                ObservationBatchOutcome.FAILED_UNREADABLE, null, null);
        };
    }

    private static ObservationBatchConclusion succeededWith(
        final int providerRows
    ) {
        return new ObservationBatchConclusion(
            ObservationBatchOutcome.forProviderRows(providerRows), null, GbisResultCode.SUCCESS.code());
    }

    private static ObservationBatchConclusion rejectedBy(
        ObservationBatchFailureCode failureCode,
        Integer upstreamResultCode
    ) {
        return new ObservationBatchConclusion(
            ObservationBatchOutcome.FAILED_UPSTREAM, failureCode, upstreamResultCode);
    }
}
