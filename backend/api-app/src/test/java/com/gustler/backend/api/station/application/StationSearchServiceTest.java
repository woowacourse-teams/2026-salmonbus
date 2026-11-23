package com.gustler.backend.api.station.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gustler.backend.api.error.ServiceUnavailableException;
import com.gustler.backend.api.station.InvalidStationSearchException;
import com.gustler.backend.api.station.domain.MapStation;
import com.gustler.backend.api.station.domain.StationSearchQuery;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

class StationSearchServiceTest {
    private final StationReadRepository repository = mock(StationReadRepository.class);
    private final StationSearchService service = new StationSearchService(repository);

    @Test
    void 정규화한_검색어와_30개_상한으로_조회한다() {
        // given
        List<MapStation> found = List.of(new MapStation("206000532", "판교역", 127.1, 37.3, null, null, null, List.of()));
        given(repository.search(any(), anyInt())).willReturn(found);

        // when
        List<MapStation> actual = service.search(" 판교 역 ");

        // then
        ArgumentCaptor<StationSearchQuery> query = ArgumentCaptor.forClass(StationSearchQuery.class);
        then(repository).should().search(query.capture(), eq(30));
        assertThat(query.getValue().key()).isEqualTo("판교역");
        assertThat(actual).isEqualTo(found);
    }

    @Test
    void 잘못된_검색어는_저장소를_부르지_않는다() {
        // when & then
        assertThatThrownBy(() -> service.search("%")).isInstanceOf(InvalidStationSearchException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void 저장소_연결_실패는_서비스_불가로_바꾼다() {
        // given
        given(repository.search(any(), anyInt())).willThrow(new DataAccessResourceFailureException("down"));

        // when & then
        assertThatThrownBy(() -> service.search("판교")).isInstanceOf(ServiceUnavailableException.class);
    }
}
