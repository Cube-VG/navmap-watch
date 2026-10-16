import React from 'react';
import {interpolate, spring, useCurrentFrame} from 'remotion';
import {CLAMP, EASE_IN_OUT, EASE_OUT} from '../lib/anim';
import {FPS} from '../timings';

// A semi-transparent fingertip that glides in, presses at `land` and leaves
// a ripple. Coordinates are in the parent's space (the phone screen).
export const TapIndicator: React.FC<{x: number; y: number; land: number; size?: number}> = ({x, y, land, size = 46}) => {
  const frame = useCurrentFrame();
  const appear = land - 16;
  if (frame < appear || frame > land + 26) return null;

  const enter = spring({frame: frame - appear, fps: FPS, config: {damping: 20, stiffness: 150}});
  const press = interpolate(frame, [land - 3, land, land + 4, land + 10], [1, 0.78, 0.8, 0.96], {
    ...CLAMP,
    easing: EASE_IN_OUT,
  });
  const scale = (1.3 - 0.3 * enter) * press;
  const fadeOut = interpolate(frame, [land + 10, land + 24], [1, 0], {...CLAMP, easing: EASE_IN_OUT});
  const opacity = Math.min(enter, fadeOut);
  const dx = (1 - enter) * 28;
  const dy = (1 - enter) * 40;

  const rippleT = interpolate(frame, [land, land + 20], [0, 1], {...CLAMP, easing: EASE_OUT});
  const rippleSize = size * (1 + 1.7 * rippleT);

  return (
    <div style={{position: 'absolute', left: 0, top: 0, zIndex: 100, pointerEvents: 'none'}}>
      {frame >= land && rippleT < 1 && (
        <div
          style={{
            position: 'absolute',
            left: x - rippleSize / 2,
            top: y - rippleSize / 2,
            width: rippleSize,
            height: rippleSize,
            borderRadius: '50%',
            border: '3px solid rgba(255,255,255,0.95)',
            boxShadow: '0 0 0 1.5px rgba(20,30,50,0.28), inset 0 0 0 1.5px rgba(20,30,50,0.2)',
            opacity: (1 - rippleT) * 0.9,
          }}
        />
      )}
      <div
        style={{
          position: 'absolute',
          left: x + dx - size / 2,
          top: y + dy - size / 2,
          width: size,
          height: size,
          borderRadius: '50%',
          background: 'radial-gradient(circle at 40% 35%, rgba(255,255,255,0.55), rgba(190,200,215,0.42) 60%, rgba(120,130,150,0.45))',
          border: '2.5px solid rgba(255,255,255,0.92)',
          boxShadow: '0 6px 16px rgba(0,0,0,0.35), 0 0 0 1px rgba(20,30,50,0.25)',
          transform: `scale(${scale})`,
          opacity,
        }}
      />
    </div>
  );
};
