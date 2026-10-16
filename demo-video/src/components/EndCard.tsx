import React from 'react';
import {AbsoluteFill, useCurrentFrame} from 'remotion';
import {smooth} from '../lib/anim';
import {Layout} from '../layouts';
import {COLORS, FONT} from '../theme';
import {T} from '../timings';
import {NavMapWordmark} from './NavMapIcon';

// "Start in Google Maps. Share to NavMap. Go." + wordmark.
export const EndCard: React.FC<{layout: Layout}> = ({layout}) => {
  const frame = useCurrentFrame();
  if (frame < T.endDim) return null;
  const scrim = smooth(frame, T.endDim, 22);
  const parts = [
    {text: 'Start in Google Maps.', at: T.endLine1},
    {text: 'Share to NavMap.', at: T.endLine2},
    {text: 'Go.', at: T.endLine3, accent: true},
  ];
  const mark = smooth(frame, T.endWordmark, 20);
  return (
    <AbsoluteFill style={{alignItems: 'center', justifyContent: 'center', fontFamily: FONT}}>
      <AbsoluteFill
        style={{
          background: `radial-gradient(ellipse ${layout.width * 0.55}px ${layout.height * 0.42}px at 50% 50%, rgba(11,15,20,0.82) 0%, rgba(11,15,20,0.45) 100%)`,
          opacity: scrim,
        }}
      />
      <div
        style={{
          display: 'flex',
          flexDirection: layout.endStacked ? 'column' : 'row',
          alignItems: 'center',
          gap: layout.endStacked ? layout.endSize * 0.2 : layout.endSize * 0.32,
        }}
      >
        {parts.map((p) => {
          const t = smooth(frame, p.at, 18);
          return (
            <div
              key={p.text}
              style={{
                fontSize: layout.endSize,
                fontWeight: 700,
                letterSpacing: -layout.endSize * 0.02,
                color: '#fff',
                opacity: t,
                transform: `translateY(${(1 - t) * 26}px)`,
                textShadow: '0 4px 24px rgba(0,0,0,0.6)',
                ...(p.accent
                  ? {
                      background: `linear-gradient(90deg, #6FB2FF, ${COLORS.navmapTeal})`,
                      WebkitBackgroundClip: 'text',
                      backgroundClip: 'text',
                      color: 'transparent',
                      textShadow: 'none',
                    }
                  : {}),
              }}
            >
              {p.text}
            </div>
          );
        })}
      </div>
      <div style={{marginTop: layout.endSize * 0.9, opacity: mark, transform: `translateY(${(1 - mark) * 18}px) scale(${0.94 + 0.06 * mark})`}}>
        <NavMapWordmark size={layout.endSize * 0.85} />
      </div>
    </AbsoluteFill>
  );
};
