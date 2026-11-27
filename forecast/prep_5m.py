"""
5분 원본(EVision_5m_*.zip) -> 학습용 30분 구간 표 (occupancy_30m 과 같은 형식 + 5분 변수)

  python prep_5m.py --occ occupancy_5m.csv.gz --raw status_history_raw.csv.gz --chargers chargers.csv --out data30.pkl

만들어지는 표 (충전소 x 30분 구간):
  기존 occupancy_30m 과 같은 컬럼: station_id, window_start, fast_charger_count, occupancy_rate, available,
                                    full_minutes, valid_minutes, observed_points, window_quality
  5분에서 새로 뽑은 변수(f5_, 구간 마지막 시점 기준):
    f5_nfree_last / f5_ncharg_last / f5_nunk_last : 마지막 관측의 빈 충전기·충전 중·미확인 대수
    f5_occ_last / f5_occ_first / f5_occ_max        : 구간 안 혼잡도의 마지막·처음·최댓값
  충전기별 상태 변화 원본에서 뽑은 변수(f5_, --raw 를 줄 때만; 구간 마지막 시점 기준, 그 시점에 서버가 알던 기록만 사용):
    f5_chg_dur_max / f5_chg_dur_mean : 충전 중인 충전기들이 지금까지 충전한 시간(분)의 최대·평균
    f5_chg_n_lt10 / f5_chg_n_ge30    : 충전 시작 10분 미만 / 30분 이상인 충전기 수
    f5_idle_max                       : 빈 채로 가장 오래 놀고 있는 충전기의 대기 시간(분, 최대 1440)
    f5_since_change                   : 충전소 안에서 가장 최근에 상태가 바뀐 지 몇 분 됐는지(최대 1440)

30분 값 계산 규칙은 가현 님 README 와 같다.
  occupancy_rate = 유효 관측 3개 이상일 때, 관측마다 충전중/(사용가능+충전중)의 평균 (분모 0인 관측은 빼고 평균)
  available      = 구간 마지막 유효 관측에 빈 충전기가 있으면 1, 없고 전부 확인됐으면 0, 판단 불가면 빈칸
  full_minutes   = 혼잡도 1 인 관측 수 x 5
  window_quality = available=0 & occupancy 빈칸 -> FAULT / available 빈칸 -> UNKNOWN / 나머지 OK
"""
import argparse
import time

import numpy as np
import pandas as pd

PTS = 6               # 30분 구간 안의 5분 관측 수
STUCK_MIN = 360       # 충전중이 6시간 넘게 그대로면 센서 멈춤 (가현 님 규칙)


