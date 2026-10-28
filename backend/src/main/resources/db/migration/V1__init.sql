-- EVision 수집·도메인·조회 스키마 (요구사항분석서 6·8장 v2 + 팀 통합 변경분)

-- 고속도로 기준 데이터
CREATE TABLE route (
  route_id    BIGSERIAL PRIMARY KEY,
  route_no    VARCHAR(10) NOT NULL UNIQUE,
  route_name  VARCHAR(50) NOT NULL
);

CREATE TABLE rest_area (
  rest_area_id  BIGSERIAL PRIMARY KEY,
  route_id      BIGINT NOT NULL REFERENCES route(route_id),
  name          VARCHAR(100) NOT NULL,
  direction     VARCHAR(20)  NOT NULL,          -- 원천 노선방향 값 그대로
  latitude      DOUBLE PRECISION NOT NULL,
  longitude     DOUBLE PRECISION NOT NULL,
  ev_charger_yn CHAR(1) NOT NULL,
  UNIQUE (route_id, name, direction)
);

CREATE TABLE interchange (
  ic_id         BIGSERIAL PRIMARY KEY,
  route_id      BIGINT NOT NULL REFERENCES route(route_id),
  facility_code VARCHAR(20) NOT NULL UNIQUE,
  name          VARCHAR(100) NOT NULL,
  facility_type VARCHAR(5)  NOT NULL,           -- IC / JCT
  latitude      DOUBLE PRECISION NOT NULL,
  longitude     DOUBLE PRECISION NOT NULL
);

-- 충전 인프라
CREATE TABLE station (
  station_id      BIGSERIAL PRIMARY KEY,
  stat_id         CHAR(8) NOT NULL UNIQUE,      -- 공식 명세 크기 8
  name            VARCHAR(100) NOT NULL,
  address         VARCHAR(255) NOT NULL,
  address_detail  VARCHAR(255),
  latitude        DOUBLE PRECISION NOT NULL,
  longitude       DOUBLE PRECISION NOT NULL,
  zcode           CHAR(2) NOT NULL,
  zscode          CHAR(5),
  kind            VARCHAR(2),
  kind_detail     VARCHAR(4),
  busi_id         CHAR(2) NOT NULL,
  org_name        VARCHAR(100),
  operator_name   VARCHAR(100),
  operator_call   VARCHAR(20),
  use_time        VARCHAR(100),
  parking_free_yn CHAR(1),
  limit_yn        CHAR(1),
  limit_detail    VARCHAR(255),
  del_yn          CHAR(1) NOT NULL DEFAULT 'N',
  updated_at      TIMESTAMP NOT NULL
);
CREATE INDEX idx_station_kind_detail ON station(kind_detail);
CREATE INDEX idx_station_lat_lng     ON station(latitude, longitude);

CREATE TABLE charger (
  charger_id                 BIGSERIAL PRIMARY KEY,
  station_id                 BIGINT NOT NULL REFERENCES station(station_id),
  chger_id                   CHAR(2) NOT NULL,  -- 공식 명세 크기 2
  charger_type               VARCHAR(2) NOT NULL,
  output_kw                  INTEGER,
  method                     VARCHAR(20),
  is_fast                    BOOLEAN NOT NULL,
  del_yn                     CHAR(1) NOT NULL DEFAULT 'N',
  current_status_code        CHAR(1),
  current_normalized_status  VARCHAR(12),       -- AVAILABLE/CHARGING/UNAVAILABLE/UNKNOWN
  current_status_updated_at  TIMESTAMP,
  UNIQUE (station_id, chger_id)
);

