-- "인덱스 적용 후" 상태: V1__init.sql의 인덱스·UNIQUE 제약을 그대로 다시 만든다.
\set ON_ERROR_STOP on

CREATE INDEX IF NOT EXISTS idx_station_lat_lng ON station (latitude, longitude);
CREATE INDEX IF NOT EXISTS idx_access_rest_area ON station_access (rest_area_id);
CREATE INDEX IF NOT EXISTS idx_access_ic ON station_access (ic_id);

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'station_access_station_id_rest_area_id_ic_id_key') THEN
    ALTER TABLE station_access ADD CONSTRAINT station_access_station_id_rest_area_id_ic_id_key
      UNIQUE (station_id, rest_area_id, ic_id);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'charger_station_id_chger_id_key') THEN
    ALTER TABLE charger ADD CONSTRAINT charger_station_id_chger_id_key UNIQUE (station_id, chger_id);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'charger_status_history_charger_id_status_updated_at_key') THEN
    ALTER TABLE charger_status_history ADD CONSTRAINT charger_status_history_charger_id_status_updated_at_key
      UNIQUE (charger_id, status_updated_at);
  END IF;
END $$;

ANALYZE station, station_access, charger, charger_status_history;
