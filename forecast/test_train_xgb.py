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


def test_load_csv_reads_t_f_and_blank(tmp_path):
    """백엔드가 준 실제 형식(available = t / f / 빈칸)을 읽을 수 있어야 한다."""
    csv = tmp_path / "occ.csv"
    csv.write_text(
        "station_id,window_start,fast_charger_count,occupancy_rate,available\n"
        "1,2026-10-01 00:00:00,4,0.25,t\n"
        "1,2026-10-01 00:30:00,4,1.0,f\n"
        "1,2026-10-01 01:00:00,4,,\n"
    )
    acc = tmp_path / "acc.csv"
    acc.write_text("station_id,access_type\n1,IC\n1,REST_AREA\n")
    df = T.load_csv(str(csv), str(acc))
    assert df["available"].iloc[0] == 1.0
    assert df["available"].iloc[1] == 0.0
    assert pd.isna(df["available"].iloc[2])
    assert (df["station_type"] == "REST_AREA").all()      # 둘 다 연결되면 휴게소로 본다


# ----------------------------------------------------------------------------- v0.2: 변수 그룹
def make_group_df(n_steps=500, seed=3):
    """full / fac / static 변수 그룹 테스트용 데이터 (충전소 1·2는 같은 휴게소, 3은 다른 곳)."""
    rng = np.random.default_rng(seed)
    times = pd.date_range("2026-09-01", periods=n_steps, freq="30min")
    rows = []
    for sid, fk in ((1, "100|A휴게소|상행"), (2, "100|A휴게소|상행"), (3, "100|B휴게소|상행")):
        occ = np.clip(rng.random(n_steps), 0, 1)
        rows.append(pd.DataFrame({
            "station_id": sid, "window_start": times,
            "occupancy_rate": occ,
            "available": (occ < 0.9),
            "full_minutes": (occ >= 0.9) * 30.0,
            "fast_charger_count": 4, "max_output_kw": 100, "detour_km": 0.0,
            "fac_key": fk, "route_key": "100", "station_type": "REST_AREA",
        }))
    return pd.concat(rows, ignore_index=True)


def test_group_features_do_not_use_future_rows():
    """full·fac·static 그룹의 변수도 기준 시각 t 이후 데이터를 지워도 t 행의 값이 같아야 한다."""
    full = make_group_df()
    t = pd.Timestamp("2026-09-08 12:00")
    a = T.build_dataset(full, 1, set(), T.ALL_GROUPS)
    b = T.build_dataset(full[full.window_start <= t], 1, set(), T.ALL_GROUPS)
    cols = [c for g in ("full", "fac", "static") for c in T.GROUP_COLS[g]]
    assert set(cols) <= set(a.columns)
    for sid in (1, 2, 3):
        ra = a[(a.station_id == sid) & (a.window_start == t)][cols].iloc[0]
        rb = b[(b.station_id == sid) & (b.window_start == t)][cols].iloc[0]
        pd.testing.assert_series_equal(ra, rb, check_names=False)


def test_full_streak_counts_consecutive_unavailable():
    times = pd.date_range("2026-09-01", periods=6, freq="30min")
    av = [True, False, False, True, False, False]
    df = pd.DataFrame({"station_id": 1, "window_start": times, "occupancy_rate": 0.5, "available": av,
                       "full_minutes": [0.0, 30, 30, 0, 30, 30]})
    d = T.build_dataset(df, 1, set(), ("full",)).sort_values("window_start")
    assert d["full_streak"].tolist() == [0, 1, 2, 0, 1, 2]
    assert d["unavail_cnt6"].iloc[-1] == 4


def test_fac_others_excludes_self():
    times = pd.date_range("2026-09-01", periods=3, freq="30min")
    rows = []
    for sid, occ in ((1, 0.2), (2, 0.6), (3, 0.9)):
        rows.append(pd.DataFrame({"station_id": sid, "window_start": times, "occupancy_rate": occ, "available": True,
                                  "fac_key": "k" if sid != 3 else "other", "route_key": "r"}))
    d = T.build_dataset(pd.concat(rows), 1, set(), ("fac",))
    r = d[(d.station_id == 1) & (d.window_start == times[0])].iloc[0]
    assert r.fac_others_occ0 == pytest.approx(0.6)          # 같은 휴게소의 '다른' 충전소만
    assert r.fac_others_n == 1
    r3 = d[(d.station_id == 3) & (d.window_start == times[0])].iloc[0]
    assert np.isnan(r3.fac_others_occ0)                     # 혼자인 충전소는 비교 대상 없음


