// A small made-up city in world units (y grows downward). Both the phone's
// maps look-alike and the watch map draw this same data, so the route the
// phone shares is the route the watch shows.

export type Pt = {x: number; y: number};

export const FT_PER_UNIT = 500 / 95; // the first leg (95 units) is 500 ft

// Market St runs diagonally; the grid north of it is square, the grid south
// of it ("SoMa") follows Market's angle.
const M1: Pt = {x: 520, y: 1140}; // 4th St & Market St, the first turn
const D: Pt = {x: 0.883, y: -0.469}; // along Market St, heading east-north-east
const N: Pt = {x: 0.469, y: 0.883}; // perpendicular to Market, pointing south

const add = (a: Pt, b: Pt, k = 1): Pt => ({x: a.x + b.x * k, y: a.y + b.y * k});
const dot = (a: Pt, b: Pt) => a.x * b.x + a.y * b.y;
const sub = (a: Pt, b: Pt): Pt => ({x: a.x - b.x, y: a.y - b.y});

export const WORLD = {x0: -500, y0: -200, x1: 1700, y1: 1900};

const marketY = (x: number) => M1.y + ((x - M1.x) * D.y) / D.x;
const marketX = (y: number) => M1.x + ((y - M1.y) * D.x) / D.y;
const southOfMarket = (p: Pt) => dot(sub(p, M1), N) > 0;

export type RoadKind = 'minor' | 'major' | 'highway';
export type Road = {kind: RoadKind; pts: Pt[]; name?: string};

const GRID_X = (i: number) => 20 + 100 * i; // north-grid avenues
const GRID_Y = (j: number) => 10 + 85 * j; // north-grid streets

const roads: Road[] = [];

// North grid, stopping at Market St.
for (let i = -6; i <= 17; i++) {
  const x = GRID_X(i);
  const major = x === 1020 || x === 420 || x === 20;
  roads.push({kind: major ? 'major' : 'minor', pts: [{x, y: WORLD.y0}, {x, y: marketY(x)}]});
}
for (let j = -2; j <= 22; j++) {
  const y = GRID_Y(j);
  const xEnd = Math.min(WORLD.x1, marketX(y));
  if (xEnd <= WORLD.x0) continue;
  const major = y === 520 || y === 690;
  roads.push({kind: major ? 'major' : 'minor', pts: [{x: WORLD.x0, y}, {x: xEnd, y}]});
}

// SoMa grid: streets parallel and perpendicular to Market.
for (let k = 1; k <= 9; k++) {
  const o = add(M1, N, 92 * k);
  roads.push({kind: k === 1 ? 'major' : 'minor', pts: [add(o, D, -1400), add(o, D, 1600)]});
}
for (let m = -8; m <= 12; m++) {
  const o = add(M1, D, 118 * m);
  roads.push({kind: m === 0 ? 'major' : 'minor', pts: [o, add(o, N, 1100)]});
}

// Market St itself.
roads.push({kind: 'major', pts: [add(M1, D, -1300), add(M1, D, 1500)], name: 'Market St'});

// A curving freeway through SoMa.
const freeway: Pt[] = [];
for (let t = 0; t <= 1.0001; t += 0.05) {
  const a = {x: 1750, y: 980};
  const c = {x: 1180, y: 1180};
  const b = {x: 860, y: 1950};
  const u = 1 - t;
  freeway.push({x: u * u * a.x + 2 * u * t * c.x + t * t * b.x, y: u * u * a.y + 2 * u * t * c.y + t * t * b.y});
}
roads.push({kind: 'highway', pts: freeway});

export const ROADS = roads;

// City blocks (slightly darker than the land), inset from the streets.
export const BLOCKS: Pt[][] = [];
const INSET = 9;
for (let i = -6; i < 17; i++) {
  for (let j = -2; j < 22; j++) {
    const x0 = GRID_X(i) + INSET;
    const x1 = GRID_X(i + 1) - INSET;
    const y0 = GRID_Y(j) + INSET;
    const y1 = GRID_Y(j + 1) - INSET;
    const corners = [
      {x: x0, y: y0},
      {x: x1, y: y0},
      {x: x1, y: y1},
      {x: x0, y: y1},
    ];
    if (corners.some(southOfMarket)) continue;
    if (y1 > 160 && y0 < 395) continue; // the park
    BLOCKS.push(corners);
  }
}
for (let k = 0; k < 9; k++) {
  for (let m = -8; m < 12; m++) {
    const o = add(add(M1, N, 92 * k + INSET), D, 118 * m + INSET);
    const quad = [o, add(o, D, 118 - 2 * INSET), add(add(o, D, 118 - 2 * INSET), N, 92 - 2 * INSET), add(o, N, 92 - 2 * INSET)];
    BLOCKS.push(quad);
  }
}