CREATE TABLE station_access (
  access_id      BIGSERIAL PRIMARY KEY,
  station_id     BIGINT NOT NULL REFERENCES station(station_id) ON DELETE CASCADE,
  access_type    VARCHAR(10) NOT NULL,          -- REST_AREA / IC
  rest_area_id   BIGINT REFERENCES rest_area(rest_area_id) ON DELETE CASCADE,
  ic_id          BIGINT REFERENCES interchange(ic_id) ON DELETE CASCADE,
  detour_km      NUMERIC(5,2) NOT NULL DEFAULT 0,
  mapping_method VARCHAR(10) NOT NULL,          -- MANUAL / AUTO
  verified_at    TIMESTAMP,
  CHECK ((access_type = 'REST_AREA' AND rest_area_id IS NOT NULL AND ic_id IS NULL)
      OR (access_type = 'IC'        AND ic_id IS NOT NULL AND rest_area_id IS NULL)),
  CHECK (access_type <> 'REST_AREA' OR mapping_method = 'MANUAL'),
  UNIQUE (station_id, rest_area_id, ic_id)
);
CREATE INDEX idx_access_rest_area ON station_access(rest_area_id);
CREATE INDEX idx_access_ic        ON station_access(ic_id);

-- 수집
CREATE TABLE collection_run (
  run_id          BIGSERIAL PRIMARY KEY,
  run_type        VARCHAR(10) NOT NULL,         -- INFO / STATUS
  started_at      TIMESTAMP NOT NULL,
  ended_at        TIMESTAMP,
  run_status      VARCHAR(10) NOT NULL,         -- RUNNING / SUCCESS / PARTIAL / FAILED
  api_call_count  SMALLINT NOT NULL DEFAULT 0,
  total_count     INTEGER,
  fetched_count   INTEGER NOT NULL DEFAULT 0,
  inserted_count  INTEGER NOT NULL DEFAULT 0,
  duplicate_count INTEGER NOT NULL DEFAULT 0,
  unknown_count   INTEGER NOT NULL DEFAULT 0,   -- 미등록 충전기로 건너뛴 건수
  invalid_count   INTEGER NOT NULL DEFAULT 0,   -- 형식 오류 건수
  retry_count     SMALLINT NOT NULL DEFAULT 0,
  error_code      VARCHAR(40),                  -- API resultCode 또는 내부 오류 코드
  error_message   TEXT
);
CREATE INDEX idx_run_type_started ON collection_run(run_type, started_at);

CREATE TABLE charger_status_history (
  history_id         BIGSERIAL PRIMARY KEY,
  charger_id         BIGINT NOT NULL REFERENCES charger(charger_id),
  status_code        CHAR(1) NOT NULL,
  status_updated_at  TIMESTAMP NOT NULL,
  last_charge_start  TIMESTAMP,                 -- INFO 수집분에만 존재
  last_charge_end    TIMESTAMP,
  now_charge_start   TIMESTAMP,
  source_api         VARCHAR(10) NOT NULL,      -- INFO / STATUS
  run_id             BIGINT NOT NULL REFERENCES collection_run(run_id),
  collected_at       TIMESTAMP NOT NULL,
  UNIQUE (charger_id, status_updated_at)
);

-- 분석
CREATE TABLE occupancy_30m (
  occupancy_id       BIGSERIAL PRIMARY KEY,
  station_id         BIGINT NOT NULL REFERENCES station(station_id),
  window_start       TIMESTAMP NOT NULL,        -- 00분 또는 30분 정각
  fast_charger_count SMALLINT NOT NULL,
  occupancy_rate     NUMERIC(5,4),              -- 분모 0이면 NULL
  available          BOOLEAN,                   -- 미확인이면 NULL
  full_minutes       SMALLINT NOT NULL,
  valid_minutes      SMALLINT NOT NULL,
  observed_points    SMALLINT NOT NULL,         -- 유효 관측 시점 수 (최대 6)
  computed_at        TIMESTAMP NOT NULL,
  UNIQUE (station_id, window_start)
);

-- 공통 코드
CREATE TABLE code_dictionary (
  code_id           BIGSERIAL PRIMARY KEY,
  code_group        VARCHAR(30)  NOT NULL,      -- CHARGER_STAT / CHARGER_TYPE / KIND / KIND_DETAIL
  code              VARCHAR(10)  NOT NULL,
  code_name         VARCHAR(100) NOT NULL,
  normalized_status VARCHAR(12),                -- CHARGER_STAT 그룹에서만 사용
  source_doc        VARCHAR(100) NOT NULL,
  UNIQUE (code_group, code)
);
