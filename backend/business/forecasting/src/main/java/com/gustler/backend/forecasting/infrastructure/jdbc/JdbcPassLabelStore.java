package com.gustler.backend.forecasting.infrastructure.jdbc;

import static com.gustler.backend.forecasting.infrastructure.jdbc.MultiRowInsert.timestamp;

import com.gustler.backend.forecasting.application.forecast.PassLabel;
import com.gustler.backend.forecasting.application.forecast.PassLabelStore;
import com.gustler.backend.forecasting.application.forecast.UnresolvedForecast;
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 통과 정답({@code pass_label})과 미해결 예측({@code forecast_unresolved})을 날짜 파티션 테이블에 저장한다.
 *
 * <p>쓰기는 호출자의 트랜잭션에 참여하며, 트랜잭션 없이 호출하면 실패한다. 이미 있는 키는 건너뛰고, 새로 저장한 행을 기본 키로
 * 되짚어 반환한다.
 */
@Repository
public class JdbcPassLabelStore implements PassLabelStore {

    private static final MultiRowInsert LABELS = new MultiRowInsert("pass_label", List.of(
            "source_date",
            "route_id",
            "trip_start_observation_id",
            "stop_order",
            "capacity",
            "label_version",
            "model_deployment_id",
            "state",
            "reason",
            "label_value",
            "previous_last_observation_id",
            "previous_closure_observation_id",
            "parameter_hash",
            "computed_at"
    ));

    private static final List<String> LABEL_KEY = List.of(
            "source_date",
            "route_id",
            "trip_start_observation_id",
            "stop_order",
            "capacity",
            "label_version",
            "model_deployment_id"
    );

    private static final MultiRowInsert UNRESOLVED = new MultiRowInsert("forecast_unresolved", List.of(
            "source_date",
            "vehicle_observation_id",
            "target_stop_order",
            "route_id",
            "state",
            "reason",
            "closed_at"
    ));

    private static final List<String> UNRESOLVED_KEY =
        List.of("source_date", "vehicle_observation_id", "target_stop_order");

    private final JdbcClient jdbc;

    public JdbcPassLabelStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = false, propagation = Propagation.MANDATORY)
    public List<PassLabel> saveLabels(List<PassLabel> labels) {
        return LABELS.insertNew(
                jdbc,
                labels,
                label -> Arrays.asList(
                        label.sourceDate(),
                        label.routeId(),
                        label.tripStartObservationId(),
                        label.stopOrder(),
                        label.capacity(),
                        label.labelVersion(),
                        label.modelDeploymentId(),
                        label.state().name(),
                        label.reason(),
                        label.labelValue(),
                        label.previousLastObservationId(),
                        label.previousClosureObservationId(),
                        label.parameterHash(),
                        timestamp(label.computedAt())
                ),
                LABEL_KEY
        );
    }

    @Override
    @Transactional(readOnly = false, propagation = Propagation.MANDATORY)
    public List<UnresolvedForecast> saveUnresolved(List<UnresolvedForecast> unresolved) {
        return UNRESOLVED.insertNew(
                jdbc,
                unresolved,
                forecast -> Arrays.asList(
                        forecast.sourceDate(),
                        forecast.vehicleObservationId(),
                        forecast.targetStopOrder(),
                        forecast.routeId(),
                        forecast.state().name(),
                        forecast.reason(),
                        timestamp(forecast.closedAt())
                ),
                UNRESOLVED_KEY
        );
    }
}
