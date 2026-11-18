#!/usr/bin/env bash
# 운영 서버 DB에서 서비스 대상 충전소의 데이터만 뽑아, 로컬 DB에 바로 넣을 수 있는 스냅샷 파일(.sql.gz)을 만든다.
#   사용: backend/scripts/snapshot/export.sh [출력 파일]
#   기본 출력: backend/scripts/snapshot/evision_snapshot.sql.gz
#
# 담기는 것 (서비스 대상 = station_access에 매핑된 충전소)
#   노선·휴게소·IC 전체, 서비스 대상 충전소·충전기(현재 상태 포함), 매핑,
#   최근 HIST_DAYS일 충전기 상태 이력, 최근 OCC_DAYS일 30분 집계, AI 예측, 최근 RUN_DAYS일 수집 기록
# 수집 기록의 error_message는 비운다 (요청 URL 등이 들어갈 수 있어서).
# 서버에 파일을 만들지 않고 ssh로 받아 맥에서 합친다. 서버 주소·키는 deploy.sh와 같은 방법으로 찾는다.
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
key="${EVISION_DEPLOY_KEY:-$HOME/.ssh/evision-key}"
server="${EVISION_DEPLOY_SERVER:-}"
target_file="$script_dir/../deploy-target.txt"
if [[ -z "$server" && -f "$target_file" ]]; then
  server="$(head -n 1 "$target_file" | tr -d '\r[:space:]')"
fi
if [[ -z "$server" ]]; then
  echo "서버 주소가 없습니다. backend/scripts/deploy-target.txt에 'ubuntu@<서버 IP>'를 적으세요." >&2
  exit 1
fi
out="${1:-$script_dir/evision_snapshot.sql.gz}"
HIST_DAYS="${HIST_DAYS:-3}"
OCC_DAYS="${OCC_DAYS:-28}"
RUN_DAYS="${RUN_DAYS:-29}"

work="$(mktemp -d -t evision-snapshot)"
trap 'rm -rf "$work"' EXIT

psql_remote() {
  # 표준입력의 psql 명령을 서버 DB에서 실행하고 결과를 표준출력으로 (gzip 압축해서) 돌려준다
  ssh -i "$key" -o BatchMode=yes -o ServerAliveInterval=30 "$server" \
    'cd ~/evision/backend && sudo docker compose -f compose.prod.yaml exec -T postgres sh -c "psql -U \$POSTGRES_USER -d \$POSTGRES_DB -q -X -v ON_ERROR_STOP=1 | gzip -6"'
}

snap="$(printf '%s\n' "\\copy (SELECT to_char(date_trunc('minute', now()), 'YYYY-MM-DD HH24:MI:SS')) TO STDOUT" | psql_remote | gzip -dc)"
[[ "$snap" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}\ [0-9]{2}:[0-9]{2}:[0-9]{2}$ ]] || { echo "기준 시각을 못 읽었습니다: $snap" >&2; exit 1; }
echo "스냅샷 기준 시각: $snap (이 시각 이전 데이터만 담는다)"

TARGET="SELECT station_id FROM station_access"
RUNS="SELECT run_id FROM collection_run WHERE started_at >= timestamp '$snap' - interval '$RUN_DAYS days' AND started_at < timestamp '$snap'"

