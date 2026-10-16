import React from 'react';

type IconProps = {size: number; color?: string; style?: React.CSSProperties};

// Turn arrow: up then right (mirror for left).
export const TurnArrow: React.FC<IconProps & {dir?: 'right' | 'left'}> = ({size, color = '#fff', dir = 'right', style}) => (
  <svg width={size} height={size} viewBox="0 0 48 48" style={{display: 'block', ...style}}>
    <g transform={dir === 'left' ? 'translate(48 0) scale(-1 1)' : undefined}>
      <path d="M 15 44 L 15 25 Q 15 17 23 17 L 31 17" fill="none" stroke={color} strokeWidth={6.5} strokeLinejoin="round" />
      <path d="M 29 6 L 44 17 L 29 28 Z" fill={color} stroke={color} strokeWidth={2} strokeLinejoin="round" />
    </g>
  </svg>
);

export const ShareIcon: React.FC<IconProps> = ({size, color = '#1C1C1E', style}) => (
  <svg width={size} height={size} viewBox="0 0 24 24" style={{display: 'block', ...style}}>
    <g fill="none" stroke={color} strokeWidth={1.9} strokeLinecap="round" strokeLinejoin="round">
      <path d="M12 2.8 V 14.5" />
      <path d="M 8 6.6 L 12 2.8 L 16 6.6" />
      <path d="M 8.5 9.5 H 6.8 A 1.8 1.8 0 0 0 5 11.3 V 19.4 A 1.8 1.8 0 0 0 6.8 21.2 H 17.2 A 1.8 1.8 0 0 0 19 19.4 V 11.3 A 1.8 1.8 0 0 0 17.2 9.5 H 15.5" />
    </g>
  </svg>
);

export const SpeakerIcon: React.FC<IconProps> = ({size, color = '#1C1C1E', style}) => (
  <svg width={size} height={size} viewBox="0 0 24 24" style={{display: 'block', ...style}}>
    <path d="M 3.5 9 H 7.5 L 12.5 5 V 19 L 7.5 15 H 3.5 Z" fill={color} strokeLinejoin="round" stroke={color} strokeWidth={1} />
    <path d="M 15.5 9 Q 17.5 12 15.5 15" fill="none" stroke={color} strokeWidth={1.9} strokeLinecap="round" />
    <path d="M 18 6.5 Q 22 12 18 17.5" fill="none" stroke={color} strokeWidth={1.9} strokeLinecap="round" />
  </svg>
);

export const SearchIcon: React.FC<IconProps> = ({size, color = '#1C1C1E', style}) => (
  <svg width={size} height={size} viewBox="0 0 24 24" style={{display: 'block', ...style}}>
    <circle cx={10.5} cy={10.5} r={6.2} fill="none" stroke={color} strokeWidth={2.1} />
    <path d="M 15.2 15.2 L 20 20" stroke={color} strokeWidth={2.3} strokeLinecap="round" />
  </svg>
);

export const CloseIcon: React.FC<IconProps> = ({size, color = '#1C1C1E', style}) => (
  <svg width={size} height={size} viewBox="0 0 24 24" style={{display: 'block', ...style}}>
    <path d="M 6 6 L 18 18 M 18 6 L 6 18" stroke={color} strokeWidth={2.2} strokeLinecap="round" />
  </svg>
);

export const BackIcon: React.FC<IconProps> = ({size, color = '#1C1C1E', style}) => (
  <svg width={size} height={size} viewBox="0 0 24 24" style={{display: 'block', ...style}}>
    <path d="M 20 12 H 5 M 11 5.5 L 4.5 12 L 11 18.5" fill="none" stroke={color} strokeWidth={2.2} strokeLinecap="round" strokeLinejoin="round" />
  </svg>
);

