#!/usr/bin/env bash
# Pulls one frame from the middle of each of the 8 scenes of both renders
# into out/stills/, for checking the result. Scene ranges mirror src/timings.ts.
set -euo pipefail
cd "$(dirname "$0")/.."
FPS=30
SCENES=(
  "1-title 0 90"
  "2-route-preview 90 240"
  "3-navigating 240 330"
  "4-share-sheet 330 420"
  "5-sending 420 510"
  "6-watch-notification 510 630"
  "7-watch-map 630 780"
  "8-end-card 780 840"
)
for video in out/navmap-demo.mp4 out/navmap-demo-vertical.mp4; do
  [ -f "$video" ] || { echo "missing $video (run npm run render first)"; exit 1; }
  name=$(basename "$video" .mp4)
  mkdir -p "out/stills/$name"
  for s in "${SCENES[@]}"; do
    read -r label from to <<<"$s"
    mid=$(( (from + to) / 2 ))
    t=$(awk -v f="$mid" -v fps="$FPS" 'BEGIN { printf "%.4f", (f + 0.5) / fps }')
    ffmpeg -loglevel error -y -ss "$t" -i "$video" -frames:v 1 -q:v 2 "out/stills/$name/scene-$label.jpg"
    echo "out/stills/$name/scene-$label.jpg  (frame $mid, ${t}s)"
  done
done
