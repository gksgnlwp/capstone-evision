"""
충전소 혼잡도·이용 가능 여부 예측 — XGBoost 1차 모델
3조 EVISION · AI 예측 담당

하는 일
  1) 30분 집계 데이터(OCCUPANCY_30M)를 읽는다.
  2) 충전소별로 "지금까지의 점유율"로 N구간(기본 1구간 = 30분) 뒤를 예측하는 데이터셋을 만든다.
  3) 시간순으로 학습/검증/평가를 나눈다 (무작위 분할 금지).
  4) 기준 모델(요일·시간대 평균)과 XGBoost를 같은 평가 구간에서 비교한다.
  5) 모델 2개(혼잡도 회귀, 이용 가능 분류)와 임계값을 저장한다.
  6) 예측 결과를 PREDICTION 테이블 형태의 표로 만든다.

실행
  python train_xgb.py --demo                      # 가짜 데이터로 전체 흐름 확인 (성능 숫자는 의미 없음)
  python train_xgb.py --csv occupancy30m.csv      # 실제 집계 데이터(CSV)
  python train_xgb.py --db "postgresql://user:pw@host:5432/dbname"

필요 패키지
  pip install xgboost pandas numpy scikit-learn holidays
  (--db 를 쓸 때만) pip install sqlalchemy psycopg2-binary

입력 컬럼 (CSV/DB 공통)
  station_id        충전소 ID
  window_start      30분 구간 시작 시각 (매시 00분·30분)
  occupancy_rate    혼잡도 0~1  (유효 관측 없으면 NULL)
  available         이용 가능 여부 True/False (미확인이면 NULL)
  station_type      선택. REST_AREA / IC
  ※ 컬럼명은 팀 DB 정의서와 다르면 load_* 함수에서 이름만 바꿔 주세요.
"""
import argparse
import json
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.metrics import mean_absolute_error, precision_recall_fscore_support
from xgboost import XGBClassifier, XGBRegressor

STEP_MIN = 30              # 집계 단위(분)
STEPS_PER_DAY = 48         # 하루 30분 구간 수
STEPS_PER_WEEK = 336
MODEL_VERSION = "xgb-v0.1"


# --------------------------------------------------------------------------- 데이터 읽기
def load_csv(path):
    df = pd.read_csv(path, parse_dates=["window_start"])
    return df


def load_db(url):
    from sqlalchemy import create_engine  # 지연 import: DB를 쓸 때만 필요

    sql = """
        SELECT o.station_id, o.window_start, o.occupancy_rate, o.available
        FROM occupancy_30m o
    """  # 테이블·컬럼명은 6.3 테이블 정의서 기준으로 확인
    return pd.read_sql(sql, create_engine(url), parse_dates=["window_start"])


def make_demo(n_station=40, weeks=6, seed=7):
    """모델 코드가 끝까지 도는지 확인하기 위한 가짜 데이터. 실제 패턴이 아니다."""
    rng = np.random.default_rng(seed)
    times = pd.date_range("2026-08-03", periods=weeks * STEPS_PER_WEEK, freq="30min")
    rows = []
    for sid in range(n_station):
        base = rng.uniform(0.2, 0.5)
        peak = rng.uniform(0.3, 0.5)
        stype = "REST_AREA" if sid % 3 else "IC"
        hour = times.hour + times.minute / 60
        daily = np.exp(-((hour - 12.5) ** 2) / 8) + 0.7 * np.exp(-((hour - 18) ** 2) / 6)
        weekend = (times.dayofweek >= 5) * 0.15
        occ = np.clip(base + peak * daily + weekend + rng.normal(0, 0.08, len(times)), 0, 1)
        avail = (occ < rng.uniform(0.85, 0.97)) | (rng.random(len(times)) < 0.03)
        keep = rng.random(len(times)) > 0.03          # 3% 결측 구간
        rows.append(
            pd.DataFrame(
                {"station_id": sid, "window_start": times[keep],
                 "occupancy_rate": occ[keep], "available": avail[keep],
                 "station_type": stype}
            )
        )
    return pd.concat(rows, ignore_index=True)


# --------------------------------------------------------------------------- 공휴일
def get_holidays(years, extra=()):
    days = set(pd.to_datetime(list(extra)).normalize()) if extra else set()
    try:
        import holidays  # 있으면 한국 공휴일 자동 사용
        kr = holidays.KR(years=list(years))
        days |= {pd.Timestamp(d) for d in kr.keys()}
    except ImportError:
        print("[안내] holidays 패키지가 없어 공휴일 없이 진행합니다. (pip install holidays)")
    return days


