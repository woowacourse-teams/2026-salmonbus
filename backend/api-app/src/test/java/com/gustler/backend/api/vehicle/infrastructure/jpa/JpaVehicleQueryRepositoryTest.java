package com.gustler.backend.api.vehicle.infrastructure.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.gustler.backend.api.error.ServiceUnavailableException;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.infrastructure.jpa.RouteVersionJpaEntity;
import com.gustler.backend.api.vehicle.application.VehicleSnapshot;
import com.gustler.backend.api.vehicle.domain.ObservedVehicle;
import com.gustler.backend.api.vehicle.domain.VehicleDirection;
import com.gustler.backend.api.vehicle.domain.VehiclePhase;
import com.gustler.backend.api.vehicle.domain.VehiclePollOutcome;
import com.gustler.backend.api.vehicle.domain.VehicleSeat;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.AutoCloseableSoftAssertions;
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
        // when
        RouteId routeId = new RouteId("204000057");
        given(routeVersionRepository.findByRoute_SourceRouteIdAndValidToIsNull(
            routeId.value()
        ))
            .willThrow(new DataAccessResourceFailureException("database unavailable"));

        // then

        // when
        Throwable phaseFailure1 = catchThrowable(() -> repository.findLatestSnapshot(routeId));

        // then
        assertThat(phaseFailure1)
            .isNotNull()
            .isInstanceOf(ServiceUnavailableException.class)
            .hasMessage("일시적인 서버 장애가 발생했습니다.");
    }

    @Test
    void 차량_조회_중_DB_장애가_발생하면_서비스_불가_예외로_변환한다() {
        // when
        given(vehicleObservationRepository.findAllByBatchId(1L))
            .willThrow(new DataAccessResourceFailureException("database unavailable"));

        // then

        // when
        Throwable phaseFailure1 = catchThrowable(() -> repository.findVehicles(1L));

        // then
        assertThat(phaseFailure1)
            .isNotNull()
            .isInstanceOf(ServiceUnavailableException.class)
            .hasMessage("일시적인 서버 장애가 발생했습니다.");
    }

    /**
     * SAL-84가 running_state에 NOT NULL + CHECK (0, 1, 2) 제약을 적용해 해석할 수 없는 값은
     * DB에 저장할 수 없다. DB 픽스처로 만들 수 없는 상태이므로, 행을 제외하는 이 계층에서 검증한다.
     */
    @Test
    void 운행_상태를_해석할_수_없는_관측은_그_행만_빼고_나머지를_돌려준다() {
        // given
        VehicleObservationJpaEntity readable = mock(VehicleObservationJpaEntity.class);
        VehicleObservationJpaEntity unreadable = mock(VehicleObservationJpaEntity.class);
        ObservedVehicle vehicle = new ObservedVehicle(
            "204000205",
            VehicleDirection.UP,
            5,
            "205000005",
            "상행 다섯 번째",
            VehiclePhase.ARRIVING,
            VehicleSeat.from(7)
        );
        given(readable.toDomain()).willReturn(Optional.of(vehicle));
        given(unreadable.toDomain()).willReturn(Optional.empty());
        given(vehicleObservationRepository.findAllByBatchId(1L))
            .willReturn(List.of(unreadable, readable));

        // when
        List<ObservedVehicle> actual = repository.findVehicles(1L);

        // then
        assertThat(actual).containsExactly(vehicle);
    }

    @Test
    void 요청한_노선_ID를_스냅샷에_그대로_사용한다() {
        // given
        RouteId routeId = new RouteId("204000057");
        RouteVersionJpaEntity routeVersion = mock(RouteVersionJpaEntity.class);
        PageRequest firstResultPage = PageRequest.of(0, 1);
        given(routeVersionRepository.findByRoute_SourceRouteIdAndValidToIsNull(
            routeId.value()
        )).willReturn(Optional.of(routeVersion));
        given(routeVersion.id()).willReturn(42L);
        given(observationBatchRepository.findLatestSuccessfulByRouteVersion(
            routeVersion,
            firstResultPage
        )).willReturn(List.of());

        // when
        VehicleSnapshot actual = repository.findLatestSnapshot(routeId).orElseThrow();

        // then
        assertThat(actual.routeId()).isEqualTo(routeId.value());
        verify(observationBatchRepository).findLatestSuccessfulByRouteVersion(
            routeVersion,
            firstResultPage
        );
        verifyNoMoreInteractions(observationBatchRepository);
    }

    @Test
    void 최신_성공_배치_하나에서_ID와_상태와_관측_시각을_만든다() {
        // given
        RouteId routeId = new RouteId("204000057");
        RouteVersionJpaEntity routeVersion = mock(RouteVersionJpaEntity.class);
        ObservationBatchJpaEntity batch = mock(ObservationBatchJpaEntity.class);
        OffsetDateTime observedAt = OffsetDateTime.parse("2026-08-27T03:58:00Z");
        PageRequest firstResultPage = PageRequest.of(0, 1);
        given(routeVersionRepository.findByRoute_SourceRouteIdAndValidToIsNull(
            routeId.value()
        )).willReturn(Optional.of(routeVersion));
        given(routeVersion.id()).willReturn(42L);
        given(observationBatchRepository.findLatestSuccessfulByRouteVersion(
            routeVersion,
            firstResultPage
        )).willReturn(List.of(batch));
        given(batch.id()).willReturn(7L);
        given(batch.outcome()).willReturn(VehiclePollOutcome.SUCCESS_ROWS.name());
        given(batch.responseReceivedAt()).willReturn(observedAt);

        // when
        VehicleSnapshot actual = repository.findLatestSnapshot(routeId).orElseThrow();

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(actual.latestBatchId()).isEqualTo(7L);
            softly.assertThat(actual.latestOutcome()).isEqualTo(VehiclePollOutcome.SUCCESS_ROWS);
            softly.assertThat(actual.observedAt()).isEqualTo(
                OffsetDateTime.parse("2026-08-27T12:58:00+09:00")
            );
        }
    }
}
