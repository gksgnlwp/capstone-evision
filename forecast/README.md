# forecast — 충전소 혼잡도 예측 (AI)

3조 EVISION · AI 예측 담당: 박상현

충전소별 **30분 후 혼잡도(0~1)** 와 **이용 가능 확률**을 예측하는 XGBoost 학습 코드입니다. (v0.2: 변수 그룹을 바꿔가며 효과를 비교할 수 있음)

> **현재 상태 (작성 시점 기준)**
> - 백엔드 전달 데이터(2026-09-27 ~ 10-27, 충전소 1,361곳)로 학습·평가를 실행함 (평가 구간 약 4.5일, 초기 결과)
> - 요일×시간대 평균 기준 모델보다 혼잡도 오차와 만차 탐지 모두 개선됨
> - v0.2에서 만차 이력·주변 충전소·충전소 정보 변수를 추가해 비교 실험 중
> - 정확도 목표(정밀도 0.90 / 재현율 0.80 / F1 0.85)는 초기 목표이며, 이용가능 기준은 기준 모델도 달성하므로 **만차 기준 개선 폭**으로 다시 정할 예정

## 파일

| 파일 | 설명 |
|---|---|
| `train_xgb.py` | 데이터셋 생성, 시간순 분할, 기준 모델 비교, XGBoost 학습·평가, 예측 결과 표 생성 |
| `test_train_xgb.py` | 학습 코드 테스트 (정답 시점 정렬, 미래 정보 누수 방지, 분할 순서, 임계값 등) |
| `requirements.txt` | 필요 패키지 |

## 실행 방법

```bash
cd forecast
pip install -r requirements.txt

# 1) 가상 데이터로 전체 흐름 확인 (성능 숫자는 의미 없음)
python train_xgb.py --demo

# 2) 백엔드가 준 실제 데이터로 학습 (.csv.gz 그대로 읽힘)
python train_xgb.py --csv occupancy_30m.csv.gz --access station_access.csv --stations stations.csv

# 3) 변수 그룹 사용: full,fac,static,hist 중 선택하거나 all
python train_xgb.py --csv occupancy_30m.csv.gz --access station_access.csv --stations stations.csv --groups all
python train_xgb.py ... --groups all --balance      # 만차 가중치 (만차를 더 중요하게 학습)

# 4) 하이퍼파라미터 탐색: 검증 구간으로 고른 설정과 기본 설정을 평가 구간에서 비교
python train_xgb.py ... --groups all --tune --tune-n 6

# 5) 학습 데이터에서 제외 충전소를 빼거나, 서비스 대상 목록만 사용
python train_xgb.py ... --exclude 학습데이터_제외_충전소_1104.csv
python train_xgb.py ... --include 서비스대상_충전소_1104.csv

# 6) 변수 그룹별 비교표 (모델을 여러 번 학습, 시간이 걸림)
python train_xgb.py ... --compare

# 7) DB에서 직접 읽기 (sqlalchemy, psycopg2-binary 추가 설치 필요)
python train_xgb.py --db "postgresql://user:pw@host:5432/dbname"

# 8) 임계값을 만차 F1 기준으로 선택 (기본은 이용가능 정밀도 목표 후 만차 F1)
python train_xgb.py ... --groups all --thr-mode f1

# 9) 11/09 데이터(오류 구분 포함): window_quality가 OK가 아닌 구간과 data_quality=LOW 충전소는 기본으로 제외됨
python train_xgb.py ... --groups all                          # 정리 적용 (기본)
python train_xgb.py ... --groups all --keep-fault --keep-low  # 정리 없이 사용 (정리 전 비교용)

# 테스트
pytest test_train_xgb.py
```

Colab에서 돌리려면 `train_xgb_colab.ipynb`를 열어 셀을 순서대로 실행합니다.

주요 옵션: `--horizon-steps`(1=30분 후, 2=60분 후), `--target-precision`(기본 0.90), `--holidays`(추가 공휴일), `--out-dir`(기본 `models`)

## 변수 그룹 (v0.2)

기본 변수(최근 혼잡도, 어제·지난주 같은 시간대, 요일·시간대·공휴일, 충전소 평균 등)에 아래 그룹을 더해 효과를 비교합니다. 모든 변수는 **기준 시각 t까지의 정보만** 사용하며, 테스트로 누수가 없는지 확인합니다.

