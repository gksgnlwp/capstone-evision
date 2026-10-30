# forecast — 충전소 혼잡도 예측 (AI)

3조 EVISION · AI 예측 담당: 박상현

충전소별 **30분 후 혼잡도(0~1)** 와 **이용 가능 확률**을 예측하는 1차 모델(XGBoost) 학습 코드입니다.

> **현재 상태 (작성 시점 기준)**
> - 학습 코드와 테스트 작성 완료
> - 가상 데이터(`--demo`)로 실행 흐름만 확인함. 가상 데이터의 지표는 성능이 아님
> - 실데이터 학습·평가는 백엔드에서 30분 집계 데이터를 전달받은 뒤 진행 예정
> - 정확도 목표(정밀도 0.90 / 재현율 0.80 / F1 0.85)는 초기 목표이며 아직 측정 전

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

# 2) 실제 30분 집계 CSV로 학습
python train_xgb.py --csv occupancy30m.csv

# 3) DB에서 직접 읽기 (sqlalchemy, psycopg2-binary 추가 설치 필요)
python train_xgb.py --db "postgresql://user:pw@host:5432/dbname"

# 테스트
pytest test_train_xgb.py
```

주요 옵션: `--horizon-steps`(1=30분 후, 2=60분 후), `--target-precision`(기본 0.90), `--holidays`(추가 공휴일), `--out-dir`(기본 `models`)

## 입력 데이터 (CSV/DB 공통)

| 컬럼 | 설명 |
|---|---|
| `station_id` | 충전소 ID |
| `window_start` | 30분 구간 시작 시각 (매시 00분·30분) |
| `occupancy_rate` | 혼잡도 0~1 = 충전 중 ÷ (이용 가능 + 충전 중). 유효 관측이 없으면 NULL |
| `available` | 이용 가능 여부 True/False. 미확인이면 NULL |
| `station_type` | 선택. `REST_AREA` / `IC` |

팀 DB 정의서의 컬럼명과 다르면 `train_xgb.py`의 `load_csv` / `load_db`에서 이름만 바꿉니다.

## 출력

`--out-dir` 아래에 저장됩니다.

- `reg_h{h}.json` — 혼잡도 회귀 모델
- `clf_h{h}.json` — 이용 가능 분류 모델
- `meta_h{h}.json` — 모델 버전, 예측 시점, 사용한 변수 목록, 임계값, 충전소별 평균, 학습 종료 시각 (평가 지표는 실행 시 화면에 출력)
- `prediction_h{h}.csv` — PREDICTION 테이블 형태 (`station_id`, `target_time`, `horizon_min`, `congestion_rate`, `available_prob`, `model_version`, `predicted_at`)

## 설계 원칙

- 학습·검증·평가는 **시간순**으로 나누고 사이에 간격(gap)을 둡니다. 무작위 분할은 쓰지 않습니다.
- 기준 모델(충전소 × 요일 × 시간대 평균)과 같은 평가 구간에서 비교하며, 기준 모델보다 나을 때만 반영합니다.
- 예측 결과는 배치로 만들어 PREDICTION 테이블에 저장하고, 추천 API는 저장된 값만 읽습니다.
- 검증되지 않은 모델은 자동 배포하지 않습니다.

## 합의가 필요한 항목 (`docs/forecast/README.md` 참고)

- 예측 결과 연동 방식
- 예측 배치 주기 (30분 제안)
- 오래된 예측 폐기 기준
