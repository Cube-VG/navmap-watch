import React from 'react';

// A round 5-button sports watch with a 454 x 454 AMOLED screen.
export const WATCH_W = 640;
export const WATCH_H = 900;
export const WATCH_SCREEN = 454;
export const CASE_R = 286;
const CX = WATCH_W / 2;
const CY = WATCH_H / 2;
const STRAP_LEN = 168;

// Button positions, degrees clockwise from 3 o'clock.
const BUTTONS = [
  {angle: -32, accent: true}, // START·STOP (2 o'clock)
  {angle: 32}, // BACK·LAP (4 o'clock)
  {angle: 214}, // LIGHT (10 o'clock)
  {angle: 180}, // UP·MENU (9 o'clock)
  {angle: 146}, // DOWN (8 o'clock)
];

const arc = (r: number, a0: number, a1: number) => {
  const p = (a: number) => [CX + r * Math.cos((a * Math.PI) / 180), CY + r * Math.sin((a * Math.PI) / 180)];
  const [x0, y0] = p(a0);
  const [x1, y1] = p(a1);
  return `M ${x0} ${y0} A ${r} ${r} 0 0 1 ${x1} ${y1}`;
};

export const GarminFrame: React.FC<{
  children: React.ReactNode;
  vibration?: number; // 0..1 progress of the vibration arcs (0 or 1 = hidden)
}> = ({children, vibration = 0}) => {
  const strap = (dir: -1 | 1) => {
    const y0 = CY + dir * (CASE_R - 40);
    const y1 = CY + dir * (CASE_R + STRAP_LEN);
    return (
      <g>
        <path
          d={`M ${CX - 128} ${y0} L ${CX + 128} ${y0} L ${CX + 122} ${y1} L ${CX - 122} ${y1} Z`}
          fill={`url(#strap${dir})`}
        />
        {[0.38, 0.56, 0.74].map((t) => (
          <ellipse
            key={t}
            cx={CX}
            cy={CY + dir * (CASE_R + STRAP_LEN * t)}
            rx={9}
            ry={5}
            fill="#0A0C0E"
            opacity={0.8 * (1 - t * 0.9)}
          />
        ))}
      </g>
    );
  };

  const vibOpacity = vibration > 0 && vibration < 1 ? Math.sin(vibration * Math.PI) : 0;

  return (
    <div style={{position: 'relative', width: WATCH_W, height: WATCH_H}}>
      <svg width={WATCH_W} height={WATCH_H} style={{position: 'absolute', inset: 0, overflow: 'visible'}}>
        <defs>
          {([-1, 1] as const).map((dir) => (
            <linearGradient key={dir} id={`strap${dir}`} x1="0" y1={dir === -1 ? '1' : '0'} x2="0" y2={dir === -1 ? '0' : '1'}>
              <stop offset="0" stopColor="#2A2F35" />
              <stop offset="0.55" stopColor="#1E2227" stopOpacity={0.95} />
              <stop offset="1" stopColor="#1E2227" stopOpacity={0} />
            </linearGradient>
          ))}
          <radialGradient id="case" cx="0.38" cy="0.3" r="0.8">
            <stop offset="0" stopColor="#5A6069" />
            <stop offset="0.55" stopColor="#30343A" />
            <stop offset="1" stopColor="#17191C" />
          </radialGradient>
          <linearGradient id="bezel" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" stopColor="#4B5058" />
            <stop offset="0.5" stopColor="#24272C" />
            <stop offset="1" stopColor="#3D4148" />
          </linearGradient>
          <linearGradient id="button" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stopColor="#6F757E" />
            <stop offset="0.5" stopColor="#3A3E44" />
            <stop offset="1" stopColor="#5B6068" />
          </linearGradient>
        </defs>

        {strap(-1)}
        {strap(1)}

        {/* Lugs */}
        {([-1, 1] as const).map((dir) => (
          <path
            key={dir}
            d={`M ${CX - 150} ${CY + dir * 210} Q ${CX - 150} ${CY + dir * 296} ${CX - 112} ${CY + dir * 300} L ${CX + 112} ${CY + dir * 300} Q ${CX + 150} ${CY + dir * 296} ${CX + 150} ${CY + dir * 210} Z`}
            fill="#2A2E33"
          />
        ))}

        {/* Buttons */}
        {BUTTONS.map((b) => (
          <g key={b.angle} transform={`rotate(${b.angle} ${CX} ${CY})`}>
            <rect x={CX + CASE_R - 10} y={CY - 21} width={27} height={42} rx={7} fill="url(#button)" stroke="#1A1C1F" strokeWidth={1.5} />
            {[-9, -3, 3, 9].map((dy) => (
              <path key={dy} d={`M ${CX + CASE_R + 9} ${CY + dy} h 6`} stroke="#2A2D31" strokeWidth={1.6} />
            ))}
            {b.accent && <rect x={CX + CASE_R + 14} y={CY - 16} width={3} height={32} rx={1.5} fill="#E2683C" />}
          </g>
        ))}

        {/* Case */}
        <circle cx={CX} cy={CY} r={CASE_R} fill="url(#case)" />
        <circle cx={CX} cy={CY} r={CASE_R - 1} fill="none" stroke="rgba(255,255,255,0.18)" strokeWidth={2} />

        {/* Bezel with tick marks */}
        <circle cx={CX} cy={CY} r={272} fill="url(#bezel)" />
        {Array.from({length: 60}).map((_, i) => {
          const major = i % 5 === 0;
          const a = (i * 6 * Math.PI) / 180;
          const r0 = major ? 252 : 257;
          const r1 = 266;
          return (
            <line
              key={i}
              x1={CX + r0 * Math.cos(a)}
              y1={CY + r0 * Math.sin(a)}
              x2={CX + r1 * Math.cos(a)}
              y2={CY + r1 * Math.sin(a)}
              stroke={major ? '#9AA1AB' : '#5E646D'}
              strokeWidth={major ? 3 : 1.5}
              strokeLinecap="round"
            />
          );
        })}
        {/* Black glass around the display */}
        <circle cx={CX} cy={CY} r={247} fill="#030405" />
        <circle cx={CX} cy={CY} r={247} fill="none" stroke="rgba(0,0,0,0.9)" strokeWidth={3} />

        {/* Vibration arcs */}
        {vibOpacity > 0 &&
          [0, 1, 2].map((i) => {
            const grow = vibration * 26;
            const r = CASE_R + 34 + i * 22 + grow;
            const o = vibOpacity * (1 - i * 0.25);
            return (
              <g key={i} opacity={o} stroke="#9FE3FF" strokeWidth={5} strokeLinecap="round" fill="none">
                <path d={arc(r, -13 + i * 1.5, 13 - i * 1.5)} />
                <path d={arc(r, 167 + i * 1.5, 193 - i * 1.5)} />
              </g>
            );
          })}
      </svg>

      {/* Display */}
      <div
        style={{
          position: 'absolute',
          left: CX - WATCH_SCREEN / 2,
          top: CY - WATCH_SCREEN / 2,
          width: WATCH_SCREEN,
          height: WATCH_SCREEN,
          borderRadius: '50%',
          overflow: 'hidden',
          background: '#000',
          isolation: 'isolate',
          WebkitMaskImage: '-webkit-radial-gradient(white, black)',
        }}
      >
        {children}
      </div>
      {/* Glass reflection */}
      <div
        style={{
          position: 'absolute',
          left: CX - 247,
          top: CY - 247,
          width: 494,
          height: 494,
          borderRadius: '50%',
          background:
            'linear-gradient(140deg, rgba(255,255,255,0.13) 0%, rgba(255,255,255,0.04) 30%, rgba(255,255,255,0) 45%, rgba(255,255,255,0) 80%, rgba(255,255,255,0.05) 100%)',
          pointerEvents: 'none',
        }}
      />
    </div>
  );
};
