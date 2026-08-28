package com.gustler.backend.api.vehicle.persistence.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.gustler.backend.api.http.ServiceUnavailableException;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.persistence.jpa.RouteVersionJpaEntity;
import com.gustler.backend.api.vehicle.application.VehicleSnapshot;
import com.gustler.backend.api.vehicle.domain.VehiclePollOutcome;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageRequest;

class JpaVehicleQueryRepositoryTest {

    private VehicleRouteVersionEntityRepository routeVersionRepository;
    private ObservationBatchEntityRepository observationBatchRepository;
    private VehicleObservationEntityRepository vehicleObservationRepository;
    private JpaVehicleQueryRepository repository;

    @BeforeEach
    void setUp() {
        routeVersionRepository = mock(VehicleRouteVersionEntityRepository.class);
        observationBatchRepository = mock(ObservationBatchEntityRepository.class);
        vehicleObservationRepository = mock(VehicleObservationEntityRepository.class);
        repository = new JpaVehicleQueryRepository(
            routeVersionRepository,
            observationBatchRepository,
            vehicleObservationRepository
        );
    }

    @Test
    void 스냅샷_조회_중_DB_장애가_발생하면_서비스_불가_예외로_변환한다() {
        RouteId routeId = new RouteId("204000057");
        given(routeVersionRepository.findByRoute_SourceRouteIdAndValidToIsNull(
            routeId.value()
        ))
            .willThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThatThrownBy(() -> repository.findLatestSnapshot(routeId))
            .isInstanceOf(ServiceUnavailableException.class)
            .hasMessage("일시적인 서버 장애가 발생했습니다.");
    }

    @Test
    void 차량_조회_중_DB_장애가_발생하면_서비스_불가_예외로_변환한다() {
        given(vehicleObservationRepository.findAllByBatchId(1L))
            .willThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThatThrownBy(() -> repository.findVehicles(1L))
            .isInstanceOf(ServiceUnavailableException.class)
            .hasMessage("일시적인 서버 장애가 발생했습니다.");
    }

    @Test
    void 요청한_노선_ID를_스냅샷에_그대로_사용한다() {
        RouteId routeId = new RouteId("204000057");
        RouteVersionJpaEntity routeVersion = mock(RouteVersionJpaEntity.class);
        PageRequest firstResultPage = PageRequest.of(0, 1);
        given(routeVersionRepository.findByRoute_SourceRouteIdAndValidToIsNull(
            routeId.value()
        )).willReturn(Optional.of(routeVersion));
        given(routeVersion.id()).willReturn(42L);
        given(observationBatchRepository.findLatestByRouteVersion(
            routeVersion,
            firstResultPage
        )).willReturn(List.of());
        given(observationBatchRepository.findLatestNormalByRouteVersion(
            routeVersion,
            List.of(
                VehiclePollOutcome.SUCCESS_ROWS.name(),
                VehiclePollOutcome.SUCCESS_EMPTY.name()
            ),
            firstResultPage
        )).willReturn(List.of());

        VehicleSnapshot actual = repository.findLatestSnapshot(routeId).orElseThrow();

        assertThat(actual.routeId()).isEqualTo(routeId.value());
    }
}
