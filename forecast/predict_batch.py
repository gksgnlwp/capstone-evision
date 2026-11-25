"""
predict_batch.py — 저장된 모델로 '가장 최근 구간 이후'의 예측을 만들어 예측 결과 표(CSV)로 저장한다.
서버에서 30분마다 돌리는 배치용이다. 학습은 하지 않는다 (학습은 train_xgb.py).

하는 일
  1. train_xgb.py 가 저장한 모델 폴더(reg_h1.json, clf_h1.json, meta_h1.json ...)를 읽는다.
  2. 최신 데이터(학습 때와 같은 형식의 표)로 충전소별 변수를 만든다.
  3. 30분 후(h=1), 60분 후(h=2) 예측을 한 파일로 저장한다.

실행 예
  python predict_batch.py --data recent.pkl --models models_c50 --horizons 1,2 \
      --access station_access.csv --stations stations.csv --out prediction.csv

입력 데이터 (--data)
  학습 때 쓴 표와 같은 형식: prep_5m.py 가 만든 .pkl 또는 같은 컬럼의 CSV.
  ※ 지난주 같은 시간대 변수를 쓰므로 최근 8일 이상의 구간이 들어 있어야 정확하다. (짧으면 해당 변수가 비어 정확도가 떨어진다)
  ※ 학습 때 쓴 --access, --stations 를 똑같이 주어야 한다 (같은 휴게소 정보, 충전기 출력).

출력 컬럼 (예측 결과 테이블)
  station_id, target_time(예측 대상 구간 시작 시각), horizon_min,
  predicted_occupancy_rate(사용률 0~1), p_available(쾌적 이용 확률 — 모델을 학습할 때 정한 쾌적 기준 미만일 확률),
  model_version, predicted_at(한국 시간)

안전장치
  - 가장 최근 구간이 너무 오래됐으면(기본 90분 초과) 예측을 만들지 않고, 기존 결과 파일을 그대로 둔 채 종료 코드 2로 끝난다.
  - 결과는 임시 파일에 쓴 뒤 바꿔치기해서, 도중에 실패해도 직전 결과가 깨지지 않는다.
  - 학습에 쓰지 않은 충전소(데이터 품질 낮은 충전소 등)는 예측하지 않는다. 추천 쪽에서 기존 방식으로 대체해야 한다.
종료 코드: 0 성공 / 2 데이터가 오래됨 / 1 그 밖의 오류
"""
import argparse
import json
import os
import sys
from pathlib import Path

import numpy as np
import pandas as pd

import train_xgb as T

OUT_COLUMNS = ["station_id", "target_time", "horizon_min", "predicted_occupancy_rate", "p_available",
               "model_version", "predicted_at"]
FRESH_COLS = [f"occ_lag{k}" for k in range(6)]


def _kst_now():
    return pd.Timestamp.now(tz="Asia/Seoul").tz_localize(None)


def _stat_series(d, int_keys=True):
    """meta 에 문자열 키로 저장된 통계를 다시 시리즈로 (충전소 ID가 숫자면 숫자 키로)."""
    keys = list(d.keys())
    if int_keys:
        try:
            idx = [int(float(k)) for k in keys]
        except ValueError:
            idx = keys
    else:
        idx = keys
    return pd.Series([d[k] for k in keys], index=idx, dtype=float)


def load_model_bundle(model_dir, h):
    from xgboost import XGBClassifier, XGBRegressor

    model_dir = Path(model_dir)
    meta_path = model_dir / f"meta_h{h}.json"
    if not meta_path.exists():
        raise FileNotFoundError(f"{meta_path} 가 없습니다. 먼저 train_xgb.py --horizon-steps {h} 로 학습하세요.")
    meta = json.loads(meta_path.read_text())
    reg, clf = XGBRegressor(), XGBClassifier()
    reg.load_model(str(model_dir / f"reg_h{h}.json"))
    clf.load_model(str(model_dir / f"clf_h{h}.json"))
    stats = {"st_mean": _stat_series(meta["station_mean"]),
             "st_full": _stat_series(meta["station_full_rate"]),
             "slot_mean": _stat_series(meta["slot_mean"])}
    return meta, reg, clf, stats