# --------------------------------------------------------------------------- 특징 만들기
def build_dataset(df, horizon_steps, holiday_days):
    """
    기준 구간 t(마지막으로 집계가 끝난 구간)의 정보만으로
    t + horizon_steps 구간(1이면 30분 뒤)의 혼잡도·이용 가능 여부를 맞춘다.
    누락된 구간도 격자에 NaN으로 남겨, shift가 항상 '실제 시간' 기준이 되게 한다.
    """
    df = df.copy()
    df["available"] = df["available"].astype("float")       # True/False/NULL -> 1/0/NaN
    df = df.sort_values(["station_id", "window_start"])

    all_times = pd.date_range(df["window_start"].min(), df["window_start"].max(), freq=f"{STEP_MIN}min")
    stations = df["station_id"].unique()
    full = pd.MultiIndex.from_product([stations, all_times], names=["station_id", "window_start"])
    g = df.set_index(["station_id", "window_start"]).reindex(full)

    grp = g.groupby(level="station_id")
    X = pd.DataFrame(index=g.index)

    # (1) 최근 점유율: t, t-1, ... t-5 구간
    for k in range(6):
        X[f"occ_lag{k}"] = grp["occupancy_rate"].shift(k)
    X["avail_lag0"] = grp["available"].shift(0)
    X["occ_mean6"] = X[[f"occ_lag{k}" for k in range(6)]].mean(axis=1)
    X["occ_trend"] = X["occ_lag0"] - X["occ_lag2"]

    # (2) 같은 시간대의 과거 값 (목표 구간 기준 어제·지난주) — 기준 시점에 이미 알고 있는 값
    X["occ_yesterday"] = grp["occupancy_rate"].shift(STEPS_PER_DAY - horizon_steps)
    X["occ_lastweek"] = grp["occupancy_rate"].shift(STEPS_PER_WEEK - horizon_steps)

    # (3) 목표 구간의 시간 정보 (미래 시각이지만 달력이라 미리 알 수 있다)
    t_target = g.index.get_level_values("window_start") + pd.Timedelta(minutes=STEP_MIN * horizon_steps)
    X["target_slot"] = t_target.hour * 2 + t_target.minute // 30
    X["target_dow"] = t_target.dayofweek
    X["target_weekend"] = (t_target.dayofweek >= 5).astype(int)
    tdate = t_target.normalize()
    X["target_holiday"] = tdate.isin(holiday_days).astype(int)
    near = pd.Series(False, index=range(len(tdate)))
    for d in (-1, 1):
        near |= pd.Series((tdate + pd.Timedelta(days=d)).isin(holiday_days))
    X["target_near_holiday"] = near.values.astype(int)

    if "station_type" in g.columns:
        X["is_ic"] = (g["station_type"] == "IC").astype(int)

    # (4) 레이블: 기준 구간으로부터 horizon_steps 뒤 구간
    y_occ = grp["occupancy_rate"].shift(-horizon_steps)
    y_av = grp["available"].shift(-horizon_steps)

    out = X.copy()
    out["y_occ"] = y_occ
    out["y_av"] = y_av
    out["target_time"] = t_target
    return out.reset_index()


def time_split(data, horizon_steps, train=0.70, valid=0.15):
    """시간순 분할. 학습 끝과 검증 시작 사이를 horizon만큼 비워 레이블이 겹치지 않게 한다."""
    times = np.sort(data["window_start"].unique())
    c1 = times[int(len(times) * train)]
    c2 = times[int(len(times) * (train + valid))]
    gap = pd.Timedelta(minutes=STEP_MIN * horizon_steps)
    tr = data[data["window_start"] < c1 - gap]
    va = data[(data["window_start"] >= c1) & (data["window_start"] < c2 - gap)]
    te = data[data["window_start"] >= c2]
    return tr, va, te


