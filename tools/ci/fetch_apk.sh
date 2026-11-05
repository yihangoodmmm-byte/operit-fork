#!/usr/bin/env bash
# 从 GitHub Actions 下载 Android Build 产物（APK）。
#
# 用法:
#   export GITHUB_TOKEN=xxx            # classic token: repo + workflow
#   tools/ci/fetch_apk.sh              # main 上最新一次成功的 Build
#   tools/ci/fetch_apk.sh --sha c0bb804
#   tools/ci/fetch_apk.sh --run 41630708160
#   tools/ci/fetch_apk.sh --dry-run    # 只打印将要下载什么，不下载
#
# 环境变量: REPO / WORKFLOW_NAME / OUT_DIR 可覆盖默认值。
set -euo pipefail

REPO="${REPO:-yihangoodmmm-byte/operit-fork}"
WORKFLOW_NAME="${WORKFLOW_NAME:-Android Build}"
OUT_DIR="${OUT_DIR:-dist}"

: "${GITHUB_TOKEN:?需要 export GITHUB_TOKEN=...（classic: repo+workflow 权限）}"

api() {
  curl -fsS -H "Authorization: token ${GITHUB_TOKEN}" \
    -H "Accept: application/vnd.github+json" "$@"
}

# token 预检：失效就立刻报错，别等后面 json 解析崩在 traceback 里
if ! curl -fsS -m 20 -H "Authorization: token ${GITHUB_TOKEN}" \
    -H "Accept: application/vnd.github+json" https://api.github.com/user >/dev/null 2>&1; then
  echo "token 无效或已吊销（Bad credentials / 401）。请换一个新 token（fine-grained: Actions=Read, Contents=Read）。" >&2
  exit 1
fi

sha=""
run_id=""
dry_run=0
while [ $# -gt 0 ]; do
  case "$1" in
    --sha) sha="$2"; shift 2 ;;
    --run) run_id="$2"; shift 2 ;;
    --out) OUT_DIR="$2"; shift 2 ;;
    --dry-run) dry_run=1; shift ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

# ---------- 1. 定位 run ----------
if [ -z "$run_id" ]; then
  echo "==> 查找 $WORKFLOW_NAME（${sha:-最新}）"
  run_id=$(api "https://api.github.com/repos/$REPO/actions/runs?per_page=30" | \
    SHA="$sha" NAME="$WORKFLOW_NAME" python3 -c '
import json, os, sys
sha = os.environ["SHA"]
name = os.environ["NAME"]
data = json.load(sys.stdin)
for r in data["workflow_runs"]:
    if r["name"] != name:
        continue
    if r["status"] != "completed" or r["conclusion"] != "success":
        continue
    if sha and not r["head_sha"].startswith(sha):
        continue
    print(r["id"])
    break
')
  [ -n "$run_id" ] || { echo "没有找到匹配的成功 run" >&2; exit 1; }
fi
echo "==> run id: $run_id"

# ---------- 2. 等它跑完 ----------
for _ in $(seq 1 160); do
  status=$(api "https://api.github.com/repos/$REPO/actions/runs/$run_id" | \
    python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["status"], d["conclusion"])')
  echo "    $status"
  case "$status" in
    "completed success") break ;;
    completed*) echo "构建失败: $status" >&2; exit 1 ;;
    *) sleep 30 ;;
  esac
done

# ---------- 3. 找 artifact ----------
# 可用环境变量覆盖：ARTIFACT_ID / ARTIFACT_NAME / ARTIFACT_BYTES
# （API 配额耗尽时，可以从 run 页面 HTML 里拿到 data-artifact-id 直接下）
if [ -n "${ARTIFACT_ID:-}" ]; then
  meta="${ARTIFACT_ID} ${ARTIFACT_BYTES:-0} ${ARTIFACT_NAME:-operit-android-manual}"
  echo "==> 使用手工指定的 artifact: $meta"
  api_ok=1
else
api_ok=0
meta=$(api "https://api.github.com/repos/$REPO/actions/runs/$run_id/artifacts" | \
  python3 -c '
import json, sys
for a in json.load(sys.stdin)["artifacts"]:
    if a["name"].startswith("operit-android-"):
        print(a["id"], a["size_in_bytes"], a["name"])
        break
')
fi
[ -n "$meta" ] || { echo "该 run 没有 operit-android-* 产物" >&2; exit 1; }
set -- $meta
artifact_id=$1
expected_bytes=$2
artifact_name=$3
echo "==> artifact: $artifact_name ($expected_bytes bytes)"

if [ "$dry_run" = 1 ]; then
  echo "==> dry-run，跳过下载"
  exit 0
fi

# ---------- 4. 下载（断点续传直到字节数对齐） ----------
mkdir -p "$OUT_DIR"
zip_path="$OUT_DIR/${artifact_name}.zip"
rm -f "$zip_path"
for i in $(seq 1 60); do
  # -L 跟随 302；-C - 续传，避免传输被截断后从头再来
  curl -sL -H "Authorization: token ${GITHUB_TOKEN}" \
    "https://api.github.com/repos/$REPO/actions/artifacts/$artifact_id/zip" \
    -C - -o "$zip_path"
  got=$(stat -c %s "$zip_path" 2>/dev/null || echo 0)
  echo "    $got / ${expected_bytes:-?}"
  if [ "$expected_bytes" != "0" ] && [ "$got" = "$expected_bytes" ]; then break; fi
  if [ "$expected_bytes" = "0" ] && [ "$got" -gt 1000000 ]; then break; fi
  sleep 3
done
got=$(stat -c %s "$zip_path" 2>/dev/null || echo 0)
if [ "$expected_bytes" != "0" ] && [ "$got" != "$expected_bytes" ]; then
  echo "下载未完成（$got / $expected_bytes）" >&2; exit 1
fi
if [ "$got" -lt 1000000 ]; then
  echo "下载内容过小（$got 字节），可能是 404/401 响应而不是 zip" >&2; exit 1
fi

# ---------- 5. 解包 ----------
unpack_dir="$OUT_DIR/${artifact_name}-unpacked"
rm -rf "$unpack_dir"
unzip -q "$zip_path" -d "$unpack_dir"
apk=$(find "$unpack_dir" -name '*.apk' | head -1)
[ -n "$apk" ] || { echo "压缩包里没有 APK" >&2; exit 1; }

final="$OUT_DIR/$(basename "$apk")"
cp "$apk" "$final"
echo "==> APK: $final"
stat -c '    %s bytes' "$final"
sha256sum "$final"
echo
echo "下一步: tools/ci/android_install_and_aot.sh \"$final\""
