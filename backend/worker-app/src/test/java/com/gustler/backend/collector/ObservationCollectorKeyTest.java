package com.gustler.backend.collector;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ObservationCollectorKeyTest {

    private static final String ROUTE_3330 = "204000057";
    private static final long ROUTE_VERSION_ID = 1L;
    private static final long BATCH_ID = 1L;
    private static final String KEY_ALIAS_B = "b";
    private static final Clock KOREA_NOON =
        Clock.fixed(Instant.parse("2026-08-28T03:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Mock
    private RouteCatalogLoader routeCatalogLoader;

    @Mock
    private ObservationBatchLedger batchLedger;

    @Mock
    private GbisLocationSource locationSource;

    @Test
    void 예약한_키로_보내기_직전_자리를_확인하고_상류를_부른다() {
        // given
        ObservationCollector collector =
            new ObservationCollector(routeCatalogLoader, batchLedger, locationSource, KOREA_NOON);
        given(routeCatalogLoader.currentVersionOf(eq(ROUTE_3330), any()))
            .willReturn(OptionalLong.of(ROUTE_VERSION_ID));
        given(batchLedger.reserve(any(), any()))
            .willReturn(new ObservationBatchReservation(BATCH_ID, true, KEY_ALIAS_B));
        given(batchLedger.markDispatching(anyLong(), any(), any(), eq(KEY_ALIAS_B))).willReturn(true);

        // when
        collector.collectOnce(ROUTE_3330);

        // then
        then(locationSource).should().read(ROUTE_3330, KEY_ALIAS_B);
    }
}
