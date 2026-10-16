import React from 'react';
import {MapCanvas} from '../../components/MapCanvas';
import {COLORS} from '../../theme';
import {PhoneMapView} from './phoneMapViews';

// The SVG is much bigger than the screen so the far side of the tilted map
// still reaches the top of the screen.
const SVG_W = 1300;
const SVG_H = 2150;
const SVG_ANCHOR = {x: 650, y: 1700};

export const PhoneMap: React.FC<{
  view: PhoneMapView;
  routeStart?: number;
  routeReveal?: number;
  showStartDot?: boolean;
  showPin?: boolean;
}> = ({view, routeStart = 0, routeReveal = 1, showStartDot, showPin}) => {
  const haze = Math.min(1, view.tilt / 40);
  return (
    <div
      style={{
        position: 'absolute',
        inset: 0,
        overflow: 'hidden',
        background: COLORS.mapLand,
        perspective: 820,
        perspectiveOrigin: `${view.anchor.x}px ${view.anchor.y}px`,
      }}
    >
      <div
        style={{
          position: 'absolute',
          left: view.anchor.x - SVG_ANCHOR.x,
          top: view.anchor.y - SVG_ANCHOR.y,
          width: SVG_W,
          height: SVG_H,
          transformOrigin: `${SVG_ANCHOR.x}px ${SVG_ANCHOR.y}px`,
          transform: `rotateX(${view.tilt}deg)`,
        }}
      >
        <MapCanvas
          width={SVG_W}
          height={SVG_H}
          anchor={SVG_ANCHOR}
          center={view.center}
          zoom={view.zoom}
          rotation={view.rotation}
          theme="phone"
          routePx={view.routePx}
          routeStart={routeStart}
          routeReveal={routeReveal}
          roadScale={1.35}
          labelPx={view.labelPx}
          showStartDot={showStartDot}
          showPin={showPin}
          pinScale={1.1}
        />
      </div>
      {/* Haze toward the horizon when tilted */}
      <div
        style={{
          position: 'absolute',
          inset: 0,
          background: 'linear-gradient(to bottom, rgba(236,240,245,0.95) 0%, rgba(236,240,245,0.6) 22%, rgba(236,240,245,0) 45%)',
          opacity: haze,
        }}
      />
    </div>
  );
};
