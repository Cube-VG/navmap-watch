import {PHONE_H, PHONE_W} from './components/IPhoneFrame';
import {CASE_R, WATCH_H, WATCH_W} from './components/GarminFrame';

export type LayoutName = 'landscape' | 'vertical';

type Device = {x: number; y: number; scale: number; rotateX: number; rotateY: number};

export type Layout = {
  width: number;
  height: number;
  phone: Device; // x, y = centre
  watch: Device;
  captionY: number; // centre of the caption line
  captionSize: number;
  titleSize: number;
  taglineSize: number;
  taglineWidth: number;
  endSize: number;
  endStacked: boolean;
  taglineBreak: boolean; // break the tagline after "Garmin,"
  phoneFocusDy: number; // camera zooms around a point this far below the phone's centre
  watchFocusDy: number; // same for the watch
};

export const LAYOUTS: Record<LayoutName, Layout> = {
  landscape: {
    width: 1920,
    height: 1080,
    phone: {x: 600, y: 488, scale: 0.9, rotateX: 0, rotateY: 6},
    watch: {x: 1268, y: 488, scale: 0.86, rotateX: 0, rotateY: -6},
    captionY: 1004,
    captionSize: 36,
    titleSize: 120,
    taglineSize: 46,
    taglineWidth: 1400,
    endSize: 58,
    endStacked: false,
    taglineBreak: false,
    phoneFocusDy: 0,
    watchFocusDy: 0,
  },
  vertical: {
    width: 1080,
    height: 1920,
    phone: {x: 540, y: 530, scale: 0.94, rotateX: -5, rotateY: 0},
    watch: {x: 540, y: 1388, scale: 0.86, rotateX: 5, rotateY: 0},
    captionY: 1832,
    captionSize: 44,
    titleSize: 132,
    taglineSize: 56,
    taglineWidth: 900,
    endSize: 66,
    endStacked: true,
    taglineBreak: true,
    phoneFocusDy: -290, // keeps the top of the phone on screen
    watchFocusDy: -200,
  },
};

// Useful device edges in stage pixels.
export const phoneBox = (l: Layout) => ({
  left: l.phone.x - (PHONE_W * l.phone.scale) / 2,
  right: l.phone.x + (PHONE_W * l.phone.scale) / 2,
  top: l.phone.y - (PHONE_H * l.phone.scale) / 2,
  bottom: l.phone.y + (PHONE_H * l.phone.scale) / 2,
});

export const watchBox = (l: Layout) => ({
  caseR: CASE_R * l.watch.scale,
  left: l.watch.x - (WATCH_W * l.watch.scale) / 2,
  right: l.watch.x + (WATCH_W * l.watch.scale) / 2,
  top: l.watch.y - (WATCH_H * l.watch.scale) / 2,
  bottom: l.watch.y + (WATCH_H * l.watch.scale) / 2,
});
