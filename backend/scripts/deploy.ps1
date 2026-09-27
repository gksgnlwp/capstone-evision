# EC2 수집 서버에 현재 커밋(HEAD)의 backend를 배포한다.
#   사용: powershell -File backend\scripts\deploy.ps1 [-Server ubuntu@<IP>] [-Key <개인키 경로>]
# 1) HEAD를 압축해 서버로 올리고 이미지를 빌드한다 (이 동안 기존 백엔드는 계속 수집한다)
# 2) 수집 회차(:x0, :x5)가 끝난 직후에 컨테이너를 교체한다 (.env와 DB 볼륨은 유지)
param(
    [string]$Server = 'ubuntu@43.201.250.140',
    [string]$Key = "$env:USERPROFILE\.ssh\evision-key"
)
$ErrorActionPreference = 'Stop'
$repo = (git rev-parse --show-toplevel).Trim()
$tar = Join-Path $env:TEMP 'evision-backend.tar'

$dirty = git -C $repo status --porcelain -- backend
if ($dirty) { Write-Warning "커밋되지 않은 변경은 배포되지 않습니다:`n$dirty" }
$head = git -C $repo log --oneline -n 1
"deploy: $head"

git -C $repo archive --format=tar -o $tar HEAD backend
scp -i $Key -q $tar "${Server}:/tmp/evision-backend.tar"
Remove-Item $tar

"[$((Get-Date).ToString('HH:mm:ss'))] build on server"
ssh -i $Key -o BatchMode=yes -o ServerAliveInterval=30 $Server @'
set -e
cd ~/evision
tar -xf /tmp/evision-backend.tar && rm /tmp/evision-backend.tar
cd backend
sudo docker compose -f compose.prod.yaml build app 2>&1 | tail -2
'@

# 수집 회차가 끝난 뒤(분 % 5 == 1, 10초)에 교체한다
$now = Get-Date
$slot = $now.Date.AddHours($now.Hour).AddMinutes([Math]::Floor($now.Minute / 5) * 5 + 1).AddSeconds(10)
if ($slot -le $now) { $slot = $slot.AddMinutes(5) }
$wait = [int]($slot - $now).TotalSeconds
"waiting $wait s (until $($slot.ToString('HH:mm:ss')))"
Start-Sleep -Seconds $wait

"[$((Get-Date).ToString('HH:mm:ss'))] restart app"
ssh -i $Key -o BatchMode=yes $Server @'
set -e
cd ~/evision/backend
sudo docker compose -f compose.prod.yaml up -d app 2>&1 | tail -1
for i in $(seq 1 30); do
  if sudo docker compose -f compose.prod.yaml logs --since 2m app 2>&1 | grep -q 'Started BackendApplication'; then echo started; break; fi
  sleep 2
done
sudo docker compose -f compose.prod.yaml ps --format '{{.Service}} {{.Status}}'
'@
"[$((Get-Date).ToString('HH:mm:ss'))] done"
