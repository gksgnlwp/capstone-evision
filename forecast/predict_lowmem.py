"""
predict_lowmem.py — prep_5m.py + predict_batch.py 를 메모리를 적게 쓰도록 나눠서 돌린다 (서버 메모리 1GB 미만용).
계산은 두 파일의 함수를 그대로 쓰므로 결과는 한 번에 돌린 것과 같다 (test_db_pipeline.py 에서 비교).

  python predict_lowmem.py prep    --export-dir work/export --out work/data30.pkl
  python predict_lowmem.py predict --data work/data30.pkl --export-dir work/export --models models_c50 --out work/prediction.csv
  (run_forecast.sh 가 두 단계를 서로 다른 프로세스로 돌려, 한 단계가 쓴 메모리가 다음 단계에 남지 않게 한다)

나누는 방법
  prep    : 30분 표는 충전소별로 독립 계산이라 충전소 묶음(--chunk-stations, 기본 150곳)씩 만든다.
  predict : 충전소끼리 서로 참조하는 변수(같은 휴게소 다른 충전소, 같은 노선 평균)는 모두 같은 노선 안에서만 계산되므로
            노선별로 나눠 변수를 만들고 예측한다.
입력: export_recent.py 가 만든 폴더 (.pkl 또는 .csv.gz 5분 표·상태 이력, chargers.csv, stations.csv, station_access.csv)
종료 코드는 predict_batch.py 와 같다: 0 성공 / 2 데이터가 오래됨 / 1 그 밖의 오류
"""
import argparse
import gc
import os
import sys
import tempfile
from pathlib import Path

import numpy as np
import pandas as pd

import prep_5m

# predict_batch·train_xgb 는 xgboost·scikit-learn 을 불러와 메모리를 더 쓰므로 predict 단계에서만 불러온다
P = T = None


def _load_predict_modules():
    global P, T
    if P is None:
        import predict_batch
        import train_xgb
        P, T = predict_batch, train_xgb

OCC_DTYPES = {"station_id": "int32", "valid": "int8", "fast_charger_count": "int16", "available": "float32",
              "charging": "float32", "unavailable": "float32", "unknown": "float32"}


def _read(export_dir, name, csv_kw):
    """export 폴더에서 .pkl 이 있으면 그것을, 없으면 .csv.gz 를 나눠 읽는다."""
    d = Path(export_dir)
    if (d / f"{name}.pkl").exists():
        return pd.read_pickle(d / f"{name}.pkl")
    return pd.concat(pd.read_csv(d / f"{name}.csv.gz", chunksize=500_000, **csv_kw), ignore_index=True)


def build_data30(export_dir, chunk_stations=150):
    """prep_5m.py main 과 같은 30분 표 (+ 5분 변수, 충전 경과시간 변수, 최대 출력)를 충전소 묶음별로 만든다."""
    occ = _read(export_dir, "occupancy_5m",
                dict(usecols=list(OCC_DTYPES) + ["ts"], dtype=OCC_DTYPES, parse_dates=["ts"]))
    raw = _read(export_dir, "status_history_raw",
                dict(usecols=["charger_id", "status_code", "status_updated_at", "collected_at"],
                     parse_dates=["status_updated_at", "collected_at"]))
    chg_all = pd.read_csv(Path(export_dir) / "chargers.csv")
    kw = chg_all.groupby("station_id")["output_kw"].max()

    stations = np.sort(occ["station_id"].unique())
    windows = None
    parts = []
    for i in range(0, len(stations), chunk_stations):
        ids = stations[i:i + chunk_stations]
        w = prep_5m.windows_from_5m(occ[occ["station_id"].isin(ids)])
        if windows is None:
            windows = np.sort(w["window_start"].unique())
        chg = chg_all[chg_all["station_id"].isin(ids)]
        feats = prep_5m.charging_duration_features(raw[raw["charger_id"].isin(chg["charger_id"])], chg, windows)
        w = w.merge(feats, on=["station_id", "window_start"], how="left")
        w["max_output_kw"] = w["station_id"].map(kw)
        parts.append(w)
        gc.collect()
    del occ, raw
    gc.collect()
    return pd.concat(parts, ignore_index=True)


def route_groups(data30, access_path, stations_path):
    """충전소 → 노선 묶음. predict_batch 와 같은 load_csv 규칙으로 충전소당 한 행만 만들어 계산한다."""
    _load_predict_modules()
    one = data30.drop_duplicates("station_id")[["station_id", "window_start", "available"]]
    with tempfile.TemporaryDirectory() as tmp:
        pkl = os.path.join(tmp, "one.pkl")
        one.to_pickle(pkl)
        m = T.load_csv(pkl, access_path, stations_path)
    key = m["route_key"] if "route_key" in m.columns else pd.Series(np.nan, index=m.index)
    return pd.Series(key.fillna("_").to_numpy(), index=m["station_id"].to_numpy())