def windows_from_5m(o):
    """occupancy_5m 표 -> 30분 구간 표. 충전소 x 시점이 빠짐없이 6개씩 묶이는 형태를 가정한다 (README 기준 그렇다)."""
    o = o.sort_values(["station_id", "ts"], kind="stable")
    ts = o["ts"].to_numpy()
    n_st = o["station_id"].nunique()
    n_ts = len(o) // n_st
    if n_st * n_ts != len(o) or n_ts % PTS != 0:
        raise SystemExit(f"충전소 x 시점이 빠짐없이 채워진 표가 아닙니다 ({len(o):,}행, 충전소 {n_st}곳).")
    W = n_ts // PTS
    sid = o["station_id"].to_numpy().reshape(n_st, n_ts)[:, 0]
    t0 = pd.Timestamp(ts[0])
    if t0.minute % 30 != 0:
        raise SystemExit("첫 시점이 00분/30분이 아닙니다. 30분 구간에 맞춰 잘라 주세요.")

    def arr(col, dtype="float32"):
        return o[col].to_numpy(dtype=dtype, na_value=np.nan).reshape(n_st, W, PTS)

    valid = o["valid"].to_numpy().reshape(n_st, W, PTS) == 1
    a, c, unk = arr("available"), arr("charging"), arr("unknown")
    una = arr("unavailable")
    den = a + c
    rate = np.where(valid & (den > 0), c / np.where(den > 0, den, np.nan), np.nan).astype("float32")

    obs = valid.sum(axis=2)
    cnt = np.isfinite(rate).sum(axis=2)
    import warnings
    warnings.simplefilter("ignore", RuntimeWarning)           # 전부 빈 구간의 평균·최댓값 경고는 정상 (결과는 빈칸)
    with np.errstate(all="ignore"):
        occ = np.nanmean(rate, axis=2)
    occ = np.where((obs >= 3) & (cnt > 0), occ, np.nan)
    full_n = np.nansum(np.where(rate >= 0.9999, 1.0, 0.0), axis=2)

    # 구간 마지막 유효 관측
    last = PTS - 1 - np.argmax(valid[:, :, ::-1], axis=2)
    has = obs > 0
    ii, jj = np.meshgrid(np.arange(n_st), np.arange(W), indexing="ij")
    a_l, c_l, u_l, n_l = (x[ii, jj, last] for x in (a, c, unk, una))
    av = np.where(~has, np.nan, np.where(a_l > 0, 1.0, np.where(u_l > 0, np.nan, 0.0)))

    with np.errstate(all="ignore"):
        occ_first = np.nanmin(np.where(np.cumsum(np.isfinite(rate), axis=2) == 1, rate, np.inf), axis=2)
        occ_max = np.nanmax(rate, axis=2)
    occ_first = np.where(np.isfinite(occ_first), occ_first, np.nan)
    occ_last = rate[ii, jj, last]

    wq = np.where(np.isnan(av), "UNKNOWN", np.where((av == 0) & np.isnan(occ), "FAULT", "OK"))
    fcc = o["fast_charger_count"].to_numpy().reshape(n_st, W, PTS)[:, :, 0]

    wstart = t0 + pd.to_timedelta(np.arange(W) * 30, unit="m")
    out = pd.DataFrame({
        "station_id": np.repeat(sid, W),
        "window_start": np.tile(wstart, n_st),
        "fast_charger_count": fcc.ravel(),
        "occupancy_rate": occ.ravel(),
        "available": av.ravel(),
        "full_minutes": (full_n * 5).ravel(),
        "valid_minutes": (obs * 5).ravel(),
        "observed_points": obs.ravel(),
        "window_quality": wq.ravel(),
        "f5_nfree_last": np.where(has, a_l, np.nan).ravel(),
        "f5_ncharg_last": np.where(has, c_l, np.nan).ravel(),
        "f5_nunk_last": np.where(has, u_l, np.nan).ravel(),
        "f5_occ_last": occ_last.ravel(),
        "f5_occ_first": occ_first.ravel(),
        "f5_occ_max": occ_max.ravel(),
    })
    out.loc[out["observed_points"] == 0, "full_minutes"] = np.nan
    return out


