import React from 'react';
import {AbsoluteFill, interpolate, useCurrentFrame} from 'remotion';
import {Background} from './components/Background';
import {BluetoothPulse} from './components/BluetoothPulse';
import {Captions} from './components/Captions';
import {EndCard} from './components/EndCard';
import {WATCH_H, WATCH_W} from './components/GarminFrame';
import {PHONE_H, PHONE_W} from './components/IPhoneFrame';
import {Sounds} from './components/Sounds';
import {TitleCard} from './components/TitleCard';
import {CLAMP, EASE_IN_OUT, keyframes, smooth} from './lib/anim';
import {LayoutName, LAYOUTS} from './layouts';
import {PhoneScreen} from './screens/PhoneScreen';
import {WatchScreen} from './screens/WatchScreen';
import {CAMERA, CameraTarget, GLOW, T} from './timings';

export type NavMapDemoProps = {layout: LayoutName};

const Device: React.FC<{
  w: number;
  h: number;
  x: number;
  y: number;
  scale: number;
  rotateX: number;
  rotateY: number;
  extra?: string;
  children: React.ReactNode;
}> = ({w, h, x, y, scale, rotateX, rotateY, extra = '', children}) => (
  <div
    style={{
      position: 'absolute',
      left: x - w / 2,
      top: y - h / 2,
      width: w,
      height: h,
      transform: `${extra} perspective(2600px) rotateX(${rotateX}deg) rotateY(${rotateY}deg) scale(${scale})`,
      filter: 'drop-shadow(0 34px 44px rgba(0,0,0,0.55)) drop-shadow(0 8px 14px rgba(0,0,0,0.35))',
    }}
  >
    {children}
  </div>
);

export const NavMapDemo: React.FC<NavMapDemoProps> = ({layout: layoutName}) => {
  const frame = useCurrentFrame();
  const L = LAYOUTS[layoutName];

  const targets: Record<CameraTarget, {x: number; y: number}> = {
    center: {x: L.width / 2, y: (L.phone.y + L.watch.y) / 2},
    phone: {x: L.phone.x, y: L.phone.y + L.phoneFocusDy},
    watch: {x: L.watch.x, y: L.watch.y + L.watchFocusDy},
  };

  // Camera: scale around a focus point.
  const zoom = keyframes(frame, CAMERA.map((k) => ({frame: k.frame, value: k.zoom})));
  const fx = keyframes(frame, CAMERA.map((k) => ({frame: k.frame, value: targets[k.target].x})));
  const fy = keyframes(frame, CAMERA.map((k) => ({frame: k.frame, value: targets[k.target].y})));

  const gx = keyframes(frame, GLOW.map((k) => ({frame: k.frame, value: targets[k.target].x})));
  const gy = keyframes(frame, GLOW.map((k) => ({frame: k.frame, value: targets[k.target].y})));

  // Devices fade in under the title, dim for the end card.
  const devIn = smooth(frame, T.devicesIn, 34);
  const endDim = smooth(frame, T.endDim, 24);
  const devicesStyle: React.CSSProperties = {
    opacity: devIn,
    transform: `translateY(${(1 - devIn) * 70}px) scale(${(0.92 + 0.08 * devIn) * (1 - 0.035 * endDim)})`,
    transformOrigin: `${targets.center.x}px ${targets.center.y}px`,
    filter: endDim > 0 ? `brightness(${1 - 0.5 * endDim}) saturate(${1 - 0.3 * endDim}) blur(${2.5 * endDim}px)` : undefined,
  };

  // Gentle idle float.
  const bobPhone = Math.sin(frame / 38) * 4;
  const bobWatch = Math.sin(frame / 38 + 1.9) * 4;

  // 3-frame shake on each haptic pulse.
  const SHAKE = [-7, 7, -4];
  const shakeAt = (start: number) => (frame >= start && frame < start + 3 ? SHAKE[frame - start] : 0);
  const shake = shakeAt(T.buzz) + shakeAt(T.buzz + 13) * 0.6;
  const vertical = L.height > L.width;

  return (
    <AbsoluteFill style={{background: '#0B0F14', overflow: 'hidden'}}>
      <Background glowX={gx} glowY={gy} size={Math.max(L.width, L.height) * 0.55} />
      <AbsoluteFill
        style={{
          transformOrigin: '0 0',
          transform: `translate(${fx * (1 - zoom)}px, ${fy * (1 - zoom)}px) scale(${zoom})`,
        }}
      >
        <AbsoluteFill style={devicesStyle}>
          <Device
            w={PHONE_W}
            h={PHONE_H}
            x={L.phone.x}
            y={L.phone.y + bobPhone}
            scale={L.phone.scale}
            rotateX={L.phone.rotateX}
            rotateY={L.phone.rotateY}
          >
            <PhoneScreen />
          </Device>
          <Device
            w={WATCH_W}
            h={WATCH_H}
            x={L.watch.x}
            y={L.watch.y + bobWatch}
            scale={L.watch.scale}
            rotateX={L.watch.rotateX}
            rotateY={L.watch.rotateY}
            extra={vertical ? `translateX(${shake}px)` : `translate(${shake}px, ${shake * 0.3}px)`}
          >
            <WatchScreen />
          </Device>
          <BluetoothPulse layout={L} />
        </AbsoluteFill>
      </AbsoluteFill>
      <AbsoluteFill style={{opacity: interpolate(frame, [T.endDim - 6, T.endDim + 6], [1, 0], {...CLAMP, easing: EASE_IN_OUT})}}>
        <Captions y={L.captionY} size={L.captionSize} />
      </AbsoluteFill>
      <TitleCard layout={L} />
      <EndCard layout={L} />
      <Sounds />
    </AbsoluteFill>
  );
};
