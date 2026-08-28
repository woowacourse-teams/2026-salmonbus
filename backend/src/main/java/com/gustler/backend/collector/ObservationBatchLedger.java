package com.gustler.backend.collector;

import com.gustler.backend.collector.GbisLocationResult.NoVehicles;
import com.gustler.backend.collector.GbisLocationResult.Success;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 위치정보 호출 한 판의 사다리.
 *
 * <p>자리를 잡고 → 보내고 → 닫는다. 각 칸이 묶음 행에 남아서, 프로세스가 중간에 죽어도
 * 그 판이 어디까지 갔는지 알아본다. 특히 "자리를 잡았는데 안 보냈다"는 죽은 프로세스가
 * 남길 수 없는 기록이라, 행을 미리 열어두는 것 말고는 남길 방법이 없다.
 */
@Component
public class ObservationBatchLedger {

    private final CallQuotaLedger callQuotaLedger;
    private final ObservationRepository observationRepository;
    private final ObservationLoader observationLoader;

    public ObservationBatchLedger(
        CallQuotaLedger callQuotaLedger,
        ObservationRepository observationRepository,
        ObservationLoader observationLoader
    ) {
        this.callQuotaLedger = callQuotaLedger;
        this.observationRepository = observationRepository;
        this.observationLoader = observationLoader;
    }

    /** 자리를 먼저 잡고 판을 연다. 못 잡으면 안 보냈다는 판을 열고 거짓을 준다. */
    @Transactional
    public ObservationBatchReservation reserve(
        ObservationAttempt attempt,
        OffsetDateTime reservedAt
    ) {
        if (callQuotaLedger.reserve(CallQuota.BUS_LOCATION, reservedAt)) {
            return new ObservationBatchReservation(observationRepository.openReserved(attempt), true);
        }
        return new ObservationBatchReservation(observationRepository.openNotReserved(attempt), false);
    }

    @Transactional
    public void markDispatching(
        final long batchId,
        OffsetDateTime requestedAt
    ) {
        observationRepository.markDispatching(batchId, requestedAt);
    }

    @Transactional
    public void abandonBeforeSend(
        final long batchId
    ) {
        observationRepository.abandonBeforeSend(batchId);
    }

    /**
     * 응답을 결말로 옮겨 판을 닫는다. 응답이 온 판은 어느 갈래든 여기 하나로 들어온다.
     *
     * <p>상류가 정상으로 답했으면 관측까지 같이 쌓는다. 그 순간 차가 한 대도 없었어도
     * "상류가 준 행이 0" 이라는 사실은 남겨야 해서 빈 목록으로 같은 길을 탄다.
     */
    @Transactional
    public void conclude(
        final long batchId,
        GbisLocationResult result,
        OffsetDateTime responseReceivedAt
    ) {
        ObservationBatchConclusion conclusion = ObservationBatchConclusion.from(result);

        switch (result) {
            case Success success ->
                observationLoader.load(batchId, conclusion, success.buses(), responseReceivedAt);
            case NoVehicles ignored ->
                observationLoader.load(batchId, conclusion, List.of(), responseReceivedAt);
            default -> observationRepository.concludeWithoutRows(batchId, conclusion, responseReceivedAt);
        }
    }
}
