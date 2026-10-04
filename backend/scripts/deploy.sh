#!/usr/bin/env bash
# EC2 수집 서버에 현재 커밋(HEAD)의 backend를 배포한다. (macOS/Linux용, deploy.ps1과 같은 동작)
#   사용: backend/scripts/deploy.sh [ubuntu@<IP>]
# 1) HEAD를 압축해 서버로 올리고 이미지를 빌드한다 (이 동안 기존 백엔드는 계속 수집한다)
# 2) 수집 회차(:x0, :x5)가 끝난 직후에 컨테이너를 교체한다 (.env와 DB 볼륨은 유지)
#
# 서버 주소는 공개 레포에 두지 않는다. 인자를 주지 않으면 다음 순서로 찾는다.
#   환경변수 EVISION_DEPLOY_SERVER → backend/scripts/deploy-target.txt (Git에 올라가지 않음, 한 줄: ubuntu@<IP>)
# 개인키 경로는 환경변수 EVISION_DEPLOY_KEY로 바꿀 수 있다 (기본 ~/.ssh/evision-key).
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
repo="$(git -C "$script_dir" rev-parse --show-toplevel)"
key="${EVISION_DEPLOY_KEY:-$HOME/.ssh/evision-key}"

server="${1:-${EVISION_DEPLOY_SERVER:-}}"
target_file="$script_dir/deploy-target.txt"
if [[ -z "$server" && -f "$target_file" ]]; then
  server="$(head -n 1 "$target_file" | tr -d '\r[:space:]')"
fi
if [[ -z "$server" ]]; then
  echo "배포할 서버 주소가 없습니다. $target_file 파일에 'ubuntu@<서버 IP>' 한 줄을 적거나 인자로 넘기세요." >&2
  exit 1
fi
tar_file="$(mktemp -t evision-backend).tar"

dirty="$(git -C "$repo" status --porcelain -- backend)"
if [[ -n "$dirty" ]]; then echo "경고: 커밋되지 않은 변경은 배포되지 않습니다:"; echo "$dirty"; fi
echo "deploy: $(git -C "$repo" log --oneline -n 1)"

git -C "$repo" archive --format=tar -o "$tar_file" HEAD backend
scp -i "$key" -q "$tar_file" "$server:/tmp/evision-backend.tar"
rm -f "$tar_file"

echo "[$(date +%H:%M:%S)] build on server"
ssh -i "$key" -o BatchMode=yes -o ServerAliveInterval=30 "$server" bash -s <<'EOF'
set -e
cd ~/evision
tar -xf /tmp/evision-backend.tar && rm /tmp/evision-backend.tar
cd backend
sudo docker compose -f compose.prod.yaml build app 2>&1 | tail -2
EOF

# 수집 회차가 끝난 뒤(분 % 5 == 1, 10초)에 교체한다. 한국 시간은 UTC와 정시 단위로 차이 나므로 epoch 기준 5분 정렬과 같다.
now=$(date +%s)
slot=$(( now - now % 300 + 70 ))
if (( slot <= now )); then slot=$(( slot + 300 )); fi
echo "waiting $(( slot - now )) s"
sleep $(( slot - now ))

echo "[$(date +%H:%M:%S)] restart app"
ssh -i "$key" -o BatchMode=yes "$server" bash -s <<'EOF'
set -e
cd ~/evision/backend
sudo docker compose -f compose.prod.yaml up -d app 2>&1 | tail -1
for i in $(seq 1 30); do
  if sudo docker compose -f compose.prod.yaml logs --since 2m app 2>&1 | grep -q 'Started BackendApplication'; then echo started; break; fi
  sleep 2
done
sudo docker compose -f compose.prod.yaml ps --format '{{.Service}} {{.Status}}'
EOF
echo "[$(date +%H:%M:%S)] done"
