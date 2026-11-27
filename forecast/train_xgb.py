"""
충전소 혼잡도·이용 가능 여부 예측 — XGBoost 모델 (v0.2: 변수 그룹 실험 추가)
3조 EVISION · AI 예측 담당

하는 일
  1) 30분 집계 데이터(OCCUPANCY_30M)를 읽는다.
  2) 충전소별로 "지금까지의 값"으로 N구간(기본 1구간 = 30분) 뒤를 예측하는 데이터셋을 만든다.
  3) 시간순으로 학습/검증/평가를 나눈다 (무작위 분할 금지).
  4) 기준 모델(요일·시간대 평균)과 XGBoost를 같은 평가 구간에서 비교한다.
  5) 모델 2개(혼잡도 회귀, 이용 가능 분류)와 임계값을 저장한다.
  6) 예측 결과를 PREDICTION 테이블 형태의 표로 만든다.

변수 그룹 (v0.2) — 기본(v0.1)에 아래 그룹을 더해 효과를 비교한다
  full    만차 이력: 직전 구간의 만차 시간, 최근 6구간 만차 횟수, 만차가 이어진 구간 수, 어제·지난주 만차 시간
  fac     주변 상황: 같은 휴게소(방향 포함)의 다른 충전소 현재 혼잡도, 같은 노선 평균 혼잡도
  static  충전소 정보: 급속충전기 수, 최대 출력, IC까지 거리
  hist    학습 구간 통계: 충전소별 만차 비율, 요일·시간대별 평균 혼잡도

실행
  python train_xgb.py --demo                                    # 가짜 데이터로 전체 흐름 확인
  python train_xgb.py --csv occupancy_30m.csv.gz --access station_access.csv --stations stations.csv
  python train_xgb.py --csv ... --groups all                    # 모든 변수 그룹 사용
  python train_xgb.py --csv ... --groups all --balance          # + 만차 가중치(만차를 더 중요하게)
  python train_xgb.py --csv ... --compare                       # 변수 그룹별 비교표 출력 (여러 번 학습)
  python train_xgb.py --csv ... --groups all --tune             # 하이퍼파라미터 탐색(검증 구간 기준) 후 기본 설정과 비교
  python train_xgb.py --csv ... --exclude 학습데이터_제외_충전소_1104.csv   # 제외 충전소를 빼고 학습
  python train_xgb.py --csv ... --groups all --thr-mode f1       # 임계값을 만차 F1 기준으로 선택
  python train_xgb.py --csv ... --groups all --keep-fault --keep-low   # 11/09 데이터를 정리 없이 사용 (정리 전 비교용)
  python train_xgb.py --csv ... --groups all --target-full-min 5     # 만차 = 대상 구간 안에 만차 시간이 5분 이상 (기본 0 = 구간 마지막 관측)
  python train_xgb.py --csv ... --groups all --gpu               # NVIDIA GPU 사용 (xgboost 2.0 이상)
  python train_xgb.py --db "postgresql://user:pw@host:5432/dbname"

필요 패키지
  pip install xgboost pandas numpy scikit-learn holidays
  (--db 를 쓸 때만) pip install sqlalchemy psycopg2-binary

입력 컬럼 (CSV/DB 공통)
  station_id        충전소 ID
  window_start      30분 구간 시작 시각 (매시 00분·30분)
  occupancy_rate    혼잡도 0~1  (유효 관측 없으면 NULL)
  available         이용 가능 여부 True/False 또는 t/f (미확인이면 NULL)
  station_type      선택. REST_AREA / IC   (--access 를 주면 자동으로 붙음)
  full_minutes, fast_charger_count   선택. 있으면 full/static 그룹에서 사용
  ※ 컬럼명은 팀 DB 정의서와 다르면 load_* 함수에서 이름만 바꿔 주세요.
"""
import argparse
import json
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.metrics import average_precision_score, mean_absolute_error, precision_recall_fscore_support
from xgboost import XGBClassifier, XGBRegressor

STEP_MIN = 30              # 집계 단위(분)
STEPS_PER_DAY = 48         # 하루 30분 구간 수
STEPS_PER_WEEK = 336
MODEL_VERSION = "xgb-v0.7"

ALL_GROUPS = ("full", "fac", "static", "hist")
GROUP_COLS = {
    "full": ["full_lag0", "full_lag1", "full_sum6", "unavail_cnt6", "full_streak",
             "full_yesterday", "full_lastweek"],
    "fac": ["fac_others_occ0", "fac_others_unavail0", "fac_others_n", "route_occ0"],
    "static": ["charger_cnt", "max_output_kw", "detour_km"],
    "hist": ["station_full_rate", "slot_mean"],
}
EXCLUDE = ("station_id", "window_start", "y_occ", "y_av", "y_fm", "target_time")


# --------------------------------------------------------------------------- 데이터 읽기
def _to_bool_float(col):
    """t/f, true/false, 1/0, True/False, 빈칸 -> 1.0/0.0/NaN"""
    if pd.api.types.is_bool_dtype(col) or pd.api.types.is_numeric_dtype(col):
        return col.astype("float")
    m = {"t": 1.0, "true": 1.0, "1": 1.0, "1.0": 1.0, "f": 0.0, "false": 0.0, "0": 0.0, "0.0": 0.0}
    return col.astype(str).str.strip().str.lower().map(m)       # 그 외(빈칸, nan)는 NaN


def load_csv(path, access_path=None, stations_path=None):
    """집계 CSV(.csv / .csv.gz)를 읽는다.
    access_path(station_access.csv)를 주면 station_type, 휴게소/노선 키, IC까지 거리를 붙인다.
    stations_path(stations.csv)를 주면 급속충전기 최대 출력을 붙인다."""
    if str(path).endswith(".pkl"):                           # prep_5m.py 가 만든 파일 (빠르게 읽힘)
        df = pd.read_pickle(path)
    else:
        df = pd.read_csv(path, parse_dates=["window_start"])
    df["available"] = _to_bool_float(df["available"])
    if access_path:
        acc = pd.read_csv(access_path)
        # 한 충전소가 휴게소와 IC에 모두 연결되면 휴게소로 본다
        typ = acc.groupby("station_id")["access_type"].agg(lambda x: "REST_AREA" if (x == "REST_AREA").any() else "IC")
        df["station_type"] = df["station_id"].map(typ)
        # 충전소 한 곳당 한 행: 휴게소 우선, 그다음 IC까지 거리가 가장 가까운 행
        acc = acc.copy()
        acc["_r"] = (acc["access_type"] != "REST_AREA").astype(int)
        sort_cols = ["station_id", "_r"] + (["detour_km"] if "detour_km" in acc.columns else [])
        best = acc.sort_values(sort_cols).drop_duplicates("station_id").set_index("station_id")
        if "facility_name" in best.columns:
            route = best["route_no"].astype(str) if "route_no" in best.columns else ""
            direc = best["direction"].fillna("").astype(str) if "direction" in best.columns else ""
            key = route + "|" + best["facility_name"].astype(str) + "|" + direc
            df["fac_key"] = df["station_id"].map(key)
        if "route_no" in best.columns:
            df["route_key"] = df["station_id"].map(best["route_no"].astype(str))
        if "detour_km" in best.columns:
            df["detour_km"] = df["station_id"].map(best["detour_km"])
    if stations_path:
        st = pd.read_csv(stations_path).drop_duplicates("station_id").set_index("station_id")
        if "max_output_kw" in st.columns:
            df["max_output_kw"] = df["station_id"].map(st["max_output_kw"])
        if "data_quality" in st.columns:                       # 11/09 데이터부터: 충전소 단위 데이터 품질 (OK / LOW)
            df["data_quality"] = df["station_id"].map(st["data_quality"])
    return df


