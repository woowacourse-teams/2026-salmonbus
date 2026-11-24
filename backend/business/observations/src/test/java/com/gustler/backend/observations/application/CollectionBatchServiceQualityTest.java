package com.gustler.backend.observations.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gustler.backend.gbis.api.GbisLocationResult;
import com.gustler.backend.observations.api.ObservationChange;
import com.gustler.backend.observations.api.ObservationSeatQualityChecker;
import com.gustler.backend.observations.domain.CollectionResult;
import com.gustler.backend.observations.infrastructure.gbis.GbisObservationMapper;
import com.gustler.backend.observations.infrastructure.jpa.CollectionBatchRepository;
import com.gustler.backend.quota.api.ApiCallQuota;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 서비스의 훅 호출 계약을 확인한다. 실제 DB 롤백은 기존 통합 테스트에서 검증한다. */
class CollectionBatchServiceQualityTest {

    private static final OffsetDateTime RECEIVED_AT = OffsetDateTime.parse("2026-08-19T11:14:04+09:00");

    @Test
    void 빈_성공도_품질_훅에_알리고_그_뒤_저장_완료를_전달한다() {
        // given
        CollectionBatchRepository batches = batches();
        List<String> events = new ArrayList<>();
        ObservationSeatQualityChecker quality = (batchId, routeVersionId, hasPositiveSeats) ->
            events.add("quality:" + batchId + ":" + routeVersionId + ":" + hasPositiveSeats);
        CollectionBatchService service = new CollectionBatchService(
                mock(ApiCallQuota.class),
                batches,
                List.of(quality),
                List.of(batch -> events.add("delivery:" + batch.kind()))
        );

        // when
        service.conclude(10,
            at -> GbisObservationMapper.response(new GbisLocationResult.NoVehicles("time"), at), RECEIVED_AT);

        // then
        assertThat(events).containsExactly("quality:10:7:false", "delivery:STORED");
    }

    @Test
    void 품질_실패를_유지하고_저장_완료_알림을_등록하지_않는다() {
        // given
        IllegalStateException rejection = new IllegalStateException("품질 처리 실패");
        ObservationSeatQualityChecker quality = (batchId, routeVersionId, hasPositiveSeats) -> {
            throw rejection;
        };
        List<ObservationChange> deliveries = new ArrayList<>();
        CollectionBatchService service = new CollectionBatchService(
                mock(ApiCallQuota.class),
                batches(),
                List.of(quality),
                List.of(deliveries::add)
        );

        // when
        Throwable failure = catchThrowable(() -> service.conclude(10,
            at -> GbisObservationMapper.response(new GbisLocationResult.NoVehicles("time"), at), RECEIVED_AT));

        // then
        assertSoftly(softly -> {
            softly.assertThat(failure).isSameAs(rejection);
            softly.assertThat(deliveries).isEmpty();
        });
    }

    private CollectionBatchRepository batches() {
        CollectionBatchRepository batches = mock(CollectionBatchRepository.class);
        when(batches.conclude(eq(10L), any(CollectionResult.class), eq(RECEIVED_AT))).thenReturn(7L);
        return batches;
    }
}
