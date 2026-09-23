package com.gustler.backend.api.board.infrastructure.jpa;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.gustler.backend.api.http.ServiceUnavailableException;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.infrastructure.jpa.ModelDeploymentEntityRepository;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class JpaBoardQueryRepositoryTest {

    private BoardRouteVersionEntityRepository routeVersionRepository;
    private JpaBoardQueryRepository repository;
    private BoardVehicleObservationEntityRepository vehicleObservationRepository;

    @BeforeEach
    void setUp() {
        routeVersionRepository = mock(BoardRouteVersionEntityRepository.class);
        vehicleObservationRepository = mock(BoardVehicleObservationEntityRepository.class);
        repository = new JpaBoardQueryRepository(
            Clock.systemUTC(),
            routeVersionRepository,
            mock(BoardObservationBatchEntityRepository.class),
            mock(BoardRouteStopEntityRepository.class),
            mock(SeatForecastEntityRepository.class),
            vehicleObservationRepository,
            mock(ModelDeploymentEntityRepository.class)
        );
    }

    @Test
    void 차량_관측_DB_조회_장애를_예보_없음으로_처리하지_않는다() {
        given(vehicleObservationRepository.findAllByBatchIdAndRouteVersionId(100L, 1L))
            .willThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThatThrownBy(() -> repository.findObservedVehicles(100L, 1L))
            .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void DB_조회_장애를_서비스_불가_예외로_변환한다() {
        RouteId routeId = new RouteId("204000057");
        given(routeVersionRepository.findCurrent(routeId.value()))
            .willThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThatThrownBy(() -> repository.findSnapshot(routeId))
            .isInstanceOf(ServiceUnavailableException.class)
            .hasMessage("일시적인 서버 장애가 발생했습니다.");
    }
}
