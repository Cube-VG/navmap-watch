import React from 'react';
import {FootstepsIcon, HeartIcon, SunIcon} from '../../components/icons';
import {FONT} from '../../theme';

const C = 227;

const ringArc = (r: number, a0: number, a1: number) => {
  const p = (a: number) => [C + r * Math.cos((a * Math.PI) / 180), C + r * Math.sin((a * Math.PI) / 180)];
  const [x0, y0] = p(a0);
  const [x1, y1] = p(a1);
  const large = Math.abs(a1 - a0) > 180 ? 1 : 0;
  return `M ${x0} ${y0} A ${r} ${r} 0 ${large} 1 ${x1} ${y1}`;
};

// Everyday watch face: time, date, steps and heart rate.
export const WatchFace: React.FC<{frame: number; dim: number}> = ({frame, dim}) => {
  const beat = 1 + 0.12 * Math.max(0, Math.sin((frame / 30) * Math.PI * 2 * 1.03)) ** 6;
  return (
    <div
      style={{
        position: 'absolute',
        inset: 0,
        fontFamily: FONT,
        color: '#fff',
        opacity: 1 - 0.75 * dim,
        transform: `scale(${1 - 0.08 * dim})`,
        filter: dim > 0 ? `blur(${dim * 3}px)` : undefined,
      }}
    >
      <svg width={454} height={454} style={{position: 'absolute', inset: 0}}>
        {/* Steps (left) and battery (right) gauges */}
        <path d={ringArc(213, 125, 235)} stroke="#1C2A33" strokeWidth={9} fill="none" strokeLinecap="round" />
        <path d={ringArc(213, 235 - 110 * 0.62, 235)} stroke="#22C7E0" strokeWidth={9} fill="none" strokeLinecap="round" />
        <path d={ringArc(213, -55, 55)} stroke="#1E2E22" strokeWidth={9} fill="none" strokeLinecap="round" />
        <path d={ringArc(213, 55 - 110 * 0.86, 55)} stroke="#4BD865" strokeWidth={9} fill="none" strokeLinecap="round" />
      </svg>

      <div style={{position: 'absolute', top: 62, left: 0, right: 0, display: 'flex', justifyContent: 'center', alignItems: 'center', gap: 8}}>
        <SunIcon size={26} />
        <span style={{fontSize: 24, fontWeight: 600}}>64°</span>
      </div>
      <div
        style={{
          position: 'absolute',
          top: 112,
          left: 0,
          right: 0,
          textAlign: 'center',
          fontSize: 24,
          fontWeight: 600,
          letterSpacing: 3,
          color: '#A9B4C2',
        }}
      >
        FRI 16
      </div>
      <div
        style={{
          position: 'absolute',
          top: 142,
          left: 0,
          right: 0,
          textAlign: 'center',
          fontSize: 138,
          fontWeight: 700,
          letterSpacing: -5,
          lineHeight: '150px',
          fontVariantNumeric: 'tabular-nums',
        }}
      >
        9:41
      </div>
      <div style={{position: 'absolute', top: 308, left: 0, right: 0, display: 'flex', justifyContent: 'center', gap: 34}}>
        <div style={{display: 'flex', alignItems: 'center', gap: 8}}>
          <FootstepsIcon size={28} color="#22C7E0" />
          <span style={{fontSize: 30, fontWeight: 700}}>6,248</span>
        </div>
        <div style={{display: 'flex', alignItems: 'center', gap: 8}}>
          <HeartIcon size={28} style={{transform: `scale(${beat})`}} />
          <span style={{fontSize: 30, fontWeight: 700}}>62</span>
        </div>
      </div>
      <div
        style={{
          position: 'absolute',
          top: 372,
          left: 0,
          right: 0,
          display: 'flex',
          justifyContent: 'center',
          alignItems: 'center',
          gap: 6,
          fontSize: 19,
          fontWeight: 600,
          color: '#8E9AA8',
        }}
      >
        <svg width={26} height={13} viewBox="0 0 26 13">
          <rect x={0.75} y={0.75} width={21.5} height={11.5} rx={3} fill="none" stroke="#8E9AA8" strokeWidth={1.5} />
          <rect x={3} y={3} width={16} height={7} rx={1.5} fill="#4BD865" />
          <rect x={23.2} y={4} width={2.3} height={5} rx={1} fill="#8E9AA8" />
        </svg>
        86%
      </div>
    </div>
  );
};