def test_select_features_drops_unselected_groups():
    cols = ["station_id", "occ_lag0", "full_lag0", "fac_others_occ0", "charger_cnt", "slot_mean", "y_occ"]
    assert T.select_features(cols, ()) == ["occ_lag0"]
    assert T.select_features(cols, ("full",)) == ["occ_lag0", "full_lag0"]
    assert set(T.select_features(cols, "all")) == {"occ_lag0", "full_lag0", "fac_others_occ0", "charger_cnt", "slot_mean"}


def test_train_stats_use_only_given_rows():
    """학습 통계(충전소별 만차 비율 등)는 넘겨준 학습 구간만으로 계산한다."""
    d = build(T.make_demo(n_station=5, weeks=3)).dropna(subset=["y_occ", "y_av"])
    tr, va, te = T.time_split(d, 1)
    s = T.make_train_stats(tr)
    sid = tr.station_id.iloc[0]
    assert s["st_full"][sid] == pytest.approx(float((tr[tr.station_id == sid].y_av == 0).mean()))


def test_load_station_ids_reads_station_id_column_or_first(tmp_path):
    a = tmp_path / "a.csv"
    a.write_text("name,station_id\nx,5\ny,7\n")
    assert T.load_station_ids(str(a)) == {5, 7}
    b = tmp_path / "b.csv"
    b.write_text("id,reason\n3,아파트\n4,차고지\n")        # station_id 컬럼이 없으면 첫 컬럼
    assert T.load_station_ids(str(b)) == {3, 4}


def test_sample_configs_includes_default_and_is_unique():
    cfgs = T.sample_configs(6, seed=0)
    assert cfgs[0] == {} and len(cfgs) == 6
    keys = [tuple(sorted(c.items())) for c in cfgs]
    assert len(set(keys)) == 6
    for c in cfgs[1:]:
        assert all(c[k] in T.TUNE_GRID[k] for k in c)


def test_threshold_f1_mode_ignores_precision_target():
    # 이용가능 정밀도 0.99를 요구해도 f1 모드는 조건 없이 만차 F1이 최대인 값을 고른다
    rng = np.random.default_rng(0)
    y = (rng.random(2000) > 0.1).astype(int)
    prob = np.clip(y * 0.5 + rng.normal(0.25, 0.2, 2000), 0, 1)
    thr, ok = T.pick_threshold(y, prob, 0.99, mode="f1")
    assert ok is True
    f_at = lambda t: T.cls_report(y, prob, t)["만차 F1"]
    assert f_at(thr) >= max(f_at(t) for t in np.linspace(0.05, 0.95, 91)) - 1e-9


def test_clean_labels_modes():
    df = pd.DataFrame({
        "station_id": [1] * 5,
        "window_start": pd.date_range("2026-10-01", periods=5, freq="30min"),
        "available": ["t", "f", "f", "f", "t"],
        "occupancy_rate": [0.0, np.nan, 0.5, 1.0, 0.2],
    })
    assert T.clean_labels(df, "none")["available"].tolist() == ["t", "f", "f", "f", "t"]
    n = T.clean_labels(df, "noocc")["available"]
    assert n.isna().tolist() == [False, True, False, False, False]      # 점유 값 없는 불가만 알 수 없음
    assert n.tolist()[2] == 0 and n.tolist()[3] == 0
    s = T.clean_labels(df, "strict")["available"]
    assert s.isna().tolist() == [False, True, True, False, False]       # 점유율 100% 미만 불가도 알 수 없음
    assert s.tolist()[3] == 0                                           # 100%인 만차만 남는다
    assert df["available"].tolist() == ["t", "f", "f", "f", "t"]        # 원본은 바뀌지 않는다


