import React from 'react';
import {BackIcon, BikeIcon, CarIcon, SwapIcon, TransitIcon, WalkIcon} from '../../components/icons';
import {SCREEN_H, SCREEN_W} from '../../components/IPhoneFrame';
import {COLORS, FONT} from '../../theme';
import {PhoneMap} from './PhoneMap';
import {PhoneMapView, PREVIEW_VIEW} from './phoneMapViews';

// Centre of the green Start button, for the tap indicator.
export const START_BUTTON = {x: 82, y: 746};

const SHEET_TOP = 606;
const TOP_CARD_H = 198;

const Field: React.FC<{icon: React.ReactNode; text: string; top: number}> = ({icon, text, top}) => (
  <div
    style={{
      position: 'absolute',
      left: 54,
      right: 52,
      top,
      height: 40,
      borderRadius: 10,
      border: '1.5px solid #DADCE0',
      display: 'flex',
      alignItems: 'center',
      gap: 11,
      paddingLeft: 13,
      fontSize: 16,
      color: '#202124',
      fontWeight: 500,
    }}
  >
    {icon}
    {text}
  </div>
);

const Chip: React.FC<{icon: React.ReactNode; label: string; selected?: boolean}> = ({icon, label, selected}) => (
  <div
    style={{
      height: 32,
      padding: '0 10px',
      borderRadius: 16,
      display: 'flex',
      alignItems: 'center',
      gap: 5,
      fontSize: 13,
      fontWeight: 600,
      background: selected ? '#D3E3FD' : 'transparent',
      color: selected ? '#0B57D0' : '#444746',
      whiteSpace: 'nowrap',
    }}
  >
    {icon}
    {label}
  </div>
);

