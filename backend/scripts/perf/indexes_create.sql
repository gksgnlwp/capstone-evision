-- "인덱스 적용 후" 상태: 마이그레이션(V1·V3)의 인덱스·UNIQUE 제약을 그대로 다시 만든다.
\set ON_ERROR_STOP on

CREATE INDEX IF NOT EXISTS idx_station_lat_lng ON station (latitude, longitude);
CREATE INDEX IF NOT EXISTS idx_access_rest_area ON station_access (rest_area_id);
CREATE INDEX IF NOT EXISTS idx_access_ic ON station_access (ic_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_access_rest_area ON station_access (station_id, rest_area_id) WHERE access_type = 'REST_AREA';
CREATE UNIQUE INDEX IF NOT EXISTS uk_access_ic        ON station_access (station_id, ic_id)        WHERE access_type = 'IC';

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'charger_station_id_chger_id_key') THEN
    ALTER TABLE charger ADD CONSTRAINT charger_station_id_chger_id_key UNIQUE (station_id, chger_id);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'charger_status_history_charger_id_status_updated_at_key') THEN
    ALTER TABLE charger_status_history ADD CONSTRAINT charger_status_history_charger_id_status_updated_at_key
      UNIQUE (charger_id, status_updated_at);
  END IF;
END $$;

ANALYZE station, station_access, charger, charger_status_history;
