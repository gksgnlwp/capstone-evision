-- "인덱스 적용 전" 상태: 조회 API와 집계가 쓰는 보조 인덱스를 모두 뺀다 (PK는 둔다). 측정 전용 DB에서만 실행한다.
-- UNIQUE 제약은 인덱스를 겸하므로 제약째 뺀다. indexes_create.sql로 V1__init.sql과 같은 상태로 되돌린다.
\set ON_ERROR_STOP on

DROP INDEX IF EXISTS idx_station_lat_lng;
DROP INDEX IF EXISTS idx_access_rest_area;
DROP INDEX IF EXISTS idx_access_ic;
ALTER TABLE station_access DROP CONSTRAINT IF EXISTS station_access_station_id_rest_area_id_ic_id_key;
ALTER TABLE charger DROP CONSTRAINT IF EXISTS charger_station_id_chger_id_key;
ALTER TABLE charger_status_history DROP CONSTRAINT IF EXISTS charger_status_history_charger_id_status_updated_at_key;

ANALYZE station, station_access, charger, charger_status_history;
