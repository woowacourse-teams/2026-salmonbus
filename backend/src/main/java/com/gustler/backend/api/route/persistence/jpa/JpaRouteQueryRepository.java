package com.gustler.backend.api.route.persistence.jpa;

import com.gustler.backend.api.http.ServiceUnavailableException;
import com.gustler.backend.api.route.application.RouteQueryRepository;
import com.gustler.backend.api.route.domain.Route;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JpaRouteQueryRepository implements RouteQueryRepository {

    private final RouteEntityRepository routeRepository;
    private final ModelDeploymentEntityRepository modelDeploymentRepository;

    @Override
    public List<Route> findAllCurrentRoutes() {
        try {
            return routeRepository.findAllCurrentRoutes()
                .stream()
                .map(RouteJpaEntity::toDomain)
                .toList();
        } catch (DataAccessException exception) {
            throw new ServiceUnavailableException();
        }
    }

    @Override
    public boolean existsActiveModel() {
        try {
            return modelDeploymentRepository.existsByState(ModelDeploymentState.ACTIVE);
        } catch (DataAccessException exception) {
            throw new ServiceUnavailableException();
        }
    }
}
