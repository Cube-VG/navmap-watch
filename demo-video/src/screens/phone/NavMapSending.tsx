import React from 'react';
import {SCREEN_W} from '../../components/IPhoneFrame';
import {NavMapGlyph} from '../../components/NavMapIcon';
import {COLORS, FONT} from '../../theme';

const CARD_W = 316;
const CARD_TOP = 268;
const RING = 92;
const R = 38;
const CIRC = 2 * Math.PI * R;

// NavMap's confirmation card: a progress ring while the route is sent,
// then a check mark and "Sent".
export const NavMapSending: React.FC<{
  enter: number; // spring 0..1 (may overshoot)
  exit: number; // 0..1
  progress: number; // 0..1
  done: number; // 0..1 crossfade to the sent state
}> = ({enter, exit, progress, done}) => {
  const visible = Math.min(1, Math.max(0, enter)) * (1 - exit);
  const scale = (0.86 + 0.14 * enter) * (1 - 0.06 * exit);
  return (
    <div style={{position: 'absolute', inset: 0, fontFamily: FONT, zIndex: 40}}>
      <div style={{position: 'absolute', inset: 0, background: `rgba(0,0,0,${0.28 * visible})`}} />
      <div
        style={{
          position: 'absolute',
          left: (SCREEN_W - CARD_W) / 2,
          top: CARD_TOP,
          width: CARD_W,
          height: 268,
          borderRadius: 26,
          background: 'rgba(255,255,255,0.98)',
          boxShadow: '0 18px 50px rgba(0,0,0,0.35)',
          opacity: visible,
          transform: `translateY(${(1 - enter) * 40}px) scale(${scale})`,
        }}
      >
        <div style={{position: 'absolute', left: 18, top: 16, display: 'flex', alignItems: 'center', gap: 8}}>
          <NavMapGlyph size={22} radius={6} />
          <div style={{fontSize: 13, fontWeight: 600, color: '#6D6D72', letterSpacing: 0.2}}>NavMap</div>
        </div>

        {/* Progress ring -> check */}
        <svg
          width={RING}
          height={RING}
          viewBox={`${-RING / 2} ${-RING / 2} ${RING} ${RING}`}
          style={{position: 'absolute', left: (CARD_W - RING) / 2, top: 50}}
        >
          <defs>
            <linearGradient id="sendRing" x1="0" y1="0" x2="1" y2="1">
              <stop offset="0" stopColor={COLORS.navmapBlue} />
              <stop offset="1" stopColor={COLORS.navmapTeal} />
            </linearGradient>
          </defs>
          <circle r={R} fill="none" stroke="#E7EBF1" strokeWidth={7} opacity={1 - done} />
          <circle
            r={R}
            fill="none"
            stroke="url(#sendRing)"
            strokeWidth={7}
            strokeLinecap="round"
            strokeDasharray={CIRC}
            strokeDashoffset={CIRC * (1 - progress)}
            transform="rotate(-90)"
            opacity={1 - done}
          />
          {/* Watch glyph inside the ring */}
          <g opacity={1 - done} transform={`scale(${1 - 0.3 * done})`}>
            <rect x={-7} y={-21} width={14} height={8} rx={2.5} fill="#9AA6B8" />
            <rect x={-7} y={13} width={14} height={8} rx={2.5} fill="#9AA6B8" />
            <circle r={14} fill="#1C2330" stroke="#9AA6B8" strokeWidth={3.5} />
            <path d="M -5 4 L 0 -6 L 5 4 L 0 1.5 Z" fill={COLORS.navmapTeal} />
          </g>
          <circle r={R + 3.5} fill="#34C759" opacity={done} transform={`scale(${0.7 + 0.3 * done})`} />
          <path
            d="M -15 1 L -5 11 L 16 -11"
            fill="none"
            stroke="#fff"
            strokeWidth={7}
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeDasharray={50}
            strokeDashoffset={50 * (1 - done)}
          />
        </svg>

        {/* Sending text */}
        <div
          style={{
            position: 'absolute',
            left: 0,
            right: 0,
            top: 160,
            textAlign: 'center',
            opacity: 1 - done,
            transform: `translateY(${-8 * done}px)`,
          }}
        >
          <div style={{fontSize: 17, fontWeight: 600, color: COLORS.iosLabel, lineHeight: '23px'}}>
            Sending route to
            <br />
            Garmin Forerunner 965…
          </div>
          <div style={{fontSize: 14, color: COLORS.iosSecondary, marginTop: 8}}>Golden Gate Park · 6.2 mi</div>
        </div>
        {/* Sent text */}
        <div
          style={{
            position: 'absolute',
            left: 0,
            right: 0,
            top: 166,
            textAlign: 'center',
            opacity: done,
            transform: `translateY(${10 * (1 - done)}px)`,
          }}
        >
          <div style={{fontSize: 24, fontWeight: 700, color: COLORS.iosLabel, lineHeight: '30px'}}>Sent</div>
          <div style={{fontSize: 14, color: COLORS.iosSecondary, marginTop: 6}}>Garmin Forerunner 965</div>
        </div>
      </div>
    </div>
  );
};