def apply_quality(df, keep_fault=False, keep_low=False):
    """
    11/09 데이터의 오류 구분을 적용한다. (해당 컬럼이 없는 예전 데이터에서는 아무것도 하지 않는다)
      window_quality: OK 가 아닌 구간(FAULT=충전기 전부 고장이라 만차처럼 보인 구간, UNKNOWN=상태 불명)은
                      혼잡도·이용 가능·만차 시간을 '알 수 없음(NaN)'으로 바꾼다.
                      행을 지우지 않는 이유: 어제·지난주 값을 찾는 시간 계산이 어긋나지 않게 하기 위해서다.
      data_quality  : LOW 인 충전소(대부분의 기간 고장·상태 불명)는 통째로 뺀다.
    keep_fault / keep_low 를 켜면 해당 정리를 하지 않는다 (정리 전 성적을 같은 데이터로 비교할 때 사용).
    """
    df = df.copy()
    if "window_quality" in df.columns and not keep_fault:
        bad = df["window_quality"].astype(str).str.strip().str.upper() != "OK"
        for c in ("occupancy_rate", "available", "full_minutes"):
            if c in df.columns:
                df[c] = df[c].mask(bad) if c != "available" else _to_bool_float(df[c]).mask(bad)
        print(f"구간 품질: OK가 아닌 {int(bad.sum()):,}행({bad.mean():.1%})을 알 수 없음 처리 (FAULT·UNKNOWN)")
    if "data_quality" not in df.columns and "window_quality" in df.columns:
        # 11/13 5분 데이터에는 충전소별 data_quality 가 없다 -> 같은 규칙으로 계산 (고장·상태불명 구간이 절반 이상이면 LOW)
        bad_share = (df["window_quality"].astype(str).str.strip().str.upper() != "OK").groupby(df["station_id"]).mean()
        low_map = pd.Series(np.where(bad_share >= 0.5, "LOW", "OK"), index=bad_share.index)
        df["data_quality"] = df["station_id"].map(low_map)
    if "data_quality" in df.columns and not keep_low:
        low = df["data_quality"].astype(str).str.strip().str.upper() == "LOW"
        n_low = df.loc[low, "station_id"].nunique()
        df = df[~low]
        print(f"충전소 품질: data_quality=LOW {n_low}곳 제외 -> {df['station_id'].nunique()}곳")
    return df


def clean_labels(df, mode="none"):
    """
    [진단용] 의심스러운 '이용 불가' 라벨을 '알 수 없음(NaN)'으로 바꿔 학습·평가에서 뺀다.
      none  : 그대로 (기본)
      noocc : 이용 불가인데 점유 값이 없는 구간 -> 알 수 없음.
              (정답으로는 이미 제외되고 있지만, '최근 만차 횟수' 같은 입력 변수에는 섞여 있었다)
      strict: noocc + 이용 불가인데 점유율이 100% 미만인 구간 -> 알 수 없음.
              [주의] 점유율은 구간 평균이고 available 은 구간 마지막 관측이라, 점유율이 100% 미만이어도 오염이라고 볼 수 없다.
              11/09 데이터부터는 window_quality 를 쓰므로 이 옵션은 예전 데이터용 참고 기능이다.
    라벨 정의가 달라지므로 기존 점수와 직접 비교할 수 없다. 오염이 점수에 영향을 주는지 보는 용도다.
    """
    if mode == "none":
        return df
    df = df.copy()
    av = _to_bool_float(df["available"])
    occ = df["occupancy_rate"]
    bad_noocc = (av == 0) & occ.isna()
    bad = bad_noocc.copy()
    if mode == "strict":
        bad |= (av == 0) & (occ < 0.999)
    n_un = int((av == 0).sum())
    print(f"라벨 정리({mode}): 이용 불가 {n_un:,}행 중 점유 값 없음 {int(bad_noocc.sum()):,}행"
          + (f", 점유율 100% 미만 {int((bad & ~bad_noocc).sum()):,}행" if mode == "strict" else "")
          + f" -> 알 수 없음 처리 (남는 이용 불가 {n_un - int(bad.sum()):,}행)")
    df["available"] = av.mask(bad)
    return df


def load_station_ids(path):
    """제외·대상 충전소 목록 CSV에서 충전소 ID를 읽는다. 'station_id' 컬럼이 없으면 첫 번째 컬럼을 쓴다."""
    d = pd.read_csv(path, encoding="utf-8-sig")
    col = next((c for c in d.columns if c.strip().lower() == "station_id"), d.columns[0])
    return set(d[col].dropna().astype(int))


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
def _norm_groups(groups):
    """'all' 은 기존 4개 그룹(full,fac,static,hist). 5분 변수(f5_*)는 'five' 를 따로 붙여야 쓴다 (예: all,five)."""
    if groups in (None, "", ()):
        return ()
    if isinstance(groups, str):
        groups = tuple(x.strip() for x in groups.split(",") if x.strip())
    out = []
    for x in groups:
        out.extend(ALL_GROUPS if x == "all" else (x,))
    return tuple(dict.fromkeys(out))