# --------------------------------------------------------------------------- 기준 모델
class Baseline:
    """요일·시간대 평균. XGBoost가 반드시 이겨야 하는 기준선."""

    def fit(self, tr):
        k = ["station_id", "target_dow", "target_slot"]
        self.by_station = tr.groupby(k)[["y_occ", "y_av"]].mean()
        self.by_slot = tr.groupby(["target_dow", "target_slot"])[["y_occ", "y_av"]].mean()
        self.by_st = tr.groupby("station_id")[["y_occ", "y_av"]].mean()
        self.glob = tr[["y_occ", "y_av"]].mean()
        return self

    def predict(self, df):
        k = ["station_id", "target_dow", "target_slot"]
        m = df[k].merge(self.by_station.reset_index(), on=k, how="left")
        m = m.merge(self.by_slot.reset_index(), on=["target_dow", "target_slot"], how="left", suffixes=("", "_s"))
        m = m.merge(self.by_st.reset_index(), on="station_id", how="left", suffixes=("", "_st"))
        occ = m["y_occ"].fillna(m["y_occ_s"]).fillna(m["y_occ_st"]).fillna(self.glob["y_occ"])
        av = m["y_av"].fillna(m["y_av_s"]).fillna(m["y_av_st"]).fillna(self.glob["y_av"])
        return occ.to_numpy(), av.to_numpy()


# --------------------------------------------------------------------------- 평가
def pick_threshold(y, prob, target_precision):
    """
    임계값 선택 규칙 (검증 데이터 기준)
      - '이용가능' 정밀도가 목표(기본 0.90) 이상인 임계값만 후보로 둔다.
      - 그중 '만차'를 가장 잘 찾아내는(만차 F1이 최대인) 값을 고른다.
    이용가능 재현율만 키우면 거의 전부를 '이용 가능'이라고 답하는 쓸모없는 모델이 되기 쉽다.
    (만차 구간은 전체의 일부라서 이용가능 정밀도는 원래 높게 나온다)
    """
    cands = []
    for thr in np.linspace(0.05, 0.95, 91):
        p, r, f, _ = precision_recall_fscore_support(y, prob >= thr, labels=[1, 0], zero_division=0)
        cands.append((float(thr), float(p[0]), float(f[1])))
    ok = [c for c in cands if c[1] >= target_precision]
    pool = ok if ok else cands
    best = max(pool, key=lambda c: c[2])
    return best[0], bool(ok)


def cls_report(y, prob, thr):
    p, r, f, s = precision_recall_fscore_support(y, prob >= thr, labels=[1, 0], zero_division=0)
    return {
        "이용가능 정밀도": round(float(p[0]), 3), "이용가능 재현율": round(float(r[0]), 3), "이용가능 F1": round(float(f[0]), 3),
        "만차 정밀도": round(float(p[1]), 3), "만차 재현율": round(float(r[1]), 3), "만차 F1": round(float(f[1]), 3),
        "만차 비율": round(float(np.mean(y == 0)), 3),
    }


