import React from 'react';
import {interpolate} from 'remotion';
import {CloseIcon, ForkIcon, SearchIcon, ShareIcon, SpeakerIcon, TurnArrow} from '../../components/icons';
import {SCREEN_H, SCREEN_W} from '../../components/IPhoneFrame';
import {CLAMP} from '../../lib/anim';
import {COLORS, FONT} from '../../theme';
import {PhoneMap} from './PhoneMap';
import {PhoneMapView} from './phoneMapViews';

const BANNER_H = 158;
const ETA_TOP = 706;
const BTN = 48;
const BTN_X = SCREEN_W - 16 - BTN;
const BTN_YS = [452, 512, 572];

// Centre of the share button in the navigation UI, for the tap indicator.
export const SHARE_BUTTON = {x: BTN_X + BTN / 2, y: BTN_YS[2] + BTN / 2};

const RoundButton: React.FC<{top: number; children: React.ReactNode; pressed?: number; opacity: number; shift: number}> = ({
  top,
  children,
  pressed = 0,
  opacity,
  shift,
}) => (
  <div
    style={{
      position: 'absolute',
      left: BTN_X + shift,
      top,
      width: BTN,
      height: BTN,
      borderRadius: BTN / 2,
      background: pressed > 0 ? '#E3E6EA' : '#fff',
      boxShadow: '0 2px 8px rgba(0,0,0,0.25)',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      opacity,
      transform: `scale(${1 - 0.08 * pressed})`,
    }}
  >
    {children}
  </div>
);

