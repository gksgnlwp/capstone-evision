"""export_recent.py / upsert_forecast.py 테스트. DB 없이 도는 계산·검사 테스트.
DB 연동까지 보려면 EVISION_TEST_DB_URL(빈 테스트용 DB, 마이그레이션 적용됨)을 주면 마지막 테스트가 돈다."""
import os

import numpy as np
import pandas as pd
import pytest

import export_recent as E
import prep_5m
import upsert_forecast as U

T0 = pd.Timestamp("2026-11-24 10:00")


def ts(m):
    return T0 + pd.Timedelta(minutes=m)


# --------------------------------------------------------------------------- 유효 시점
def test_valid_point_window_is_open_before_and_closed_after():
    pts = [ts(10)]
    assert not E.valid_points(pts, [ts(0)])[0]                                   # 정확히 10분 전: 제외
    assert E.valid_points(pts, [ts(0) + pd.Timedelta(seconds=1)])[0]              # 10분 안
    assert E.valid_points(pts, [ts(11)])[0]                                       # 1분 뒤까지 허용
    assert not E.valid_points(pts, [ts(11) + pd.Timedelta(seconds=1)])[0]
    assert not E.valid_points(pts, [])[0]


# --------------------------------------------------------------------------- 시점 상태
def _hist(rows):
    return pd.DataFrame(rows, columns=["charger_id", "status_updated_at", "normalized_status"])


def test_point_status_uses_latest_change_at_or_before_t():
    ch = pd.DataFrame({"charger_id": [1], "station_id": [10]})
    h = _hist([(1, ts(-60), "AVAILABLE"), (1, ts(5), "CHARGING")])
    c = E.point_counts([ts(0), ts(5)], ch, h).set_index("ts")
    assert c.loc[ts(0), ["available", "charging"]].tolist() == [1, 0]
    assert c.loc[ts(5), ["available", "charging"]].tolist() == [0, 1]           # 같은 시각 변경은 반영


def test_no_history_or_unknown_code_counts_as_unknown():
    ch = pd.DataFrame({"charger_id": [1, 2], "station_id": [10, 10]})
    h = _hist([(2, ts(-5), None)])                                                # 코드 사전에 없는 상태
    c = E.point_counts([ts(0)], ch, h).iloc[0]
    assert (c["unknown"], c["available"], c["charging"]) == (2, 0, 0)


def test_charging_longer_than_six_hours_is_unknown():
    ch = pd.DataFrame({"charger_id": [1, 2], "station_id": [10, 10]})
    h = _hist([(1, ts(-360), "CHARGING"), (2, ts(-361), "CHARGING")])          # 정확히 6시간은 아직 충전중
    c = E.point_counts([ts(0)], ch, h).iloc[0]
    assert (c["charging"], c["unknown"]) == (1, 1)


def test_chunking_gives_same_result():
    rng = np.random.default_rng(1)
    ch = pd.DataFrame({"charger_id": range(1, 9), "station_id": [1, 1, 1, 2, 2, 3, 3, 3]})
    h = _hist([(int(rng.integers(1, 9)), ts(int(m)), str(rng.choice(E.STATUSES)))
               for m in rng.integers(-400, 300, 200)]).drop_duplicates(["charger_id", "status_updated_at"])
    pts = pd.date_range(ts(0), ts(295), freq="5min")
    a = E.point_counts(pts, ch, h, chunk=7)
    b = E.point_counts(pts, ch, h, chunk=1000)
    pd.testing.assert_frame_equal(a, b)


def test_occupancy_5m_feeds_prep_5m_with_backend_rules():
    """충전기 4대: 2대 충전, 1대 빈, 1대 고장 → 혼잡도 2/3. 유효 시점 2개뿐인 구간은 빈칸."""
    ch = pd.DataFrame({"charger_id": [1, 2, 3, 4], "station_id": [7] * 4, "output_kw": [100] * 4})
    h = _hist([(1, ts(-30), "CHARGING"), (2, ts(-30), "CHARGING"), (3, ts(-30), "AVAILABLE"),
               (4, ts(-30), "UNAVAILABLE")])
    # 두 번째 구간: 30분(25분 회차로 유효), 55분만 유효 → 2개
    runs = [ts(m) for m in range(0, 30, 5)] + [ts(55)]
    occ5 = E.build_occupancy_5m(ts(0), ts(60), ch, h, runs)
    assert len(occ5) == 12 and occ5["fast_charger_count"].eq(4).all()
    w = prep_5m.windows_from_5m(occ5).set_index("window_start")
    assert w.loc[ts(0), "occupancy_rate"] == pytest.approx(2 / 3, abs=1e-6)
    assert w.loc[ts(0), "observed_points"] == 6 and w.loc[ts(0), "available"] == 1
    assert np.isnan(w.loc[ts(30), "occupancy_rate"]) and w.loc[ts(30), "observed_points"] == 2


def test_export_range_ends_at_last_closed_window():
    s, e = E.export_range(pd.Timestamp("2026-11-24 14:05"), 9)
    assert e == pd.Timestamp("2026-11-24 14:00") and s == e - pd.Timedelta(days=9)
    _, e = E.export_range(pd.Timestamp("2026-11-24 14:01"), 9)                    # 집계 전(2분 여유)
    assert e == pd.Timestamp("2026-11-24 13:30")


