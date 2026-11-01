#!/usr/bin/env bash
# NSRR SHHS download helper.
#
# Two supported flows (you need a free NSRR account: https://sleepdata.org):
#
# A) nsrr gem (recommended; handles chunked download + token automatically)
#      gem install nsrr
#      nsrr auth                       # paste your token from sleepdata.org/account
#      nsrr download shhs/datasets/shhs1 polysomnography/edfs      --token=YOUR_TOKEN
#      nsrr download shhs/datasets/shhs1 polysomnography/annotations-events-nsrr  \
#                  -r xml --token=YOUR_TOKEN
#
# B) direct token URLs (no ruby). NSRR exposes files with ?token=<NSRR_TOKEN>.
#    The snippet below fetches a defined file list from the shhs1 file index.
#
# Output layout expected by build_dataset.py:
#   <nsrr_root>/shhs1-*.edf
#   <nsrr_root>/shhs1-*-nsrr.xml   (staged annotations)
set -euo pipefail

NSRR_TOKEN="${NSRR_TOKEN:-}"
DATASET="${DATASET:-shhs1}"          # shhs1 | mesa
LIST="${LIST:-}"                     # optional file with one relative path per line
OUT="${OUT:-$HOME/nsrr/$DATASET}"

if [[ -z "$NSRR_TOKEN" ]] && ! command -v nsrr >/dev/null; then
  echo "Set NSRR_TOKEN (from https://sleepdata.org/account) or install the nsrr gem." >&2
  exit 1
fi

mkdir -p "$OUT"

if [[ -n "$NSRR_TOKEN" ]]; then
  : "${LIST:? direct mode needs LIST=file with one 'datasets/<ds>/polysomnography/...' path per line}"
  while IFS= read -r rel; do
    [[ -z "$rel" ]] && continue
    dest="$OUT/$(basename "$rel")"
    [[ -s "$dest" ]] && { echo "skip $dest"; continue; }
    echo "fetch $rel"
    curl -fL --retry 3 -o "$dest.part" \
      "https://sleepdata.org/datalake/${DATASET}/public/${rel}?token=${NSRR_TOKEN}"
    mv "$dest.part" "$dest"
  done < "$LIST"
else
  # gem flow: everything under the two polysomnography folders
  nsrr download "shhs/datasets/${DATASET}" polysomnography/edfs
  nsrr download "shhs/datasets/${DATASET}" polysomnography/annotations-events-nsrr -r xml
  echo "Gem flow downloads into ./shhs/... — pass that directory tree to build_dataset.py."
fi

echo "Done. Point build_dataset.py at the directory containing the EDFs + XMLs."
