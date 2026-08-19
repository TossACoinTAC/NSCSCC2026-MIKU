#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
usage: build_soc_linux.sh [options]

  --root DIR             repository root
  --chiplab DIR          clean Chiplab checkout at the locked commit
  --chiplab-commit SHA   expected Chiplab commit
  --rtl FILE             generated mycpu_top.v
  --generation-manifest FILE
                         matching CPU generation manifest
  --out DIR              artifact directory
  --vivado FILE          Vivado 2023.2 executable
  --cpu-mhz NUMBER       CPU clock target (default: 100)
  --uncore-mhz NUMBER    APB/uncore clock target (default: 100)
  --jobs NUMBER          Vivado parallel jobs (default: 32)
EOF
  exit 2
}

absolute_path() {
  python3 - "$1" <<'PY'
import os
import sys
print(os.path.abspath(os.path.expanduser(sys.argv[1])))
PY
}

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
root=$(cd "$script_dir/../.." && pwd)
chiplab=""
chiplab_commit=""
rtl=""
generation_manifest=""
out=""
vivado=${VIVADO:-/opt/Xilinx/Vivado/2023.2/bin/vivado}
cpu_mhz=100
uncore_mhz=100
jobs=32

while (($#)); do
  case "$1" in
    --root) root=$(realpath "$2"); shift 2 ;;
    --chiplab) chiplab=$(realpath "$2"); shift 2 ;;
    --chiplab-commit) chiplab_commit=$2; shift 2 ;;
    --rtl) rtl=$(realpath "$2"); shift 2 ;;
    --generation-manifest) generation_manifest=$(realpath "$2"); shift 2 ;;
    --out) out=$(absolute_path "$2"); shift 2 ;;
    --vivado) vivado=$(realpath "$2"); shift 2 ;;
    --cpu-mhz) cpu_mhz=$2; shift 2 ;;
    --uncore-mhz) uncore_mhz=$2; shift 2 ;;
    --jobs) jobs=$2; shift 2 ;;
    -h|--help) usage ;;
    *) printf 'unknown option: %s\n' "$1" >&2; usage ;;
  esac
done

chiplab=${chiplab:-$root/chiplab}
rtl=${rtl:-$root/build/rtl/mycpu_top.v}
generation_manifest=${generation_manifest:-$root/build/rtl/generation-manifest.json}
out=${out:-$root/build/vivado/soc-linux}
overlay="$root/platform/soc-linux/overlay"
lock_file="$root/cpu/reference/manifest.lock"

[[ -x "$vivado" ]] || { printf 'Vivado is not executable: %s\n' "$vivado" >&2; exit 1; }
[[ -f "$rtl" ]] || { printf 'generated RTL not found: %s\n' "$rtl" >&2; exit 1; }
[[ -f "$generation_manifest" ]] || { printf 'generation manifest not found: %s\n' "$generation_manifest" >&2; exit 1; }
[[ -d "$overlay" ]] || { printf 'Linux overlay not found: %s\n' "$overlay" >&2; exit 1; }
[[ -f "$lock_file" ]] || { printf 'repository lock not found: %s\n' "$lock_file" >&2; exit 1; }
[[ -d "$chiplab/.git" || -f "$chiplab/.git" ]] || { printf 'Chiplab checkout not found: %s\n' "$chiplab" >&2; exit 1; }

locked_chiplab_commit=$(awk -F= '$1 == "chiplab_commit" {print $2}' "$lock_file")
[[ "$locked_chiplab_commit" =~ ^[0-9a-f]{40}$ ]] || { printf 'invalid chiplab lock\n' >&2; exit 1; }
if [[ -z "$chiplab_commit" ]]; then
  chiplab_commit=$locked_chiplab_commit
elif [[ "$chiplab_commit" != "$locked_chiplab_commit" ]]; then
  printf 'requested Chiplab commit does not match the lock\n' >&2
  exit 1
fi
[[ "$(git -C "$chiplab" rev-parse HEAD)" == "$chiplab_commit" ]] || {
  printf 'Chiplab HEAD does not match the lock\n' >&2
  exit 1
}

root_commit=$(git -C "$root" rev-parse HEAD)
dirty=$(git -C "$root" status --porcelain --untracked-files=all -- \
  platform/soc-linux scripts/vivado/build_soc_linux.sh \
  scripts/vivado/build_soc_linux.tcl scripts/vivado/write_soc_linux_manifest.py)
if [[ -n "$dirty" ]]; then
  printf 'Linux build inputs must be committed at %s:\n%s\n' "$root_commit" "$dirty" >&2
  exit 1
fi

