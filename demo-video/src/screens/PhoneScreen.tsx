import React from 'react';
import {Easing, interpolate, useCurrentFrame} from 'remotion';
import {IPhoneFrame} from '../components/IPhoneFrame';
import {TapIndicator} from '../components/TapIndicator';
import {bouncy, CLAMP, ease, EASE_IN_OUT, smooth} from '../lib/anim';
import {distanceFtAt, travelAt} from '../lib/drive';
import {T, tapLandFrame, TapName} from '../timings';
import {MapsNavigating, SHARE_BUTTON} from './phone/MapsNavigating';
import {MapsRoutePreview, START_BUTTON} from './phone/MapsRoutePreview';
import {blendViews, navView, PREVIEW_VIEW} from './phone/phoneMapViews';
import {NavMapSending} from './phone/NavMapSending';
import {NAVMAP_SHARE_ICON, ShareSheet} from './phone/ShareSheet';

// 0..1..0 while a tapped control is held down.
const pressed = (frame: number, tap: TapName) => {
  const land = tapLandFrame(tap);
  return interpolate(frame, [land - 2, land + 1, land + 7, land + 13], [0, 1, 1, 0], {...CLAMP, easing: EASE_IN_OUT});
};

export const PhoneScreen: React.FC = () => {
  const frame = useCurrentFrame();

  // Route preview
  const uiIn = smooth(frame, T.devicesIn + 4, 30);
  const routeReveal = ease(frame, T.devicesIn + 24, T.devicesIn + 64, 0, 1, EASE_IN_OUT);
  const drift = ease(frame, T.devicesIn, T.navEnter - 16, 0, 1, EASE_IN_OUT);
  const previewView = {...PREVIEW_VIEW, zoom: PREVIEW_VIEW.zoom * (0.9 + 0.1 * drift)};
  const previewExit = smooth(frame, T.navEnter, 20);

  // Navigation
  const navEnter = smooth(frame, T.navEnter, 40);
  const travel = travelAt(frame);
  const view = blendViews(PREVIEW_VIEW, navView(travel), navEnter);

  // Share sheet
  const sheetEnter = bouncy(frame, T.shareSheetIn, 15, 0.9, 120);
  const sheetExit = smooth(frame, T.shareSheetOut, 22);

  // Sending card + Dynamic Island live activity
  const cardEnter = bouncy(frame, T.sendingCardIn, 14, 0.8, 150);
  const cardExit = smooth(frame, T.sendingCardOut, 20);
  const progress = ease(frame, T.sendProgressStart, T.sendProgressEnd, 0, 1, Easing.bezier(0.45, 0.05, 0.25, 1));
  const done = smooth(frame, T.sent, 16);
  const island = smooth(frame, T.sendingCardIn + 4, 22) * (1 - smooth(frame, T.sendingCardOut + 16, 22));

  return (
    <IPhoneFrame statusTone={navEnter} island={{expand: island, progress, done}}>
      {frame < T.navEnter + 40 && (
        <MapsRoutePreview
          exit={previewExit}
          view={previewView}
          routeReveal={routeReveal}
          startPressed={pressed(frame, 'start')}
          uiIn={uiIn}
        />
      )}
      {frame >= T.navEnter && (
        <MapsNavigating enter={navEnter} view={view} travel={travel} distanceFt={distanceFtAt(frame)} sharePressed={pressed(frame, 'share')} />
      )}
      {frame >= T.shareSheetIn && frame < T.shareSheetOut + 40 && (
        <ShareSheet enter={sheetEnter} exit={sheetExit} navmapPressed={pressed(frame, 'navmap')} />
      )}
      {frame >= T.sendingCardIn && frame < T.sendingCardOut + 30 && (
        <NavMapSending enter={cardEnter} exit={cardExit} progress={progress} done={done} />
      )}
      <TapIndicator x={START_BUTTON.x} y={START_BUTTON.y} land={tapLandFrame('start')} />
      <TapIndicator x={SHARE_BUTTON.x} y={SHARE_BUTTON.y} land={tapLandFrame('share')} />
      <TapIndicator x={NAVMAP_SHARE_ICON.x} y={NAVMAP_SHARE_ICON.y} land={tapLandFrame('navmap')} />
    </IPhoneFrame>
  );
};