def build_dataset(df, horizon_steps, holiday_days, groups=()):
    """
    기준 구간 t(마지막으로 집계가 끝난 구간)의 정보만으로
    t + horizon_steps 구간(1이면 30분 뒤)의 혼잡도·이용 가능 여부를 맞춘다.
    누락된 구간도 격자에 NaN으로 남겨, shift가 항상 '실제 시간' 기준이 되게 한다.
    groups: 추가할 변수 그룹 ('full', 'fac', 'static'). 'hist'는 학습 구간이 정해진 뒤 attach_train_stats 에서 붙는다.
    필요한 컬럼이 없는 그룹은 건너뛴다.
    """
    groups = _norm_groups(groups)
    df = df.copy()
    df["available"] = _to_bool_float(df["available"])       # True/False/t/f/NULL -> 1/0/NaN
    df = df.sort_values(["station_id", "window_start"])

    has_fm = "full_minutes" in df.columns
    use_full = "full" in groups and has_fm
    keep = ["station_id", "window_start", "occupancy_rate", "available"]
    if has_fm:
        keep += ["full_minutes"]
    f5cols = [c for c in df.columns if c.startswith("f5_")] if "five" in groups else []
    keep += f5cols

    all_times = pd.date_range(df["window_start"].min(), df["window_start"].max(), freq=f"{STEP_MIN}min")
    stations = df["station_id"].unique()
    full = pd.MultiIndex.from_product([stations, all_times], names=["station_id", "window_start"])
    g = df[keep].set_index(["station_id", "window_start"]).reindex(full)
    sid = g.index.get_level_values("station_id")

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

    # 충전소별로 변하지 않는 정보 (충전소 ID -> 값)
    one = df.drop_duplicates("station_id").set_index("station_id")
    if "station_type" in one.columns:
        X["is_ic"] = (pd.Series(sid.map(one["station_type"]), index=g.index) == "IC").astype(int)

    # (4) [full] 만차 이력 — 모두 기준 시각 t 이전·이하 정보만 사용
    if use_full:
        fm = grp["full_minutes"]
        X["full_lag0"] = fm.shift(0)
        X["full_lag1"] = fm.shift(1)
        X["full_sum6"] = pd.concat([fm.shift(k) for k in range(6)], axis=1).sum(axis=1, min_count=1)
        av = grp["available"]
        X["unavail_cnt6"] = pd.concat([(av.shift(k) == 0).astype(float).where(av.shift(k).notna()) for k in range(6)],
                                      axis=1).sum(axis=1, min_count=1)
        is0 = (g["available"] == 0)
        gid = (~is0).groupby(sid).cumsum().values          # 만차가 아닌 구간이 나올 때마다 새 묶음
        X["full_streak"] = is0.astype(int).groupby([sid, gid]).cumsum().values   # t까지 연속 만차 구간 수
        X["full_yesterday"] = fm.shift(STEPS_PER_DAY - horizon_steps)
        X["full_lastweek"] = fm.shift(STEPS_PER_WEEK - horizon_steps)

    # (4b) [five] 5분 원본에서 뽑은 변수 — prep_5m.py 가 '구간 t의 마지막 시점' 기준으로 만든 값이라 그대로 쓴다
    for c in f5cols:
        X[c] = g[c]

    # (5) [fac] 같은 휴게소(방향 포함)·같은 노선의 현재 상황 (모두 시각 t 값)
    if "fac" in groups and "fac_key" in one.columns:
        wt = g.index.get_level_values("window_start")
        fk = pd.Series(sid.map(one["fac_key"]), index=g.index)
        fk = fk.where(fk.notna(), "s" + pd.Series(sid, index=g.index).astype(str))   # 연결 정보 없으면 혼자 한 묶음
        own = X["occ_lag0"]
        un = (X["avail_lag0"] == 0).astype(float).where(X["avail_lag0"].notna())
        tmp = pd.DataFrame({"fk": fk.values, "t": wt, "own": own.values, "un": un.values})
        gp = tmp.groupby(["fk", "t"])
        s_occ, n_occ = gp["own"].transform("sum"), gp["own"].transform("count")
        s_un, n_un = gp["un"].transform("sum"), gp["un"].transform("count")
        own_n = tmp["own"].notna().astype(int)
        on = n_occ - own_n
        X["fac_others_n"] = on.values
        X["fac_others_occ0"] = ((s_occ - tmp["own"].fillna(0)) / on.where(on > 0)).values
        un_own_n = tmp["un"].notna().astype(int)
        un_n = n_un - un_own_n
        X["fac_others_unavail0"] = ((s_un - tmp["un"].fillna(0)) / un_n.where(un_n > 0)).values
        if "route_key" in one.columns:
            rk = pd.Series(sid.map(one["route_key"]), index=g.index)
            tmp2 = pd.DataFrame({"rk": rk.values, "t": wt, "own": own.values})
            X["route_occ0"] = tmp2.groupby(["rk", "t"])["own"].transform("mean").values

    # (6) [static] 충전소 정보
    if "static" in groups:
        if "fast_charger_count" in df.columns:
            X["charger_cnt"] = sid.map(df.groupby("station_id")["fast_charger_count"].median()).values
        if "max_output_kw" in one.columns:
            X["max_output_kw"] = sid.map(one["max_output_kw"]).values
        if "detour_km" in one.columns:
            X["detour_km"] = sid.map(one["detour_km"]).values

    # (7) 레이블: 기준 구간으로부터 horizon_steps 뒤 구간
    y_occ = grp["occupancy_rate"].shift(-horizon_steps)
    y_av = grp["available"].shift(-horizon_steps)

    out = X.copy()
    out["y_occ"] = y_occ
    out["y_av"] = y_av
    out["y_fm"] = grp["full_minutes"].shift(-horizon_steps) if has_fm else np.nan   # 정답 전용 (변수로 쓰지 않음: EXCLUDE)
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


def make_train_stats(tr):
    """학습 구간만으로 만든 통계 (평가 구간 정보 누수 방지)."""
    return {
        "st_mean": tr.groupby("station_id")["y_occ"].mean(),
        "st_full": tr.groupby("station_id")["y_av"].apply(lambda s: float((s == 0).mean())),
        "slot_mean": tr.assign(_k=tr["target_dow"] * STEPS_PER_DAY + tr["target_slot"]).groupby("_k")["y_occ"].mean(),
    }


def attach_train_stats(d, stats):
    d = d.copy()
    d["station_mean"] = d["station_id"].map(stats["st_mean"])
    d["station_full_rate"] = d["station_id"].map(stats["st_full"])
    d["slot_mean"] = (d["target_dow"] * STEPS_PER_DAY + d["target_slot"]).map(stats["slot_mean"])
    return d


def select_features(columns, groups):
    """기본 변수 + 선택한 그룹의 변수 이름 목록."""
    groups = _norm_groups(groups)
    drop = {c for gname, cols in GROUP_COLS.items() if gname not in groups for c in cols}
    return [c for c in columns if c not in EXCLUDE and c not in drop and (not c.startswith("f5_") or "five" in groups)]


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
def pick_threshold(y, prob, target_precision, mode="precision"):
    """
    임계값 선택 규칙 (검증 데이터 기준)
      mode="precision" (기본):
        - '이용가능' 정밀도가 목표(기본 0.90) 이상인 임계값만 후보로 둔다.
        - 그중 '만차'를 가장 잘 찾아내는(만차 F1이 최대인) 값을 고른다.
      mode="f1":
        - 이용가능 정밀도 조건 없이, 검증 구간에서 '만차 F1'이 가장 높은 값을 고른다.
    이용가능 재현율만 키우면 거의 전부를 '이용 가능'이라고 답하는 쓸모없는 모델이 되기 쉽다.
    (만차 구간은 전체의 일부라서 이용가능 정밀도는 원래 높게 나온다)
    """
    cands = []
    for thr in np.linspace(0.05, 0.95, 91):
        p, r, f, _ = precision_recall_fscore_support(y, prob >= thr, labels=[1, 0], zero_division=0)
        cands.append((float(thr), float(p[0]), float(f[1])))
    if mode == "f1":
        return max(cands, key=lambda c: c[2])[0], True
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


def pr_auc_full(y, prob):
    """만차(=0)를 찾는 능력을 임계값과 무관하게 보는 지표 (0~1, 높을수록 좋음). 무작위 기준은 '만차 비율'."""
    return round(float(average_precision_score(1 - np.asarray(y), 1 - np.asarray(prob))), 3)


# --------------------------------------------------------------------------- 쾌적 이용 평가 (comfort 모드)
def best_macro_thr(y, prob):
    """검증 구간에서 '쾌적'·'불쾌적' 두 클래스 F1의 평균(macro F1)이 가장 큰 임계값."""
    best = (0.5, -1.0)
    for thr in np.linspace(0.05, 0.95, 91):
        f = precision_recall_fscore_support(y, prob >= thr, labels=[1, 0], zero_division=0)[2]
        if f.mean() > best[1]:
            best = (float(thr), float(f.mean()))
    return best[0]


def comfort_report(y, prob, thr):
    """쾌적(=1) / 불쾌적(=0) 분류 성적. 불쾌적 PR-AUC 의 무작위 기준은 불쾌적 비율, 브라이어 점수는 낮을수록 좋다."""
    y = np.asarray(y).astype(int)
    p, r, f, _ = precision_recall_fscore_support(y, prob >= thr, labels=[1, 0], zero_division=0)
    return {"쾌적 정밀도": round(float(p[0]), 3), "쾌적 재현율": round(float(r[0]), 3), "쾌적 F1": round(float(f[0]), 3),
            "불쾌적 정밀도": round(float(p[1]), 3), "불쾌적 재현율": round(float(r[1]), 3), "불쾌적 F1": round(float(f[1]), 3),
            "macro F1": round(float(f.mean()), 3),
            "불쾌적 PR-AUC": round(float(average_precision_score(1 - y, 1 - np.asarray(prob))), 3),
            "브라이어": round(float(np.mean((np.asarray(prob) - y) ** 2)), 4),
            "불쾌적 비율": round(float(np.mean(y == 0)), 3)}


