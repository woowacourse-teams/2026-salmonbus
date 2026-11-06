-- 판본 지문에서 정류장 이름을 뺀다(SAL-360). 저장된 지문을 새 규칙으로 다시 계산한다.
-- 규칙은 RouteContentDigest와 같다: 길이(4바이트 big-endian) 접두 UTF-8 값을 회차 순번, 정류장 순번, 정류장 ID 순으로 이어 SHA-256.
-- 회차 순번이 없으면 '단방향'을 넣는다.
UPDATE route_version AS version
SET content_digest = recomputed.digest
FROM (
    SELECT v.id,
           encode(sha256(
               int4send(octet_length(convert_to(COALESCE(v.turn_sequence::text, '단방향'), 'UTF8')))
               || convert_to(COALESCE(v.turn_sequence::text, '단방향'), 'UTF8')
               || string_agg(
                      int4send(octet_length(s.stop_order::text)) || convert_to(s.stop_order::text, 'UTF8')
                      || int4send(octet_length(convert_to(s.stop_id, 'UTF8'))) || convert_to(s.stop_id, 'UTF8'),
                      ''::bytea ORDER BY s.stop_order)
           ), 'hex') AS digest
    FROM route_version v
    JOIN route_stop s ON s.route_version_id = v.id
    GROUP BY v.id, v.turn_sequence
) AS recomputed
WHERE recomputed.id = version.id
  AND recomputed.digest <> version.content_digest;
