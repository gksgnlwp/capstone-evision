"""
저장된 평가 예측(eval_pred_h*.pkl)으로 '추천 순위 평가'만 다시 한다. 학습은 하지 않으므로 몇 초~1분이면 끝난다.

  python eval_rank.py --pred models_c80/eval_pred_h1.pkl --stations EVision_5m_1113/stations.csv \\
                      --radius 50 --max-cand 30 --lambdas 0.001,0.005 --margins 0,0.1,0.2,0.3

후보 = 각 충전소 주변 반경 radius km 안의 충전소(가까운 순 max-cand 곳). 장거리 이동처럼 '어디서 충전해도 되는' 상황을 흉내 내려면
반경을 넓히고(예: 50km) 거리 가중을 아주 작게(예: 0.001/km) 둔다.
"""
import argparse

import pandas as pd

from train_xgb import rank_eval


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pred", required=True, help="train_xgb.py --rank-eval 이 저장한 eval_pred_h*.pkl")
    ap.add_argument("--stations", required=True, help="stations.csv (위도·경도)")
    ap.add_argument("--radius", type=float, default=10.0)
    ap.add_argument("--max-cand", type=int, default=10)
    ap.add_argument("--lambdas", default="0.005,0.01,0.02,0.05")
    ap.add_argument("--margins", default="0")
    a = ap.parse_args()
    e = pd.read_pickle(a.pred)
    tab, info = rank_eval(e, e["comfort_prob"], e["base_prob"], a.stations, a.radius,
                          tuple(float(x) for x in a.lambdas.split(",")), a.max_cand,
                          margins=tuple(float(x) for x in a.margins.split(",")))
    if not len(tab):
        print("후보를 만들 수 없습니다.")
        return
    for c in tab.columns[1:]:
        tab[c] = tab[c].astype(float).round(3)
    print(f"== 추천 순위 평가 (반경 {a.radius:g}km, 후보 최대 {a.max_cand}곳, 점수 = 쾌적확률 - 거리가중 x km) ==")
    print(tab.to_string(index=False))
    print(f"질의 {info['queries']:,}건, 후보 평균 {info['avg_candidates']:.1f}곳, 가장 가까운 곳이 막혀 있던 질의 {info['hard_share']:.1%}")
    print("※ 후보를 반경으로 잡은 단순 평가입니다. 실제 경로·거리 규칙과는 다를 수 있습니다.")


if __name__ == "__main__":
    main()