def test_apply_quality_masks_bad_windows_and_drops_low_stations():
    df = pd.DataFrame({
        "station_id": [1, 1, 1, 2, 2, 2],
        "window_start": list(pd.date_range("2026-10-01", periods=3, freq="30min")) * 2,
        "available": [1.0, 0.0, np.nan, 1.0, 0.0, 1.0],
        "occupancy_rate": [0.1, np.nan, 0.3, 0.2, 1.0, 0.0],
        "full_minutes": [0, 0, 5, 0, 30, 0],
        "window_quality": ["OK", "FAULT", "UNKNOWN", "OK", "OK", "OK"],
        "data_quality": ["OK", "OK", "OK", "LOW", "LOW", "LOW"],
    })
    r = T.apply_quality(df)
    assert set(r["station_id"]) == {1}                                  # LOW 충전소는 빠진다
    assert len(r) == 3                                                  # 구간 행은 지우지 않는다(시간 계산 유지)
    assert r["available"].tolist()[:1] == [1.0] and r["available"].iloc[1:].isna().all()
    assert r["occupancy_rate"].iloc[1:].isna().all() and r["full_minutes"].iloc[1:].isna().all()
    k = T.apply_quality(df, keep_fault=True, keep_low=True)
    assert len(k) == 6 and np.array_equal(k["available"].to_numpy(), df["available"].to_numpy(), equal_nan=True)   # 정리하지 않으면 그대로
    old = df.drop(columns=["window_quality", "data_quality"])
    assert T.apply_quality(old).equals(old)                                         # 예전 데이터는 영향 없음


def test_target_full_min_redefines_label_without_leaking_into_features():
    df = T.make_demo(n_station=6, weeks=3, seed=3)
    rng = np.random.default_rng(0)
    df["full_minutes"] = rng.choice([0, 0, 0, 5, 10, 30], size=len(df)).astype(float)
    p0 = T.prepare(df, 1, set(), "all", min_full=0)
    p5 = T.prepare(df, 1, set(), "all", min_full=10)
    tr = p5["tr"]
    feats = T.select_features(tr.columns, "all")
    assert "y_fm" not in feats and "y_av" not in feats                 # 정답 컬럼이 변수로 들어가지 않는다
    ok = tr["y_fm"].notna()
    assert ((tr.loc[ok, "y_fm"] < 10).astype(float) == tr.loc[ok, "y_av"]).all()   # 정의대로 라벨이 만들어진다
    assert (p0["tr"]["y_av"].mean() - p5["tr"]["y_av"].mean()) != 0       # 정의를 바꾸면 만차 비율이 달라진다


# --------------------------------------------------------------------------- 11/13 5분 데이터 · 쾌적 이용 · 추천 순위
def test_norm_groups_all_does_not_include_five():
    assert "five" not in T._norm_groups("all")
    g = T._norm_groups("all,five")
    assert set(g) == {"full", "fac", "static", "hist", "five"}


def test_five_group_uses_value_at_t_and_is_dropped_when_not_selected():
    df = make_df(n_steps=100)
    df["f5_x"] = np.arange(len(df)) % 100 * 10.0                       # 구간 번호 x 10
    d = T.build_dataset(df, 1, set(), ("five",))
    r = d[(d.station_id == 1) & (d.window_start == pd.Timestamp("2026-09-01 10:00"))].iloc[0]
    pos = int((r.window_start - pd.Timestamp("2026-09-01")) / pd.Timedelta(minutes=30))
    assert r.f5_x == pytest.approx(pos * 10.0)                         # 한 구간 뒤 값이 아니다 (누수 없음)
    assert "f5_x" in T.select_features(d.columns, ("five",))
    assert "f5_x" not in T.select_features(d.columns, ("full", "fac"))
    d0 = T.build_dataset(df, 1, set(), ())
    assert "f5_x" not in d0.columns                                   # 그룹을 안 고르면 아예 만들지 않는다


def test_comfort_label_is_occupancy_threshold_and_not_a_feature():
    df = make_df(n_steps=400)
    h = 1
    prep = T.prepare(df, h, set(), (), 0, comfort_thr=0.2)
    for part in ("tr", "va", "te"):
        d = prep[part]
        assert np.array_equal(d["y_av"].to_numpy(), (d["y_occ"] < 0.2).astype(float).to_numpy())
    assert "y_av" not in T.select_features(prep["tr"].columns, ())
    assert set(prep["tr"]["y_av"].unique()) <= {0.0, 1.0}


def test_comfort_report_and_macro_thr():
    y = np.array([1] * 90 + [0] * 10)
    p = np.array([0.9] * 85 + [0.2] * 5 + [0.1] * 8 + [0.8] * 2)       # 불쾌적 10건 중 8건을 낮은 확률로 맞힘
    thr = T.best_macro_thr(y, p)
    rep = T.comfort_report(y, p, thr)
    assert rep["불쾌적 재현율"] >= 0.8 and rep["macro F1"] > 0.8
    assert rep["불쾌적 비율"] == pytest.approx(0.1)