[[ "$jobs" =~ ^[1-9][0-9]*$ ]] || { printf 'jobs must be a positive integer\n' >&2; exit 2; }
python3 "$root/platform/soc-linux/check_contracts.py" --repo-root "$root" \
  --pll-xci "$chiplab/IP/xilinx_ip/2023.2/clk_pll_33/clk_pll_33.xci" \
  --allow-clock-mismatch

python3 - "$generation_manifest" "$rtl" "$root_commit" "$root" <<'PY'
import hashlib
import json
import pathlib
import subprocess
import sys

manifest_path = pathlib.Path(sys.argv[1])
rtl_path = pathlib.Path(sys.argv[2])
root_commit = sys.argv[3]
root = pathlib.Path(sys.argv[4])
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
if manifest.get("source_dirty") is not False:
    raise SystemExit("generation manifest does not certify a clean CPU source tree")
if manifest.get("source_commit") != root_commit:
    raise SystemExit(f"generation source commit mismatch: {manifest.get('source_commit')} != {root_commit}")
actual = hashlib.sha256(rtl_path.read_bytes()).hexdigest()
if manifest.get("published_rtl_sha256") != actual:
    raise SystemExit("generated RTL hash does not match generation manifest")
source_hash = subprocess.run(
    ["python3", str(root / "scripts/common/content_hash.py"), "--profile", "cpu-source", str(root / "cpu")],
    check=True, text=True, stdout=subprocess.PIPE,
).stdout.strip()
if manifest.get("source_tree_sha256") != source_hash:
    raise SystemExit("CPU source tree hash does not match generation manifest")
PY

if [[ -e "$out" ]] && [[ -n "$(find "$out" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null)" ]]; then
  printf 'refusing to reuse non-empty Linux SoC output: %s\n' "$out" >&2
  exit 1
fi
mkdir -p "$out"
vivado_version_file="$out/vivado-version.txt"
"$vivado" -version > "$vivado_version_file"
grep -Eiq 'Vivado v2023\.2' "$vivado_version_file" || { cat "$vivado_version_file" >&2; exit 1; }

overlay_tree=$(git -C "$root" rev-parse "$root_commit:platform/soc-linux/overlay")
chiplab_tree=$(git -C "$chiplab" rev-parse "$chiplab_commit^{tree}")
tmp=$(mktemp -d "$(dirname "$out")/.nscscc-soc-linux.XXXXXX")
stage="$tmp/chiplab"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$stage"
git -C "$chiplab" archive "$chiplab_commit" | tar -xf - -C "$stage"
git -C "$root" archive "$root_commit" platform/soc-linux/overlay | tar -xf - -C "$stage" --strip-components=3
mkdir -p "$stage/IP/myCPU"
install -m 0644 "$rtl" "$stage/IP/myCPU/mycpu_top.v"

project="$stage/fpga/loongson/2023.2/system_run.xpr"
[[ -f "$project" ]] || { printf 'locked Chiplab project not found: %s\n' "$project" >&2; exit 1; }
build_log="$out/vivado-build.log"
build_rc=0
set +e
(
  cd "$out"
  "$vivado" -mode batch -log "$out/vivado.log" -journal "$out/vivado.jou" \
    -source "$root/scripts/vivado/build_soc_linux.tcl" \
    -tclargs "$project" "$out" "$cpu_mhz" "$uncore_mhz" "$jobs"
) >"$build_log" 2>&1
build_rc=$?
set -e

cp "$root/platform/soc-linux/source-manifest.json" "$out/source-manifest.json"
cp "$generation_manifest" "$out/generation-manifest.json"
cp "$rtl" "$out/mycpu_top.v"
printf '%s\n' "$build_rc" > "$out/vivado-build.exit"
if [[ "$build_rc" -ne 0 ]]; then
  printf 'Linux SoC Vivado build failed with exit %s; evidence is in %s\n' "$build_rc" "$out" >&2
  exit "$build_rc"
fi

python3 "$root/scripts/vivado/write_soc_linux_manifest.py" \
  --root "$root" --source-commit "$root_commit" --overlay-tree "$overlay_tree" \
  --chiplab-commit "$chiplab_commit" --chiplab-tree "$chiplab_tree" \
  --cpu-rtl "$out/mycpu_top.v" \
  --generation-manifest "$out/generation-manifest.json" \
  --source-manifest "$out/source-manifest.json" --artifact-dir "$out" \
  --vivado "$vivado" --vivado-version-file "$vivado_version_file" \
  --cpu-mhz "$cpu_mhz" --uncore-mhz "$uncore_mhz" --jobs "$jobs" \
  --out "$out/manifest.json"

printf 'soc-linux output: %s\n' "$out"
