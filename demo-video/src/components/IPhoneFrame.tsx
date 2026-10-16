import React from 'react';
import {interpolateColors} from 'remotion';
import {FONT} from '../theme';
import {NavMapGlyph} from './NavMapIcon';

// iPhone 16 Pro proportions, in points. The screen is 393 x 852.
export const SCREEN_W = 393;
export const SCREEN_H = 852;
const FRAME = 5; // titanium band
const BEZEL = 9; // black border around the display
export const PHONE_W = SCREEN_W + 2 * (FRAME + BEZEL);
export const PHONE_H = SCREEN_H + 2 * (FRAME + BEZEL);
const SCREEN_R = 55;
const OUTER_R = SCREEN_R + FRAME + BEZEL;

const SignalBars: React.FC<{color: string}> = ({color}) => (
  <svg width={18} height={12} viewBox="0 0 18 12">
    {[4, 6.5, 9, 11.5].map((h, i) => (
      <rect key={i} x={i * 4.6} y={12 - h} width={3.2} height={h} rx={0.9} fill={color} />
    ))}
  </svg>
);

const Battery: React.FC<{color: string}> = ({color}) => (
  <svg width={28} height={13} viewBox="0 0 28 13">
    <rect x={0.5} y={0.5} width={24} height={12} rx={3.8} fill="none" stroke={color} strokeOpacity={0.4} />
    <rect x={2.5} y={2.5} width={20} height={8} rx={2.2} fill={color} />
    <path d="M26 4.3 C27.2 4.6 27.6 5.4 27.6 6.5 C27.6 7.6 27.2 8.4 26 8.7 Z" fill={color} fillOpacity={0.45} />
  </svg>
);

export const StatusBar: React.FC<{tone: number}> = ({tone}) => {
  const color = interpolateColors(tone, [0, 1], ['#0B0B0C', '#FFFFFF']);
  return (
    <div style={{position: 'absolute', left: 0, top: 0, width: SCREEN_W, height: 54, fontFamily: FONT, color, zIndex: 50}}>
      <div
        style={{
          position: 'absolute',
          left: 44,
          width: 88,
          top: 17,
          textAlign: 'center',
          fontSize: 17,
          fontWeight: 600,
          letterSpacing: -0.3,
        }}
      >
        9:41
      </div>
      <div style={{position: 'absolute', right: 30, top: 20, display: 'flex', alignItems: 'center', gap: 6}}>
        <SignalBars color={color} />
        <div style={{fontSize: 15, fontWeight: 650, letterSpacing: -0.2, lineHeight: '13px'}}>5G</div>
        <Battery color={color} />
      </div>
    </div>
  );
};

// Dynamic Island. When `expand` > 0 it grows into a live activity showing
// NavMap's progress (`progress` 0..1, `done` 0..1 swaps the ring for a check).
const DynamicIsland: React.FC<{expand: number; progress: number; done: number}> = ({expand, progress, done}) => {
  const w = 126 + expand * 44;
  const h = 37 + expand * 3;
  const r = 10;
  const circ = 2 * Math.PI * r;
  return (
    <div
      style={{
        position: 'absolute',
        top: 11,
        left: (SCREEN_W - w) / 2,
        width: w,
        height: h,
        borderRadius: h / 2,
        background: '#000',
        zIndex: 60,
        overflow: 'hidden',
      }}
    >
      {expand > 0.01 && (
        <>
          <div style={{position: 'absolute', left: 9, top: (h - 22) / 2, opacity: expand, transform: `scale(${0.6 + 0.4 * expand})`}}>
            <NavMapGlyph size={22} radius={6} />
          </div>
          <svg
            width={26}
            height={26}
            viewBox="-13 -13 26 26"
            style={{position: 'absolute', right: 8, top: (h - 26) / 2, opacity: expand}}
          >
            <circle r={r} fill="none" stroke="rgba(255,255,255,0.18)" strokeWidth={2.6} />
            <circle
              r={r}
              fill="none"
              stroke="#34C759"
              strokeWidth={2.6}
              strokeLinecap="round"
              strokeDasharray={circ}
              strokeDashoffset={circ * (1 - progress)}
              transform="rotate(-90)"
              opacity={1 - done}
            />
            <circle r={r + 1.5} fill="#34C759" opacity={done} />
            <path
              d="M -4.8 0.4 L -1.6 3.6 L 5.2 -3.6"
              fill="none"
              stroke="#fff"
              strokeWidth={2.4}
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeDasharray={22}
              strokeDashoffset={22 * (1 - done)}
            />
          </svg>
        </>
      )}
    </div>
  );
};

export type IPhoneFrameProps = {
  children: React.ReactNode;
  statusTone: number; // 0 = dark status bar text, 1 = white
  homeTone?: number; // 0 = dark home indicator, 1 = white
  island?: {expand: number; progress: number; done: number};
};

export const IPhoneFrame: React.FC<IPhoneFrameProps> = ({children, statusTone, homeTone = 0, island}) => {
  const sideButton = (side: 'left' | 'right', top: number, height: number) => (
    <div
      style={{
        position: 'absolute',
        [side]: -3,
        top,
        width: 6,
        height,
        borderRadius: 3,
        background: 'linear-gradient(90deg, #2b2b2e, #6a6a70 50%, #2b2b2e)',
      }}
    />
  );
  return (
    <div style={{position: 'relative', width: PHONE_W, height: PHONE_H}}>
      {sideButton('left', 128, 34)}
      {sideButton('left', 190, 62)}
      {sideButton('left', 266, 62)}
      {sideButton('right', 214, 98)}
      {sideButton('right', 470, 48)}
      {/* Titanium band */}
      <div
        style={{
          position: 'absolute',
          inset: 0,
          borderRadius: OUTER_R,
          background: 'linear-gradient(135deg, #6c6b70 0%, #2d2d31 22%, #46464b 50%, #2a2a2e 78%, #5d5c61 100%)',
          boxShadow: 'inset 0 0 0 1px rgba(255,255,255,0.18), inset 0 0 0 2.5px rgba(0,0,0,0.6)',
        }}
      />
      {/* Black display border */}
      <div style={{position: 'absolute', inset: FRAME, borderRadius: OUTER_R - FRAME, background: '#030304'}} />
      {/* Display */}
      <div
        style={{
          position: 'absolute',
          left: FRAME + BEZEL,
          top: FRAME + BEZEL,
          width: SCREEN_W,
          height: SCREEN_H,
          borderRadius: SCREEN_R,
          overflow: 'hidden',
          background: '#000',
          // Keeps the rounded clip when children use 3D transforms.
          isolation: 'isolate',
          WebkitMaskImage: '-webkit-radial-gradient(white, black)',
        }}
      >
        {children}
        <StatusBar tone={statusTone} />
        <DynamicIsland expand={island?.expand ?? 0} progress={island?.progress ?? 0} done={island?.done ?? 0} />
        <div
          style={{
            position: 'absolute',
            bottom: 8,
            left: (SCREEN_W - 136) / 2,
            width: 136,
            height: 5,
            borderRadius: 3,
            background: interpolateColors(homeTone, [0, 1], ['#111111', '#FFFFFF']),
            zIndex: 60,
          }}
        />
        {/* Glass reflection */}
        <div
          style={{
            position: 'absolute',
            inset: 0,
            background: 'linear-gradient(115deg, rgba(255,255,255,0.10) 0%, rgba(255,255,255,0.03) 28%, rgba(255,255,255,0) 42%)',
            pointerEvents: 'none',
            zIndex: 70,
          }}
        />
      </div>
    </div>
  );
};
