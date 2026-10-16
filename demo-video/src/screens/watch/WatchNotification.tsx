import React from 'react';
import {NavMapGlyph} from '../../components/NavMapIcon';
import {COLORS, FONT} from '../../theme';

// Centre of the "Open Map" button, where the map launches from.
export const OPEN_MAP_BUTTON = {x: 227, y: 278};
const BTN_W = 250;
const BTN_H = 68;
const RING_R = 17;
const RING_C = 2 * Math.PI * RING_R;

// "Route received" notification with a 3-2-1 countdown that opens the map
// automatically.
export const WatchNotification: React.FC<{
  enter: number; // spring 0..1 (may overshoot)
  countdown: number; // seconds left, 3 → 0, fractional
  digitPop: number; // 0..1 pop each time the digit changes
  exit: number; // 0..1 as the map takes over
}> = ({enter, countdown, digitPop, exit}) => {
  const digit = Math.max(1, Math.ceil(countdown - 1e-6));
  const ringLeft = Math.max(0, Math.min(1, countdown / 3));
  const y = (1 - enter) * 454;
  return (
    <div
      style={{
        position: 'absolute',
        inset: 0,
        fontFamily: FONT,
        color: '#fff',
        transform: `translateY(${y}px)`,
        opacity: 1 - exit,
      }}
    >
      <div style={{position: 'absolute', inset: 0, background: 'radial-gradient(circle at 50% 40%, #141B26 0%, #07090C 70%)'}} />
      <div style={{position: 'absolute', left: (454 - 70) / 2, top: 50}}>
        <NavMapGlyph size={70} radius={18} shadow />
      </div>
      <div style={{position: 'absolute', top: 134, left: 0, right: 0, textAlign: 'center', fontSize: 33, fontWeight: 700, letterSpacing: -0.5}}>
        Route received
      </div>
      <div style={{position: 'absolute', top: 180, left: 0, right: 0, textAlign: 'center', fontSize: 22, fontWeight: 500, color: '#AEB8C6'}}>
        Golden Gate Park · 6.2 mi
      </div>

      {/* Open Map button with the countdown ring */}
      <div
        style={{
          position: 'absolute',
          left: OPEN_MAP_BUTTON.x - BTN_W / 2,
          top: OPEN_MAP_BUTTON.y - BTN_H / 2,
          width: BTN_W,
          height: BTN_H,
          borderRadius: BTN_H / 2,
          background: `linear-gradient(90deg, ${COLORS.navmapBlue}, ${COLORS.navmapTeal})`,
          boxShadow: '0 6px 20px rgba(40,140,255,0.35)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          gap: 12,
          transform: `scale(${1 + 0.04 * exit})`,
        }}
      >
        <svg width={44} height={44} viewBox="-22 -22 44 44">
          <circle r={RING_R} fill="rgba(0,0,0,0.18)" stroke="rgba(255,255,255,0.3)" strokeWidth={4} />
          <circle
            r={RING_R}
            fill="none"
            stroke="#fff"
            strokeWidth={4}
            strokeLinecap="round"
            strokeDasharray={RING_C}
            strokeDashoffset={RING_C * (1 - ringLeft)}
            transform="rotate(-90)"
          />
          <text
            y={1}
            textAnchor="middle"
            dominantBaseline="central"
            fontFamily={FONT}
            fontSize={19}
            fontWeight={700}
            fill="#fff"
            transform={`scale(${1 + 0.25 * digitPop})`}
          >
            {digit}
          </text>
        </svg>
        <span style={{fontSize: 27, fontWeight: 700}}>Open Map</span>
      </div>

      <div style={{position: 'absolute', top: 334, left: 0, right: 0, textAlign: 'center', fontSize: 21, fontWeight: 500, color: '#AEB8C6'}}>
        Opening in <span style={{color: '#fff', fontWeight: 700}}>{digit}…</span>
      </div>
    </div>
  );
};
