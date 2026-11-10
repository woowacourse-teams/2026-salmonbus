-- 검증된 8개 노선의 현재 모델 발행을 확인한다. 지원 시간 밖의 빈 발행도 정상 처리다.
WITH supported AS (
    SELECT v.id AS route_version_id FROM route_version v JOIN route r ON r.id=v.route_id
    WHERE v.valid_to IS NULL AND r.source_route_id IN ('204000057','234000050','204000070','234000886','233000392','227000038','228000184','234000051')
), active AS (
    SELECT id FROM model_deployment WHERE state='ACTIVE'
)
SELECT count(*)=8 AND bool_and(EXISTS (
    SELECT 1 FROM forecast_publication p
    WHERE p.route_version_id=s.route_version_id AND p.model_deployment_id=a.id AND p.provenance='RECORDED'
)) FROM supported s CROSS JOIN active a;