def persistence_comfort(te, comfort_thr):
    """'지금 값 그대로' 기준선: 지금 구간의 사용률이 30분 뒤에도 그대로라고 본다. 지금 값이 있는 행에서만 계산한다."""
    k = te["occ_lag0"].notna().to_numpy()
    sub = te[k]
    y = sub["y_av"].astype(int).to_numpy()
    occ0 = sub["occ_lag0"].to_numpy()
    pred = (occ0 < comfort_thr).astype(float)
    rep = comfort_report(y, pred, 0.5)
    rep["불쾌적 PR-AUC"] = round(float(average_precision_score(1 - y, occ0)), 3)      # 점유율 자체를 점수로 쓴 값
    return float(mean_absolute_error(sub["y_occ"], occ0)), rep, float(k.mean())


def rank_eval(te, p_model, p_base, stations_csv, radius_km=10.0, lambdas=(0.005, 0.01, 0.02, 0.05), max_cand=10, min_cand=3,
              margins=(0.0,)):
    """
    '가까운 충전소 몇 곳 중 어디를 추천할까'를 흉내 내어 평가한다.
    질의 = (충전소 i, 시각 t): i 를 '사용자에게 가장 가까운 충전소'로 보고, 반경 radius_km 안의 충전소(i 포함, 가까운 순 최대 max_cand 곳)가 후보.
    추천 점수 = 쾌적 이용확률 - lam x 거리(km)  → 점수가 가장 높은 곳이 1위. (lam 이 클수록 거리를 중요하게 본다)
    margin > 0 이면 '1위 점수가 가장 가까운 곳 점수보다 margin 이상 높을 때만' 다른 곳으로 돌린다 (불필요한 우회를 줄이는 규칙).
    정답 = 실제로 그 구간에 쾌적했는지(y_av). 비교 대상: 가장 가까운 곳 / 기준 모델(요일×시간 평균) / AI / 정답(상한).
    ※ 후보를 '반경'으로 잡은 단순 평가이고, 실제 서비스의 경로·거리 규칙(한휘제 님 쪽)과는 다를 수 있다.
    """
    from sklearn.neighbors import BallTree
    st = pd.read_csv(stations_csv).drop_duplicates("station_id").set_index("station_id")[["latitude", "longitude"]]
    d = pd.DataFrame({"station_id": te["station_id"].to_numpy(), "t": te["window_start"].to_numpy(),
                      "pm": np.asarray(p_model, dtype="float32"), "pb": np.asarray(p_base, dtype="float32"),
                      "y": te["y_av"].to_numpy(dtype="float32")})
    d = d[d["station_id"].isin(st.index)]
    ids = np.array(sorted(d["station_id"].unique()))
    times = np.sort(d["t"].unique())
    ti = pd.Index(times).get_indexer(d["t"])
    si = pd.Index(ids).get_indexer(d["station_id"])
    M = {k: np.full((len(times), len(ids)), np.nan, dtype="float32") for k in ("pm", "pb", "y")}
    for k in M:
        M[k][ti, si] = d[k].to_numpy()
    xy = np.radians(st.loc[ids, ["latitude", "longitude"]].to_numpy())
    tree = BallTree(xy, metric="haversine")
    nbr, dist = tree.query_radius(xy, r=radius_km / 6371.0, return_distance=True)

    pol_names = ["nearest", "oracle"] + [f"{p}|{lam}|{mg}" for lam in lambdas for mg in margins for p in ("base", "model")]
    acc = {k: dict(n=0, sy=0.0, hn=0, hdiv=0, ndiv=0, ndiv_hard=0, sd=0.0, t3=0.0, t3n=0) for k in pol_names}
    n_cand_sum, n_q = 0, 0
    for i in range(len(ids)):
        pairs = [(dd, j) for dd, j in sorted(zip(dist[i], nbr[i])) if j != i][: max_cand - 1]
        cidx = np.array([i] + [j for _, j in pairs])
        if len(cidx) < min_cand:
            continue
        dk = np.array([0.0] + [float(dd) * 6371.0 for dd, _ in pairs])
        Y, PM, PB = M["y"][:, cidx], M["pm"][:, cidx], M["pb"][:, cidx]
        ok = np.isfinite(Y).all(1) & np.isfinite(PM).all(1) & np.isfinite(PB).all(1)
        if ok.sum() == 0:
            continue
        Y, PM, PB = Y[ok], PM[ok], PB[ok]
        hard = Y[:, 0] == 0
        n_q += len(Y)
        n_cand_sum += len(Y) * len(cidx)

        def add(name, choice, score=None):
            a = acc[name]
            yc = Y[np.arange(len(Y)), choice]
            a["n"] += len(Y); a["sy"] += float(yc.sum())
            div = choice != 0                                   # 가장 가까운 곳이 아닌 다른 곳을 1위로 추천
            a["hn"] += int(hard.sum()); a["hdiv"] += int((div & hard).sum())
            a["ndiv"] += int(div.sum()); a["ndiv_hard"] += int((div & hard).sum())
            a["sd"] += float(dk[choice].sum())
            if score is not None and len(cidx) >= 3:
                top3 = np.argsort(-score, axis=1, kind="stable")[:, :3]
                a["t3"] += float(np.take_along_axis(Y, top3, 1).mean(1).sum()); a["t3n"] += len(Y)

        add("nearest", np.zeros(len(Y), dtype=int))
        add("oracle", np.argmax(Y > 0, axis=1))
        for lam in lambdas:
            for mg in margins:
                for tag, P in (("base", PB), ("model", PM)):
                    s = P - lam * dk[None, :]
                    best = np.argmax(s, axis=1)
                    gain = s[np.arange(len(s)), best] - s[:, 0]
                    add(f"{tag}|{lam}|{mg}", np.where(gain >= mg, best, 0), s)
    if n_q == 0:
        return pd.DataFrame(), {}
    rows = []
    label = {"nearest": "가장 가까운 곳", "oracle": "정답(상한: 쾌적한 곳 중 가장 가까운 곳)"}
    for k in pol_names:
        a = acc[k]
        if a["n"] == 0:
            continue
        if "|" in k:
            tag, lam, mg = k.split("|")
            nm = ("기준 모델" if tag == "base" else "AI") + f" (거리 가중 {lam}/km" + (f", 여유 {mg} 이상일 때만" if float(mg) > 0 else "") + ")"
        else:
            nm = label[k]
        rows.append([nm, a["sy"] / a["n"], a["ndiv"] / a["n"], (a["ndiv_hard"] / a["ndiv"]) if a["ndiv"] else np.nan,
                     a["hdiv"] / max(a["hn"], 1), a["sd"] / a["n"], (a["t3"] / a["t3n"]) if a["t3n"] else np.nan])
    tab = pd.DataFrame(rows, columns=["추천 방식", "1위가 쾌적했던 비율↑", "다른 곳으로 돌린 비율", "돌린 것 중 실제로 막혔던 비율↑",
                                      "막힌 곳을 알아채 돌린 비율↑", "평균 추가 거리(km)", "상위3 평균 쾌적 비율↑"])
    info = {"queries": n_q, "hard_share": acc["nearest"]["hn"] / acc["nearest"]["n"], "avg_candidates": n_cand_sum / n_q}
    return tab, info


