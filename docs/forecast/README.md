# AI 예측 ↔ 추천 연동 규격 (초안)

| 항목 | 내용 |
| --- | --- |
| 상태 | **초안: 합의 필요** (아래 "합의할 것" 참고) |
| 예측 모델 | 박상현 |
| 추천 API | 한휘제 |
| 관련 코드 | `V3__occupancy_forecast.sql`, `forecast/domain/OccupancyForecast`, `recommend/RecommendService` |

## 1. 한 줄 요약

예측 배치가 **충전소 × 30분 구간별 "급속충전기 1기 이상 비어 있을 확률"** 을 `occupancy_forecast` 테이블에 upsert 하면,
추천 API가 사용자의 **도착 구간** 값을 읽어 추천 점수에 쓴다. 예측이 없거나 오래됐으면 추천은 과거 통계로 대체하므로,
예측 배치가 멈춰도 추천 API는 계속 동작한다.

```
공공데이터 수집 (5분) ─▶ charger_status_history ─▶ 30분 집계 ─▶ occupancy_30m ──┐ (학습·입력)
                                                                                    ▼
                                                                         예측 배치 (박상현)
                                                                                    │ upsert
                                                                                    ▼
사용자 ─▶ GET /api/recommendations ─▶ RecommendService ◀── occupancy_forecast (도착 구간)
                                             ▲
                                   charger.current_* (실시간 상태)
```

## 2. 예측 대상 (라벨) 정의

추천에 필요한 값은 **"도착했을 때 바로 충전할 수 있는가"** 이다. 그래서 예측 대상은 기존 집계 테이블 `occupancy_30m`의 컬럼과 같은 정의를 쓴다.

| 출력 | 정의 | 학습 라벨 |
| --- | --- | --- |
| `p_available` (필수) | 구간 `[t, t+30분)` 마지막 유효 관측 시점에 급속충전기 AVAILABLE이 1기 이상일 확률 | `occupancy_30m.available` (true/false, NULL은 학습에서 제외) |
| `predicted_occupancy_rate` (선택) | 구간 평균 혼잡도 = CHARGING / (AVAILABLE + CHARGING) | `occupancy_30m.occupancy_rate` |

- 정의는 `aggregation/OccupancyWindowCalculator`의 주석에 있는 것과 같다. 급속충전기만 세고, 고장(UNAVAILABLE)은 분모에서 뺀다.
- 추천은 `p_available`만 쓴다. `predicted_occupancy_rate`는 화면 표시나 분석용이라 비워도 된다.

## 3. 출력 테이블 `occupancy_forecast`

```sql
CREATE TABLE occupancy_forecast (
  forecast_id               BIGSERIAL PRIMARY KEY,
  station_id                BIGINT NOT NULL REFERENCES station(station_id) ON DELETE CASCADE,
  target_window_start       TIMESTAMP NOT NULL,      -- KST, 00분 또는 30분 정각
  p_available               NUMERIC(5,4) NOT NULL,   -- 0~1
  predicted_occupancy_rate  NUMERIC(5,4),            -- 선택, 0~1
  model_version             VARCHAR(40) NOT NULL,    -- 예: 'lgbm-2026-10-06'
  generated_at              TIMESTAMP NOT NULL,      -- KST, 예측을 만든 시각
  UNIQUE (station_id, target_window_start)
);
```

| 컬럼 | 규칙 |
| --- | --- |
| `station_id` | `station.station_id` (내부 PK). `stat_id`가 아니다. `stat_id`로 찾으려면 `station` 테이블을 조인한다 |
| `target_window_start` | **KST**, `HH:00:00` 또는 `HH:30:00`만 허용 (CHECK 제약). `occupancy_30m.window_start`와 같은 기준 |
| `p_available` | 0 이상 1 이하 (CHECK 제약) |
| `model_version` | 모델이 바뀌면 값을 바꾼다. 추천 응답에 그대로 나가고, 백테스트할 때 모델별로 비교하는 데 쓴다 |
| `generated_at` | 예측을 실행한 시각. 추천은 이 값으로 신선도를 판단한다 |

## 4. 쓰기 규칙 (예측 배치)

