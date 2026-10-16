import React from 'react';
import {AbsoluteFill, useCurrentFrame} from 'remotion';
import {bouncy, smooth} from '../lib/anim';
import {Layout} from '../layouts';
import {COLORS, FONT} from '../theme';
import {T} from '../timings';
import {NavMapGlyph} from './NavMapIcon';

// "NavMap — Google Maps to your Garmin, in one tap."
export const TitleCard: React.FC<{layout: Layout}> = ({layout}) => {
  const frame = useCurrentFrame();
  if (frame > T.titleOut + 34) return null;
  const icon = bouncy(frame, T.titleIn, 12, 0.7, 150);
  const word = smooth(frame, T.titleIn + 5, 26);
  const tag = smooth(frame, T.titleIn + 14, 28);
  const out = smooth(frame, T.titleOut, 20);
  const iconSize = layout.titleSize * 0.92;
  return (
    <AbsoluteFill
      style={{
        alignItems: 'center',
        justifyContent: 'center',
        fontFamily: FONT,
        opacity: 1 - out,
        transform: `translateY(${-40 * out}px) scale(${1 + 0.04 * out})`,
        filter: out > 0.01 ? `blur(${out * 8}px)` : undefined,
      }}
    >
      <div style={{display: 'flex', alignItems: 'center', gap: layout.titleSize * 0.3}}>
        <div style={{transform: `scale(${icon})`, opacity: Math.min(1, icon * 1.5)}}>
          <NavMapGlyph size={iconSize} shadow />
        </div>
        <div
          style={{
            fontSize: layout.titleSize,
            fontWeight: 800,
            letterSpacing: -layout.titleSize * 0.035,
            color: '#fff',
            opacity: word,
            transform: `translateX(${(1 - word) * -24}px)`,
          }}
        >
          Nav<span style={{color: '#7CC8FF'}}>Map</span>
        </div>
      </div>
      <div
        style={{
          marginTop: layout.titleSize * 0.36,
          maxWidth: layout.taglineWidth,
          textAlign: 'center',
          fontSize: layout.taglineSize,
          fontWeight: 500,
          lineHeight: 1.25,
          letterSpacing: -0.4,
          color: COLORS.caption,
          opacity: tag,
          transform: `translateY(${(1 - tag) * 22}px)`,
        }}
      >
        Google Maps to your Garmin,{layout.taglineBreak ? <br /> : ' '}in one tap.
      </div>
    </AbsoluteFill>
  );
};
