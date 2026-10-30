"""
train_xgb.py 의 데이터 처리·분할·평가 로직 단위 테스트
(모델 학습 자체는 검증하지 않는다. 같은 폴더에 train_xgb.py 를 두고 `pytest -q` 로 실행)
"""
import numpy as np
import pandas as pd
import pytest

import train_xgb as T


def make_df(n_steps=400, stations=(1, 2), drop=None):
    """occupancy_rate 가 '구간 번호/1000'이 되는 단순 데이터 -> 값으로 위치를 바로 알 수 있다."""
    times = pd.date_range("2026-09-01", periods=n_steps, freq="30min")
    rows = []
    for sid in stations:
        d = pd.DataFrame({
            "station_id": sid,
            "window_start": times,
            "occupancy_rate": np.arange(n_steps) / 1000.0,
            "available": True,
        })
        rows.append(d)
    df = pd.concat(rows, ignore_index=True)
    if drop is not None:
        df = df[~((df["station_id"] == stations[0]) & (df.index % n_steps == drop))]
    return df


def build(df, h=1):
    return T.build_dataset(df, h, set())


def test_label_is_h_steps_ahead():
    d = build(make_df(), h=1)
    r = d[(d.station_id == 1) & (d.window_start == pd.Timestamp("2026-09-01 10:00"))].iloc[0]
    pos = int((r.window_start - pd.Timestamp("2026-09-01")) / pd.Timedelta(minutes=30))
    assert r.y_occ == pytest.approx((pos + 1) / 1000.0)
    assert r.occ_lag0 == pytest.approx(pos / 1000.0)


def test_horizon_2_means_60_minutes():
    d = build(make_df(), h=2)
    r = d[(d.station_id == 1) & (d.window_start == pd.Timestamp("2026-09-01 10:00"))].iloc[0]
    pos = int((r.window_start - pd.Timestamp("2026-09-01")) / pd.Timedelta(minutes=30))
    assert r.y_occ == pytest.approx((pos + 2) / 1000.0)
    assert r.target_time == r.window_start + pd.Timedelta(minutes=60)


def test_missing_window_keeps_time_alignment():
    """한 구간이 통째로 없어도 shift 가 '행 순서'가 아니라 '실제 시간'을 따라야 한다."""
    full = make_df()
    gap = full[(full.station_id == 1) & (full.window_start == pd.Timestamp("2026-09-01 10:30"))].index
    d = build(full.drop(gap), h=1)
    r = d[(d.station_id == 1) & (d.window_start == pd.Timestamp("2026-09-01 10:00"))].iloc[0]
    assert np.isnan(r.y_occ)            # 다음 구간(10:30)이 없으므로 레이블도 없어야 한다
    r2 = d[(d.station_id == 1) & (d.window_start == pd.Timestamp("2026-09-01 11:00"))].iloc[0]
    assert np.isnan(r2.occ_lag1)        # 한 구간 전(10:30)이 없으므로 NaN


def test_features_do_not_use_future_rows():
    """기준 시각 t 이후 데이터를 지워도 t 행의 입력 변수는 그대로여야 한다 (누수 방지)."""
    full = make_df(n_steps=500)
    t = pd.Timestamp("2026-09-08 12:00")
    feat_cols = ["occ_lag0", "occ_lag1", "occ_lag2", "occ_lag5", "occ_mean6",
                 "occ_trend", "occ_yesterday", "target_slot", "target_dow"]
    a = build(full, h=1)
    b = build(full[full.window_start <= t], h=1)
    ra = a[(a.station_id == 1) & (a.window_start == t)][feat_cols].iloc[0]
    rb = b[(b.station_id == 1) & (b.window_start == t)][feat_cols].iloc[0]
    pd.testing.assert_series_equal(ra, rb)


def test_time_split_is_ordered_and_has_gap():
    d = build(make_df(n_steps=600), h=2).dropna(subset=["y_occ"])
    tr, va, te = T.time_split(d, horizon_steps=2)
    assert tr.window_start.max() < va.window_start.min() < te.window_start.min()
    gap = pd.Timedelta(minutes=30 * 2)
    assert va.window_start.min() - tr.window_start.max() >= gap
    assert len(tr) > len(va) > 0 and len(te) > 0


def test_baseline_has_no_nan_for_unseen_station():
    df = T.make_demo(n_station=6, weeks=3)
    d = build(df).dropna(subset=["y_occ", "y_av"])
    tr, va, te = T.time_split(d, 1)
    base = T.Baseline().fit(tr)
    unseen = te.copy()
    unseen["station_id"] = 9999          # 학습에 없던 충전소
    occ, av = base.predict(unseen)
    assert not np.isnan(occ).any() and not np.isnan(av).any()


def test_threshold_meets_precision_when_possible():
    rng = np.random.default_rng(0)
    y = (rng.random(4000) < 0.9).astype(int)
    prob = np.clip(y * 0.6 + rng.random(4000) * 0.4, 0, 1)   # 구분이 잘 되는 점수
    thr, ok = T.pick_threshold(y, prob, 0.90)
    pred = prob >= thr
    precision = (y[pred] == 1).mean()
    assert ok and precision >= 0.90


def test_threshold_reports_when_target_unreachable():
    y = np.array([1, 0] * 200)
    prob = np.full(400, 0.5)                                  # 구분 불가능한 점수
    thr, ok = T.pick_threshold(y, prob, 0.99)
    assert ok is False


def test_demo_dataset_is_valid():
    d = build(T.make_demo(n_station=5, weeks=3)).dropna(subset=["y_occ", "y_av"])
    assert d["y_occ"].between(0, 1).all()
    assert set(d["y_av"].unique()) <= {0.0, 1.0}
    assert d["target_slot"].between(0, 47).all()
