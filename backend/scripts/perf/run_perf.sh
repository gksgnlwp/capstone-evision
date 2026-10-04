#!/usr/bin/env bash
# 성능 측정 (명세 9장): 인덱스 적용 전·후 각각 EXPLAIN (ANALYZE, BUFFERS)와 HTTP 응답시간을 기록한다.
#
# 준비 (README.md 참고)
#   1. 측정 전용 PostgreSQL 컨테이너(evision-perf)에 V1·V2 스키마 적용 후 generate_data.sql로 데이터 생성
#   2. 앱을 그 DB에 붙여 실행 (스케줄러 끔)
#
# 사용: bash run_perf.sh            결과: 레포 루트 docs/perf/<날짜>/
# 환경변수: PG_CONTAINER(evision-perf) APP_URL(http://localhost:18080) RUNS(30) TIMEOUT(60s) OUT
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PG_CONTAINER="${PG_CONTAINER:-evision-perf}"
APP_URL="${APP_URL:-http://localhost:18080}"
RUNS="${RUNS:-30}"
TIMEOUT="${TIMEOUT:-60s}"
OUT="${OUT:-$HERE/../../../docs/perf/$(date +%F)}"
mkdir -p "$OUT"

q() { docker exec -i "$PG_CONTAINER" psql -X -q -U perf -d evision -At -c "$1"; }
psql_file() { docker exec -i "$PG_CONTAINER" psql -X -q -U perf -d evision "$@"; }

# ---- 측정 입력값 (서비스 대상 충전소 1번 기준) ----
STATION_ID=$(q "SELECT min(station_id) FROM station_access")
read -r LAT LNG <<<"$(q "SELECT latitude, longitude FROM station WHERE station_id = $STATION_ID" | tr '|' ' ')"
# 지도 한 화면 크기: 위도 ±0.15°, 경도 ±0.2° (약 33km × 36km)
MIN_LAT=$(awk "BEGIN{print $LAT-0.15}"); MAX_LAT=$(awk "BEGIN{print $LAT+0.15}")
MIN_LNG=$(awk "BEGIN{print $LNG-0.2}");  MAX_LNG=$(awk "BEGIN{print $LNG+0.2}")
CHARGER_ID=$(q "SELECT min(charger_id) FROM charger WHERE station_id = $STATION_ID AND is_fast")
read -r ROUTE_ID DIRECTION <<<"$(q "SELECT ra.route_id, ra.direction FROM station_access sa JOIN rest_area ra USING (rest_area_id) WHERE sa.station_id = $STATION_ID LIMIT 1" | tr '|' ' ')"
HIST_TO=$(q "SELECT to_char(date_trunc('day', max(status_updated_at)) + interval '1 day', 'YYYY-MM-DD\"T\"HH24:MI:SS') FROM charger_status_history")
HIST_FROM=$(q "SELECT to_char(CAST('$HIST_TO' AS timestamp) - interval '7 days', 'YYYY-MM-DD\"T\"HH24:MI:SS')")
WINDOW=$(q "SELECT to_char(CAST('$HIST_TO' AS timestamp) - interval '1 day' + interval '18 hours', 'YYYY-MM-DD\"T\"HH24:MI:SS')")
AREA_IDS=$(q "SELECT coalesce(string_agg(station_id::text, ',' ORDER BY station_id), '0') FROM (SELECT s.station_id FROM station s WHERE s.del_yn = 'N' AND s.latitude BETWEEN $MIN_LAT AND $MAX_LAT AND s.longitude BETWEEN $MIN_LNG AND $MAX_LNG AND EXISTS (SELECT 1 FROM station_access sa WHERE sa.station_id = s.station_id) ORDER BY s.station_id LIMIT 100) x")
ALL_IDS=$(q "SELECT string_agg(station_id::text, ',' ORDER BY station_id) FROM (SELECT DISTINCT station_id FROM station_access ORDER BY station_id LIMIT 500) x")

{
  echo "# 측정 환경"
  echo
  echo "- 측정일시: $(date '+%F %T')"
  echo "- DB: $(q 'SELECT version()' | cut -d, -f1) (컨테이너 $PG_CONTAINER)"
  echo "- 규모: 충전소 $(q 'SELECT count(*) FROM station'), 충전기 $(q 'SELECT count(*) FROM charger'), 매핑 $(q 'SELECT count(*) FROM station_access'), 상태 이력 $(q 'SELECT count(*) FROM charger_status_history')행 ($(q "SELECT pg_size_pretty(pg_total_relation_size('charger_status_history'))")), 서비스 대상 급속 충전기 $(q 'SELECT count(*) FROM charger c WHERE is_fast AND EXISTS (SELECT 1 FROM station_access sa WHERE sa.station_id = c.station_id)')대"
  echo "- 입력값: stationId=$STATION_ID, chargerId=$CHARGER_ID, routeId=$ROUTE_ID, direction=$DIRECTION"
  echo "- 지도 영역: lat $MIN_LAT~$MAX_LAT, lng $MIN_LNG~$MAX_LNG (결과 $(echo "$AREA_IDS" | tr ',' '\n' | grep -vc '^0$')곳)"
  echo "- 이력 기간: $HIST_FROM ~ $HIST_TO, 집계 구간: $WINDOW"
} > "$OUT/environment.md"
cat "$OUT/environment.md"