def predict_horizon(df, h, bundle, hol, now, max_age_min=90):
    """한 예측 구간(h)의 예측표를 만든다. 데이터가 오래됐으면 (None, 메시지)."""
    meta, reg, clf, stats = bundle
    last_t = df["window_start"].max()
    age_min = (now - (last_t + pd.Timedelta(minutes=T.STEP_MIN))).total_seconds() / 60
    if age_min > max_age_min:
        return None, (f"가장 최근 구간({last_t}) 집계가 끝난 지 {age_min:.0f}분이 지났습니다 "
                      f"(허용 {max_age_min}분). 예측을 만들지 않습니다.")

    groups = T._norm_groups(meta["groups"])
    feat = meta["features"]
    trained = set(stats["st_mean"].index)
    d = df[df["station_id"].isin(trained)]
    if d.empty:
        return None, "학습한 충전소가 입력 데이터에 하나도 없습니다 (충전소 ID 확인)."

    latest = T.build_dataset(d, h, hol, groups)
    cur = T.attach_train_stats(latest[latest["window_start"] == last_t], stats)
    missing = [c for c in feat if c not in cur.columns]
    if missing:
        return None, ("모델이 쓰는 변수가 입력 데이터에 없습니다: " + ", ".join(missing[:8]) +
                      " … (학습 때와 같은 --access/--stations 를 주었는지, prep_5m.py 로 만든 표인지 확인)")
    cur = cur[cur[FRESH_COLS].notna().any(axis=1)]          # 최근 3시간 값이 하나도 없는 충전소는 건너뜀
    if cur.empty:
        return None, "최근 구간 값이 있는 충전소가 없습니다."

    X = cur[feat]
    out = pd.DataFrame({
        "station_id": cur["station_id"].to_numpy(),
        "target_time": cur["target_time"].to_numpy(),
        "horizon_min": h * T.STEP_MIN,
        "predicted_occupancy_rate": np.clip(reg.predict(X), 0, 1).round(4),
        "p_available": np.clip(clf.predict_proba(X)[:, 1], 0, 1).round(4),
        "model_version": meta["model_version"],
        "predicted_at": now.floor("s"),
    })[OUT_COLUMNS]
    info = (f"h={h}({h * T.STEP_MIN}분 후): {len(out)}곳, 대상 구간 {out['target_time'].iloc[0]}, "
            f"모델 {meta['model_version']}, 쾌적 기준 사용률 {meta.get('comfort_thr', 0):g} 미만")
    return out, info


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", required=True, help="최신 구간 표 (.pkl 또는 .csv, 학습 때와 같은 형식)")
    ap.add_argument("--models", required=True, help="train_xgb.py --out-dir 로 저장한 모델 폴더")
    ap.add_argument("--horizons", default="1,2", help="예측 구간 목록: 1=30분 후, 2=60분 후 (기본 1,2)")
    ap.add_argument("--out", default="prediction.csv")
    ap.add_argument("--access", default=None, help="station_access.csv (학습 때와 같게)")
    ap.add_argument("--stations", default=None, help="stations.csv (학습 때와 같게)")
    ap.add_argument("--holidays", default="", help="추가 공휴일 (예: 2026-12-25)")
    ap.add_argument("--max-age-min", type=float, default=90, help="가장 최근 구간 집계가 이보다 오래되면 예측하지 않음 (분)")
    ap.add_argument("--now", default=None, help="현재 시각 지정 (시험용, 기본은 한국 현재 시각)")
    a = ap.parse_args(argv)

    now = pd.Timestamp(a.now) if a.now else _kst_now()
    df = T.load_csv(a.data, a.access, a.stations)
    df = T.apply_quality(df, keep_low=True)                  # 충전소 제외는 학습한 충전소 목록으로 한다
    years = range(df["window_start"].dt.year.min(), df["window_start"].dt.year.max() + 2)
    hol = T.get_holidays(years, [x for x in a.holidays.split(",") if x])

    parts = []
    for h in [int(x) for x in a.horizons.split(",") if x.strip()]:
        try:
            bundle = load_model_bundle(a.models, h)
        except FileNotFoundError as e:
            print("[오류]", e)
            return 1
        out, msg = predict_horizon(df, h, bundle, hol, now, a.max_age_min)
        if out is None:
            print("[건너뜀]", msg)
            print("기존 결과 파일은 그대로 둡니다.")
            return 2 if "지났습니다" in msg else 1
        print(msg)
        parts.append(out)

    res = pd.concat(parts, ignore_index=True)
    out_path = Path(a.out)
    tmp = out_path.with_suffix(out_path.suffix + ".tmp")
    res.to_csv(tmp, index=False)
    os.replace(tmp, out_path)                                # 한 번에 바꿔치기: 도중 실패해도 직전 결과 유지
    print(f"저장: {out_path} ({len(res)}행)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
