#!/usr/bin/env bash
# 30분 예측 배치: DB 추출 → 30분 표 만들기 → 예측 → DB 저장.
# 서버 cron 예 (매시 05분·35분, 30분 집계가 02분·32분에 끝난 뒤):
#   5,35 * * * * cd /opt/evision/forecast && ./run_forecast.sh >> /var/log/evision-forecast.log 2>&1
# 필요한 환경변수 (서버의 .env 등에 둔다. 코드·저장소에 주소를 적지 않는다):
#   EVISION_FORECAST_DB_URL  postgresql://계정:비밀번호@호스트:5432/DB
#   FORECAST_MODEL_DIR       train_xgb.py 로 학습한 모델 폴더 (기본 models_c50)
#   FORECAST_WORK_DIR        중간 파일 폴더 (기본 work)
#   FORECAST_PYTHON          파이썬 (기본 python3)
# 종료 코드: 0 성공 / 2 데이터가 오래돼 건너뜀 (기존 예측은 90분 뒤 자동으로 안 쓰임) / 그 밖 오류
set -euo pipefail
cd "$(dirname "$0")"

PY="${FORECAST_PYTHON:-python3}"
MODELS="${FORECAST_MODEL_DIR:-models_c50}"
WORK="${FORECAST_WORK_DIR:-work}"
LOCK="$WORK/.lock"

mkdir -p "$WORK"
if ! mkdir "$LOCK" 2>/dev/null; then
  # 이전 실행이 아직 도는 중. 25분 넘은 잠금은 죽은 실행이 남긴 것으로 보고 지운다.
  if [ -n "$(find "$LOCK" -maxdepth 0 -mmin +25 2>/dev/null)" ]; then
    rmdir "$LOCK" && mkdir "$LOCK"
  else
    echo "$(date '+%F %T') [건너뜀] 이전 예측 배치가 아직 실행 중입니다."
    exit 0
  fi
fi
trap 'rmdir "$LOCK" 2>/dev/null || true' EXIT

echo "$(date '+%F %T') 예측 배치 시작"
# 메모리 1GB 미만 서버용: 단계마다 다른 프로세스로 돌려 앞 단계 메모리가 남지 않게 한다 (단계별 최대 약 0.5GB)
export MALLOC_ARENA_MAX="${MALLOC_ARENA_MAX:-2}"
"$PY" export_recent.py --out-dir "$WORK/export" --days "${FORECAST_DAYS:-9}" --pickle
"$PY" predict_lowmem.py prep --export-dir "$WORK/export" --out "$WORK/data30.pkl"

set +e
"$PY" predict_lowmem.py predict --data "$WORK/data30.pkl" --export-dir "$WORK/export" --models "$MODELS" \
  --horizons 1,2 --out "$WORK/prediction.csv"
rc=$?
set -e
if [ "$rc" -ne 0 ]; then
  echo "$(date '+%F %T') 예측을 만들지 않았습니다 (종료 코드 $rc). DB는 그대로 둡니다."
  exit "$rc"
fi

"$PY" upsert_forecast.py --csv "$WORK/prediction.csv"
echo "$(date '+%F %T') 예측 배치 끝"