def predict_by_route(data30, access_path, stations_path, horizons, models, holidays, now, max_age_min):
    """노선별로 load_csv → apply_quality → predict_horizon. (결과, None) 또는 (None, (메시지, 종료 코드))"""
    _load_predict_modules()
    last_t = data30["window_start"].max()
    age_min = (now - (last_t + pd.Timedelta(minutes=T.STEP_MIN))).total_seconds() / 60
    if age_min > max_age_min:
        return None, (f"가장 최근 구간({last_t}) 집계가 끝난 지 {age_min:.0f}분이 지났습니다 "
                      f"(허용 {max_age_min:g}분). 예측을 만들지 않습니다.", 2)
    try:
        bundles = {h: P.load_model_bundle(models, h) for h in horizons}
    except FileNotFoundError as e:
        return None, (str(e), 1)

    years = range(data30["window_start"].dt.year.min(), data30["window_start"].dt.year.max() + 2)
    hol = T.get_holidays(years, holidays)
    route = route_groups(data30, access_path, stations_path)
    outs = {h: [] for h in horizons}
    with tempfile.TemporaryDirectory() as tmp:
        for key in sorted(route.unique()):
            ids = route.index[route == key]
            pkl = os.path.join(tmp, "part.pkl")
            data30[data30["station_id"].isin(ids)].to_pickle(pkl)
            df = T.apply_quality(T.load_csv(pkl, access_path, stations_path), keep_low=True)
            for h in horizons:
                out, msg = P.predict_horizon(df, h, bundles[h], hol, now, max_age_min)
                if out is not None:
                    outs[h].append(out)
                elif "변수가 입력 데이터에 없습니다" in msg:          # 형식 문제는 노선과 상관없이 오류
                    return None, (msg, 1)
                # 학습하지 않은 충전소뿐인 노선 등은 건너뛴다 (추천은 과거 통계로 대체)
            del df
            gc.collect()

    parts = []
    for h in horizons:
        if not outs[h]:
            return None, ("예측할 수 있는 충전소가 없습니다 (학습한 충전소·최근 값 확인).", 1)
        res = pd.concat(outs[h], ignore_index=True).sort_values("station_id", kind="stable")
        meta = bundles[h][0]
        print(f"h={h}({h * T.STEP_MIN}분 후): {len(res)}곳, 대상 구간 {res['target_time'].iloc[0]}, "
              f"모델 {meta['model_version']}, 쾌적 기준 사용률 {meta.get('comfort_thr', 0):g} 미만")
        parts.append(res)
    return pd.concat(parts, ignore_index=True)[P.OUT_COLUMNS], None


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("stage", choices=["prep", "predict"])
    ap.add_argument("--export-dir", required=True, help="export_recent.py --out-dir 폴더")
    ap.add_argument("--data", help="predict: prep 단계가 만든 30분 표 (.pkl)")
    ap.add_argument("--out", required=True, help="prep: 30분 표 .pkl / predict: 예측 CSV")
    ap.add_argument("--models", help="predict: 모델 폴더")
    ap.add_argument("--horizons", default="1,2")
    ap.add_argument("--holidays", default="")
    ap.add_argument("--max-age-min", type=float, default=90)
    ap.add_argument("--now", default=None)
    ap.add_argument("--chunk-stations", type=int, default=150, help="prep: 한 번에 처리할 충전소 수")
    a = ap.parse_args(argv)

    if a.stage == "prep":
        data30 = build_data30(a.export_dir, a.chunk_stations)
        data30.to_pickle(a.out)
        print(f"30분 표: {len(data30):,}행, 충전소 {data30['station_id'].nunique()}곳 → {a.out}")
        return 0

    if not a.data or not a.models:
        ap.error("predict 에는 --data 와 --models 가 필요합니다.")
    _load_predict_modules()
    now = pd.Timestamp(a.now) if a.now else P._kst_now()
    d = Path(a.export_dir)
    res, err = predict_by_route(pd.read_pickle(a.data), str(d / "station_access.csv"), str(d / "stations.csv"),
                                [int(x) for x in a.horizons.split(",") if x.strip()], a.models,
                                [x for x in a.holidays.split(",") if x], now, a.max_age_min)
    if err:
        print("[건너뜀]" if err[1] == 2 else "[오류]", err[0])
        print("기존 결과 파일은 그대로 둡니다.")
        return err[1]
    out_path = Path(a.out)
    tmp = out_path.with_suffix(out_path.suffix + ".tmp")
    res.to_csv(tmp, index=False)
    os.replace(tmp, out_path)
    print(f"저장: {out_path} ({len(res)}행)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
