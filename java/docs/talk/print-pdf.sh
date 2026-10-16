#!/usr/bin/env bash
# Exports the deck to PDF with headless Chrome or Chromium.
#
#   ./print-pdf.sh            # talk-steps.pdf: one page per click (every fragment step)
#   ./print-pdf.sh final      # talk-final.pdf: one page per slide, every fragment shown
#   ./print-pdf.sh both       # both files
#
# THEME=java ./print-pdf.sh   uses the Java theme. NOTES=1 adds the speaker notes on their own pages.
set -euo pipefail

cd "$(dirname "$0")"
mode=${1:-steps}
browser=$(command -v chromium || command -v chromium-browser || command -v google-chrome || true)
[ -n "$browser" ] || { echo "Needs Chrome or Chromium on the PATH" >&2; exit 1; }

port=8790
python3 -m http.server "$port" >/dev/null 2>&1 &
server=$!
trap 'kill $server 2>/dev/null' EXIT
sleep 1

print() { # $1: output file, $2: extra query parameters
  local query="print-pdf$2"
  [ -n "${THEME:-}" ] && query+="&theme=$THEME"
  [ -n "${NOTES:-}" ] && query+="&showNotes=separate-page"
  "$browser" --headless --no-sandbox --disable-gpu --no-pdf-header-footer \
    --run-all-compositor-stages-before-draw --virtual-time-budget=20000 \
    --print-to-pdf="$1" "http://localhost:$port/index.html?$query" >/dev/null 2>&1
  echo "$1"
}

case "$mode" in
  steps) print talk-steps.pdf "" ;;
  final) print talk-final.pdf "&pdfSeparateFragments=false" ;;
  both)  print talk-steps.pdf ""; print talk-final.pdf "&pdfSeparateFragments=false" ;;
  *) echo "Usage: $0 [steps|final|both]" >&2; exit 2 ;;
esac
