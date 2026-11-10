SET LOCAL lock_timeout = '5s';

ALTER TABLE vehicle_observation
    ADD COLUMN reported_remaining_seats integer,
    ADD COLUMN seat_quality_status varchar(12),
    ADD CONSTRAINT ck_observation_seat_quality_status CHECK (
        (reported_remaining_seats IS NULL AND seat_quality_status IS NULL)
        OR (
            reported_remaining_seats > 0 AND reported_remaining_seats IS NOT NULL
            AND seat_quality_status IN ('REVIEW', 'OUTLIER') AND seat_quality_status IS NOT NULL
            AND remaining_seats IS NULL
        )
    ) NOT VALID;

ALTER TABLE vehicle_capacity
    ADD COLUMN verified_seat_limit integer,
    ADD CONSTRAINT ck_vehicle_capacity_verified_limit
        CHECK (verified_seat_limit IS NULL OR (verified_seat_limit > 0 AND capacity <= verified_seat_limit));


-- 초기 적재 차량 중 차종 기준을 넘은 17대는 같은 기간에 관측한 기준 내 최대값을 사용한다.
UPDATE vehicle_capacity AS saved
SET capacity = corrected.capacity
FROM (VALUES
    ('204000147', 42),
    ('204000151', 45),
    ('204000268', 44),
    ('204003549', 44),
    ('204010357', 40),
    ('221000049', 45),
    ('221010255', 44),
    ('228000136', 45),
    ('228000145', 45),
    ('228000157', 45),
    ('228000234', 45),
    ('228000235', 45),
    ('228000236', 45),
    ('234000187', 45),
    ('234002402', 44),
    ('234002461', 44),
    ('234002815', 40)
) corrected(vehicle_id, capacity)
WHERE saved.source_id = 'GBIS' AND saved.vehicle_id = corrected.vehicle_id
  AND saved.evidence_batch_id IS NULL AND saved.verified_seat_limit IS NULL
  AND saved.capacity > corrected.capacity;