export const MapsNavigating: React.FC<{
  enter: number; // 0..1 from route preview
  view: PhoneMapView;
  travel: number; // world units driven
  distanceFt: number;
  sharePressed: number; // 0..1
}> = ({enter, view, travel, distanceFt, sharePressed}) => {
  const bannerY = -(BANNER_H + 60) * (1 - enter);
  const etaY = (SCREEN_H - ETA_TOP + 30) * (1 - enter);
  const uiOpacity = interpolate(enter, [0.4, 1], [0, 1], CLAMP);
  const puck = interpolate(enter, [0.7, 1], [0, 1], CLAMP);
  const bannerText = interpolate(enter, [0.6, 0.95], [0, 1], CLAMP);

  return (
    <div style={{position: 'absolute', inset: 0, fontFamily: FONT}}>
      <PhoneMap view={view} routeStart={travel} showStartDot={puck < 0.95} />

      {/* Position puck */}
      <div
        style={{
          position: 'absolute',
          left: view.anchor.x - 30,
          top: view.anchor.y - 30,
          width: 60,
          height: 60,
          opacity: puck,
          transform: `scale(${0.6 + 0.4 * puck})`,
        }}
      >
        <svg width={60} height={60} viewBox="-30 -30 60 60">
          <ellipse cx={0} cy={6} rx={21} ry={10} fill="rgba(0,0,0,0.18)" />
          <g transform="scale(1 0.82)">
            <path d="M 0 -22 L 17 17 L 0 8 L -17 17 Z" fill="#fff" />
            <path d="M 0 -15.5 L 11.5 11.5 L 0 5 L -11.5 11.5 Z" fill={COLORS.routeBlue} stroke={COLORS.routeBlueDark} strokeWidth={1} strokeLinejoin="round" />
          </g>
        </svg>
      </div>

      {/* Turn banner */}
      <div
        style={{
          position: 'absolute',
          left: 0,
          top: 0,
          width: SCREEN_W,
          height: BANNER_H,
          background: COLORS.navGreen,
          borderBottomLeftRadius: 20,
          borderBottomRightRadius: 20,
          boxShadow: '0 4px 14px rgba(0,0,0,0.25)',
          transform: `translateY(${bannerY}px)`,
          color: '#fff',
        }}
      >
        <div style={{opacity: bannerText}}>
          <TurnArrow size={56} style={{position: 'absolute', left: 18, top: 70}} />
          <div style={{position: 'absolute', left: 90, top: 64, fontSize: 32, fontWeight: 700, letterSpacing: -0.5, lineHeight: '38px'}}>
            {distanceFt} ft
          </div>
          <div style={{position: 'absolute', left: 90, top: 104, fontSize: 18, fontWeight: 600, lineHeight: '24px', opacity: 0.95}}>
            Turn right onto Market St
          </div>
        </div>
      </div>
      {/* "Then" pill */}
      <div
        style={{
          position: 'absolute',
          left: 16,
          top: BANNER_H + 8,
          height: 36,
          padding: '0 12px 0 14px',
          borderRadius: 18,
          background: COLORS.navGreenDark,
          color: '#fff',
          display: 'flex',
          alignItems: 'center',
          gap: 6,
          fontSize: 15,
          fontWeight: 600,
          boxShadow: '0 2px 8px rgba(0,0,0,0.2)',
          transform: `translateY(${bannerY}px)`,
          opacity: uiOpacity,
        }}
      >
        Then
        <TurnArrow size={22} dir="left" />
      </div>

      {/* Speed limit sign */}
      <div
        style={{
          position: 'absolute',
          left: 16,
          top: 610,
          width: 46,
          height: 58,
          borderRadius: 8,
          background: '#fff',
          border: '2px solid #222',
          boxShadow: '0 2px 6px rgba(0,0,0,0.25)',
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          color: '#111',
          opacity: uiOpacity,
          transform: `translateX(${-70 * (1 - uiOpacity)}px)`,
        }}
      >
        <div style={{fontSize: 8, fontWeight: 700, lineHeight: '9px', textAlign: 'center', letterSpacing: 0.2}}>
          SPEED
          <br />
          LIMIT
        </div>
        <div style={{fontSize: 22, fontWeight: 700, lineHeight: '24px'}}>25</div>
      </div>

      {/* Floating buttons */}
      <RoundButton top={BTN_YS[0]} opacity={uiOpacity} shift={70 * (1 - uiOpacity)}>
        <SpeakerIcon size={24} color="#3C4043" />
      </RoundButton>
      <RoundButton top={BTN_YS[1]} opacity={uiOpacity} shift={70 * (1 - uiOpacity)}>
        <SearchIcon size={23} color="#3C4043" />
      </RoundButton>
      <RoundButton top={BTN_YS[2]} opacity={uiOpacity} shift={70 * (1 - uiOpacity)} pressed={sharePressed}>
        <ShareIcon size={23} color="#1A73E8" />
      </RoundButton>

      {/* ETA bar */}
      <div
        style={{
          position: 'absolute',
          left: 0,
          top: ETA_TOP,
          width: SCREEN_W,
          height: SCREEN_H - ETA_TOP,
          background: '#fff',
          borderTopLeftRadius: 24,
          borderTopRightRadius: 24,
          boxShadow: '0 -3px 14px rgba(0,0,0,0.18)',
          transform: `translateY(${etaY}px)`,
        }}
      >
        <div style={{position: 'absolute', left: (SCREEN_W - 36) / 2, top: 8, width: 36, height: 5, borderRadius: 3, background: '#DADCE0'}} />
        <div
          style={{
            position: 'absolute',
            left: 18,
            top: 32,
            width: 46,
            height: 46,
            borderRadius: 23,
            background: '#F1F3F4',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <CloseIcon size={22} color="#3C4043" />
        </div>
        <div style={{position: 'absolute', left: 0, right: 0, top: 26, textAlign: 'center'}}>
          <div style={{fontSize: 26, fontWeight: 700, color: COLORS.etaGreen, letterSpacing: -0.3, lineHeight: '32px'}}>18 min</div>
          <div style={{fontSize: 16, fontWeight: 500, color: '#5F6368', lineHeight: '24px'}}>6.2 mi · 9:59 AM</div>
        </div>
        <div
          style={{
            position: 'absolute',
            right: 18,
            top: 32,
            width: 46,
            height: 46,
            borderRadius: 23,
            background: '#F1F3F4',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <ForkIcon size={22} color="#3C4043" />
        </div>
      </div>
    </div>
  );
};