# --------------------------------------------------------------------------- 학습·평가
def prepare(df, h, hol, groups="all", min_full=0, comfort_thr=0.0):
    """데이터셋 생성 + 시간순 분할 + 학습 구간 통계 부착. (변수 그룹 비교를 위해 한 번만 만든다)
    min_full > 0 이면 '만차' 정의를 바꾼다: 대상 구간 안에서 만차였던 시간이 min_full분 이상이면 만차(=0),
    아니면 이용 가능(=1). 0(기본)이면 예전 정의(구간 마지막 관측에 빈 충전기가 없으면 만차).
    comfort_thr > 0 이면 '쾌적 이용' 정의를 쓴다: 대상 구간의 혼잡도(충전기 사용률)가 comfort_thr 미만이면 쾌적(=1),
    이상이면 불쾌적(=0). 이때 y_av 는 쾌적 여부이고 분류 모델은 '쾌적 이용확률'을 낸다."""
    data = build_dataset(df, h, hol, groups)
    if comfort_thr > 0:
        data["y_av"] = (data["y_occ"] < comfort_thr).astype(float).where(data["y_occ"].notna())
    elif min_full > 0:
        if data["y_fm"].isna().all():
            raise SystemExit("--target-full-min 을 쓰려면 full_minutes 컬럼이 필요합니다.")
        data["y_av"] = (data["y_fm"] < min_full).astype(float).where(data["y_fm"].notna())
    data = data.dropna(subset=["y_occ", "y_av"])          # 레이블이 없는 행 제외
    tr, va, te = time_split(data, h)
    stats = make_train_stats(tr)
    tr, va, te = (attach_train_stats(d, stats) for d in (tr, va, te))
    return {"tr": tr, "va": va, "te": te, "stats": stats, "h": h}


REG_DEFAULT = dict(n_estimators=600, learning_rate=0.05, max_depth=6, subsample=0.8, colsample_bytree=0.8,
                   tree_method="hist", early_stopping_rounds=40, eval_metric="mae", random_state=42)
CLF_DEFAULT = dict(n_estimators=600, learning_rate=0.05, max_depth=6, subsample=0.8, colsample_bytree=0.8,
                   tree_method="hist", early_stopping_rounds=40, eval_metric="logloss", random_state=42)


def fit_reg(tr, va, feat, params=None):
    reg = XGBRegressor(**{**REG_DEFAULT, **(params or {})})
    reg.fit(tr[feat], tr["y_occ"], eval_set=[(va[feat], va["y_occ"])], verbose=False)
    return reg


def fit_clf(tr, va, feat, params=None, balance=False):
    ytr = tr["y_av"].astype(int)
    spw = float((ytr == 0).sum() / max((ytr == 1).sum(), 1)) if balance else 1.0   # 만차(0)를 더 중요하게
    clf = XGBClassifier(**{**CLF_DEFAULT, **(params or {})}, scale_pos_weight=spw)
    clf.fit(tr[feat], ytr, eval_set=[(va[feat], va["y_av"].astype(int))], verbose=False)
    return clf


def train_eval(prep, groups=(), balance=False, target_precision=0.90, quiet=True, reg_params=None, clf_params=None,
               thr_mode="precision", comfort=False, comfort_thr=0.0):
    """선택한 변수 그룹으로 학습하고 평가 구간 성적을 돌려준다. reg_params/clf_params 로 XGBoost 설정을 바꿀 수 있다.
    comfort=True 면 y_av 를 '쾌적 여부'로 보고 macro F1 기준 임계값·쾌적 평가 지표를 함께 계산한다."""
    tr, va, te = prep["tr"], prep["va"], prep["te"]
    feat = select_features(tr.columns, groups)

    base = Baseline().fit(tr)
    b_occ, b_av = base.predict(te)

    reg = fit_reg(tr, va, feat, reg_params)
    clf = fit_clf(tr, va, feat, clf_params, balance)

    pv = clf.predict_proba(va[feat])[:, 1]
    yv = va["y_av"].astype(int).to_numpy()
    if comfort:
        thr, ok = best_macro_thr(yv, pv), True
    else:
        thr, ok = pick_threshold(yv, pv, target_precision, thr_mode)
    y = te["y_av"].astype(int).to_numpy()
    prob = clf.predict_proba(te[feat])[:, 1]
    pred_occ = reg.predict(te[feat])
    res = {
        "feat": feat, "reg": reg, "clf": clf, "thr": thr, "thr_ok": ok, "balance": balance,
        "mae": float(mean_absolute_error(te["y_occ"], pred_occ)),
        "mae_base": float(mean_absolute_error(te["y_occ"], b_occ)),
        "cls": cls_report(y, prob, thr), "cls_base": cls_report(y, b_av, 0.5),
        "pr_auc": pr_auc_full(y, prob), "pr_auc_base": pr_auc_full(y, b_av),
        "prob": prob, "pred_occ": pred_occ, "b_av": b_av, "b_occ": b_occ,
    }
    if comfort:
        b_thr = best_macro_thr(yv, Baseline().fit(tr).predict(va)[1])
        res["cmf"] = comfort_report(y, prob, thr)
        res["cmf_base"] = comfort_report(y, b_av, b_thr)
        res["thr_base"] = b_thr
        if comfort_thr > 0:
            res["mae_persist"], res["cmf_persist"], res["persist_cover"] = persistence_comfort(te, comfort_thr)
    return res


# --------------------------------------------------------------------------- 하이퍼파라미터 탐색
TUNE_GRID = {
    "max_depth": [4, 6, 8, 10],
    "learning_rate": [0.03, 0.05, 0.1],
    "min_child_weight": [1, 5, 20, 50],
    "subsample": [0.7, 0.8, 1.0],
    "colsample_bytree": [0.6, 0.8, 1.0],
    "reg_lambda": [1, 5, 20],
}


def sample_configs(n, seed=0):
    """기본 설정 1개 + 무작위로 뽑은 설정 n-1개."""
    rng = np.random.default_rng(seed)
    cfgs = [{}]
    seen = {()}
    while len(cfgs) < n:
        c = {k: v[int(rng.integers(len(v)))] for k, v in TUNE_GRID.items()}
        key = tuple(sorted(c.items()))
        if key not in seen:
            seen.add(key)
            cfgs.append(c)
    return cfgs


def run_tune(prep, groups, balance, n=6, seed=0):
    """
    검증 구간 성적만으로 설정을 고른다 (평가 구간은 보지 않는다).
      - 혼잡도 모델: 검증 MAE가 가장 낮은 설정
      - 이용 가능 모델: 검증 '만차 PR-AUC'가 가장 높은 설정
    """
    tr, va = prep["tr"], prep["va"]
    feat = select_features(tr.columns, groups)
    yv = va["y_av"].astype(int).to_numpy()
    rows = []
    for i, cfg in enumerate(sample_configs(n, seed)):
        reg = fit_reg(tr, va, feat, cfg)
        clf = fit_clf(tr, va, feat, cfg, balance)
        v_mae = float(mean_absolute_error(va["y_occ"], reg.predict(va[feat])))
        v_pr = pr_auc_full(yv, clf.predict_proba(va[feat])[:, 1])
        rows.append((i, cfg, v_mae, v_pr))
        print(f"  [{i + 1}/{n}] 검증 MAE {v_mae:.4f} | 검증 만차 PR-AUC {v_pr:.3f} | {cfg or '기본 설정'}", flush=True)
    best_reg = min(rows, key=lambda r: r[2])
    best_clf = max(rows, key=lambda r: r[3])
    return best_reg[1], best_clf[1], rows


def persistence_row(te):
    """'지금 값 그대로' 방식 (참고용 기준선)."""
    m = te["occ_lag0"].notna()
    m2 = te["avail_lag0"].notna().to_numpy()
    y = te["y_av"].astype(int).to_numpy()
    rep = cls_report(y[m2], te["avail_lag0"].to_numpy()[m2], 0.5)
    return float(mean_absolute_error(te["y_occ"][m], te["occ_lag0"][m])), rep


