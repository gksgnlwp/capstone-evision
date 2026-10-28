#!/usr/bin/env bash
# 추천 API 성능 측정: EXPLAIN (ANALYZE, BUFFERS)와 HTTP 응답시간을 한 상태(label)로 기록한다.
#
# 준비 (README.md 참고): 측정 DB에 generate_data.sql → generate_recommend_data.sql, 앱을 그 DB에 붙여 실행
# 사용: bash run_recommend_perf.sh <label>      결과: 레포 루트 docs/perf/<날짜>-recommend/
# 환경변수: PG_CONTAINER(evision-perf) APP_URL(http://localhost:18080) RUNS(30) ROUTE_ID(3) OUT
set -euo pipefail

LABEL="${1:?label (예: before, after)}"
HERE="$(cd "$(dirname "$0")" && pwd)"
PG_CONTAINER="${PG_CONTAINER:-evision-perf}"
APP_URL="${APP_URL:-http://localhost:18080}"
RUNS="${RUNS:-30}"
ROUTE_ID="${ROUTE_ID:-3}"
OUT="${OUT:-$HERE/../../../docs/perf/$(date +%F)-recommend}"
mkdir -p "$OUT"

q() { docker exec -i "$PG_CONTAINER" psql -X -q -U perf -d evision -At -c "$1"; }

# 입력값: 노선의 첫 휴게소에서 출발, 남은 주행거리 1000km (노선 위 후보 전부)
read -r LAT LNG <<<"$(q "SELECT latitude, longitude FROM rest_area WHERE route_id = $ROUTE_ID ORDER BY rest_area_id LIMIT 1" | tr '|' ' ')"
IDS=$(q "SELECT string_agg(DISTINCT sa.station_id::text, ',') FROM station_access sa
         LEFT JOIN rest_area ra USING (rest_area_id) LEFT JOIN interchange ic USING (ic_id)
         WHERE ra.route_id = $ROUTE_ID OR ic.route_id = $ROUTE_ID")
VARS=(-v timeout=60s -v route_id="$ROUTE_ID" -v ids="$IDS"
      -v history_from="$(q "SELECT localtimestamp - interval '4 weeks'")"
      -v window_from="$(q "SELECT date_trunc('hour', localtimestamp)")"
      -v window_to="$(q "SELECT date_trunc('hour', localtimestamp) + interval '5 hours'")"
      -v generated_after="$(q "SELECT localtimestamp - interval '90 minutes'")"
      -v slot="$(q "SELECT to_char(date_trunc('hour', localtimestamp), 'HH24:MI:SS')")")

{
  echo "# 측정 환경 ($LABEL)"
  echo
  echo "- 측정일시: $(date '+%F %T')"
  echo "- 규모: 충전소 $(q 'SELECT count(*) FROM station'), 충전기 $(q 'SELECT count(*) FROM charger'), 매핑 $(q 'SELECT count(*) FROM station_access'), occupancy_30m $(q 'SELECT count(*) FROM occupancy_30m')행 ($(q "SELECT pg_size_pretty(pg_total_relation_size('occupancy_30m'))")), occupancy_forecast $(q 'SELECT count(*) FROM occupancy_forecast')행"
  echo "- 입력값: routeId=$ROUTE_ID, 출발 ($LAT, $LNG), remainingRangeKm=1000, 후보 충전소 $(echo "$IDS" | tr ',' '\n' | wc -l | tr -d ' ')곳"
  echo "- occupancy_30m 인덱스: $(q "SELECT string_agg(indexname, ', ') FROM pg_indexes WHERE tablename = 'occupancy_30m'")"
} > "$OUT/environment_$LABEL.md"
cat "$OUT/environment_$LABEL.md"

echo "== EXPLAIN (워밍업 1회 후 측정)"
docker exec -i "$PG_CONTAINER" psql -X -q -U perf -d evision "${VARS[@]}" < "$HERE/recommend_queries.sql" > /dev/null 2>&1 || true
docker exec -i "$PG_CONTAINER" psql -X -q -U perf -d evision "${VARS[@]}" < "$HERE/recommend_queries.sql" > "$OUT/explain_$LABEL.txt" 2>&1 || true
awk '/^### /{ name = substr($0, 5) } /Buffers: shared/ && !(name in buf){ buf[name] = $0 } /Execution Time/{ printf "| %s | %s ms | %s |\n", name, $3, buf[name] }' \
  "$OUT/explain_$LABEL.txt" | sed 's/  */ /g' > "$OUT/summary_explain_$LABEL.md"
cat "$OUT/summary_explain_$LABEL.md"

echo "== HTTP ($RUNS회, 워밍업 3회)"
URL="$APP_URL/api/recommendations?lat=$LAT&lng=$LNG&routeId=$ROUTE_ID&remainingRangeKm=1000&limit=20"
times=$(for _ in 1 2 3; do curl -s -o /dev/null "$URL"; done
        for _ in $(seq 1 "$RUNS"); do curl -s -o /dev/null -w '%{time_total}\n' "$URL"; done)
echo "$times" | sort -n | awk '{ v[NR] = $1 * 1000 } END {
    printf "| 추천 | %.1f | %.1f | %.1f |\n", v[int(NR*0.5+0.5)], v[int(NR*0.95+0.5)], v[NR] }' > "$OUT/http_$LABEL.md"
cat "$OUT/http_$LABEL.md"
echo "결과: $OUT"