# 이름|컬럼|조회문  (컬럼 순서 = 조회 결과 순서)
TABLES=(
  "route|route_id, route_no, route_name|SELECT route_id, route_no, route_name FROM route ORDER BY 1"
  "rest_area|rest_area_id, route_id, name, direction, latitude, longitude, ev_charger_yn|SELECT rest_area_id, route_id, name, direction, latitude, longitude, ev_charger_yn FROM rest_area ORDER BY 1"
  "interchange|ic_id, route_id, facility_code, name, facility_type, latitude, longitude|SELECT ic_id, route_id, facility_code, name, facility_type, latitude, longitude FROM interchange ORDER BY 1"
  "station|station_id, stat_id, name, address, address_detail, latitude, longitude, zcode, zscode, kind, kind_detail, busi_id, org_name, operator_name, operator_call, use_time, parking_free_yn, limit_yn, limit_detail, del_yn, updated_at|SELECT station_id, stat_id, name, address, address_detail, latitude, longitude, zcode, zscode, kind, kind_detail, busi_id, org_name, operator_name, operator_call, use_time, parking_free_yn, limit_yn, limit_detail, del_yn, updated_at FROM station WHERE station_id IN ($TARGET) ORDER BY 1"
  "charger|charger_id, station_id, chger_id, charger_type, output_kw, method, is_fast, del_yn, current_status_code, current_normalized_status, current_status_updated_at|SELECT charger_id, station_id, chger_id, charger_type, output_kw, method, is_fast, del_yn, current_status_code, current_normalized_status, current_status_updated_at FROM charger WHERE station_id IN ($TARGET) ORDER BY 1"
  "station_access|access_id, station_id, access_type, rest_area_id, ic_id, detour_km, mapping_method, verified_at|SELECT access_id, station_id, access_type, rest_area_id, ic_id, detour_km, mapping_method, verified_at FROM station_access ORDER BY 1"
  "collection_run|run_id, run_type, started_at, ended_at, run_status, api_call_count, total_count, fetched_count, inserted_count, duplicate_count, unknown_count, invalid_count, retry_count, error_code, error_message|SELECT run_id, run_type, started_at, ended_at, run_status, api_call_count, total_count, fetched_count, inserted_count, duplicate_count, unknown_count, invalid_count, retry_count, error_code, NULL FROM collection_run WHERE run_id IN ($RUNS) ORDER BY 1"
  "charger_status_history|history_id, charger_id, status_code, status_updated_at, last_charge_start, last_charge_end, now_charge_start, source_api, run_id, collected_at|SELECT h.history_id, h.charger_id, h.status_code, h.status_updated_at, h.last_charge_start, h.last_charge_end, h.now_charge_start, h.source_api, h.run_id, h.collected_at FROM charger_status_history h JOIN charger c ON c.charger_id = h.charger_id WHERE c.station_id IN ($TARGET) AND h.collected_at >= timestamp '$snap' - interval '$HIST_DAYS days' AND h.collected_at < timestamp '$snap' AND h.run_id IN ($RUNS) ORDER BY 1"
  "occupancy_30m|occupancy_id, station_id, window_start, fast_charger_count, occupancy_rate, available, full_minutes, valid_minutes, observed_points, computed_at|SELECT occupancy_id, station_id, window_start, fast_charger_count, occupancy_rate, available, full_minutes, valid_minutes, observed_points, computed_at FROM occupancy_30m WHERE station_id IN ($TARGET) AND window_start >= timestamp '$snap' - interval '$OCC_DAYS days' AND window_start < timestamp '$snap' ORDER BY 1"
  "occupancy_forecast|forecast_id, station_id, target_window_start, p_available, predicted_occupancy_rate, model_version, generated_at|SELECT forecast_id, station_id, target_window_start, p_available, predicted_occupancy_rate, model_version, generated_at FROM occupancy_forecast WHERE station_id IN ($TARGET) ORDER BY 1"
)

for entry in "${TABLES[@]}"; do
  name="${entry%%|*}"; rest="${entry#*|}"; query="${rest#*|}"
  printf '%s\n' "\\copy ($query) TO STDOUT WITH (FORMAT csv)" | psql_remote > "$work/$name.csv.gz"
  gzip -t "$work/$name.csv.gz"
  rows="$(gzip -dc "$work/$name.csv.gz" | python3 -I -c 'import csv,sys; print(sum(1 for _ in csv.reader(sys.stdin)))')"
  echo "  $name: $rows행"
  printf '%s\n' "$rows" > "$work/$name.rows"
done

{
  sed "s|__SNAP__|$snap|" "$script_dir/load_header.sql"
  for entry in "${TABLES[@]}"; do
    name="${entry%%|*}"; rest="${entry#*|}"; cols="${rest%%|*}"
    printf '\n\\echo 적재: %s (%s행)\n' "$name" "$(cat "$work/$name.rows")"
    printf 'COPY stg_%s (%s) FROM STDIN WITH (FORMAT csv);\n' "$name" "$cols"
    gzip -dc "$work/$name.csv.gz"
    printf '\\.\n'
  done
  cat "$script_dir/load_footer.sql"
} | gzip -9 > "$out"

ls -l "$out"
echo "완료. 로컬 DB에 넣는 방법은 backend/scripts/snapshot/README.md"