COMPARE_SETS = [
    ("① 기본 (v0.1)", (), False),
    ("② + 만차 이력", ("full",), False),
    ("③ + 주변 충전소", ("full", "fac"), False),
    ("④ + 충전소 정보", ("full", "fac", "static"), False),
    ("⑤ + 학습 통계 (전체)", ALL_GROUPS, False),
    ("⑥ 전체 + 만차 가중치", ALL_GROUPS, True),
]


def run_compare(prep, target_precision):
    rows = []
    te = prep["te"]
    p_mae, p_rep = persistence_row(te)
    b = Baseline().fit(prep["tr"])
    bo, ba = b.predict(te)
    y = te["y_av"].astype(int).to_numpy()
    brep = cls_report(y, ba, 0.5)
    rows.append(["기준: 요일×시간대 평균", mean_absolute_error(te["y_occ"], bo), brep["만차 정밀도"], brep["만차 재현율"],
                 brep["만차 F1"], pr_auc_full(y, ba), "-"])
    rows.append(["기준: 지금 값 그대로", p_mae, p_rep["만차 정밀도"], p_rep["만차 재현율"], p_rep["만차 F1"], "-", "-"])
    for name, groups, bal in COMPARE_SETS:
        r = train_eval(prep, groups, bal, target_precision)
        print(f"  완료: {name}  (변수 {len(r['feat'])}개)", flush=True)
        c = r["cls"]
        rows.append([name, r["mae"], c["만차 정밀도"], c["만차 재현율"], c["만차 F1"], r["pr_auc"], f"{r['thr']:.2f}"])
    tab = pd.DataFrame(rows, columns=["설정", "혼잡도 오차(MAE)↓", "만차 정밀도", "만차 재현율", "만차 F1↑", "만차 PR-AUC↑", "임계값"])
    tab["혼잡도 오차(MAE)↓"] = tab["혼잡도 오차(MAE)↓"].astype(float).round(4)
    return tab


def run_comfort_sweep(prep, groups, thetas, balance=False):
    """쾌적 기준(사용률 몇 % 미만을 쾌적으로 볼지)을 바꿔 가며 분류 점수를 비교한다. 분류 모델만 기준마다 새로 학습한다.
    ※ 기준마다 '불쾌적 비율'이 달라 문제 난이도가 다르므로 점수를 서로 직접 비교하지 말고, 각 기준의 기준 모델과 비교한다."""
    tr0, va0, te0 = prep["tr"], prep["va"], prep["te"]
    rows = []
    for th in thetas:
        parts = []
        for d in (tr0, va0, te0):
            parts.append(d.assign(y_av=(d["y_occ"] < th).astype(float)))
        stats = make_train_stats(parts[0])
        tr, va, te = (attach_train_stats(d, stats) for d in parts)
        del parts
        feat = select_features(tr.columns, groups)
        yv, y = va["y_av"].astype(int).to_numpy(), te["y_av"].astype(int).to_numpy()
        base = Baseline().fit(tr)
        b_thr = best_macro_thr(yv, base.predict(va)[1])
        rb = comfort_report(y, base.predict(te)[1], b_thr)
        clf = fit_clf(tr, va, feat, None, balance)
        thr = best_macro_thr(yv, clf.predict_proba(va[feat])[:, 1])
        rx = comfort_report(y, clf.predict_proba(te[feat])[:, 1], thr)
        print(f"  완료: 사용률 {th:.0%} 미만 = 쾌적 (불쾌적 비율 {rx['불쾌적 비율']})", flush=True)
        rows.append([f"{th:.0%}", rx["불쾌적 비율"], rb["쾌적 F1"], rx["쾌적 F1"], rb["불쾌적 F1"], rx["불쾌적 F1"],
                     rb["macro F1"], rx["macro F1"], rb["불쾌적 PR-AUC"], rx["불쾌적 PR-AUC"], rb["브라이어"], rx["브라이어"]])
        del tr, va, te
    cols = ["쾌적 기준(사용률 미만)", "불쾌적 비율", "쾌적 F1 기준", "쾌적 F1 XGB", "불쾌적 F1 기준", "불쾌적 F1 XGB",
            "macro F1 기준", "macro F1 XGB", "PR-AUC 기준", "PR-AUC XGB", "브라이어 기준", "브라이어 XGB"]
    return pd.DataFrame(rows, columns=cols)


