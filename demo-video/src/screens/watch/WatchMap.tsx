import React from 'react';
import {TurnArrow} from '../../components/icons';
import {MapCanvas} from '../../components/MapCanvas';
import {FIRST_LEG_BEARING, routeAt} from '../../lib/mapData';
import {COLORS, FONT} from '../../theme';

const S = 454;
const ARROW = {x: 227, y: 296};
const TOP_BAND = 132;
const BOTTOM_BAND = 344;

// Garmin-style navigation map: heading-up, thick magenta route, position
// arrow near the bottom, data fields top and bottom.
export const WatchMap: React.FC<{
  travel: number; // world units driven
  distanceFt: number;
  enter: number; // 0..1, map settles in after the reveal
  frame: number;
}> = ({travel, distanceFt, enter, frame}) => {
  const {p} = routeAt(travel);
  const zoom = 1.3 * (1.15 - 0.15 * enter);
  const pulse = (Math.sin((frame / 30) * Math.PI * 2 * 0.8) + 1) / 2;
  return (
    <div style={{position: 'absolute', inset: 0, fontFamily: FONT}}>
      <MapCanvas
        width={S}
        height={S}
        center={p}
        anchor={ARROW}
        zoom={zoom}
        rotation={-FIRST_LEG_BEARING}
        theme="watch"
        routeStart={travel}
        routePx={21}
        roadScale={1.25}
        showPin
      />

      {/* Position arrow */}
      <svg width={70} height={70} viewBox="-35 -35 70 70" style={{position: 'absolute', left: ARROW.x - 35, top: ARROW.y - 35}}>
        <circle r={22 + pulse * 6} fill={COLORS.wArrow} opacity={0.14 * (1 - pulse * 0.6)} />
        <path d="M 0 -22 L 16 16 L 0 8 L -16 16 Z" fill="#fff" stroke="#fff" strokeWidth={5} strokeLinejoin="round" />
        <path d="M 0 -19 L 13.5 13.5 L 0 6.5 L -13.5 13.5 Z" fill={COLORS.wArrow} strokeLinejoin="round" />
      </svg>

      {/* Scale bar */}
      <div style={{position: 'absolute', left: 300, top: 312, width: 62, textAlign: 'center', color: '#333', fontSize: 13, fontWeight: 700}}>
        <div>100 ft</div>
        <div style={{height: 6, borderLeft: '2.5px solid #333', borderRight: '2.5px solid #333', borderBottom: '2.5px solid #333'}} />
      </div>

      {/* Top field: next turn */}
      <div
        style={{
          position: 'absolute',
          left: 0,
          top: 0,
          width: S,
          height: TOP_BAND,
          background: '#000',
          borderBottom: '2px solid #2B3038',
          color: '#fff',
        }}
      >
        <div style={{position: 'absolute', left: 0, right: 0, top: 30, display: 'flex', justifyContent: 'center', alignItems: 'center', gap: 10}}>
          <TurnArrow size={40} />
          <span style={{fontSize: 38, fontWeight: 800, letterSpacing: -0.8, lineHeight: '44px'}}>{distanceFt} ft</span>
        </div>
        <div style={{position: 'absolute', left: 0, right: 0, top: 80, textAlign: 'center', fontSize: 25, fontWeight: 600, color: '#D4DCE6'}}>
          Market St
        </div>
      </div>

      {/* Bottom field: ETA and distance */}
      <div
        style={{
          position: 'absolute',
          left: 0,
          top: BOTTOM_BAND,
          width: S,
          height: S - BOTTOM_BAND,
          background: '#000',
          borderTop: '2px solid #2B3038',
          color: '#fff',
        }}
      >
        <div style={{position: 'absolute', left: 0, right: 0, top: 18, textAlign: 'center', fontSize: 28, fontWeight: 700, letterSpacing: -0.3}}>
          <span style={{color: '#8E9AA8', fontWeight: 600, fontSize: 22}}>ETA </span>9:59
          <span style={{color: '#5E6874'}}> · </span>6.2 mi
        </div>
        <div style={{position: 'absolute', left: 0, right: 0, top: 58, textAlign: 'center', fontSize: 16, fontWeight: 500, color: '#7F8B99'}}>
          Golden Gate Park
        </div>
      </div>
    </div>
  );
};
