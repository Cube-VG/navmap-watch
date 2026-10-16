import React from 'react';
import {CloseIcon, CopyIcon, FolderIcon, StarIcon} from '../../components/icons';
import {SCREEN_H, SCREEN_W} from '../../components/IPhoneFrame';
import {MapCanvas} from '../../components/MapCanvas';
import {NavMapGlyph} from '../../components/NavMapIcon';
import {COLORS, FONT} from '../../theme';

const SHEET_H = 478;
const SHEET_TOP = SCREEN_H - SHEET_H;
const ICON = 62;
const ROW_Y = 104; // icon row top, inside the sheet
const ICON_STEP = (SCREEN_W - 32 - ICON) / 4;
const iconX = (i: number) => 16 + i * ICON_STEP;

// Centre of the NavMap icon on screen, for the tap indicator.
export const NAVMAP_SHARE_ICON = {x: iconX(2) + ICON / 2, y: SHEET_TOP + ROW_Y + ICON / 2};

// Generic share-sheet app icons, drawn in a 62 x 62 box.
const AppTile: React.FC<{bg: string; children?: React.ReactNode}> = ({bg, children}) => (
  <div style={{width: ICON, height: ICON, borderRadius: 14, background: bg, overflow: 'hidden', position: 'relative'}}>
    <svg width={ICON} height={ICON} viewBox="0 0 62 62" style={{display: 'block'}}>
      {children}
    </svg>
  </div>
);

const MessagesTile = () => (
  <AppTile bg="linear-gradient(180deg, #6CE87F, #1DB83A)">
    <ellipse cx={31} cy={29} rx={19} ry={15.5} fill="#fff" />
    <path d="M 17 37 Q 15 44 11 46 Q 20 46 25 41 Z" fill="#fff" />
  </AppTile>
);

const MailTile = () => (
  <AppTile bg="linear-gradient(180deg, #4FA9FF, #1366F0)">
    <rect x={12} y={18} width={38} height={26} rx={4} fill="#fff" />
    <path d="M 13 20 L 31 33 L 49 20" fill="none" stroke="#2C7DF7" strokeWidth={2.4} strokeLinejoin="round" />
  </AppTile>
);

const NotesTile = () => (
  <AppTile bg="#FFFFFF">
    <rect width={62} height={17} fill="#FFCF3A" />
    {[27, 36, 45].map((y) => (
      <path key={y} d={`M 11 ${y} H 51`} stroke="#D6D6DA" strokeWidth={2} />
    ))}
    <rect x={0.5} y={0.5} width={61} height={61} rx={13.5} fill="none" stroke="#E2E2E6" />
  </AppTile>
);

const MoreTile = () => (
  <div
    style={{
      width: ICON,
      height: ICON,
      borderRadius: ICON / 2,
      background: '#E3E3E8',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      gap: 5,
    }}
  >
    {[0, 1, 2].map((i) => (
      <div key={i} style={{width: 7, height: 7, borderRadius: 4, background: '#6D6D72'}} />
    ))}
  </div>
);

const ActionRow: React.FC<{label: string; icon: React.ReactNode; last?: boolean}> = ({label, icon, last}) => (
  <div
    style={{
      height: 50,
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'space-between',
      padding: '0 16px',
      fontSize: 16.5,
      color: COLORS.iosLabel,
      borderBottom: last ? undefined : '1px solid #E5E5EA',
    }}
  >
    {label}
    {icon}
  </div>
);

export const ShareSheet: React.FC<{
  enter: number; // spring 0..1 (may overshoot)
  exit: number; // 0..1
  navmapPressed: number; // 0..1
}> = ({enter, exit, navmapPressed}) => {
  const y = (1 - enter) * (SHEET_H + 30) + exit * (SHEET_H + 30);
  const dim = Math.min(1, Math.max(0, enter)) * (1 - exit);
  const apps: {label: string; tile: React.ReactNode}[] = [
    {label: 'Messages', tile: <MessagesTile />},
    {label: 'Mail', tile: <MailTile />},
    {label: 'NavMap', tile: <NavMapGlyph size={ICON} radius={14} />},
    {label: 'Notes', tile: <NotesTile />},
    {label: 'More', tile: <MoreTile />},
  ];
  return (
    <div style={{position: 'absolute', inset: 0, fontFamily: FONT, zIndex: 30}}>
      <div style={{position: 'absolute', inset: 0, background: `rgba(0,0,0,${0.32 * dim})`}} />
      <div
        style={{
          position: 'absolute',
          left: 0,
          top: SHEET_TOP,
          width: SCREEN_W,
          height: SHEET_H + 60,
          background: COLORS.iosSheet,
          borderTopLeftRadius: 16,
          borderTopRightRadius: 16,
          boxShadow: '0 -6px 24px rgba(0,0,0,0.22)',
          transform: `translateY(${y}px)`,
        }}
      >
        {/* Header: what's being shared */}
        <div style={{position: 'absolute', left: 16, top: 18, width: 54, height: 54, borderRadius: 12, overflow: 'hidden', border: '1px solid #DADADF'}}>
          <MapCanvas width={54} height={54} center={{x: 720, y: 811}} zoom={0.062} theme="phone" routePx={2.6} roadScale={2.2} showPin pinScale={0.45} />
        </div>
        <div style={{position: 'absolute', left: 82, top: 22}}>
          <div style={{fontSize: 16, fontWeight: 600, color: COLORS.iosLabel, lineHeight: '22px'}}>Golden Gate Park</div>
          <div style={{fontSize: 13.5, color: COLORS.iosSecondary, lineHeight: '20px'}}>Directions · 18 min (6.2 mi)</div>
        </div>
        <div
          style={{
            position: 'absolute',
            right: 16,
            top: 22,
            width: 30,
            height: 30,
            borderRadius: 15,
            background: '#E3E3E8',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <CloseIcon size={16} color="#6D6D72" />
        </div>
        <div style={{position: 'absolute', left: 0, right: 0, top: 88, height: 1, background: '#DCDCE0'}} />

        {/* App row */}
        {apps.map((a, i) => {
          const isNavMap = a.label === 'NavMap';
          return (
            <div
              key={a.label}
              style={{
                position: 'absolute',
                left: iconX(i),
                top: ROW_Y,
                width: ICON,
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                gap: 7,
                transform: isNavMap ? `scale(${1 - 0.1 * navmapPressed})` : undefined,
                filter: isNavMap && navmapPressed > 0 ? `brightness(${1 - 0.18 * navmapPressed})` : undefined,
              }}
            >
              {a.tile}
              <div style={{fontSize: 12, color: COLORS.iosLabel, whiteSpace: 'nowrap', fontWeight: isNavMap ? 600 : 400}}>{a.label}</div>
            </div>
          );
        })}

        {/* Actions */}
        <div style={{position: 'absolute', left: 16, right: 16, top: 206, background: '#fff', borderRadius: 12, overflow: 'hidden'}}>
          <ActionRow label="Copy" icon={<CopyIcon size={22} />} />
          <ActionRow label="Add to Favorites" icon={<StarIcon size={22} />} />
          <ActionRow label="Save to Files" icon={<FolderIcon size={22} />} last />
        </div>
        <div style={{position: 'absolute', left: 32, top: 382, fontSize: 16.5, color: COLORS.iosBlue}}>Edit Actions…</div>
      </div>
    </div>
  );
};
