-- 성능 측정용 대용량 데이터 생성 (명세 9장). 측정 전용 DB에서만 실행한다. 기존 데이터를 모두 지운다.
--
-- 규모 기본값은 추정치다. TODO(확인 필요): 명세 12장 미결 #7 (성능 검증 데이터 규모, 박상현 11장)
--   stations          전국 충전소 수. 기본정보 수집이 약 53페이지(×9,999 충전기)라 충전기 약 55만 대 → 충전소 10만 곳으로 추정
--   mapped            서비스 대상(접근지점 매핑) 충전소 수. 실제 검증된 매핑 493건
--   fast_ratio        매핑 안 된 충전소의 급속 비율 (매핑된 고속도로 충전소는 0.9)
--   history_days      이력 보관 일수
--   changes_per_day   충전기 1대의 하루 상태 변경 수. 상태 수집 1회(10분치) 약 16,600건 → 5분마다 신규 약 8,300건
--                     → 하루 약 240만 건 ÷ 충전기 55만 대 ≈ 4.4
--
-- 사용: psql -v stations=100000 -v history_days=7 -f generate_data.sql

\set ON_ERROR_STOP on
\timing on

\if :{?stations}
\else
  \set stations 100000
\endif
\if :{?mapped}
\else
  \set mapped 493
\endif
\if :{?fast_ratio}
\else
  \set fast_ratio 0.25
\endif
\if :{?history_days}
\else
  \set history_days 7
\endif
\if :{?changes_per_day}
\else
  \set changes_per_day 4.4
\endif

SET synchronous_commit = off;
SELECT setseed(0.42);

TRUNCATE charger_status_history, occupancy_30m, station_access, charger, station, collection_run,
         rest_area, interchange, route RESTART IDENTITY CASCADE;

-- 기준 데이터: 실제 적재 건수와 같은 규모 (노선 37, 휴게소 210, IC 659)
INSERT INTO route (route_no, route_name)
SELECT g::text, '노선' || g FROM generate_series(1, 37) g;

INSERT INTO rest_area (route_id, name, direction, latitude, longitude, ev_charger_yn)
SELECT 1 + g % 37, '휴게소' || g / 2, CASE WHEN g % 2 = 0 THEN '상행' ELSE '하행' END,
       34.5 + random() * 3.5, 126.5 + random() * 3.0, 'Y'
FROM generate_series(0, 209) g;

INSERT INTO interchange (route_id, facility_code, name, facility_type, latitude, longitude)
SELECT 1 + g % 37, 'PFIC' || lpad(g::text, 6, '0'), 'IC' || g, 'IC',
       34.5 + random() * 3.5, 126.5 + random() * 3.0
FROM generate_series(1, 659) g;

-- 충전소: 남한 범위에 고르게 흩뿌린다. 서비스 대상 충전소는 아래에서 휴게소 좌표 근처로 옮긴다.
INSERT INTO station (stat_id, name, address, latitude, longitude, zcode, busi_id, updated_at)
SELECT 'PF' || lpad(g::text, 6, '0'), '충전소' || g, '측정용 주소',
       34.5 + random() * 3.5, 126.5 + random() * 3.0, '11', 'PF', now()
FROM generate_series(1, :stations) g;

-- 서비스 대상 충전소는 station_id 전체 범위에 고르게 흩어 둔다 (실제로도 고속도로 충전소 ID는 몰려 있지 않다).
-- 앞번호에 몰면 PK 순서로 읽는 계획이 금방 끝나 측정값이 실제보다 좋게 나온다.
SELECT :stations / :mapped AS step \gset

CREATE TEMP TABLE mapped_station AS
SELECT g AS n, 1 + (g - 1) * :step AS station_id FROM generate_series(1, :mapped) g;

UPDATE station s
SET latitude = ra.latitude + (random() - 0.5) * 0.002,
    longitude = ra.longitude + (random() - 0.5) * 0.002
FROM mapped_station m, rest_area ra
WHERE s.station_id = m.station_id AND ra.rest_area_id = 1 + (m.n - 1) % 210;

-- 매핑: 서비스 대상 충전소는 모두 휴게소, 그중 10%는 IC에도 매핑
INSERT INTO station_access (station_id, access_type, rest_area_id, mapping_method, verified_at)
SELECT station_id, 'REST_AREA', 1 + (n - 1) % 210, 'MANUAL', now()
FROM mapped_station;

INSERT INTO station_access (station_id, access_type, ic_id, detour_km, mapping_method, verified_at)
SELECT station_id, 'IC', 1 + (n * 7) % 659, 1.5, 'MANUAL', now()
FROM mapped_station
WHERE n % 10 = 0;

