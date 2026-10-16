import React from 'react';
import {AbsoluteFill} from 'remotion';
import {COLORS} from '../theme';

// Dark background with a soft radial glow that drifts toward the action.
export const Background: React.FC<{glowX: number; glowY: number; size: number}> = ({glowX, glowY, size}) => (
  <AbsoluteFill style={{background: COLORS.bg}}>
    <AbsoluteFill
      style={{
        background: `radial-gradient(circle ${size}px at ${glowX}px ${glowY}px, rgba(47,107,255,0.22) 0%, rgba(22,199,183,0.08) 45%, rgba(11,15,20,0) 100%)`,
      }}
    />
    <AbsoluteFill
      style={{
        background: `radial-gradient(ellipse at 50% 45%, rgba(255,255,255,0.035) 0%, rgba(0,0,0,0) 55%), radial-gradient(ellipse at 50% 50%, rgba(0,0,0,0) 55%, rgba(0,0,0,0.45) 100%)`,
      }}
    />
  </AbsoluteFill>
);
