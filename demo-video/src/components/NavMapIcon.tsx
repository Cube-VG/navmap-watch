import React, {useId} from 'react';
import {COLORS, FONT} from '../theme';

// NavMap's app icon: blue-to-teal gradient, a white navigation arrow pointing
// at a small round watch. Drawn in a 100 x 100 box.
export const NavMapGlyph: React.FC<{size: number; radius?: number; shadow?: boolean}> = ({size, radius, shadow}) => {
  const id = useId().replace(/:/g, '');
  const r = radius ?? size * 0.225;
  return (
    <div
      style={{
        width: size,
        height: size,
        borderRadius: r,
        overflow: 'hidden',
        boxShadow: shadow ? '0 6px 18px rgba(22,120,220,0.35)' : undefined,
        flexShrink: 0,
      }}
    >
      <svg width={size} height={size} viewBox="0 0 100 100" style={{display: 'block'}}>
        <defs>
          <linearGradient id={`g${id}`} x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" stopColor={COLORS.navmapBlue} />
            <stop offset="1" stopColor={COLORS.navmapTeal} />
          </linearGradient>
          <radialGradient id={`h${id}`} cx="0.3" cy="0.2" r="0.8">
            <stop offset="0" stopColor="#fff" stopOpacity={0.28} />
            <stop offset="1" stopColor="#fff" stopOpacity={0} />
          </radialGradient>
        </defs>
        <rect width={100} height={100} fill={`url(#g${id})`} />
        <rect width={100} height={100} fill={`url(#h${id})`} />
        {/* Motion dots */}
        <circle cx={12} cy={50} r={2.6} fill="#fff" opacity={0.55} />
        <circle cx={20} cy={50} r={2.6} fill="#fff" opacity={0.8} />
        {/* Navigation arrow pointing right, at the watch */}
        <path d="M 54 50 L 27 36.5 L 33.5 50 L 27 63.5 Z" fill="#fff" strokeLinejoin="round" stroke="#fff" strokeWidth={2.5} />
        {/* Small watch */}
        <rect x={67} y={27} width={14} height={11} rx={3} fill="#fff" opacity={0.9} />
        <rect x={67} y={62} width={14} height={11} rx={3} fill="#fff" opacity={0.9} />
        <circle cx={74} cy={50} r={14.5} fill="none" stroke="#fff" strokeWidth={5} />
        <circle cx={74} cy={50} r={3} fill="#fff" />
        <rect x={88.5} y={46.5} width={3.5} height={7} rx={1.5} fill="#fff" />
      </svg>
    </div>
  );
};

// Icon + wordmark lockup.
export const NavMapWordmark: React.FC<{size: number; color?: string; gap?: number}> = ({size, color = '#fff', gap}) => (
  <div style={{display: 'flex', alignItems: 'center', gap: gap ?? size * 0.28}}>
    <NavMapGlyph size={size * 1.05} shadow />
    <div
      style={{
        fontFamily: FONT,
        fontWeight: 800,
        fontSize: size,
        letterSpacing: -size * 0.035,
        color,
        lineHeight: 1,
      }}
    >
      Nav<span style={{color: '#7CC8FF'}}>Map</span>
    </div>
  </div>
);
