package com.gustler.backend.forecasting.application.quality;

import com.gustler.backend.forecasting.infrastructure.quality.TripQualityRepository;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.UncategorizedSQLException;

class TripQualityInvestigationJobTest {
    @Test
    void 잠금_시간_초과는_다음_실행으로_미루고_같은_실행에서_반복하지_않는다() {
        // given
        var repository = mock(TripQualityRepository.class);
        when(repository.investigateNext()).thenThrow(new UncategorizedSQLException(
            "investigation", "SELECT", new SQLException("lock timeout", "55P03")));

        // when
        assertThatCode(new InvestigateTripQualityService(repository)::investigate).doesNotThrowAnyException();

        // then
        verify(repository).investigateNext();
    }

    @Test
    void 잠금_시간_초과가_아닌_DB_오류는_그대로_전파한다() {
        // given
        var repository = mock(TripQualityRepository.class);
        var failure = new UncategorizedSQLException("investigation", "SELECT", new SQLException("connection lost", "08006"));
        when(repository.investigateNext()).thenThrow(failure);

        // when & then
        assertThatThrownBy(new InvestigateTripQualityService(repository)::investigate).isSameAs(failure);
    }
}
