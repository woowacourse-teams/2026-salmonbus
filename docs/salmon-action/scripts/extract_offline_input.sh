#!/usr/bin/env bash
# 연어행동 오프라인 실행기(docs/salmon-action/offline-run.md)의 입력 폴더를 PostgreSQL에서 뽑는다.
#
# 운영 DB에서 돌릴 때는 운영 조회 승인 뒤, 출퇴근 시간(KST 07시부터 10시, 17시부터 20시) 밖에 실행한다.
# 읽기 전용 SELECT만 쓴다(세션을 읽기 전용으로 열고 문장 제한 시간을 30초로 둔다). 번호판(plate_number)과
# 예보의 특징 값(feature_snapshot)은 뽑지 않는다. 출력에는 차량 ID가 들어 있으니 저장소 밖에 두고 공유하지 않는다.
#
# 사용: 접속 정보는 psql의 환경 변수(PGHOST, PGPORT, PGDATABASE, PGUSER, PGPASSWORD)로 준다.
#   extract_offline_input.sh <판정 구간 시작> <판정 구간 끝> <새 출력 폴더> <노선 source_route_id> [노선 ...]
#   예: extract_offline_input.sh 2026-11-12T10:00:00+09:00 2026-11-12T13:00:00+09:00 /tmp/salmon-input 204000057
#
# 뽑는 구간: 관측 묶음과 차량 관측은 시작 10분 전부터 끝 2시간 뒤까지(보드가 10분 전 묶음까지 보고, 실제 결과는
# 판정 뒤 2시간까지 찾는다). 예보 발행과 좌석 예보는 시작 10분 전부터 끝까지. 도착 스냅샷은 시작부터 끝까지.
# 한 시간씩 끊어 읽고 조회 사이에 2초 쉰다. 조회마다 출퇴근 시간인지 다시 보고, 그 시간에 들어서면 멈춘다.
# 멈춘 폴더에는 INCOMPLETE 파일이 남는다.
#
# 도착 스냅샷(arrival_snapshot)은 stale_at이 (지금 - arrivals.retention)보다 이전이면 정리 대상이다.
# 기본 retention이면 수신 뒤 11분(validity 60초와 retention 10분)이 지난 행부터다. 남기는 기간, 정류장 수,
# 수집 간격은 정해지지 않았다. 견적은 preservation_cost.py이고, 조건은 offline-run.md의 "보존 견적을 낸다".
set -euo pipefail