# --------------------------------------------------------------------------- 예측 CSV → 테이블
def _pred(**over):
    d = {"station_id": [21, 21], "target_time": ["2026-11-24 14:00:00", "2026-11-24 14:30:00"],
         "horizon_min": [30, 60], "predicted_occupancy_rate": [0.41234, None], "p_available": [0.8, 0.55],
         "model_version": ["xgb-v0.7", "xgb-v0.7"], "predicted_at": ["2026-11-24 14:05:07.5", "2026-11-24 14:05:07.5"]}
    d.update(over)
    return pd.DataFrame(d)


def test_rows_map_columns_to_table():
    r = U.to_rows(_pred())
    assert r[0] == (21, pd.Timestamp("2026-11-24 14:00").to_pydatetime(), 0.8, 0.4123, "xgb-v0.7",
                    pd.Timestamp("2026-11-24 14:05:07").to_pydatetime())
    assert r[1][3] is None                                                        # 사용률 예측은 선택


@pytest.mark.parametrize("over, msg", [
    ({"target_time": ["2026-11-24 14:00", "2026-11-24 14:15"]}, "정각"),
    ({"p_available": [0.8, 1.2]}, "p_available"),
    ({"p_available": [0.8, None]}, "필수 값"),
    ({"predicted_occupancy_rate": [-0.1, 0.2]}, "predicted_occupancy_rate"),
    ({"target_time": ["2026-11-24 14:00", "2026-11-24 14:00"]}, "중복"),
    ({"predicted_at": ["2026-11-24T14:05:00+09:00"] * 2}, "시간대"),
])
def test_invalid_forecast_is_rejected(over, msg):
    with pytest.raises(U.InvalidForecast, match=msg):
        U.to_rows(_pred(**over))


def test_missing_column_is_rejected():
    with pytest.raises(U.InvalidForecast, match="p_available"):
        U.to_rows(_pred().drop(columns="p_available"))


# --------------------------------------------------------------------------- DB (선택)
@pytest.mark.skipif(not os.environ.get("EVISION_TEST_DB_URL"), reason="EVISION_TEST_DB_URL 없음")
def test_upsert_overwrites_and_skips_unknown_station():
    import psycopg2

    conn = psycopg2.connect(os.environ["EVISION_TEST_DB_URL"])
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT station_id FROM station ORDER BY station_id LIMIT 1")
            sid = cur.fetchone()[0]
        first = U.to_rows(_pred(station_id=[sid, 999999999]))
        second = U.to_rows(_pred(station_id=[sid, sid], p_available=[0.3, 0.2],
                                 target_time=["2026-11-24 14:00", "2026-11-24 14:30"]))
        assert U.upsert(conn, first) == (1, 1)
        assert U.upsert(conn, second) == (2, 0)
        with conn.cursor() as cur:
            cur.execute("SELECT target_window_start, p_available FROM occupancy_forecast "
                        "WHERE station_id = %s AND target_window_start::date = '2026-11-24' ORDER BY 1", (sid,))
            got = [(str(t), float(p)) for t, p in cur.fetchall()]
        assert got == [("2026-11-24 14:00:00", 0.3), ("2026-11-24 14:30:00", 0.2)]
    finally:
        conn.rollback()
        conn.close()


# --------------------------------------------------------------------------- 메모리 절약 실행 (predict_lowmem.py)
def test_chunked_prep_equals_prep_5m(tmp_path):
    """충전소 묶음으로 나눠 만든 30분 표가 prep_5m 으로 한 번에 만든 표와 같다."""
    import predict_lowmem as L

    rng = np.random.default_rng(5)
    ch = pd.DataFrame({"charger_id": range(1, 13), "station_id": np.repeat([3, 5, 8, 9], 3), "output_kw": 100})
    hist = pd.DataFrame({"charger_id": rng.integers(1, 13, 300),
                         "status_updated_at": [ts(int(m)) for m in rng.integers(-200, 590, 300)],
                         "normalized_status": rng.choice(E.STATUSES, 300)}).drop_duplicates(["charger_id", "status_updated_at"])
    hist["status_code"] = hist["normalized_status"].map({"AVAILABLE": 2, "CHARGING": 3, "UNAVAILABLE": 5, "UNKNOWN": 9})
    hist["collected_at"] = hist["status_updated_at"] + pd.Timedelta(minutes=2)
    occ5 = E.build_occupancy_5m(ts(0), ts(600), ch, hist, [ts(m) for m in range(0, 600, 5)])
    occ5.to_csv(tmp_path / "occupancy_5m.csv.gz", index=False)
    hist[["charger_id", "status_code", "status_updated_at", "collected_at"]].to_csv(tmp_path / "status_history_raw.csv.gz", index=False)
    ch.to_csv(tmp_path / "chargers.csv", index=False)

    o = pd.read_csv(tmp_path / "occupancy_5m.csv.gz", parse_dates=["ts"])
    w = prep_5m.windows_from_5m(o)
    raw = pd.read_csv(tmp_path / "status_history_raw.csv.gz", parse_dates=["status_updated_at", "collected_at"])
    full = w.merge(prep_5m.charging_duration_features(raw, ch, np.sort(w["window_start"].unique())),
                   on=["station_id", "window_start"], how="left")
    full["max_output_kw"] = full["station_id"].map(ch.groupby("station_id")["output_kw"].max())

    got = L.build_data30(tmp_path, chunk_stations=1)
    k = ["station_id", "window_start"]
    a = full.sort_values(k).reset_index(drop=True)
    b = got.sort_values(k).reset_index(drop=True)[a.columns].astype(a.dtypes.to_dict())
    pd.testing.assert_frame_equal(a, b)