def charging_duration_features(raw, chargers, windows):
    """
    충전기별 상태 변화 원본에서, 각 30분 구간의 마지막 5분 시점(구간 시작 + 25분)에
    '서버가 이미 받아 둔 기록(collected_at <= 시점 + 1분)'만으로 충전 경과 시간 변수를 만든다.
    windows: 구간 시작 시각 목록(정렬됨)
    """
    t_start = time.time()
    r = raw[["charger_id", "status_code", "status_updated_at", "collected_at"]].copy()
    r["status_code"] = r["status_code"].astype("int16")
    for c in ("status_updated_at", "collected_at"):          # pandas 버전에 따라 시각 단위가 달라 병합이 막히는 것을 방지
        r[c] = r[c].astype("datetime64[ns]")
    r = r.sort_values(["collected_at", "status_updated_at"], kind="stable").reset_index(drop=True)

    ch = chargers[["charger_id", "station_id"]].drop_duplicates("charger_id")
    n_ch, W = len(ch), len(windows)
    T = pd.DatetimeIndex(windows).astype("datetime64[ns]") + pd.Timedelta(minutes=25)
    left = pd.DataFrame({
        "T": np.repeat(T.to_numpy(), n_ch),                       # 시간이 바깥 반복 -> T 오름차순
        "Tp": np.repeat((T + pd.Timedelta(seconds=60)).to_numpy(), n_ch),
        "charger_id": np.tile(ch["charger_id"].to_numpy(), W),
        "station_id": np.tile(ch["station_id"].to_numpy(), W),
        "w": np.repeat(np.arange(W), n_ch),
    })
    m = pd.merge_asof(left, r.rename(columns={"collected_at": "Tp"}), on="Tp", by="charger_id", direction="backward")
    code = m["status_code"].to_numpy()
    el = ((m["T"] - m["status_updated_at"]).dt.total_seconds() / 60.0).clip(lower=0).to_numpy()
    is_chg = (code == 3) & (el <= STUCK_MIN)
    is_av = (code == 2)
    key = pd.Index(sorted(ch["station_id"].unique())).get_indexer(m["station_id"]) * W + m["w"].to_numpy()
    d = pd.DataFrame({
        "key": key,
        "chg": np.where(is_chg, el, np.nan),
        "idle": np.where(is_av, np.minimum(el, 1440), np.nan),
        "since": np.where(np.isfinite(el), np.minimum(el, 1440), np.nan),
        "lt10": (is_chg & (el < 10)).astype("int8"),
        "ge30": (is_chg & (el >= 30)).astype("int8"),
    })
    g = d.groupby("key")
    agg = pd.DataFrame({
        "f5_chg_dur_max": g["chg"].max(),
        "f5_chg_dur_mean": g["chg"].mean(),
        "f5_chg_n_lt10": g["lt10"].sum(),
        "f5_chg_n_ge30": g["ge30"].sum(),
        "f5_idle_max": g["idle"].max(),
        "f5_since_change": g["since"].min(),
    })
    st_ids = np.array(sorted(ch["station_id"].unique()))
    agg["station_id"] = st_ids[agg.index.to_numpy() // W]
    agg["window_start"] = pd.DatetimeIndex(windows)[agg.index.to_numpy() % W]
    print(f"충전 경과시간 변수 계산 완료 ({time.time() - t_start:.0f}초)")
    return agg.reset_index(drop=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--occ", required=True, help="occupancy_5m.csv.gz")
    ap.add_argument("--raw", default=None, help="status_history_raw.csv.gz (충전 경과시간 변수를 만들 때)")
    ap.add_argument("--chargers", default=None, help="chargers.csv (--raw 와 함께)")
    ap.add_argument("--out", default="data30.pkl", help="저장 파일 (.pkl 권장: 빠르게 읽힘)")
    a = ap.parse_args()

    t0 = time.time()
    dt = {"station_id": "int32", "valid": "int8", "fast_charger_count": "int16", "available": "float32",
          "charging": "float32", "unavailable": "float32", "unknown": "float32"}
    o = pd.read_csv(a.occ, usecols=list(dt) + ["ts"], dtype=dt, parse_dates=["ts"])
    print(f"5분 데이터 읽기: {len(o):,}행 ({time.time() - t0:.0f}초)")
    w = windows_from_5m(o)
    del o
    print(f"30분 구간 표: {len(w):,}행, 충전소 {w['station_id'].nunique()}곳 ({time.time() - t0:.0f}초)")
    print(w["window_quality"].value_counts(normalize=True).round(4).to_dict())

    if a.raw:
        if not a.chargers:
            raise SystemExit("--raw 를 쓰려면 --chargers 도 필요합니다.")
        raw = pd.read_csv(a.raw, usecols=["charger_id", "status_code", "status_updated_at", "collected_at"],
                          parse_dates=["status_updated_at", "collected_at"])
        chg = pd.read_csv(a.chargers)
        chg = chg[chg["station_id"].isin(w["station_id"].unique())]
        print(f"상태 변화 원본 읽기: {len(raw):,}줄 ({time.time() - t0:.0f}초)")
        feats = charging_duration_features(raw, chg, np.sort(w["window_start"].unique()))
        w = w.merge(feats, on=["station_id", "window_start"], how="left")
    if a.chargers:                                   # 충전소별 최대 출력(kW) — 학습 코드의 static 그룹이 쓴다
        kw = pd.read_csv(a.chargers).groupby("station_id")["output_kw"].max()
        w["max_output_kw"] = w["station_id"].map(kw)
    if a.out.endswith(".pkl"):
        w.to_pickle(a.out)
    else:
        w.to_csv(a.out, index=False)
    print(f"저장: {a.out}  (열 {len(w.columns)}개, 총 {time.time() - t0:.0f}초)")


if __name__ == "__main__":
    main()
