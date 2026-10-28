-- AI 예측 결과 (예측 모델 → 추천). 연동 규격: docs/forecast/README.md
-- 예측 배치가 충전소 × 30분 구간별 가용 확률을 upsert 하고, 추천 API가 도착 구간 값을 읽는다.
CREATE TABLE occupancy_forecast (
  forecast_id               BIGSERIAL PRIMARY KEY,
  station_id                BIGINT NOT NULL REFERENCES station(station_id) ON DELETE CASCADE,
  target_window_start       TIMESTAMP NOT NULL,      -- 예측 대상 30분 구간 시작 (KST, 00분 또는 30분 정각)
  p_available               NUMERIC(5,4) NOT NULL,   -- 구간 안에 급속충전기 1기 이상 가용일 확률 (occupancy_30m.available = true 의 확률)
  predicted_occupancy_rate  NUMERIC(5,4),            -- 선택: 예측 점유율 (occupancy_30m.occupancy_rate 와 같은 정의)
  model_version             VARCHAR(40) NOT NULL,
  generated_at              TIMESTAMP NOT NULL,      -- 예측을 만든 시각 (KST)
  UNIQUE (station_id, target_window_start),
  CHECK (p_available BETWEEN 0 AND 1),
  CHECK (predicted_occupancy_rate IS NULL OR predicted_occupancy_rate BETWEEN 0 AND 1),
  CHECK (EXTRACT(MINUTE FROM target_window_start) IN (0, 30) AND EXTRACT(SECOND FROM target_window_start) = 0)
);
