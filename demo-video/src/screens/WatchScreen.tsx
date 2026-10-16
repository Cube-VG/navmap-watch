import React from 'react';
import {interpolate, useCurrentFrame} from 'remotion';
import {GarminFrame} from '../components/GarminFrame';
import {bouncy, CLAMP, EASE_IN_OUT, EASE_OUT, smooth} from '../lib/anim';
import {distanceFtAt, travelAt} from '../lib/drive';
import {T} from '../timings';
import {WatchFace} from './watch/WatchFace';
import {WatchMap} from './watch/WatchMap';
import {OPEN_MAP_BUTTON, WatchNotification} from './watch/WatchNotification';

export const WatchScreen: React.FC = () => {
  const frame = useCurrentFrame();

  const notifyEnter = bouncy(frame, T.notifyIn, 16, 0.9, 130);
  const countdown = interpolate(frame, [T.countdownStart, T.mapOpen], [3, 0], CLAMP);
  const sinceDigit = frame < T.countdownStart ? 99 : (frame - T.countdownStart) % T.countdownStep;
  const digitPop = interpolate(sinceDigit, [0, 3, 12], [0, 1, 0], {...CLAMP, easing: EASE_IN_OUT});

  // The map grows out of the Open Map button.
  const reveal = interpolate(frame, [T.mapOpen, T.mapOpen + 20], [0, 1], {...CLAMP, easing: EASE_OUT});
  const mapEnter = smooth(frame, T.mapOpen, 40);
  const revealR = 34 + reveal * 330;

  // Vibration arcs: two pulses after the buzz.
  const vib =
    frame < T.buzz + 13
      ? interpolate(frame, [T.buzz, T.buzz + 12], [0, 0.999], {...CLAMP, easing: EASE_OUT})
      : interpolate(frame, [T.buzz + 13, T.buzz + 25], [0, 0.999], {...CLAMP, easing: EASE_OUT});

  return (
    <GarminFrame vibration={frame >= T.buzz && frame <= T.buzz + 25 ? vib : 0}>
      {frame < T.mapOpen + 22 && <WatchFace frame={frame} dim={smooth(frame, T.notifyIn, 20)} />}
      {frame >= T.notifyIn - 1 && frame < T.mapOpen + 22 && (
        <WatchNotification enter={notifyEnter} countdown={countdown} digitPop={digitPop} exit={interpolate(frame, [T.mapOpen + 6, T.mapOpen + 20], [0, 1], {...CLAMP, easing: EASE_IN_OUT})} />
      )}
      {frame >= T.mapOpen && (
        <div style={{position: 'absolute', inset: 0, clipPath: `circle(${revealR}px at ${OPEN_MAP_BUTTON.x}px ${OPEN_MAP_BUTTON.y}px)`}}>
          <WatchMap travel={travelAt(frame)} distanceFt={distanceFtAt(frame)} enter={mapEnter} frame={frame} />
        </div>
      )}
    </GarminFrame>
  );
};
