-- 모델 지원 8개 노선마다 현재 활성 모델의 예보 실행 줄이 있는지 확인한다.
-- 실행 줄과 그 배치의 예측은 한 트랜잭션으로 저장되므로, 예측을 낸 실행 줄(PUBLISHED)이 보이면 예측도 저장되어 있다.
-- 지원 시간 밖이라 예측이 없는 실행 줄(OUT_OF_SCOPE)도 정상 처리로 센다.
-- 예보할 차량이 없었거나(EMPTY) 예보 단계를 돌리지 못한(LATE, INPUT_UNAVAILABLE) 실행 줄은 세지 않고 다음 배치를 기다린다.
WITH supported AS (
    SELECT v.id AS route_version_id FROM route_version v JOIN route r ON r.id=v.route_id
    WHERE v.valid_to IS NULL AND r.source_route_id IN ('204000057','234000050','204000070','234000886','233000392','227000038','228000184','234000051')
), active AS (
    SELECT id FROM model_deployment WHERE state='ACTIVE'
)
SELECT count(*)=8 AND bool_and(EXISTS (
    SELECT 1 FROM forecast_run run
    WHERE run.route_version_id=s.route_version_id AND run.model_deployment_id=a.id
      AND run.outcome IN ('PUBLISHED','OUT_OF_SCOPE')
)) FROM supported s CROSS JOIN active a;
