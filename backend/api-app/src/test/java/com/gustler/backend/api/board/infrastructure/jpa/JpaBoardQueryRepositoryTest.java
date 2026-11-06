package com.gustler.backend.api.board.infrastructure.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gustler.backend.api.board.application.BoardSnapshot;
import com.gustler.backend.api.board.infrastructure.jdbc.JdbcBoardSnapshotReader;
import com.gustler.backend.api.error.ServiceUnavailableException;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.infrastructure.jpa.ModelDeploymentEntityRepository;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

class JpaBoardQueryRepositoryTest {
    private BoardRouteVersionEntityRepository routeVersionRepository;
    private JpaBoardQueryRepository repository;
    private JdbcBoardSnapshotReader snapshotReader;
    private BoardVehicleObservationEntityRepository vehicleObservationRepository;

    @BeforeEach
    void setUp() {
        routeVersionRepository = mock(BoardRouteVersionEntityRepository.class);
        vehicleObservationRepository = mock(BoardVehicleObservationEntityRepository.class);
        snapshotReader = mock(JdbcBoardSnapshotReader.class);
        repository = new JpaBoardQueryRepository(
            Clock.systemUTC(),
            routeVersionRepository,
            mock(BoardObservationBatchEntityRepository.class),
            mock(BoardRouteStopEntityRepository.class),
            mock(SeatForecastEntityRepository.class),
            vehicleObservationRepository,
            mock(ModelDeploymentEntityRepository.class),
            mock(BoardForecastPublicationEntityRepository.class),
            snapshotReader
        );
        ReflectionTestUtils.setField(repository, "singleQueryEnabled", false);
    }

    @Test
    void 통합_스냅숏_질의_장애도_서비스_불가로_변환한다() {
        // given
        ReflectionTestUtils.setField(repository, "singleQueryEnabled", true);
        RouteId routeId = new RouteId("204000057");
        given(snapshotReader.findSnapshot(routeId))
            .willThrow(new DataAccessResourceFailureException("database unavailable"));

        // when
        Throwable actual = catchThrowable(() -> repository.findSnapshot(routeId));

        // then
        assertThat(actual)
            .isInstanceOf(ServiceUnavailableException.class)
            .hasMessage("일시적인 서버 장애가 발생했습니다.");
    }

    @Test
    void 통합_질의의_노선_없음을_그대로_반환한다() {
        // given
        ReflectionTestUtils.setField(repository, "singleQueryEnabled", true);
        RouteId routeId = new RouteId("999999999");
        given(snapshotReader.findSnapshot(routeId)).willReturn(Optional.empty());

        // when
        Optional<BoardSnapshot> actual = repository.findSnapshot(routeId);

        // then
        assertThat(actual).isEmpty();
        verifyNoInteractions(routeVersionRepository);
    }

    @Test
    void 차량_관측_DB_조회_장애를_예보_없음으로_처리하지_않는다() {
        // when
        given(vehicleObservationRepository.findAllByBatchIdAndRouteVersionId(100L, 1L))
            .willThrow(new DataAccessResourceFailureException("database unavailable"));

        // then

        // when
        Throwable phaseFailure1 = catchThrowable(() -> repository.findObservedVehicles(100L, 1L));

        // then
        assertThat(phaseFailure1)
            .isNotNull()
            .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void DB_조회_장애를_서비스_불가_예외로_변환한다() {
        // when
        RouteId routeId = new RouteId("204000057");
        given(routeVersionRepository.findCurrent(routeId.value()))
            .willThrow(new DataAccessResourceFailureException("database unavailable"));

        // then

        // when
        Throwable phaseFailure1 = catchThrowable(() -> repository.findSnapshot(routeId));

        // then
        assertThat(phaseFailure1)
            .isNotNull()
            .isInstanceOf(ServiceUnavailableException.class)
            .hasMessage("일시적인 서버 장애가 발생했습니다.");
    }
}
