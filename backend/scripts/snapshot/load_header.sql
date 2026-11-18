-- EVision 운영 데이터 스냅샷 (서비스 대상 충전소만). backend/scripts/snapshot/export.sh로 만든 파일이다.
-- 로컬 DB 전용: 노선·충전소·충전기·이력·집계·수집 기록 테이블을 비우고 이 스냅샷으로 다시 채운다.
--
-- 시각 맞추기: 기본으로 모든 시각을 '지금'에 맞춰 옮긴다 (스냅샷 기준 시각 → 적재 시각).
--   그래야 충전 중 6시간·사용가능 7일 '의심' 판정과 마지막 수집 시각이 실제 서버처럼 보인다.
--   원래 시각 그대로 넣으려면 psql에 -v keep_time=1 을 준다.

\set ON_ERROR_STOP on
\set snap '__SNAP__'

-- 안전장치: 운영 DB(전국 충전기 약 50만 대)에서는 아무것도 지우지 않고 멈춘다.
DO $$
BEGIN
  IF (SELECT count(*) FROM charger) > 100000 THEN
    RAISE EXCEPTION '운영 DB로 보입니다(충전기 %대). 스냅샷은 로컬 DB에만 넣으세요.', (SELECT count(*) FROM charger);
  END IF;
END $$;

-- shift: 일반 시각에 더할 값 (적재 시각 - 기준 시각, 분 단위)
-- wshift: 30분 구간 시작(window_start 등)에 더할 값. 구간이 00분·30분에 맞도록 shift를 30분 단위로 반올림한다.
\if :{?keep_time}
SELECT interval '0' AS shift, interval '0' AS wshift \gset
\else
SELECT d AS shift, make_interval(secs => round(extract(epoch FROM d) / 1800) * 1800) AS wshift
FROM (SELECT date_trunc('minute', localtimestamp) - :'snap'::timestamp AS d) x \gset
\endif
\echo 스냅샷 기준 시각 :snap, 옮기는 시간 :shift (30분 구간은 :wshift)

BEGIN;

CREATE TEMP TABLE stg_route (LIKE route) ON COMMIT DROP;
CREATE TEMP TABLE stg_rest_area (LIKE rest_area) ON COMMIT DROP;
CREATE TEMP TABLE stg_interchange (LIKE interchange) ON COMMIT DROP;
CREATE TEMP TABLE stg_station (LIKE station) ON COMMIT DROP;
CREATE TEMP TABLE stg_charger (LIKE charger) ON COMMIT DROP;
CREATE TEMP TABLE stg_station_access (LIKE station_access) ON COMMIT DROP;
CREATE TEMP TABLE stg_collection_run (LIKE collection_run) ON COMMIT DROP;
CREATE TEMP TABLE stg_charger_status_history (LIKE charger_status_history) ON COMMIT DROP;
CREATE TEMP TABLE stg_occupancy_30m (LIKE occupancy_30m) ON COMMIT DROP;
CREATE TEMP TABLE stg_occupancy_forecast (LIKE occupancy_forecast) ON COMMIT DROP;