export const SwapIcon: React.FC<IconProps> = ({size, color = '#1C1C1E', style}) => (
  <svg width={size} height={size} viewBox="0 0 24 24" style={{display: 'block', ...style}}>
    <g fill="none" stroke={color} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
      <path d="M 8 4 V 19 M 4.5 7.5 L 8 4 L 11.5 7.5" />
      <path d="M 16 20 V 5 M 12.5 16.5 L 16 20 L 19.5 16.5" />
    </g>
  </svg>
);

export const ForkIcon: React.FC<IconProps> = ({size, color = '#1C1C1E', style}) => (
  <svg width={size} height={size} viewBox="0 0 24 24" style={{display: 'block', ...style}}>
    <g fill="none" stroke={color} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
      <path d="M 12 21 V 13 Q 12 10 9 8 L 6 6" />
      <path d="M 12 13 Q 12 10 15 8 L 18 6" />
      <path d="M 5 10 V 5 H 10" />
      <path d="M 19 10 V 5 H 14" />
    </g>
  </svg>
);

export const CarIcon: React.FC<IconProps> = ({size, color = '#1C1C1E', style}) => (
  <svg width={size} height={size} viewBox="0 0 24 24" style={{display: 'block', ...style}}>
    <path
      d="M 5.2 10 L 6.8 5.8 A 2 2 0 0 1 8.7 4.5 H 15.3 A 2 2 0 0 1 17.2 5.8 L 18.8 10 A 2 2 0 0 1 20.5 12 V 17.5 A 1 1 0 0 1 19.5 18.5 H 18 A 1 1 0 0 1 17 17.5 V 16.5 H 7 V 17.5 A 1 1 0 0 1 6 18.5 H 4.5 A 1 1 0 0 1 3.5 17.5 V 12 A 2 2 0 0 1 5.2 10 Z"
      fill={color}
    />
    <path d="M 7 9.8 L 8.1 6.8 H 15.9 L 17 9.8 Z" fill="#fff" opacity={0.9} />
    <circle cx={7} cy={13} r={1.3} fill="#fff" />
    <circle cx={17} cy={13} r={1.3} fill="#fff" />
  </svg>
);

export const TransitIcon: React.FC<IconProps> = ({size, color = '#1C1C1E', style}) => (
  <svg width={size} height={size} viewBox="0 0 24 24" style={{display: 'block', ...style}}>
    <rect x={5} y={3.5} width={14} height={14} rx={3} fill="none" stroke={color} strokeWidth={2} />
    <path d="M 5 10.5 H 19" stroke={color} strokeWidth={2} />
    <circle cx={8.5} cy={14} r={1.1} fill={color} />
    <circle cx={15.5} cy={14} r={1.1} fill={color} />
    <path d="M 8 18 L 6.5 21 M 16 18 L 17.5 21" stroke={color} strokeWidth={2} strokeLinecap="round" />
  </svg>
);

export const WalkIcon: React.FC<IconProps> = ({size, color = '#1C1C1E', style}) => (
  <svg width={size} height={size} viewBox="0 0 24 24" style={{display: 'block', ...style}}>
    <circle cx={13} cy={4.2} r={2} fill={color} />
    <path
      d="M 9 21 L 11.2 14 L 13.5 16 V 21 M 11.2 14 L 12 9 L 15.5 12 L 18 12.5 M 12 9 L 9 10.5 L 7.5 13.5"
      fill="none"
      stroke={color}
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
    />
  </svg>
);

export const BikeIcon: React.FC<IconProps> = ({size, color = '#1C1C1E', style}) => (
  <svg width={size} height={size} viewBox="0 0 24 24" style={{display: 'block', ...style}}>
    <g fill="none" stroke={color} strokeWidth={1.9} strokeLinecap="round" strokeLinejoin="round">
      <circle cx={6} cy={16} r={3.6} />
      <circle cx={18} cy={16} r={3.6} />
      <path d="M 6 16 L 9.5 9 H 15.5 L 18 16 M 9.5 9 L 12.5 16 L 15.5 9 M 8.5 6.5 H 11 M 15 6 H 17" />
    </g>
  </svg>
);

