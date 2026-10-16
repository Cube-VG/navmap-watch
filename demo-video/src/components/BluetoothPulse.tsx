import React from 'react';
import {interpolate, useCurrentFrame} from 'remotion';
import {CLAMP, EASE_IN_OUT, EASE_OUT} from '../lib/anim';
import {Layout, phoneBox, watchBox} from '../layouts';
import {T} from '../timings';

type P = {x: number; y: number};

const bezier = (p0: P, p1: P, p2: P, p3: P, t: number): P => {
  const u = 1 - t;
  return {
    x: u * u * u * p0.x + 3 * u * u * t * p1.x + 3 * u * t * t * p2.x + t * t * t * p3.x,
    y: u * u * u * p0.y + 3 * u * u * t * p1.y + 3 * u * t * t * p2.y + t * t * t * p3.y,
  };
};

const pathFor = (layout: Layout): [P, P, P, P] => {
  const ph = phoneBox(layout);
  const w = watchBox(layout);
  if (layout.width > layout.height) {
    const p0 = {x: ph.right + 16, y: layout.phone.y - 70};
    const p3 = {x: layout.watch.x - w.caseR - 28, y: layout.watch.y - 70};
    return [p0, {x: p0.x + 80, y: p0.y - 130}, {x: p3.x - 80, y: p3.y - 130}, p3];
  }
  const p0 = {x: ph.right + 14, y: ph.bottom - 190};
  const p3 = {x: layout.watch.x + w.caseR + 30, y: layout.watch.y - 110};
  return [p0, {x: p0.x + 150, y: p0.y + 70}, {x: p3.x + 150, y: p3.y - 120}, p3];
};

// Dotted arc with glowing pulses travelling from the phone to the watch.
export const BluetoothPulse: React.FC<{layout: Layout}> = ({layout}) => {
  const frame = useCurrentFrame();
  if (frame < T.pulseStart - 2 || frame > T.pulseEnd + 4) return null;
  const [p0, p1, p2, p3] = pathFor(layout);
  const d = `M ${p0.x} ${p0.y} C ${p1.x} ${p1.y}, ${p2.x} ${p2.y}, ${p3.x} ${p3.y}`;
  const draw = interpolate(frame, [T.pulseStart, T.pulseStart + 18], [0, 1], {...CLAMP, easing: EASE_OUT});
  const fade = interpolate(frame, [T.pulseArrive, T.pulseEnd], [1, 0], {...CLAMP, easing: EASE_IN_OUT});
  const flow = (frame - T.pulseStart) * -2.2;
  const orbs = [0, 1, 2].map((i) => {
    const start = T.pulseStart + 6 + i * 10;
    const end = T.pulseArrive - 18 + i * 9;
    const t = interpolate(frame, [start, end], [0, 1], {...CLAMP, easing: EASE_IN_OUT});
    const vis = frame >= start && frame <= end + 2 ? Math.sin(Math.min(1, t * 1.02) * Math.PI) ** 0.4 : 0;
    return {p: bezier(p0, p1, p2, p3, t), vis, r: 9 - i * 1.5};
  });
  const flash = interpolate(frame, [T.pulseArrive - 12, T.pulseArrive + 8], [0, 1], {...CLAMP, easing: EASE_OUT});
  return (
    <svg width={layout.width} height={layout.height} style={{position: 'absolute', inset: 0, overflow: 'visible', pointerEvents: 'none'}}>
      <defs>
        <radialGradient id="orb">
          <stop offset="0" stopColor="#FFFFFF" />
          <stop offset="0.35" stopColor="#9FE3FF" />
          <stop offset="1" stopColor="#3B82F6" stopOpacity={0} />
        </radialGradient>
        <mask id="drawMask">
          <path d={d} pathLength={1} stroke="#fff" strokeWidth={30} fill="none" strokeDasharray={`${draw} 2`} />
        </mask>
      </defs>
      <g opacity={fade}>
        <path d={d} stroke="rgba(124,200,255,0.18)" strokeWidth={14} fill="none" strokeLinecap="round" mask="url(#drawMask)" />
        <path
          d={d}
          stroke="rgba(160,220,255,0.85)"
          strokeWidth={5}
          strokeDasharray="0.5 15"
          strokeDashoffset={flow}
          strokeLinecap="round"
          fill="none"
          mask="url(#drawMask)"
        />
        {orbs.map((o, i) =>
          o.vis > 0 ? (
            <g key={i} opacity={o.vis}>
              <circle cx={o.p.x} cy={o.p.y} r={o.r * 4} fill="url(#orb)" opacity={0.55} />
              <circle cx={o.p.x} cy={o.p.y} r={o.r} fill="#E8F7FF" />
            </g>
          ) : null,
        )}
        {/* Source glow on the phone side, arrival ring on the watch side */}
        <circle cx={p0.x} cy={p0.y} r={10 + draw * 6} fill="url(#orb)" opacity={0.8 * draw} />
        {flash > 0 && flash < 1 && (
          <circle cx={p3.x} cy={p3.y} r={10 + flash * 46} fill="none" stroke="#9FE3FF" strokeWidth={4} opacity={1 - flash} />
        )}
      </g>
    </svg>
  );
};
