import React from 'react';
import {AbsoluteFill, useCurrentFrame} from 'remotion';
import {smooth} from '../lib/anim';
import {CAPTIONS} from '../timings';
import {COLORS, FONT} from '../theme';

// One line under the devices per step.
export const Captions: React.FC<{y: number; size: number}> = ({y, size}) => {
  const frame = useCurrentFrame();
  return (
    <AbsoluteFill style={{pointerEvents: 'none'}}>
      {CAPTIONS.map((c, i) => {
        if (frame < c.from - 1 || frame > c.to + 4) return null;
        // Each caption leaves before the next arrives, so they never overlap.
        const inP = smooth(frame, i === 0 ? c.from : c.from + 3, 20);
        const outP = smooth(frame, c.to - 13, 12);
        const opacity = inP * (1 - outP);
        return (
          <div
            key={c.text}
            style={{
              position: 'absolute',
              left: 0,
              right: 0,
              top: y - size * 0.7,
              textAlign: 'center',
              fontFamily: FONT,
              fontSize: size,
              fontWeight: 500,
              letterSpacing: -0.2,
              lineHeight: `${size * 1.4}px`,
              color: COLORS.caption,
              opacity,
              transform: `translateY(${(1 - inP) * 18 - outP * 14}px)`,
              textShadow: '0 2px 12px rgba(0,0,0,0.5)',
            }}
          >
            {c.text}
          </div>
        );
      })}
    </AbsoluteFill>
  );
};
