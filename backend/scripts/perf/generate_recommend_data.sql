-- 추천 API 성능 측정용 추가 데이터. generate_data.sql 다음에 실행한다 (측정 전용 DB에서만).
--
--   occupancy_weeks   occupancy_30m 보관 기간. 기본 12주 (보관 정책 미정이라 3개월로 가정, 추천은 최근 4주만 읽는다)
--   forecast_hours    AI 예측 범위. 기본 6시간 (docs/forecast/README.md 제안값)
--
-- 사용: psql -v occupancy_weeks=12 -f generate_recommend_data.sql

\set ON_ERROR_STOP on
\timing on

\if :{?occupancy_weeks}
\else
  \set occupancy_weeks 12
\endif
\if :{?forecast_hours}
\else
  \set forecast_hours 6
\endif

SET synchronous_commit = off;
SELECT setseed(0.42);

TRUNCATE occupancy_30m, occupancy_forecast RESTART IDENTITY;

-- 서비스 대상 충전소 × 30분 구간. 실제 집계처럼 지난 구간까지만 있고, 구간 순서로 쌓는다
-- (30분마다 전체 충전소를 한꺼번에 upsert 하므로 한 충전소의 행은 테이블 전체에 흩어진다).
INSERT INTO occupancy_30m (station_id, window_start, fast_charger_count, occupancy_rate, available,
                           full_minutes, valid_minutes, observed_points, computed_at)
SELECT station_id, ws, 4, round(r::numeric, 4), r < 0.8, CASE WHEN r >= 0.8 THEN 30 ELSE 0 END, 30, 6, now()
FROM (
  SELECT s.station_id, ws, random() AS r
  FROM (SELECT DISTINCT station_id FROM station_access) s
  CROSS JOIN generate_series(date_trunc('hour', localtimestamp) - make_interval(weeks => :occupancy_weeks),
                             date_trunc('hour', localtimestamp) - interval '30 minutes',
                             interval '30 minutes') ws
) x
ORDER BY ws, station_id;

INSERT INTO occupancy_forecast (station_id, target_window_start, p_available, model_version, generated_at)
SELECT s.station_id, w, round(random()::numeric, 4), 'perf-v0', localtimestamp
FROM (SELECT DISTINCT station_id FROM station_access) s
CROSS JOIN generate_series(date_trunc('hour', localtimestamp),
                           date_trunc('hour', localtimestamp) + make_interval(hours => :forecast_hours),
                           interval '30 minutes') w;

VACUUM ANALYZE occupancy_30m, occupancy_forecast;

SELECT (SELECT count(*) FROM occupancy_30m) AS occupancy_rows,
       pg_size_pretty(pg_total_relation_size('occupancy_30m')) AS occupancy_size,
       (SELECT count(*) FROM occupancy_forecast) AS forecast_rows;
