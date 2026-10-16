import {mix} from '../../lib/anim';
import {FIRST_LEG_BEARING, Pt, routeAt} from '../../lib/mapData';

// How the phone's map camera looks in route preview and in navigation.
// Navigation starts exactly where the preview is, so the switch is seamless.

export type PhoneMapView = {
  center: Pt;
  zoom: number;
  rotation: number;
  anchor: Pt; // on-screen point (phone screen coords) where `center` sits
  tilt: number; // degrees
  routePx: number;
  labelPx: number;
};

export const PREVIEW_VIEW: PhoneMapView = {
  center: {x: 720, y: 811},
  zoom: 0.36,
  rotation: 0,
  anchor: {x: 196, y: 420},
  tilt: 0,
  routePx: 6.5,
  labelPx: 10.5,
};

export const NAV_ANCHOR: Pt = {x: 196, y: 612};

export const navView = (travel: number): PhoneMapView => ({
  center: routeAt(travel).p,
  zoom: 2.05,
  rotation: -FIRST_LEG_BEARING,
  anchor: NAV_ANCHOR,
  tilt: 46,
  routePx: 19,
  labelPx: 13,
});

// t: 0 = preview, 1 = navigation
export const blendViews = (a: PhoneMapView, b: PhoneMapView, t: number): PhoneMapView => ({
  center: {x: mix(a.center.x, b.center.x, t), y: mix(a.center.y, b.center.y, t)},
  zoom: a.zoom * Math.pow(b.zoom / a.zoom, t),
  rotation: mix(a.rotation, b.rotation, t),
  anchor: {x: mix(a.anchor.x, b.anchor.x, t), y: mix(a.anchor.y, b.anchor.y, t)},
  tilt: mix(a.tilt, b.tilt, t),
  routePx: mix(a.routePx, b.routePx, t),
  labelPx: mix(a.labelPx, b.labelPx, t),
});
