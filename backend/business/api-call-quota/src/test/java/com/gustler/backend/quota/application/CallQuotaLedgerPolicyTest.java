package com.gustler.backend.quota.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gustler.backend.quota.api.CallQuotaPolicy;
import com.gustler.backend.quota.domain.CallQuotaRepository;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class CallQuotaLedgerPolicyTest {

    private static final OffsetDateTime REQUESTED_AT = OffsetDateTime.parse("2026-08-28T14:59:59Z");

    private final CallQuotaRepository repository = mock(CallQuotaRepository.class);
    private final CallQuotaLedger ledger = new CallQuotaLedger(repository, CallQuotaPolicy.sameForEveryApi(1));

    @Test
    void 두_호출을_예약할_수_없는_설정이면_저장소를_변경하지_않는다() {
        assertThat(ledger.reserveRouteCatalog(REQUESTED_AT, 2)).isFalse();

        verifyNoInteractions(repository);
    }

    @Test
    void 양수가_아닌_요청은_장부에_전달하지_않는다() {
        assertThatIllegalArgumentException().isThrownBy(() -> ledger.reserveRouteCatalog(REQUESTED_AT, 0));

        verifyNoInteractions(repository);
    }

    @Test
    void 같은_한국_날짜의_전송은_추가_예약하지_않는다() {
        assertThat(ledger.ensureLocationReservation(REQUESTED_AT,
            OffsetDateTime.parse("2026-08-28T23:59:59+09:00"))).isTrue();

        verifyNoInteractions(repository);
    }
}