| 그룹 | 내용 |
|---|---|
| `full` | 만차 이력: 직전 구간 만차 시간, 최근 6구간 만차 횟수, 연속 만차 구간 수, 어제·지난주 만차 시간 |
| `fac` | 주변 상황: 같은 휴게소(방향 포함)의 다른 충전소 혼잡도, 같은 노선 평균 혼잡도 |
| `static` | 충전소 정보: 급속충전기 수, 최대 출력, IC까지 거리 |
| `hist` | 학습 구간 통계: 충전소별 만차 비율, 요일·시간대별 평균 혼잡도 |

## 입력 데이터

백엔드가 전달한 파일을 그대로 씁니다.

| 파일 | 설명 |
|---|---|
| `occupancy_30m.csv.gz` | 필수. `station_id`, `window_start`, `occupancy_rate`, `available`(`t`/`f`/빈칸 — 빈칸은 학습 제외), `full_minutes`, `fast_charger_count` 등 |
| `station_access.csv` | 선택(`--access`). 휴게소/IC 구분, 노선, 휴게소 이름·방향, IC까지 거리 |
| `stations.csv` | 선택(`--stations`). 급속충전기 최대 출력 |

필요한 컬럼이 없는 변수 그룹은 자동으로 건너뜁니다. 팀 DB 정의서의 컬럼명과 다르면 `train_xgb.py`의 `load_csv` / `load_db`에서 이름만 바꿉니다.

## 11/13 5분 원본 데이터와 쾌적 이용확률 (v0.7)

```
# 1) 5분 원본 -> 30분 구간 표 (+ 5분 변수, 충전 경과시간 변수). 약 30초~2분
python prep_5m.py --occ EVision_5m_1113/occupancy_5m.csv.gz --raw EVision_5m_1113/status_history_raw.csv.gz \
                  --chargers EVision_5m_1113/chargers.csv --out data30.pkl
# 2) 쾌적 이용확률 학습 + 평가 + 추천 순위 평가
python train_xgb.py --csv data30.pkl --access EVision_5m_1113/station_access.csv --stations EVision_5m_1113/stations.csv \
                    --groups all,five --comfort-thr 0.8 --fast --rank-eval --out-dir models_c80
```

- `prep_5m.py`: 가현 님 README 의 규칙(유효 관측 3개 이상이면 혼잡도 평균, 마지막 유효 관측의 빈 충전기, 만차 시간)으로 30분 구간을 만든다. 기존 `occupancy_30m` 과 2,305,914행이 모두 일치함을 확인했다 (혼잡도는 반올림 차이 5e-5 이내).
  충전 경과시간 변수는 `collected_at <= 기준 시점 + 1분` 인 기록만 써서, 그 시점에 서버가 실제로 알던 정보만 쓴다.
- `--comfort-thr 0.8`: 30분 뒤 충전기 사용률이 80% 미만이면 **쾌적**. 분류 모델이 **쾌적 이용확률**을 내고, 저장 표의 `available_prob` 칸에 들어간다 (의미가 바뀌므로 추천 쪽과 합의 필요). `meta_h*.json` 의 `comfort_thr` 로 정의를 구분한다.
  평가는 macro F1·불쾌적 PR-AUC·브라이어 점수를 본다. 만차 지표는 내지 않는다.
- `--groups all,five`: 5분 변수(`f5_*`)를 쓴다. `all` 에는 포함되지 않는다.
- `--rank-eval`: 반경 `--rank-radius`(기본 10km) 안 후보 충전소 중 `쾌적확률 - 거리가중 x km` 이 가장 높은 곳이 실제로 쾌적했는지 평가한다. 후보를 반경으로 잡은 단순 평가이며, 실제 서비스의 경로·거리 규칙과는 다를 수 있다.
  `eval_pred_h*.pkl` 에 평가 구간 예측이 저장된다.
- `--fast`: 학습률 0.1, 나무 최대 300개. 점수는 거의 같고 시간이 줄어든다.
- 충전소 품질(`data_quality`)이 stations.csv 에 없으면 `window_quality` 로 같은 규칙(고장·상태불명 구간 50% 이상 = LOW)을 계산해 제외한다.