-- 충전기: 충전소당 1~10대 (평균 5.5대)
INSERT INTO charger (station_id, chger_id, charger_type, output_kw, is_fast)
SELECT station_id, lpad(k::text, 2, '0'),
       CASE WHEN fast THEN '04' ELSE '02' END, CASE WHEN fast THEN 100 ELSE 7 END, fast
FROM (
  SELECT s.station_id, k,
         random() < CASE WHEN m.station_id IS NOT NULL THEN 0.9 ELSE :fast_ratio END AS fast
  FROM station s
  LEFT JOIN mapped_station m ON m.station_id = s.station_id
  CROSS JOIN LATERAL generate_series(1, 1 + (s.station_id * 7919 % 10)::int) k
) x;

-- 수집 회차: 5분마다 1회
SELECT date_trunc('day', now()) - make_interval(days => :history_days) AS hist_start \gset

INSERT INTO collection_run (run_type, started_at, ended_at, run_status, api_call_count,
                            fetched_count, inserted_count)
SELECT 'STATUS', t, t + interval '20 seconds', 'SUCCESS', 2, 16600, 8300
FROM generate_series(CAST(:'hist_start' AS timestamp),
                     CAST(:'hist_start' AS timestamp) + make_interval(days => :history_days),
                     interval '5 minutes') t;

-- 상태 이력: 대량 적재 속도를 위해 제약을 잠시 풀고 넣은 뒤 다시 건다 (V1__init.sql과 같은 이름)
ALTER TABLE charger_status_history
  DROP CONSTRAINT charger_status_history_charger_id_status_updated_at_key,
  DROP CONSTRAINT charger_status_history_charger_id_fkey,
  DROP CONSTRAINT charger_status_history_run_id_fkey;

-- 충전기마다 기간을 m칸으로 나누고 칸마다 변경 1건 (칸이 겹치지 않아 (charger_id, 시각)이 유일하다)
-- random()은 SELECT 목록에 둔다. LATERAL 안에 두면 Memoize로 캐시되어 모든 충전기가 같은 값을 받는다.
SELECT round(:history_days * :changes_per_day)::int AS m \gset

INSERT INTO charger_status_history (charger_id, status_code, status_updated_at, source_api, run_id, collected_at)
SELECT charger_id, code, t, 'STATUS', run_id,
       CAST(:'hist_start' AS timestamp) + (run_id - 1) * interval '5 minutes'
FROM (
  SELECT charger_id, t,
         CASE WHEN r < 0.60 THEN '2' WHEN r < 0.90 THEN '3' WHEN r < 0.95 THEN '5' ELSE '1' END AS code,
         1 + ceil(extract(epoch FROM t - CAST(:'hist_start' AS timestamp)) / 300)::bigint AS run_id
  FROM (
    SELECT c.charger_id,
           date_trunc('second', CAST(:'hist_start' AS timestamp)
             + (i + random() * 0.9) * (make_interval(days => :history_days) / :m)) AS t,
           random() AS r
    FROM charger c CROSS JOIN generate_series(0, :m - 1) i
  ) v
) x;

ALTER TABLE charger_status_history
  ADD CONSTRAINT charger_status_history_charger_id_status_updated_at_key UNIQUE (charger_id, status_updated_at),
  ADD CONSTRAINT charger_status_history_charger_id_fkey FOREIGN KEY (charger_id) REFERENCES charger (charger_id),
  ADD CONSTRAINT charger_status_history_run_id_fkey FOREIGN KEY (run_id) REFERENCES collection_run (run_id);

-- 충전기 현재 상태 = 마지막 이력
UPDATE charger c
SET current_status_code = h.status_code,
    current_normalized_status = coalesce(cd.normalized_status, 'UNKNOWN'),
    current_status_updated_at = h.status_updated_at
FROM (
  SELECT DISTINCT ON (charger_id) charger_id, status_code, status_updated_at
  FROM charger_status_history
  ORDER BY charger_id, status_updated_at DESC
) h
LEFT JOIN code_dictionary cd ON cd.code_group = 'CHARGER_STAT' AND cd.code = CAST(h.status_code AS text)
WHERE c.charger_id = h.charger_id;

VACUUM ANALYZE;

SELECT (SELECT count(*) FROM station) AS stations,
       (SELECT count(*) FROM charger) AS chargers,
       (SELECT count(*) FROM charger c WHERE is_fast AND EXISTS (SELECT 1 FROM mapped_station m WHERE m.station_id = c.station_id)) AS mapped_fast_chargers,
       (SELECT count(*) FROM station_access) AS accesses,
       (SELECT count(*) FROM charger_status_history) AS history_rows,
       pg_size_pretty(pg_total_relation_size('charger_status_history')) AS history_size;