def main():
    ap = argparse.ArgumentParser()
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--demo", action="store_true")
    src.add_argument("--csv")
    src.add_argument("--db")
    ap.add_argument("--horizon-steps", type=int, default=1, help="1=30분 후, 2=60분 후 (15·45분은 5분 단위 데이터 필요)")
    ap.add_argument("--target-precision", type=float, default=0.90)
    ap.add_argument("--holidays", default="", help="추가 공휴일/대체공휴일 (예: 2026-10-05,2026-10-09)")
    ap.add_argument("--out-dir", default="models")
    a = ap.parse_args()
    h = a.horizon_steps

    df = make_demo() if a.demo else load_csv(a.csv) if a.csv else load_db(a.db)
    years = range(df["window_start"].dt.year.min(), df["window_start"].dt.year.max() + 1)
    hol = get_holidays(years, [x for x in a.holidays.split(",") if x])
    span_days = (df["window_start"].max() - df["window_start"].min()).days + 1
    print(f"데이터: 충전소 {df['station_id'].nunique()}곳, {span_days}일, {len(df):,}행")
    if span_days < 28:
        print("[주의] 28일 미만입니다. 요일·지난주 패턴을 충분히 못 배우므로 결과는 참고용입니다.")

    data = build_dataset(df, h, hol)
    data = data.dropna(subset=["y_occ", "y_av"])          # 레이블이 없는 행 제외
    tr, va, te = time_split(data, h)
    print(f"학습 {len(tr):,} / 검증 {len(va):,} / 평가 {len(te):,} 행 (시간순 분할)")

    # 학습 구간만으로 만든 충전소별 평균 (평가 구간 정보 누수 방지)
    st_mean = tr.groupby("station_id")["y_occ"].mean()
    for d in (tr, va, te):
        d["station_mean"] = d["station_id"].map(st_mean)

    feat = [c for c in tr.columns if c not in
            ("station_id", "window_start", "y_occ", "y_av", "target_time")]

    # ---- 기준 모델
    base = Baseline().fit(tr)
    b_occ, b_av = base.predict(te)

    # ---- XGBoost: 혼잡도(회귀)
    reg = XGBRegressor(n_estimators=600, learning_rate=0.05, max_depth=6, subsample=0.8,
                       colsample_bytree=0.8, tree_method="hist", early_stopping_rounds=40,
                       eval_metric="mae", random_state=42)
    reg.fit(tr[feat], tr["y_occ"], eval_set=[(va[feat], va["y_occ"])], verbose=False)

    # ---- XGBoost: 이용 가능 여부(분류)
    clf = XGBClassifier(n_estimators=600, learning_rate=0.05, max_depth=6, subsample=0.8,
                        colsample_bytree=0.8, tree_method="hist", early_stopping_rounds=40,
                        eval_metric="logloss", random_state=42)
    clf.fit(tr[feat], tr["y_av"].astype(int), eval_set=[(va[feat], va["y_av"].astype(int))], verbose=False)

    thr, ok = pick_threshold(va["y_av"].astype(int).to_numpy(), clf.predict_proba(va[feat])[:, 1], a.target_precision)
    print(f"\n임계값(검증 기준): {thr:.2f}" + ("" if ok else "  [이용가능 정밀도 목표 미달 → 만차 F1 최대 지점 사용]"))

    # ---- 평가 구간에서 비교
    y_av = te["y_av"].astype(int).to_numpy()
    print("\n== 혼잡도 오차(MAE, 낮을수록 좋음) ==")
    print(f"기준 모델 {mean_absolute_error(te['y_occ'], b_occ):.4f}  |  XGBoost {mean_absolute_error(te['y_occ'], reg.predict(te[feat])):.4f}")
    print("\n== 이용 가능 여부 분류 ==")
    print("기준 모델:", cls_report(y_av, b_av, 0.5))
    print("XGBoost  :", cls_report(y_av, clf.predict_proba(te[feat])[:, 1], thr))
    print(f"\n목표: 정밀도 {a.target_precision}, 재현율 0.80, F1 0.85 (초기 목표)")
    print("※ '이용가능' 정밀도는 대부분의 구간이 이용 가능이면 쉽게 높게 나옵니다. '만차 비율'과 '만차 재현율'도 함께 보세요.")

    imp = pd.Series(clf.feature_importances_, index=feat).sort_values(ascending=False).head(8)
    print("\n중요 변수(분류 모델 상위 8):\n", imp.round(3).to_string())

    # ---- 저장
    out = Path(a.out_dir)
    out.mkdir(exist_ok=True)
    reg.save_model(str(out / f"reg_h{h}.json"))
    clf.save_model(str(out / f"clf_h{h}.json"))
    meta = {"model_version": MODEL_VERSION, "horizon_steps": h, "horizon_min": h * STEP_MIN,
            "features": feat, "threshold": thr, "station_mean": {str(k): float(v) for k, v in st_mean.items()},
            "train_end": str(tr["window_start"].max())}
    (out / f"meta_h{h}.json").write_text(json.dumps(meta, ensure_ascii=False, indent=1))
    print(f"\n저장: {out}/ (reg_h{h}.json, clf_h{h}.json, meta_h{h}.json)")

    # ---- 예측 결과 표 만들기 (PREDICTION 테이블 형태): 가장 최근 구간 기준
    latest = build_dataset(df, h, hol)
    last_t = latest["window_start"].max()
    cur = latest[latest["window_start"] == last_t].copy()
    cur["station_mean"] = cur["station_id"].map(st_mean)
    pred = pd.DataFrame({
        "station_id": cur["station_id"].values,
        "target_time": cur["target_time"].values,
        "horizon_min": h * STEP_MIN,
        "congestion_rate": np.clip(reg.predict(cur[feat]), 0, 1).round(4),
        "available_prob": clf.predict_proba(cur[feat])[:, 1].round(4),
        "model_version": MODEL_VERSION,
        "predicted_at": pd.Timestamp.now().floor("s"),
    })
    pred.to_csv(out / f"prediction_h{h}.csv", index=False)
    print("\n예측 결과 예시 (PREDICTION 테이블 형태):")
    print(pred.head().to_string(index=False))


if __name__ == "__main__":
    main()
