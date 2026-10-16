#!/usr/bin/env bash
# Generates the video's UI sounds with ffmpeg: plain sine blips and filtered
# noise, no samples or music. Writes public/audio/*.wav.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=public/audio
mkdir -p "$OUT"
gen() { # name duration expression [extra filters]
  ffmpeg -loglevel error -y -f lavfi -i "aevalsrc='$3':s=48000:d=$2" -af "${4:-anull}" -ac 1 -c:a pcm_s16le "$OUT/$1.wav"
}

# Soft tap: two damped sines.
gen tap 0.09 '0.45*sin(2*PI*1500*t)*exp(-t*85)+0.3*sin(2*PI*760*t)*exp(-t*55)'
# Countdown tick.
gen tick 0.06 '0.3*sin(2*PI*2300*t)*exp(-t*120)'
# "Sent": two rising notes.
gen sent 0.7 '0.32*sin(2*PI*880*t)*exp(-t*16)+0.32*gte(t,0.11)*sin(2*PI*1318.5*t)*exp(-(t-0.11)*7)+0.08*gte(t,0.11)*sin(2*PI*2637*t)*exp(-(t-0.11)*12)'
# Map opens: three quick rising notes.
gen open 0.8 '0.28*sin(2*PI*660*t)*exp(-t*18)+0.28*gte(t,0.08)*sin(2*PI*880*t)*exp(-(t-0.08)*14)+0.3*gte(t,0.16)*sin(2*PI*1320*t)*exp(-(t-0.16)*6)'
# Haptic buzz: two low pulses 13 frames apart, with smooth edges.
gen buzz 0.65 '(0.62*sin(2*PI*165*t)+0.28*sin(2*PI*330*t)+0.1*sin(2*PI*495*t))*(between(t,0,0.17)*pow(sin(PI*t/0.17),0.4)+between(t,0.433,0.603)*pow(sin(PI*(t-0.433)/0.17),0.4))'
# Whoosh for the phone-to-watch pulse: band-passed noise with a swell.
ffmpeg -loglevel error -y -f lavfi -i "anoisesrc=d=1.0:c=pink:a=0.6:r=48000" \
  -af "highpass=f=500,lowpass=f=2600,afade=t=in:st=0:d=0.45:curve=qsin,afade=t=out:st=0.45:d=0.55:curve=qsin,volume=0.8" \
  -ac 1 -c:a pcm_s16le "$OUT/whoosh.wav"
ls -l "$OUT"
