package com.gustler.backend.api.route.infrastructure.jpa;

import com.gustler.backend.api.board.ForecastSourceSetting;
import com.gustler.backend.api.error.ServiceUnavailableException;
import com.gustler.backend.api.route.application.RouteQueryRepository;
import com.gustler.backend.api.route.domain.Route;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Repository;

/**
 * 현재 공개 노선과 활성 모델·예보 발행 상태를 노선 목록 조회에 제공한다.
 */
@Repository
@RequiredArgsConstructor
public class JpaRouteQueryRepository implements RouteQueryRepository {
    private final RouteEntityRepository routeRepository;
    private final ModelDeploymentEntityRepository modelDeploymentRepository;
    private ForecastSourceSetting forecastSource = ForecastSourceSetting.LEGACY;

    @Override
    public List<Route> findAllCurrentRoutes() {
        try {
            return routeRepository.findAllCurrentRoutes()
                .stream()
                .map(RouteJpaEntity::toDomain)
                .toList();
        } catch (DataAccessResourceFailureException exception) {
            throw new ServiceUnavailableException();
        }
    }

    @Override
    public boolean existsActiveModel() {
        try {
            return modelDeploymentRepository.existsByState(ModelDeploymentState.ACTIVE);
        } catch (DataAccessResourceFailureException exception) {
            throw new ServiceUnavailableException();
        }
    }

    /**
     * 예보를 읽을 출처를 정한다. {@code board.forecast-source}의 값이며, 세 이름 중 하나가 아니면 빈을 만들지 못해 앱이 뜨지 않는다.
     */
    @Value("${board.forecast-source:legacy}")
    void setForecastSource(String value) {
        forecastSource = ForecastSourceSetting.of(value);
    }

    /**
     * 예보가 있는 현재 노선을 읽는다. 옛 출처는 발행 표시, 새 출처는 실행 줄이 있는 노선이고,
     * {@link ForecastSourceSetting#AUTO}면 둘 중 하나라도 있는 노선이다.
     */
    @Override
    public Set<String> findForecastPublishedRouteIds() {
        try {
            return switch (forecastSource) {
                case LEGACY -> Set.copyOf(routeRepository.findForecastPublishedRouteIds());
                case RECORD -> Set.copyOf(routeRepository.findForecastRecordedRouteIds());
                case AUTO -> {
                    Set<String> routeIds = new HashSet<>(routeRepository.findForecastPublishedRouteIds());
                    routeIds.addAll(routeRepository.findForecastRecordedRouteIds());
                    yield Set.copyOf(routeIds);
                }
            };
        } catch (DataAccessResourceFailureException exception) {
            throw new ServiceUnavailableException();
        }
    }
}