def test_windows_from_5m_follows_server_rules():
    """6개씩 묶기: 혼잡도 평균(유효 3개 이상), 마지막 유효 관측의 빈 충전기, 만차 시간, 품질 구분."""
    import prep_5m as P
    ts = pd.date_range("2026-10-01 00:00", periods=12, freq="5min")        # 30분 구간 2개
    def st(sid, av, ch, un=0, uk=0, valid=1):
        return [dict(station_id=sid, ts=t, valid=valid, fast_charger_count=2, available=a, charging=c, unavailable=un, unknown=uk)
                for t, a, c in zip(ts, av, ch)]
    rows = []
    rows += st(1, [2, 2, 1, 1, 0, 0, 1, 1, 1, 1, 1, 1], [0, 0, 1, 1, 2, 2, 1, 1, 1, 1, 1, 1])      # 구간0: 만차 2개로 끝남
    rows += st(2, [0] * 12, [0] * 12, un=2)                                                         # 전부 고장 -> FAULT
    o = pd.DataFrame(rows)
    w = P.windows_from_5m(o)
    a = w[(w.station_id == 1) & (w.window_start == ts[0])].iloc[0]
    assert a.occupancy_rate == pytest.approx((0 + 0 + 0.5 + 0.5 + 1 + 1) / 6)
    assert a.available == 0 and a.full_minutes == 10 and a.observed_points == 6 and a.window_quality == "OK"
    assert a.f5_nfree_last == 0 and a.f5_ncharg_last == 2 and a.f5_occ_last == 1 and a.f5_occ_first == 0
    b = w[(w.station_id == 1) & (w.window_start == ts[6])].iloc[0]
    assert b.available == 1 and b.occupancy_rate == pytest.approx(0.5)
    f = w[(w.station_id == 2)].iloc[0]
    assert f.window_quality == "FAULT" and f.available == 0 and np.isnan(f.occupancy_rate)


def test_charging_duration_uses_only_records_the_server_already_had():
    import prep_5m as P
    win = np.array([pd.Timestamp("2026-10-01 20:00")], dtype="datetime64[ns]")      # 기준 시점 T = 20:25
    chg = pd.DataFrame({"charger_id": [1, 2, 3], "station_id": [9, 9, 9]})
    raw = pd.DataFrame({
        "charger_id": [1, 2, 3, 3],
        "status_code": [3, 3, 2, 3],
        "status_updated_at": pd.to_datetime(["2026-10-01 20:10:00", "2026-10-01 20:24:00", "2026-10-01 19:00:00", "2026-10-01 20:25:00"]),
        "collected_at": pd.to_datetime(["2026-10-01 20:13:00", "2026-10-01 20:26:30", "2026-10-01 19:02:00", "2026-10-01 20:30:00"]),
    })
    f = P.charging_duration_features(raw, chg, win).iloc[0]
    # 1번: 20:10부터 충전 -> 15분째. 2번·3번의 충전 시작은 20:26 이후에야 서버가 받았으므로 아직 모른다.
    assert f.f5_chg_dur_max == pytest.approx(15.0) and f.f5_chg_n_lt10 == 0 and f.f5_chg_n_ge30 == 0
    assert f.f5_idle_max == pytest.approx(85.0)                                  # 3번 충전기: 19:00부터 비어 있음