if [[ $# -lt 4 ]]; then
  echo "사용: $0 <시작> <끝> <새 출력 폴더> <노선 source_route_id> [노선 ...]" >&2
  exit 1
fi
start_at="$1"
end_at="$2"
output="$3"
shift 3
for route in "$@"; do
  [[ "$route" =~ ^[0-9]{9}$ ]] || { echo "노선 ID는 9자리 숫자여야 한다: ${route}" >&2; exit 1; }
done
routes="$(printf "'%s'," "$@")"
routes="${routes%,}"

# 조회 하나를 보내기 전마다 부른다. 도는 중에 출퇴근 시간에 들어서도 멈춘다.
# 로컬 DB로 스크립트를 시험할 때만 SALMON_EXTRACT_LOCAL=1로 이 검사를 끈다. SALMON_EXTRACT_HOUR는 이 검사의 시험용이다.
stop_in_rush_hour() {
  [[ "${SALMON_EXTRACT_LOCAL:-}" == "1" ]] && return 0
  local hour_kst="${SALMON_EXTRACT_HOUR:-$(TZ=Asia/Seoul date +%H)}"
  case "$hour_kst" in
    07|08|09|17|18|19)
      echo "출퇴근 시간이라 멈춤 (KST ${hour_kst}시). ${output}에 INCOMPLETE 파일이 있으면 그 폴더는 반쪽이다" >&2
      exit 1;;
  esac
}

[[ -e "$output" ]] && { echo "출력 폴더가 이미 있다: ${output}" >&2; exit 1; }
stop_in_rush_hour
mkdir -p "$output"
# 끝까지 뽑은 뒤에 지운다. 이 파일이 남아 있으면 중간에 멈춘 반쪽 폴더이니 실행기에 넣지 않는다.
echo "추출이 끝나지 않았다. 이 폴더를 지우고 다시 뽑는다." > "${output}/INCOMPLETE"

export PGAPPNAME=salmon-offline-readonly-extract
export PGOPTIONS="-c default_transaction_read_only=on -c statement_timeout=30s -c lock_timeout=100ms"

query() {
  psql -X -q -A -t -v ON_ERROR_STOP=1 -v start="$start_at" -v end="$end_at" "$@"
}

# 구간이 없는 작은 테이블은 한 번에 뽑는다.
copy_all() {
  local table="$1" select="$2"
  stop_in_rush_hour
  query <<SQL | gzip > "${output}/${table}.csv.gz"
COPY (${select}) TO STDOUT WITH (FORMAT csv, HEADER);
SQL
  echo "${table} done" >&2
}

# 시각 열이 있는 테이블은 [from, to) 한 시간씩 뽑아 이어 붙인다. 열 이름 줄은 첫 조각에만 둔다.
# 구간의 끝 시각과 정확히 같은 행은 들어가지 않는다.
copy_hourly() {
  local table="$1" select="$2" before="$3" after="$4" header="HEADER" from to
  : > "${output}/${table}.csv"
  stop_in_rush_hour
  query <<SQL > "${output}/.windows"
SELECT to_char(moment AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
FROM generate_series(:'start'::timestamptz - interval '${before}', :'end'::timestamptz + interval '${after}',
                     interval '1 hour') AS moment;
SELECT to_char((:'end'::timestamptz + interval '${after}') AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"');
SQL
  local windows=()
  while IFS= read -r line; do windows+=("$line"); done < "${output}/.windows"
  rm -f "${output}/.windows"
  local index
  for (( index = 0; index < ${#windows[@]} - 1; index++ )); do
    from="${windows[$index]}"
    to="${windows[$((index + 1))]}"
    [[ "$from" < "$to" ]] || continue
    stop_in_rush_hour
    psql -X -q -v ON_ERROR_STOP=1 -v from="$from" -v to="$to" <<SQL >> "${output}/${table}.csv"
COPY (${select}) TO STDOUT WITH (FORMAT csv${header:+, ${header}});
SQL
    header=""
    echo "${table} ${from} 부터 ${to} 전까지 done" >&2
    sleep 2
  done
  gzip "${output}/${table}.csv"
}

versions="SELECT version.id FROM route_version version JOIN route ON route.id = version.route_id
  WHERE route.source_route_id IN (${routes})"

copy_all route "SELECT id, public_route_id, source_id, source_route_id, display_name, start_stop_name, end_stop_name
  FROM route WHERE source_route_id IN (${routes}) ORDER BY id"
copy_all route_version "SELECT id, route_id, content_digest, valid_from, valid_to, turn_sequence,
    up_first_departure_time, up_last_departure_time, down_first_departure_time, down_last_departure_time,
    weekday_min_headway_minutes, weekday_max_headway_minutes, saturday_min_headway_minutes,
    saturday_max_headway_minutes, sunday_min_headway_minutes, sunday_max_headway_minutes,
    holiday_min_headway_minutes, holiday_max_headway_minutes
  FROM route_version WHERE id IN (${versions}) ORDER BY id"
copy_all route_stop "SELECT route_version_id, stop_order, stop_id, name, direction, boarding_allowed
  FROM route_stop WHERE route_version_id IN (${versions}) ORDER BY route_version_id, stop_order"
copy_all route_data_quality "SELECT route_id, quality_revision FROM route_data_quality
  WHERE route_id IN (SELECT id FROM route WHERE source_route_id IN (${routes})) ORDER BY route_id"
copy_all model_deployment "SELECT id, deployment_key, release_id, model_key, model_version, bundle_digest,
    prediction_target_version, calculation_version, supported_scope_digest, data_until, state,
    predecessor_deployment_id, activated_at, retired_at
  FROM model_deployment ORDER BY id"

# 그 판본에서 가장 이른 묶음의 발행 시각. 입력에는 판정 구간의 발행만 들어가므로, 그 전에 발행이 있던 판본인지를
# 실행기가 이것으로 안다. 판본마다 인덱스(route_version_id, observed_at) 끝의 한 행만 읽는다.
copy_all route_version_first_publication "SELECT version.id AS route_version_id, first.published_at AS first_published_at
  FROM route_version version
  CROSS JOIN LATERAL (SELECT publication.published_at FROM forecast_publication publication
    WHERE publication.route_version_id = version.id
    ORDER BY publication.observed_at, publication.source_batch_id LIMIT 1) first
  WHERE version.id IN (${versions}) ORDER BY version.id"

copy_hourly observation_batch "SELECT id, route_version_id, scheduled_at, attempt_number, attempt_key, outcome,
    normalization_version, collection_strategy_version, requested_at, response_received_at, completed_at,
    http_status, result_code, failure_code, provider_rows, stored_rows, excluded_rows, input_confirmed_at,
    ingestion_origin
  FROM observation_batch
  WHERE route_version_id IN (${versions}) AND response_received_at >= :'from' AND response_received_at < :'to'
  ORDER BY id" "10 minutes" "2 hours"
copy_hourly vehicle_observation "SELECT observation.id, observation.observation_batch_id,
    observation.route_version_id, observation.source_row_number, observation.stop_order, observation.stop_id,
    observation.running_state, observation.passed_stop_order, observation.vehicle_id,
    observation.vehicle_trip_key, observation.remaining_seats, observation.crowd_level, observation.vehicle_type,
    observation.route_type, observation.tagless, observation.seat_unknown_reason
  FROM vehicle_observation observation
  JOIN observation_batch batch ON batch.id = observation.observation_batch_id
  WHERE batch.route_version_id IN (${versions})
    AND batch.response_received_at >= :'from' AND batch.response_received_at < :'to'
  ORDER BY observation.id" "10 minutes" "2 hours"
copy_hourly ineligible_observation "SELECT observation.id AS vehicle_observation_id
  FROM forecast_observation_quality observation
  JOIN observation_batch batch ON batch.id = observation.observation_batch_id
  WHERE batch.route_version_id IN (${versions}) AND NOT observation.forecast_eligible
    AND batch.response_received_at >= :'from' AND batch.response_received_at < :'to'
  ORDER BY observation.id" "10 minutes" "2 hours"
copy_hourly forecast_publication "SELECT publication.id, publication.source_batch_id, publication.route_version_id,
    publication.observed_at, publication.published_at, publication.prediction_count,
    publication.model_deployment_id, publication.demand_statistics_revision, publication.quality_revision,
    publication.generated_at, publication.provenance
  FROM forecast_publication publication
  WHERE publication.route_version_id IN (${versions})
    AND publication.observed_at >= :'from' AND publication.observed_at < :'to'
  ORDER BY publication.id" "10 minutes" "0 minutes"
copy_hourly seat_forecast "SELECT forecast.vehicle_observation_id, forecast.target_stop_order,
    forecast.route_version_id, forecast.stops_to_target, forecast.model_deployment_id,
    forecast.demand_statistics_revision, forecast.seat_full_chance_raw, forecast.seat_full_chance,
    forecast.generated_at, forecast.publication_id, forecast.expected_seats, forecast.quality_revision,
    forecast.evaluation_detail_required, forecast.seat_target, forecast.probability_target,
    forecast.label_version, forecast.inference_bundle_hash
  FROM seat_forecast forecast
  JOIN forecast_publication publication ON publication.id = forecast.publication_id
  WHERE publication.route_version_id IN (${versions})
    AND publication.observed_at >= :'from' AND publication.observed_at < :'to'
  ORDER BY forecast.vehicle_observation_id, forecast.target_stop_order" "10 minutes" "0 minutes"
copy_hourly arrival_snapshot "SELECT snapshot.id, snapshot.station_id, snapshot.received_at,
    snapshot.refresh_after_at, snapshot.stale_at, snapshot.stored_at, snapshot.payload
  FROM arrival_snapshot snapshot
  WHERE snapshot.provider = 'GBIS'
    AND snapshot.station_id IN (SELECT stop_id FROM route_stop WHERE route_version_id IN (${versions}))
    AND snapshot.received_at >= :'from' AND snapshot.received_at < :'to'
  ORDER BY snapshot.received_at, snapshot.id" "0 minutes" "0 minutes"

rm -f "${output}/INCOMPLETE"
echo "입력 폴더: ${output}" >&2