// Golden Gate Park: a long strip with lakes and winding paths.
export const PARK: Pt[] = [
  {x: -600, y: 168},
  {x: 1320, y: 168},
  {x: 1320, y: 392},
  {x: -600, y: 392},
];
export const PARK_LAKES = [
  {cx: 220, cy: 262, rx: 78, ry: 38},
  {cx: 905, cy: 236, rx: 46, ry: 26},
];
export const PARK_PATHS: string[] = [
  'M -500 330 C -300 250, -100 360, 120 300 S 420 220, 620 300 S 980 340, 1300 250',
  'M 40 190 C 120 260, 300 330, 480 350 S 760 300, 860 200',
  'M 600 180 C 650 240, 700 260, 780 360',
];
export const SMALL_PARKS: Pt[][] = [
  // Alamo Square
  [
    {x: 729, y: 699},
    {x: 811, y: 699},
    {x: 811, y: 766},
    {x: 729, y: 766},
  ],
];

// The bay, top right.
export const WATER: string = 'M 900 -400 L 1900 -400 L 1900 150 C 1650 120, 1450 40, 1250 30 C 1100 22, 980 -20, 900 -60 Z';

// The route: 4th St north to Market St, right on Market, left on
// Divisadero, left on Fell, right into the park.
const S = add(M1, N, 95); // start, 500 ft before the first turn
const M2: Pt = {x: 1020, y: marketY(1020)};
export const ROUTE: Pt[] = [S, M1, M2, {x: 1020, y: 520}, {x: 420, y: 520}, {x: 420, y: 398}];
export const START = S;
export const DESTINATION = ROUTE[ROUTE.length - 1];

const segLengths = ROUTE.slice(1).map((p, i) => Math.hypot(p.x - ROUTE[i].x, p.y - ROUTE[i].y));
export const ROUTE_LENGTH = segLengths.reduce((a, b) => a + b, 0);

// Point and heading (compass bearing in degrees, 0 = up) at a distance along the route.
export const routeAt = (dist: number): {p: Pt; bearing: number; seg: number} => {
  let d = Math.max(0, Math.min(ROUTE_LENGTH, dist));
  for (let i = 0; i < segLengths.length; i++) {
    if (d <= segLengths[i] || i === segLengths.length - 1) {
      const a = ROUTE[i];
      const b = ROUTE[i + 1];
      const t = segLengths[i] === 0 ? 0 : d / segLengths[i];
      const bearing = (Math.atan2(b.x - a.x, -(b.y - a.y)) * 180) / Math.PI;
      return {p: {x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t}, bearing, seg: i};
    }
    d -= segLengths[i];
  }
  return {p: ROUTE[0], bearing: 0, seg: 0};
};

// The remaining route from a distance onward.
export const routeFrom = (dist: number): Pt[] => {
  const {p, seg} = routeAt(dist);
  return [p, ...ROUTE.slice(seg + 1)];
};

export const FIRST_LEG_BEARING = routeAt(0).bearing;

// minZoom / maxZoom limit a label to close-up or overview maps.
export type MapLabel = {text: string; at: Pt; angle?: number; kind: 'street' | 'area' | 'park'; minZoom?: number; maxZoom?: number};

const marketAngle = (Math.atan2(D.y, D.x) * 180) / Math.PI;
const nAngle = (Math.atan2(N.y, N.x) * 180) / Math.PI;

export const LABELS: MapLabel[] = [
  {text: 'Market St', at: add(M1, D, 760), angle: marketAngle, kind: 'street'},
  {text: 'Market St', at: add(M1, D, -260), angle: marketAngle, kind: 'street', maxZoom: 1},
  {text: 'Market St', at: add(M1, D, -75), angle: marketAngle, kind: 'street', minZoom: 1},
  {text: 'Market St', at: add(M1, D, 230), angle: marketAngle, kind: 'street', minZoom: 1},
  {text: 'Mission St', at: add(add(M1, N, 92), D, -80), angle: marketAngle, kind: 'street', minZoom: 1},
  {text: '5th St', at: add(add(M1, D, 118), N, 40), angle: nAngle - 180, kind: 'street', minZoom: 1},
  {text: 'Mission St', at: add(add(M1, N, 92), D, 210), angle: marketAngle, kind: 'street'},
  {text: '4th St', at: add(M1, N, 150), angle: nAngle - 180, kind: 'street'},
  {text: 'Fell St', at: {x: 230, y: 520}, angle: 0, kind: 'street'},
  {text: 'Oak St', at: {x: 300, y: 435}, angle: 0, kind: 'street'},
  {text: 'Hayes St', at: {x: 600, y: 690}, angle: 0, kind: 'street'},
  {text: 'Fillmore St', at: {x: 620, y: 920}, angle: -90, kind: 'street'},
  {text: 'HAYES VALLEY', at: {x: 850, y: 812}, kind: 'area'},
  {text: 'LOWER HAIGHT', at: {x: 330, y: 610}, kind: 'area'},
  {text: 'SOMA', at: {x: 960, y: 1260}, kind: 'area'},
  {text: 'Golden Gate Park', at: {x: 690, y: 290}, kind: 'park'},
];