export const CopyIcon: React.FC<IconProps> = ({size, color = '#1C1C1E', style}) => (
  <svg width={size} height={size} viewBox="0 0 24 24" style={{display: 'block', ...style}}>
    <g fill="none" stroke={color} strokeWidth={1.8} strokeLinejoin="round">
      <rect x={8} y={8} width={11.5} height={12.5} rx={2.2} />
      <path d="M 15.5 8 V 5.8 A 2.2 2.2 0 0 0 13.3 3.6 H 6.7 A 2.2 2.2 0 0 0 4.5 5.8 V 14 A 2.2 2.2 0 0 0 6.7 16.2 H 8" />
    </g>
  </svg>
);

export const StarIcon: React.FC<IconProps> = ({size, color = '#1C1C1E', style}) => (
  <svg width={size} height={size} viewBox="0 0 24 24" style={{display: 'block', ...style}}>
    <path
      d="M 12 3.5 L 14.6 9 L 20.5 9.6 L 16 13.6 L 17.3 19.6 L 12 16.6 L 6.7 19.6 L 8 13.6 L 3.5 9.6 L 9.4 9 Z"
      fill="none"
      stroke={color}
      strokeWidth={1.8}
      strokeLinejoin="round"
    />
  </svg>
);

export const FolderIcon: React.FC<IconProps> = ({size, color = '#1C1C1E', style}) => (
  <svg width={size} height={size} viewBox="0 0 24 24" style={{display: 'block', ...style}}>
    <path
      d="M 3.5 7 A 2 2 0 0 1 5.5 5 H 9.5 L 11.5 7 H 18.5 A 2 2 0 0 1 20.5 9 V 17.5 A 2 2 0 0 1 18.5 19.5 H 5.5 A 2 2 0 0 1 3.5 17.5 Z"
      fill="none"
      stroke={color}
      strokeWidth={1.8}
      strokeLinejoin="round"
    />
  </svg>
);

export const FootstepsIcon: React.FC<IconProps> = ({size, color = '#fff', style}) => (
  <svg width={size} height={size} viewBox="0 0 24 24" style={{display: 'block', ...style}}>
    <ellipse cx={8} cy={8.5} rx={3.2} ry={5} fill={color} transform="rotate(-12 8 8.5)" />
    <rect x={5.6} y={14.6} width={4.6} height={3.4} rx={1.6} fill={color} transform="rotate(-12 8 16)" />
    <ellipse cx={16.2} cy={11} rx={3.2} ry={5} fill={color} transform="rotate(12 16.2 11)" />
    <rect x={14} y={17.2} width={4.6} height={3.4} rx={1.6} fill={color} transform="rotate(12 16.2 18.8)" />
  </svg>
);

export const HeartIcon: React.FC<IconProps> = ({size, color = '#FF4D5E', style}) => (
  <svg width={size} height={size} viewBox="0 0 24 24" style={{display: 'block', ...style}}>
    <path
      d="M 12 20.5 C 6 16.3, 2.5 13, 2.5 8.8 C 2.5 6 4.6 3.8 7.3 3.8 C 9.2 3.8 10.9 4.9 12 6.6 C 13.1 4.9 14.8 3.8 16.7 3.8 C 19.4 3.8 21.5 6 21.5 8.8 C 21.5 13 18 16.3 12 20.5 Z"
      fill={color}
    />
  </svg>
);

export const SunIcon: React.FC<IconProps> = ({size, color = '#FFC94A', style}) => (
  <svg width={size} height={size} viewBox="0 0 24 24" style={{display: 'block', ...style}}>
    <circle cx={12} cy={12} r={4.6} fill={color} />
    {Array.from({length: 8}).map((_, i) => (
      <path
        key={i}
        d="M 12 2.2 V 5"
        stroke={color}
        strokeWidth={2}
        strokeLinecap="round"
        transform={`rotate(${i * 45} 12 12)`}
      />
    ))}
  </svg>
);