## 출력

`--out-dir` 아래에 저장됩니다.

- `reg_h{h}.json` — 혼잡도 회귀 모델
- `clf_h{h}.json` — 이용 가능 분류 모델
- `meta_h{h}.json` — 모델 버전, 사용한 변수 그룹·목록, 임계값, 학습 구간 통계, 학습 종료 시각
- `prediction_h{h}.csv` — PREDICTION 테이블 형태 (`station_id`, `target_time`, `horizon_min`, `congestion_rate`, `available_prob`, `model_version`, `predicted_at`(한국 시간))

## 설계 원칙

- 학습·검증·평가는 **시간순**으로 나누고 사이에 간격(gap)을 둡니다. 무작위 분할은 쓰지 않습니다.
- 기준 모델(요일×시간대 평균)과 "지금 값 그대로" 방식을 같은 평가 구간에서 비교합니다.
- 평가는 **만차**(이용 불가) 기준으로 봅니다. 대부분의 구간이 이용 가능이라 이용가능 정밀도·재현율은 모델 없이도 높게 나옵니다.
- 예측 결과는 배치로 만들어 PREDICTION 테이블에 저장하고, 추천 API는 저장된 값만 읽습니다.
- 검증되지 않은 모델은 자동 배포하지 않습니다.

## 합의가 필요한 항목 (`docs/forecast/README.md` 참고)

- 예측 결과 연동 방식
- 예측 배치 주기 (30분 제안)
- 오래된 예측 폐기 기준

## 예측 배치 (predict_batch.py, v0.7)

학습은 `train_xgb.py`, **서버에서 30분마다 도는 예측은 `predict_batch.py`** 입니다. 노트북(.ipynb)은 Colab 학습용이며 서버 연동에는 쓰지 않습니다.

```
python predict_batch.py --data recent.pkl --models models_c50 --horizons 1,2 \
    --access station_access.csv --stations stations.csv --out prediction.csv
```

- 입력: 학습 때와 같은 형식의 최신 구간 표(`prep_5m.py`가 만든 .pkl 또는 같은 컬럼 CSV). 지난주 같은 시간대 변수를 쓰므로 **최근 8일 이상**이 필요합니다. 학습 때 쓴 `--access`, `--stations`를 똑같이 줍니다.
- 출력 컬럼: `station_id, target_time, horizon_min, predicted_occupancy_rate, p_available, model_version, predicted_at`
  - `target_time`은 예측 대상 구간의 시작 시각입니다. 마지막 집계가 13:30~14:00이면 30분 후는 14:00 구간, 60분 후는 14:30 구간입니다.
  - `p_available`은 **쾌적 이용 확률**(학습 때 정한 사용률 기준 미만일 확률)입니다. "빈 충전기가 있을 확률"이 아닙니다.
  - 사용률은 충전 중 ÷ (사용 가능 + 충전 중)의 구간 평균이며, 고장·미확인 충전기는 분모에서 뺍니다.
- 안전장치: 최근 구간이 90분보다 오래됐으면 예측을 만들지 않고 종료 코드 2로 끝나며 기존 결과 파일은 그대로 남습니다. 결과는 임시 파일에 쓴 뒤 바꿔치기합니다. 학습에 쓰지 않은 충전소(품질 낮은 충전소)는 예측하지 않습니다.
- 서버 환경: `pip install xgboost pandas numpy scikit-learn holidays`.
- 검증: 단위 테스트 35개(배치 4개 포함: 두 예측 구간의 대상 시각, 오래된 데이터 건너뛰기, 직전 결과 보존, 학습 때 예측과 같은 값). 단, 샌드박스에서는 sklearn 대용 모듈로 시험했으므로 실제 XGBoost로 한 번 더 확인이 필요합니다(Colab 노트북의 마지막 셀).

## 서버 DB 연동 (export_recent.py → predict_lowmem.py → upsert_forecast.py)

`run_forecast.sh` 하나가 30분마다 아래 단계를 차례로 돌립니다. 추천 API는 `occupancy_forecast`에서 도착 구간 값을 읽습니다 (규격: `docs/forecast/README.md`).

