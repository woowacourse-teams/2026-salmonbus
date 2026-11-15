# 수집 좌석값 조회와 대응

좌석값은 수집에서 판정한다. 예보는 DB에 저장된 좌석값을 읽는다.

## 좌석값 격리 대응

격리 판정은 정상 수집과 같은 트랜잭션에서 저장한다. 차량 위치는 사용할 수 있고 운영 잔여석은 `NULL`이다.
`REVIEW`는 차종과 차량 상한을 확인할 건이다. `OUTLIER`는 확인된 차량별 상한을 넘은 건이다.
HISTORICAL은 과거 제외 관측에서 옮긴 값이다.

1. Grafana의 **좌석 관측값 격리** 알림에서 **좌석값 격리 로그**로 이동한다. Worker 로그의
   `event=observation_seat_quality`에 나온 `batchId`로 아래 쿼리를 실행한다.
2. 원본 잔여석, 차량 ID, 차종, 확인 상한을 비교한다. GBIS 오류면 격리 상태를 유지한다. 여러 차량과 노선에서
   반복되면 GBIS 응답과 해당 시간대 수집 상태를 확인한다.
3. 실제 좌석 수가 확인된 차량은 `vehicle_capacity.verified_seat_limit`에 근거와 함께 승인된 DB 변경으로 반영한다.
   다음 수집부터 새 기준을 쓴다. 이전 격리값은 계속 격리 상태로 남는다.

```sql
-- psql에서 로그의 batchId를 설정한다.
-- \set batch_id 123
SELECT batch.response_received_at, route.display_name, observation.id, observation.vehicle_id,
       observation.vehicle_type, observation.reported_remaining_seats, observation.seat_quality_status,
       capacity.capacity, capacity.verified_seat_limit
FROM vehicle_observation observation
JOIN observation_batch batch ON batch.id = observation.observation_batch_id
JOIN route_version version ON version.id = observation.route_version_id
JOIN route ON route.id = version.route_id
LEFT JOIN vehicle_capacity capacity
  ON capacity.source_id = route.source_id AND capacity.vehicle_id = observation.vehicle_id
WHERE observation.observation_batch_id = :batch_id AND observation.seat_quality_status IS NOT NULL
ORDER BY observation.source_row_number;
```

알림 규칙은 [Grafana 준비 파일](../../../../../deploy/observability/diagnostics/alert-rules.paused.json)에 있다.
Worker의 `observability` 프로필에서 지표를 노출한다. 배포 후 지표 수집과 Discord 수신·복구를 검증하고 활성화한다.