export const MapsRoutePreview: React.FC<{
  exit: number; // 0..1, cards leave as navigation starts
  view: PhoneMapView;
  routeReveal: number;
  startPressed: number; // 0..1
  uiIn: number; // 0..1, cards settle in
}> = ({exit, view, routeReveal, startPressed, uiIn}) => {
  // "18 min" bubble on the route, projected from world coords.
  const bubbleWorld = {x: 760, y: 520};
  const bx = view.anchor.x + (bubbleWorld.x - view.center.x) * view.zoom;
  const by = view.anchor.y + (bubbleWorld.y - view.center.y) * view.zoom;

  const topY = -TOP_CARD_H * 1.1 * exit - (1 - uiIn) * 30;
  const sheetY = (SCREEN_H - SHEET_TOP + 20) * exit + (1 - uiIn) * 40;

  return (
    <div style={{position: 'absolute', inset: 0, fontFamily: FONT}}>
      <PhoneMap view={view} routeReveal={routeReveal} showStartDot showPin />

      {/* Duration bubble */}
      <div
        style={{
          position: 'absolute',
          left: bx - 44,
          top: by - 44,
          height: 30,
          whiteSpace: 'nowrap',
          padding: '0 10px',
          borderRadius: 8,
          background: '#fff',
          boxShadow: '0 2px 6px rgba(0,0,0,0.25)',
          display: 'flex',
          alignItems: 'center',
          gap: 5,
          fontSize: 13.5,
          fontWeight: 700,
          color: COLORS.etaGreen,
          opacity: routeReveal * (1 - exit),
          transform: `scale(${0.8 + 0.2 * routeReveal})`,
        }}
      >
        <CarIcon size={15} color={COLORS.etaGreen} />
        18 min
      </div>

      {/* Top card: from / to and travel modes */}
      <div
        style={{
          position: 'absolute',
          left: 0,
          top: 0,
          width: SCREEN_W,
          height: TOP_CARD_H,
          background: '#fff',
          boxShadow: '0 2px 10px rgba(0,0,0,0.18)',
          borderBottomLeftRadius: 18,
          borderBottomRightRadius: 18,
          transform: `translateY(${topY}px)`,
        }}
      >
        <BackIcon size={24} color="#444746" style={{position: 'absolute', left: 17, top: 66}} />
        <Field
          top={58}
          text="Your location"
          icon={<div style={{width: 12, height: 12, borderRadius: 6, background: COLORS.routeBlue, border: '2px solid #fff', boxShadow: '0 0 0 1.5px #9DBCF7'}} />}
        />
        <Field
          top={104}
          text="Golden Gate Park"
          icon={
            <svg width={14} height={18} viewBox="-14 -42 28 44">
              <path d="M0 0 C -4 -10, -14 -16, -14 -27 A 14 14 0 1 1 14 -27 C 14 -16, 4 -10, 0 0 Z" fill={COLORS.pinRed} />
              <circle cx={0} cy={-27} r={5.5} fill="#7A1410" />
            </svg>
          }
        />
        <SwapIcon size={24} color="#444746" style={{position: 'absolute', right: 15, top: 88}} />
        <div style={{position: 'absolute', left: 12, right: 12, top: 154, display: 'flex', justifyContent: 'space-between'}}>
          <Chip selected icon={<CarIcon size={17} color="#0B57D0" />} label="18 min" />
          <Chip icon={<TransitIcon size={17} color="#444746" />} label="34 min" />
          <Chip icon={<WalkIcon size={17} color="#444746" />} label="1 hr 58" />
          <Chip icon={<BikeIcon size={17} color="#444746" />} label="36 min" />
        </div>
      </div>

      {/* Bottom sheet: duration and Start */}
      <div
        style={{
          position: 'absolute',
          left: 0,
          top: SHEET_TOP,
          width: SCREEN_W,
          height: SCREEN_H - SHEET_TOP,
          background: '#fff',
          borderTopLeftRadius: 24,
          borderTopRightRadius: 24,
          boxShadow: '0 -3px 14px rgba(0,0,0,0.16)',
          transform: `translateY(${sheetY}px)`,
        }}
      >
        <div style={{position: 'absolute', left: (SCREEN_W - 36) / 2, top: 8, width: 36, height: 5, borderRadius: 3, background: '#DADCE0'}} />
        <div style={{position: 'absolute', left: 22, top: 26, display: 'flex', alignItems: 'baseline', gap: 7}}>
          <span style={{fontSize: 25, fontWeight: 700, color: COLORS.etaGreen, letterSpacing: -0.3}}>18 min</span>
          <span style={{fontSize: 19, fontWeight: 500, color: '#5F6368'}}>(6.2 mi)</span>
        </div>
        <div style={{position: 'absolute', left: 22, top: 64, fontSize: 14.5, color: '#5F6368', lineHeight: '21px'}}>
          Fastest route, the usual traffic
          <br />
          via Market St and Divisadero St
        </div>
        <div style={{position: 'absolute', left: 22, top: 117, display: 'flex', gap: 10}}>
          <div
            style={{
              width: 120,
              height: 46,
              borderRadius: 23,
              background: startPressed > 0 ? '#16702F' : COLORS.startGreen,
              color: '#fff',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: 8,
              fontSize: 17,
              fontWeight: 650,
              transform: `scale(${1 - 0.05 * startPressed})`,
              boxShadow: '0 2px 6px rgba(30,142,62,0.35)',
            }}
          >
            <svg width={18} height={18} viewBox="-12 -14 24 28">
              <path d="M 0 -12 L 10 12 L 0 6 L -10 12 Z" fill="#fff" />
            </svg>
            Start
          </div>
          {['Steps', 'Pin'].map((label) => (
            <div
              key={label}
              style={{
                height: 46,
                padding: '0 20px',
                borderRadius: 23,
                border: '1.5px solid #DADCE0',
                color: COLORS.startGreen,
                display: 'flex',
                alignItems: 'center',
                fontSize: 16,
                fontWeight: 600,
              }}
            >
              {label}
            </div>
          ))}
        </div>
        <div style={{position: 'absolute', left: 22, right: 22, top: 184, height: 1, background: '#ECEDEF'}} />
        <div style={{position: 'absolute', left: 22, top: 198, fontSize: 14, color: '#5F6368'}}>
          Leave now · Arrive at <span style={{color: '#202124', fontWeight: 600}}>9:59 AM</span>
        </div>
      </div>
    </div>
  );
};

export {PREVIEW_VIEW};