def main():
    ap = argparse.ArgumentParser()
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--demo", action="store_true")
    src.add_argument("--csv")
    src.add_argument("--db")
    ap.add_argument("--access", default=None, help="station_access.csv 경로 (휴게소/IC 구분, 같은 휴게소·노선 정보)")
    ap.add_argument("--stations", default=None, help="stations.csv 경로 (충전기 최대 출력)")
    ap.add_argument("--horizon-steps", type=int, default=1, help="1=30분 후, 2=60분 후 (15·45분은 5분 단위 데이터 필요)")
    ap.add_argument("--target-precision", type=float, default=0.90)
    ap.add_argument("--holidays", default="", help="추가 공휴일/대체공휴일 (예: 2026-10-05,2026-10-09)")
    ap.add_argument("--groups", default="", help="사용할 변수 그룹: full,fac,static,hist 또는 all (기본: 없음 = v0.1)")
    ap.add_argument("--balance", action="store_true", help="만차 가중치 사용 (만차를 더 중요하게 학습)")
    ap.add_argument("--compare", action="store_true", help="변수 그룹별 비교표를 출력하고 끝낸다 (여러 번 학습, 시간이 걸림)")
    ap.add_argument("--exclude", default=None, help="학습에서 뺄 충전소 목록 CSV (예: 학습데이터_제외_충전소_1104.csv)")
    ap.add_argument("--include", default=None, help="서비스 대상 충전소 목록 CSV (이 목록에 있는 충전소만 사용)")
    ap.add_argument("--tune", action="store_true", help="하이퍼파라미터를 검증 구간 기준으로 탐색해 기본 설정과 비교")
    ap.add_argument("--tune-n", type=int, default=6, help="--tune 에서 시험할 설정 개수 (기본 6, 많을수록 오래 걸림)")
    ap.add_argument("--thr-mode", choices=["precision", "f1"], default="precision",
                    help="임계값 선택: precision(이용가능 정밀도 목표 후 만차 F1, 기본) / f1(만차 F1만 최대로)")
    ap.add_argument("--clean", choices=["none", "noocc", "strict"], default="none",
                    help="[예전 데이터용] 의심 라벨 정리: noocc=점유 값 없는 이용 불가를 알 수 없음 처리 (11/09 데이터는 window_quality 로 자동 처리)")
    ap.add_argument("--keep-fault", action="store_true", help="window_quality 를 무시하고 모든 구간 사용 (정리 전 성적 비교용)")
    ap.add_argument("--keep-low", action="store_true", help="data_quality=LOW 충전소도 사용 (정리 전 성적 비교용)")
    ap.add_argument("--target-full-min", type=int, default=0,
                    help="만차 정의: 대상 구간 안 만차 시간이 이 값(분) 이상이면 만차. 0=예전 정의(구간 마지막 관측). 예: 5, 10, 15")
    ap.add_argument("--gpu", action="store_true", help="NVIDIA GPU로 학습 (xgboost 2.0 이상, CUDA 드라이버 필요)")
    ap.add_argument("--comfort-thr", type=float, default=0.0,
                    help="쾌적 이용 정의: 30분 뒤 충전기 사용률이 이 값 미만이면 쾌적. 예: 0.8. 0=사용 안 함(예전 이용가능/만차 정의). "
                         "켜면 분류 모델이 '쾌적 이용확률'을 내고(저장 표의 available_prob 자리), 만차 지표 대신 쾌적 지표를 보여 준다")
    ap.add_argument("--comfort-sweep", default="",
                    help="쾌적 기준을 여러 개 비교 (예: 0.5,0.6,0.7,0.8). 기준마다 분류 모델만 새로 학습해 F1 표를 낸다 (회귀·저장은 하지 않음)")
    ap.add_argument("--fast", action="store_true", help="빠른 학습 (학습률 0.1, 나무 최대 300개). 점수는 거의 같고 시간은 절반 정도")
    ap.add_argument("--rank-eval", action="store_true", help="추천 순위 평가: 가까운 충전소 후보 중 1위가 쾌적했는지 (--stations 필요)")
    ap.add_argument("--rank-radius", type=float, default=10.0, help="--rank-eval 후보 반경(km)")
    ap.add_argument("--rank-max-cand", type=int, default=10, help="--rank-eval 후보 최대 개수(가까운 순)")
    ap.add_argument("--rank-lambdas", default="0.005,0.01,0.02,0.05", help="--rank-eval 거리 가중 목록 (1km 당 쾌적확률을 얼마나 깎을지)")
    ap.add_argument("--rank-margins", default="0", help="--rank-eval 우회 여유 목록: 1위 점수가 가까운 곳보다 이만큼 높을 때만 돌림 (예: 0,0.1,0.2,0.3)")
    ap.add_argument("--out-dir", default="models")
    a = ap.parse_args()
    if a.gpu:
        REG_DEFAULT["device"] = "cuda"
        CLF_DEFAULT["device"] = "cuda"
    if a.fast:
        for P in (REG_DEFAULT, CLF_DEFAULT):
            P.update(learning_rate=0.1, n_estimators=300, early_stopping_rounds=30)
    comfort = a.comfort_thr > 0
    h = a.horizon_steps
    groups = _norm_groups(a.groups)

    df = make_demo() if a.demo else load_csv(a.csv, a.access, a.stations) if a.csv else load_db(a.db)
    n0 = df["station_id"].nunique()
    if a.include:
        df = df[df["station_id"].isin(load_station_ids(a.include))]
    if a.exclude:
        df = df[~df["station_id"].isin(load_station_ids(a.exclude))]
    if a.include or a.exclude:
        print(f"충전소 목록 적용: {n0}곳 → {df['station_id'].nunique()}곳")
    df = apply_quality(df, a.keep_fault, a.keep_low)
    df = clean_labels(df, a.clean)
    years = range(df["window_start"].dt.year.min(), df["window_start"].dt.year.max() + 1)
    hol = get_holidays(years, [x for x in a.holidays.split(",") if x])
    span_days = (df["window_start"].max() - df["window_start"].min()).days + 1
    print(f"데이터: 충전소 {df['station_id'].nunique()}곳, {span_days}일, {len(df):,}행")
    if span_days < 28:
        print("[주의] 28일 미만입니다. 요일·지난주 패턴을 충분히 못 배우므로 결과는 참고용입니다.")

    sweep = [float(x) for x in a.comfort_sweep.split(",") if x.strip()]
    prep = prepare(df, h, hol, ALL_GROUPS if a.compare else groups, a.target_full_min, sweep[0] if sweep else a.comfort_thr)
    tr, va, te = prep["tr"], prep["va"], prep["te"]
    print(f"학습 {len(tr):,} / 검증 {len(va):,} / 평가 {len(te):,} 행 (시간순 분할)")
    print(f"평가 기간: {te['window_start'].min()} ~ {te['window_start'].max()}")

    if sweep:
        print(f"\n쾌적 기준 비교를 시작합니다 ({', '.join(f'{x:.0%}' for x in sweep)}; 분류 모델만 기준마다 학습)...")
        tab = run_comfort_sweep(prep, groups, sweep, a.balance)
        print("\n== 쾌적 기준별 분류 점수 (평가 구간, 30분×%d 후) ==" % h)
        print(tab.to_string(index=False))
        print("\n※ 기준마다 불쾌적 비율이 달라 난이도가 다릅니다. 기준끼리 점수를 비교하지 말고, 같은 줄의 '기준 모델'과 'XGB'를 비교하세요.")
        print("※ 쾌적 기준은 점수가 좋은 값이 아니라 '서비스에서 이 정도면 막혔다고 부를 수준'으로 정하세요.")
        return

    if a.compare:
        print("\n변수 그룹별 비교를 시작합니다 (모델을 여러 번 학습하므로 시간이 걸립니다)...")
        tab = run_compare(prep, a.target_precision)
        print("\n== 변수 그룹별 비교 (평가 구간 기준, 30분×%d 후) ==" % h)
        print(tab.to_string(index=False))
        print("\n※ 만차 PR-AUC의 무작위 기준은 '만차 비율'(%.3f)입니다. 숫자가 이보다 많이 높아야 의미가 있습니다." %
              float(np.mean(te["y_av"] == 0)))
        return

    reg_params = clf_params = None
    if a.tune:
        print(f"\n하이퍼파라미터 탐색 ({a.tune_n}개 설정, 검증 구간 기준)...")
        reg_params, clf_params, _ = run_tune(prep, groups, a.balance, a.tune_n)
        print("선택된 혼잡도 모델 설정:", reg_params or "기본 설정")
        print("선택된 이용 가능 모델 설정:", clf_params or "기본 설정")
        r0 = train_eval(prep, groups, a.balance, a.target_precision, thr_mode=a.thr_mode)
        print("\n== 기본 설정 vs 튜닝 설정 (평가 구간 기준) ==")
        r1 = train_eval(prep, groups, a.balance, a.target_precision, reg_params=reg_params, clf_params=clf_params, thr_mode=a.thr_mode)
        tab = pd.DataFrame([
            ["기본 설정", r0["mae"], r0["cls"]["만차 정밀도"], r0["cls"]["만차 재현율"], r0["cls"]["만차 F1"], r0["pr_auc"]],
            ["튜닝 설정", r1["mae"], r1["cls"]["만차 정밀도"], r1["cls"]["만차 재현율"], r1["cls"]["만차 F1"], r1["pr_auc"]],
        ], columns=["설정", "혼잡도 오차(MAE)↓", "만차 정밀도", "만차 재현율", "만차 F1↑", "만차 PR-AUC↑"])
        tab["혼잡도 오차(MAE)↓"] = tab["혼잡도 오차(MAE)↓"].round(4)
        print(tab.to_string(index=False))
        r = r1
    else:
        r = train_eval(prep, groups, a.balance, a.target_precision, thr_mode=a.thr_mode, comfort=comfort, comfort_thr=a.comfort_thr)
    feat, reg, clf, thr = r["feat"], r["reg"], r["clf"], r["thr"]
    print(f"사용 변수 {len(feat)}개 (그룹: {','.join(groups) if groups else '기본'}{', 만차 가중치' if a.balance else ''}"
          f"{', 임계값=만차F1' if a.thr_mode == 'f1' else ''}{', 라벨정리=' + a.clean if a.clean != 'none' else ''}{', 만차정의=구간 내 ' + str(a.target_full_min) + '분 이상' if a.target_full_min else ''}"
          f"{', 쾌적정의=사용률 ' + str(a.comfort_thr) + ' 미만' if comfort else ''})")

    print("\n== 혼잡도 오차(MAE, 낮을수록 좋음) ==")
    print(f"기준 모델 {r['mae_base']:.4f}  |  XGBoost {r['mae']:.4f}  (개선 {(1 - r['mae'] / r['mae_base']) * 100:.1f}%)")
    if comfort:
        c, cb = r["cmf"], r["cmf_base"]
        print(f"\n== 쾌적 이용 분류 (30분 뒤 사용률 {a.comfort_thr:.0%} 미만 = 쾌적, 평가 구간 불쾌적 비율 {c['불쾌적 비율']}) ==")
        print(f"임계값(검증 macro F1 기준): XGBoost {thr:.2f} / 기준 모델 {r['thr_base']:.2f}")
        rows_, idx_ = [cb, c], ["기준 모델(요일×시간)", "XGBoost"]
        if "cmf_persist" in r:
            rows_.insert(1, r["cmf_persist"]); idx_.insert(1, "지금 값 그대로")
        print(pd.DataFrame(rows_, index=idx_).to_string())
        if "cmf_persist" in r:
            print(f"혼잡도 오차(MAE): 지금 값 그대로 {r['mae_persist']:.4f} (지금 값이 있는 행 {r['persist_cover']:.1%}만 계산)")
        print("※ 쾌적은 대부분이라 '쾌적 F1'은 기준 모델도 높게 나옵니다. 차이는 불쾌적 PR-AUC·브라이어(낮을수록 좋음)·macro F1에서 보세요.")
    else:
        print(f"\n임계값(검증 기준): {thr:.2f}" + ("" if r["thr_ok"] else "  [이용가능 정밀도 목표 미달 → 만차 F1 최대 지점 사용]"))
        print("\n== 이용 가능 여부 분류 ==")
        print("기준 모델:", r["cls_base"])
        print("XGBoost  :", r["cls"])
        print(f"만차 PR-AUC: 기준 {r['pr_auc_base']}  |  XGBoost {r['pr_auc']}  (무작위 기준 = 만차 비율 {r['cls']['만차 비율']})")
        print(f"\n목표: 정밀도 {a.target_precision}, 재현율 0.80, F1 0.85 (초기 목표)")
        print("※ '이용가능' 정밀도는 대부분의 구간이 이용 가능이면 쉽게 높게 나옵니다. '만차 비율'과 '만차 재현율'도 함께 보세요.")

    if a.rank_eval:
        if not a.stations:
            print("\n[추천 순위 평가] --stations 가 필요합니다 (위도·경도).")
        else:
            print(f"\n== 추천 순위 평가 (반경 {a.rank_radius:g}km 안 후보, 점수 = 쾌적확률 - 거리가중 x km) ==")
            tab, info = rank_eval(prep["te"], r["prob"], r["b_av"], a.stations, a.rank_radius,
                                  tuple(float(x) for x in a.rank_lambdas.split(",")), a.rank_max_cand,
                                  margins=tuple(float(x) for x in a.rank_margins.split(",")))
            if len(tab):
                for c_ in tab.columns[1:]:
                    tab[c_] = tab[c_].astype(float).round(3)
                print(tab.to_string(index=False))
                print(f"질의 {info['queries']:,}건, 후보 평균 {info['avg_candidates']:.1f}곳, 가장 가까운 곳이 막혀 있던 질의 {info['hard_share']:.1%}")
                print("※ '돌린 것 중 실제로 막혔던 비율' = 가까운 곳 대신 다른 곳을 추천했을 때, 그 가까운 곳이 정말 불쾌적했던 비율(우회 정밀도).")
                print("  '막힌 곳을 알아채 돌린 비율' = 가까운 곳이 실제로 불쾌적이었던 질의 중 다른 곳으로 돌린 비율(우회 재현율). 아무 데나 돌려도 쾌적한 곳이 대부분이라 '쾌적 비율'만 보면 부풀려지니 두 비율을 함께 보세요.")
            else:
                print("후보를 만들 수 없습니다 (충전소 위치 정보 확인).")
            tab_path = Path(a.out_dir)
            tab_path.mkdir(exist_ok=True)
            pd.DataFrame({"station_id": prep["te"]["station_id"].to_numpy(), "window_start": prep["te"]["window_start"].to_numpy(),
                          "y_occ": prep["te"]["y_occ"].to_numpy(), "y_av": prep["te"]["y_av"].to_numpy(),
                          "pred_occ": r["pred_occ"], "comfort_prob": r["prob"], "base_prob": r["b_av"],
                          "base_occ": r["b_occ"]}).to_pickle(tab_path / f"eval_pred_h{h}.pkl")

    if "is_ic" in te.columns:
        print("\n== 휴게소 / IC 별 성적 (XGBoost) ==")
        y = te["y_av"].astype(int).to_numpy()
        pr = reg.predict(te[feat])
        for name, m in (("휴게소", te["is_ic"] == 0), ("IC 인근", te["is_ic"] == 1)):
            m = m.to_numpy()
            if m.sum() == 0:
                continue
            if comfort:
                c = comfort_report(y[m], r["prob"][m], thr)
                print(f"{name}: MAE {mean_absolute_error(te['y_occ'][m], pr[m]):.4f} | 불쾌적 비율 {c['불쾌적 비율']} | "
                      f"macro F1 {c['macro F1']} | 불쾌적 PR-AUC {c['불쾌적 PR-AUC']} | 브라이어 {c['브라이어']}")
            else:
                c = cls_report(y[m], r["prob"][m], thr)
                print(f"{name}: MAE {mean_absolute_error(te['y_occ'][m], pr[m]):.4f} | 만차 비율 {c['만차 비율']} | "
                      f"만차 정밀도 {c['만차 정밀도']} 재현율 {c['만차 재현율']} F1 {c['만차 F1']}")

    imp = pd.Series(clf.feature_importances_, index=feat).sort_values(ascending=False).head(10)
    print("\n중요 변수(분류 모델 상위 10):\n", imp.round(3).to_string())

    # ---- 저장
    out = Path(a.out_dir)
    out.mkdir(exist_ok=True)
    reg.save_model(str(out / f"reg_h{h}.json"))
    clf.save_model(str(out / f"clf_h{h}.json"))
    stats = prep["stats"]
    meta = {"model_version": MODEL_VERSION, "horizon_steps": h, "horizon_min": h * STEP_MIN,
            "groups": list(groups), "balance": bool(a.balance), "thr_mode": a.thr_mode, "clean": a.clean, "target_full_min": a.target_full_min, "keep_fault": bool(a.keep_fault), "keep_low": bool(a.keep_low),
            "comfort_thr": a.comfort_thr,
            "reg_params": reg_params or {}, "clf_params": clf_params or {},
            "features": feat, "threshold": thr,
            "station_mean": {str(k): float(v) for k, v in stats["st_mean"].items()},
            "station_full_rate": {str(k): float(v) for k, v in stats["st_full"].items()},
            "slot_mean": {str(k): float(v) for k, v in stats["slot_mean"].items()},
            "train_end": str(tr["window_start"].max())}
    (out / f"meta_h{h}.json").write_text(json.dumps(meta, ensure_ascii=False, indent=1))
    print(f"\n저장: {out}/ (reg_h{h}.json, clf_h{h}.json, meta_h{h}.json)")

    # ---- 예측 결과 표 만들기 (PREDICTION 테이블 형태): 가장 최근 구간 기준
    latest = build_dataset(df, h, hol, groups)
    last_t = latest["window_start"].max()
    cur = attach_train_stats(latest[latest["window_start"] == last_t], stats)
    pred = pd.DataFrame({
        "station_id": cur["station_id"].values,
        "target_time": cur["target_time"].values,
        "horizon_min": h * STEP_MIN,
        "congestion_rate": np.clip(reg.predict(cur[feat]), 0, 1).round(4),
        "available_prob": clf.predict_proba(cur[feat])[:, 1].round(4),
        "model_version": MODEL_VERSION,
        "predicted_at": pd.Timestamp.now(tz="Asia/Seoul").tz_localize(None).floor("s"),     # 한국 시간
    })
    pred.to_csv(out / f"prediction_h{h}.csv", index=False)
    print("\n예측 결과 예시 (PREDICTION 테이블 형태):")
    print(pred.head().to_string(index=False))


if __name__ == "__main__":
    main()