| 단계 | 파일 | 하는 일 |
| --- | --- | --- |
| 1 | `export_recent.py` | DB에서 최근 9일을 뽑아 11/13 학습 데이터와 **같은 파일·컬럼**으로 저장 (`occupancy_5m.csv.gz`, `status_history_raw.csv.gz`, `chargers.csv`, `stations.csv`, `station_access.csv`) |
| 2 | `predict_lowmem.py prep` | `prep_5m.py` 함수로 30분 표 + 5분 변수 생성 (충전소 150곳씩) |
| 3 | `predict_lowmem.py predict` | `predict_batch.py` 함수로 30분 후·60분 후 예측 CSV (노선별) |
| 4 | `upsert_forecast.py` | CSV → `occupancy_forecast` upsert (한 트랜잭션, 형식 검사 후) |

**메모리 (EC2 여유 메모리 1GB 미만 대응).** `prep_5m.py`·`predict_batch.py`를 한 번에 돌리면 9일치(충전소 1,333곳, 상태 이력 172만 줄)에서
단계별 최대 1.1~1.3GB를 씁니다. `predict_lowmem.py`는 두 파일의 함수를 그대로 쓰되 나눠서 돌려 **단계별 최대 약 0.5GB**로 줄입니다.

| 단계 | 한 번에 | 나눠서 |
| --- | --- | --- |
| DB 추출 | 1,277MB | 489MB (COPY로 읽기, 상태를 정수 코드로 계산, pickle 저장) |
| 30분 표 | 827MB | 412MB (충전소 150곳씩, xgboost 미로딩) |
| 예측 | 1,088MB | 426MB (노선별) |

- 같은 휴게소의 다른 충전소·같은 노선 평균 변수는 모두 노선 안에서만 계산되므로 노선별로 나눠도 결과가 같습니다.
  스냅샷 9일치 규모에서 한 번에 돌린 결과와 2,436행이 모두 같음을 확인했고, 30분 표는 `test_db_pipeline.py`에서 비교합니다.
- 측정은 macOS 최대 RSS 기준입니다. 서버에서는 `/usr/bin/time -v ./run_forecast.sh`로 확인할 수 있습니다.

- 5분 시점 상태는 백엔드 30분 집계(`OccupancyAggregationRepository.POINT_COUNTS`)와 같은 규칙입니다: 시점 이하 최신 이력, 6시간 넘은 충전중은 미확인, `(t-10분, t+1분]` 수집 회차가 있으면 유효.
  시험 DB에서 백엔드 SQL과 4,320개(충전소×시점) 값이 모두 같음을 확인했습니다. 서버에서는 `python export_recent.py --out-dir work/export --check-30m`으로 DB `occupancy_30m`과 다시 대조할 수 있습니다.
- CSV → 테이블 컬럼: `target_time`→`target_window_start`, `predicted_at`→`generated_at`, `horizon_min`은 저장하지 않음. DB에 없는 충전소는 건너뜁니다.
- 실패하면 DB를 바꾸지 않습니다. 예측이 끊겨도 추천은 90분 뒤부터 과거 통계로 자동 대체합니다.

서버 설정 (주소·비밀번호는 저장소에 적지 않고 서버 환경변수로만 둡니다):

```bash
pip install -r requirements.txt
export EVISION_FORECAST_DB_URL='postgresql://<계정>:<비밀번호>@<호스트>:5432/<DB>'   # occupancy_forecast INSERT/UPDATE, 나머지 SELECT 권한
export FORECAST_MODEL_DIR=models_c50        # 쾌적 기준 0.5로 학습한 h1·h2 모델 (meta_h*.json 의 comfort_thr 가 0.5인지 확인)
./run_forecast.sh                           # 한 번 돌려 보고
# cron: 5,35 * * * * cd <forecast 폴더> && ./run_forecast.sh >> <로그 파일> 2>&1
```

종료 코드: 0 성공 / 1 오류 / 2 최근 데이터가 90분보다 오래돼 건너뜀 / 3 `--check-30m` 대조 불일치.
테스트: `pytest test_db_pipeline.py` (DB까지 보려면 마이그레이션을 적용한 빈 DB 주소를 `EVISION_TEST_DB_URL`로 준다).