VARS=(-v timeout="$TIMEOUT" -v min_lat="$MIN_LAT" -v max_lat="$MAX_LAT" -v min_lng="$MIN_LNG" -v max_lng="$MAX_LNG"
      -v area_ids="$AREA_IDS" -v all_ids="$ALL_IDS" -v station_id="$STATION_ID" -v charger_id="$CHARGER_ID"
      -v route_id="$ROUTE_ID" -v direction="$DIRECTION" -v hist_from="$HIST_FROM" -v hist_to="$HIST_TO" -v window="$WINDOW")

# ---- HTTP 응답시간: 워밍업 3회 뒤 RUNS회, 밀리초 p50/p95/max ----
http_bench() {
  local name="$1" url="$2" times
  times=$(for _ in 1 2 3; do curl -s -o /dev/null "$url"; done
          for _ in $(seq 1 "$RUNS"); do curl -s -o /dev/null -w '%{time_total}\n' "$url"; done)
  echo "$times" | awk -v name="$name" '{ v[NR] = $1 * 1000 } END {
      n = asort(v); printf "| %s | %.1f | %.1f | %.1f |\n", name, v[int(n*0.5+0.5)], v[int(n*0.95+0.5)], v[n] }'
}

measure_state() {
  local state="$1"
  echo "== $state: EXPLAIN (워밍업 1회 후 측정)"
  psql_file "${VARS[@]}" < "$HERE/queries.sql" > /dev/null 2>&1 || true
  psql_file "${VARS[@]}" < "$HERE/queries.sql" > "$OUT/explain_$state.txt" 2>&1 || true

  if curl -s -o /dev/null "$APP_URL/v3/api-docs"; then
    echo "== $state: HTTP ($RUNS회)"
    local enc_dir; enc_dir=$(printf '%s' "$DIRECTION" | od -An -tx1 | tr ' ' '%' | tr -d '\n' | tr 'a-f' 'A-F')
    {
      echo "| API | p50 (ms) | p95 (ms) | max (ms) |"
      echo "|---|---|---|---|"
      http_bench "검색-영역" "$APP_URL/api/stations?minLat=$MIN_LAT&minLng=$MIN_LNG&maxLat=$MAX_LAT&maxLng=$MAX_LNG"
      http_bench "검색-전국(limit 500)" "$APP_URL/api/stations?minLat=33&minLng=124&maxLat=39&maxLng=132&limit=500"
      http_bench "검색-노선+방향" "$APP_URL/api/stations?routeId=$ROUTE_ID&direction=$enc_dir"
      http_bench "상세" "$APP_URL/api/stations/$STATION_ID"
      http_bench "이력-7일(size 100)" "$APP_URL/api/chargers/$CHARGER_ID/history?from=$HIST_FROM&to=$HIST_TO&size=100"
    } > "$OUT/http_$state.md"
    cat "$OUT/http_$state.md"
  else
    echo "앱($APP_URL)이 응답하지 않아 HTTP 측정은 건너뜀"
  fi
}

echo "== 인덱스 제거"
psql_file < "$HERE/indexes_drop.sql"
measure_state before

echo "== 인덱스 생성 (V1과 같은 상태로 복구)"
psql_file < "$HERE/indexes_create.sql"
measure_state after

# ---- 쿼리별 실행시간 요약 ----
summarize() {
  awk '/^### /{ name = substr($0, 5) } /Execution Time/{ t[name] = $3 " ms" } /statement timeout/{ t[name] = "시간 초과" }
       /^### /{ order[++n] = substr($0, 5) } END { for (i = 1; i <= n; i++) print order[i] "\t" t[order[i]] }' "$1"
}
{
  echo "| 쿼리 | 인덱스 전 | 인덱스 후 |"
  echo "|---|---|---|"
  paste <(summarize "$OUT/explain_before.txt") <(summarize "$OUT/explain_after.txt" | cut -f2) \
    | awk -F'\t' '{ printf "| %s | %s | %s |\n", $1, $2, $3 }'
} > "$OUT/summary_explain.md"
cat "$OUT/summary_explain.md"
echo "결과: $OUT"