| 항목 | 제안값 |
| --- | --- |
| 실행 주기 | **30분마다, 매시 05분·35분.** 30분 집계가 02분·32분에 끝나므로 그 뒤에 돈다 |
| 예측 범위 | 실행 시점 다음 구간부터 **6시간 (12구간)**. 남은 주행거리가 길면 도착까지 몇 시간 걸릴 수 있다 |
| 대상 충전소 | 서비스 대상(`station_access`에 매핑된) 충전소. 집계 테이블과 같은 범위 |
| 쓰기 방식 | `(station_id, target_window_start)` 기준 upsert. 같은 구간을 다시 예측하면 덮어쓴다 |
| 오래된 행 | 지난 구간은 지워도 되고 남겨도 된다. 추천은 도착 구간만 읽는다 |

upsert 예시 (Python, psycopg):

```python
rows = [(station_id, window_start, p, rate, MODEL_VERSION, generated_at), ...]  # 시각은 KST naive datetime
cur.executemany("""
    INSERT INTO occupancy_forecast
        (station_id, target_window_start, p_available, predicted_occupancy_rate, model_version, generated_at)
    VALUES (%s, %s, %s, %s, %s, %s)
    ON CONFLICT (station_id, target_window_start) DO UPDATE SET
        p_available = EXCLUDED.p_available,
        predicted_occupancy_rate = EXCLUDED.predicted_occupancy_rate,
        model_version = EXCLUDED.model_version,
        generated_at = EXCLUDED.generated_at
""", rows)
```

학습 데이터 조회 예시:

```sql
SELECT o.station_id, s.stat_id, o.window_start, o.available, o.occupancy_rate, o.observed_points, o.fast_charger_count
FROM occupancy_30m o JOIN station s USING (station_id)
WHERE o.window_start >= now() - interval '8 weeks';
```

## 5. 추천에서 쓰는 방식 (백엔드)

1. 후보 충전소마다 도착 예정 시각(ETA)을 계산한다. 도착 구간은 `floor(ETA, 30분)`이다.
2. `occupancy_forecast`에서 (충전소, 도착 구간)의 행을 읽는다. 단, `generated_at ≥ 지금 − forecast-max-age(기본 90분)`인 것만 쓴다.
3. 실시간 상태와 섞는다. 곧 도착하면 실시간 상태를, 멀면 예측을 더 믿는다.
   ```
   P(도착 시 가용) = α·P_now + (1−α)·P_base,   α = exp(−도착까지 분 / 30)
   P_base = AI 예측 p_available    (없거나 오래됐으면 과거 통계)
   ```
4. 응답에 근거를 같이 내려준다.
   - `basis`: `AI_FORECAST` 또는 통계 근거 수준
   - `modelVersion`: 쓴 모델 버전
   - `baselineProbability`: P_base
   - `statisticalProbability`: 과거 통계 값. AI 예측과 비교할 수 있게 항상 내려준다

## 6. 평가 (발표용)

`charger_status_history`와 `occupancy_30m`의 실제 결과로 백테스트한다.

| 비교 대상 | 지표 |
| --- | --- |
| AI 예측 `p_available` vs 과거 통계 vs 실시간 상태만 | 구간 가용 여부 예측의 Brier score, AUC |
| 추천 1순위 (AI) vs (통계) vs 가장 가까운 충전소 | 도착 구간에 실제로 가용이었던 비율 (적중률) |

`model_version`을 남겨 두면 모델을 바꿀 때마다 같은 방식으로 비교할 수 있다.

## 7. 합의할 것

- [ ] **연동 방식**: DB 테이블(이 초안)로 할지, 모델 서버 REST API로 할지. 테이블 방식은 예측 서버가 죽어도 추천이 영향을 받지 않는다
- [ ] **예측 대상**: `p_available`(가용 확률)을 필수로 하는 것에 동의하는지. 모델이 점유율만 낸다면 변환 규칙을 정해야 한다
- [ ] **주기·범위**: 30분 주기, 6시간(12구간)이 가능한지
- [ ] **신선도 기준**: 90분이 지난 예측은 버린다 (`evision.recommend.forecast-max-age`)
- [ ] **예측 이력 보존**: 덮어쓰기(이 초안)로 할지, 백테스트용으로 `generated_at`별로 쌓을지. 쌓는다면 UNIQUE 키를 `(station_id, target_window_start, generated_at)`로 바꾼다
- [ ] **DB 접근 권한**: 예측 배치용 DB 계정. `occupancy_forecast` INSERT/UPDATE, 학습용 테이블 SELECT만 준다
- [ ] **공휴일·명절**: 모델 피처에 넣을지. 통계 방식은 지금 공휴일을 평일로 본다
- [ ] **실행 위치**: 예측 배치를 백엔드 서버(EC2)에서 같이 돌릴지, 따로 돌릴지
