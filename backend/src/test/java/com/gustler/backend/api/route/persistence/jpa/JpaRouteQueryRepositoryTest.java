package com.gustler.backend.api.route.persistence.jpa;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.gustler.backend.api.http.ServiceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;

class JpaRouteQueryRepositoryTest {

    private RouteEntityRepository routeRepository;
    private ModelDeploymentEntityRepository modelDeploymentRepository;
    private JpaRouteQueryRepository repository;

    @BeforeEach
    void setUp() {
        routeRepository = mock(RouteEntityRepository.class);
        modelDeploymentRepository = mock(ModelDeploymentEntityRepository.class);
        repository = new JpaRouteQueryRepository(routeRepository, modelDeploymentRepository);
    }

    @Test
    void 노선_조회_중_DB_장애가_발생하면_서비스_불가_예외로_변환한다() {
        given(routeRepository.findAllCurrentRoutes())
            .willThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThatThrownBy(repository::findAllCurrentRoutes)
            .isInstanceOf(ServiceUnavailableException.class)
            .hasMessage("일시적인 서버 장애가 발생했습니다.");
    }

    @Test
    void 노선_조회_결과_계약_오류는_서비스_불가_예외로_변환하지_않는다() {
        IncorrectResultSizeDataAccessException exception =
            new IncorrectResultSizeDataAccessException(1, 2);
        given(routeRepository.findAllCurrentRoutes()).willThrow(exception);

        assertThatThrownBy(repository::findAllCurrentRoutes)
            .isSameAs(exception);
    }

    @Test
    void 활성_모델_조회_중_DB_장애가_발생하면_서비스_불가_예외로_변환한다() {
        given(modelDeploymentRepository.existsByState(ModelDeploymentState.ACTIVE))
            .willThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThatThrownBy(repository::existsActiveModel)
            .isInstanceOf(ServiceUnavailableException.class)
            .hasMessage("일시적인 서버 장애가 발생했습니다.");
    }
}