def test_rank_eval_prefers_comfortable_candidate_when_nearest_is_blocked(tmp_path):
    st = pd.DataFrame({"station_id": [1, 2, 3], "latitude": [37.000, 37.010, 37.020], "longitude": [127.0] * 3})
    st.to_csv(tmp_path / "s.csv", index=False)
    times = pd.date_range("2026-10-01", periods=20, freq="30min")
    rows = []
    for t_i, t in enumerate(times):
        for sid in (1, 2, 3):
            blocked = (sid == 1 and t_i % 2 == 0)                            # 1번이 절반의 시간에 막힘
            rows.append(dict(station_id=sid, window_start=t, y_av=0.0 if blocked else 1.0,
                             pm=0.1 if blocked else 0.95, pb=0.9))             # AI 는 막힘을 알아챔, 기준 모델은 모름
    d = pd.DataFrame(rows)
    tab, info = T.rank_eval(d, d["pm"], d["pb"], str(tmp_path / "s.csv"), radius_km=10, lambdas=(0.01,), max_cand=3, min_cand=3)
    r = tab.set_index("추천 방식")
    ai, nb, base = r.loc["AI (거리 가중 0.01/km)"], r.loc["가장 가까운 곳"], r.loc["기준 모델 (거리 가중 0.01/km)"]
    assert ai["1위가 쾌적했던 비율↑"] > nb["1위가 쾌적했던 비율↑"] and ai["1위가 쾌적했던 비율↑"] > 0.99
    assert ai["돌린 것 중 실제로 막혔던 비율↑"] == pytest.approx(1.0) and ai["막힌 곳을 알아채 돌린 비율↑"] == pytest.approx(1.0)
    assert base["다른 곳으로 돌린 비율"] == 0.0                                  # 기준 모델은 전부 0.9 라 가까운 곳 유지
    assert info["hard_share"] == pytest.approx(1 / 6)                          # 충전소 3곳 x 20시각 중 1번이 막힌 10건


def test_comfort_sweep_has_one_row_per_threshold_and_share_rises_as_threshold_drops():
    df = T.make_demo(n_station=6, weeks=5, seed=3)
    prep = T.prepare(df, 1, set(), (), 0, 0.8)
    tab = T.run_comfort_sweep(prep, (), [0.3, 0.5, 0.8])
    assert list(tab["쾌적 기준(사용률 미만)"]) == ["30%", "50%", "80%"]
    shares = list(tab["불쾌적 비율"])
    assert shares[0] >= shares[1] >= shares[2]             # 기준이 낮을수록(깐깐할수록) 불쾌적이 많다
    assert prep["te"]["y_av"].isin([0.0, 1.0]).all()       # 원본 prep 는 바뀌지 않는다


def test_rank_eval_margin_stops_unneeded_detours(tmp_path):
    st = pd.DataFrame({"station_id": [1, 2, 3], "latitude": [37.000, 37.010, 37.020], "longitude": [127.0] * 3})
    st.to_csv(tmp_path / "s.csv", index=False)
    times = pd.date_range("2026-10-01", periods=20, freq="30min")
    rows = []
    for t_i, t in enumerate(times):
        for sid in (1, 2, 3):
            # AI 점수: 1번이 0.9, 나머지는 0.95 (아주 작은 차이) -> 여유가 없으면 1번을 두고 다른 곳으로 돈다
            rows.append(dict(station_id=sid, window_start=t, y_av=1.0, pm=0.90 if sid == 1 else 0.95, pb=0.9))
    d = pd.DataFrame(rows)
    tab, _ = T.rank_eval(d, d["pm"], d["pb"], str(tmp_path / "s.csv"), radius_km=10, lambdas=(0.0,), max_cand=3, min_cand=3,
                         margins=(0.0, 0.2))
    r = tab.set_index("추천 방식")
    no_margin = r.loc["AI (거리 가중 0.0/km)"]
    with_margin = r.loc["AI (거리 가중 0.0/km, 여유 0.2 이상일 때만)"]
    assert no_margin["다른 곳으로 돌린 비율"] > 0.2          # 1번이 가까운 곳인 질의에서 작은 차이로 우회
    assert with_margin["다른 곳으로 돌린 비율"] == 0.0        # 여유 0.2 가 없으면 가까운 곳 유지


def test_persistence_comfort_uses_current_occupancy():
    te = pd.DataFrame({"occ_lag0": [0.0, 0.9, np.nan, 0.5], "y_occ": [0.1, 0.9, 0.3, 0.6],
                       "y_av": [1.0, 0.0, 1.0, 1.0]})
    mae, rep, cover = T.persistence_comfort(te, 0.8)
    assert cover == pytest.approx(0.75)                              # 지금 값이 있는 3행만
    assert mae == pytest.approx((0.1 + 0.0 + 0.1) / 3)
    assert rep["불쾌적 재현율"] == 1.0 and rep["불쾌적 정밀도"] == 1.0   # 0.9 -> 불쾌적으로 정확히 맞힘


# --------------------------------------------------------------------------- predict_batch.py
def _train_demo_models(tmp_path, horizons=(1, 2)):
    import sys
    for h in horizons:
        argv_old = sys.argv
        sys.argv = ["train_xgb.py", "--demo", "--groups", "all", "--comfort-thr", "0.5",
                    "--horizon-steps", str(h), "--fast", "--out-dir", str(tmp_path / "models")]
        try:
            T.main()
        finally:
            sys.argv = argv_old


def test_predict_batch_writes_both_horizons_with_server_columns(tmp_path):
    import predict_batch as PB
    _train_demo_models(tmp_path)
    demo = T.make_demo()
    demo.to_csv(tmp_path / "recent.csv", index=False)
    last_t = demo["window_start"].max()
    now = (last_t + pd.Timedelta(minutes=40)).isoformat()
    out_csv = tmp_path / "pred.csv"
    code = PB.main(["--data", str(tmp_path / "recent.csv"), "--models", str(tmp_path / "models"),
                    "--horizons", "1,2", "--out", str(out_csv), "--now", now])
    assert code == 0
    res = pd.read_csv(out_csv, parse_dates=["target_time"])
    assert list(res.columns) == PB.OUT_COLUMNS
    assert set(res["horizon_min"]) == {30, 60}
    assert res["predicted_occupancy_rate"].between(0, 1).all() and res["p_available"].between(0, 1).all()
    h1 = res[res["horizon_min"] == 30]
    h2 = res[res["horizon_min"] == 60]
    assert (h1["target_time"] == last_t + pd.Timedelta(minutes=30)).all()      # 마지막 집계가 13:30~14:00 -> 14:00 구간
    assert (h2["target_time"] == last_t + pd.Timedelta(minutes=60)).all()      # 14:30 구간
    assert h1["station_id"].is_unique and len(h1) > 30


def test_predict_batch_skips_stale_data_and_keeps_previous_file(tmp_path):
    import predict_batch as PB
    _train_demo_models(tmp_path, horizons=(1,))
    demo = T.make_demo()
    demo.to_csv(tmp_path / "recent.csv", index=False)
    old = tmp_path / "pred.csv"
    old.write_text("이전 결과")
    stale_now = (demo["window_start"].max() + pd.Timedelta(hours=5)).isoformat()
    code = PB.main(["--data", str(tmp_path / "recent.csv"), "--models", str(tmp_path / "models"),
                    "--horizons", "1", "--out", str(old), "--now", stale_now])
    assert code == 2
    assert old.read_text() == "이전 결과"          # 직전 결과가 그대로 남아 있다


def test_predict_batch_ignores_untrained_stations(tmp_path):
    import predict_batch as PB
    _train_demo_models(tmp_path, horizons=(1,))
    demo = T.make_demo()
    extra = demo[demo["station_id"] == 0].copy()
    extra["station_id"] = 999                       # 학습에 없던 충전소
    pd.concat([demo, extra]).to_csv(tmp_path / "recent.csv", index=False)
    now = (demo["window_start"].max() + pd.Timedelta(minutes=35)).isoformat()
    out_csv = tmp_path / "pred.csv"
    assert PB.main(["--data", str(tmp_path / "recent.csv"), "--models", str(tmp_path / "models"),
                    "--horizons", "1", "--out", str(out_csv), "--now", now]) == 0
    assert 999 not in set(pd.read_csv(out_csv)["station_id"])


def test_predict_batch_same_numbers_as_training_time_prediction(tmp_path):
    """배치가 만든 값이 학습 스크립트가 마지막에 출력하는 예측과 같아야 한다."""
    import predict_batch as PB
    _train_demo_models(tmp_path, horizons=(1,))
    demo = T.make_demo()
    demo.to_csv(tmp_path / "recent.csv", index=False)
    now = (demo["window_start"].max() + pd.Timedelta(minutes=35)).isoformat()
    out_csv = tmp_path / "pred.csv"
    PB.main(["--data", str(tmp_path / "recent.csv"), "--models", str(tmp_path / "models"),
             "--horizons", "1", "--out", str(out_csv), "--now", now])
    a = pd.read_csv(out_csv).sort_values("station_id").reset_index(drop=True)
    b = pd.read_csv(tmp_path / "models" / "prediction_h1.csv").sort_values("station_id").reset_index(drop=True)
    assert len(a) == len(b)
    assert np.allclose(a["predicted_occupancy_rate"], b["congestion_rate"], atol=1e-4)
    assert np.allclose(a["p_available"], b["available_prob"], atol=1e-4)
