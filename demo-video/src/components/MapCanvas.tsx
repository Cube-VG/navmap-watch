import React from 'react';
import {COLORS, FONT} from '../theme';
import {
  BLOCKS,
  DESTINATION,
  LABELS,
  PARK,
  PARK_LAKES,
  PARK_PATHS,
  Pt,
  ROADS,
  RoadKind,
  routeFrom,
  SMALL_PARKS,
  START,
  WATER,
} from '../lib/mapData';

export type MapTheme = 'phone' | 'watch';

const PALETTES = {
  phone: {
    land: COLORS.mapLand,
    block: COLORS.mapBlock,
    park: COLORS.mapPark,
    parkPath: '#DDF0D8',
    water: COLORS.mapWater,
    road: COLORS.mapRoad,
    casing: {minor: COLORS.mapRoadCasing, major: COLORS.mapMajorCasing, highway: COLORS.mapHighwayCasing},
    fill: {minor: COLORS.mapRoad, major: COLORS.mapRoad, highway: COLORS.mapHighway},
    route: COLORS.routeBlue,
    routeCasing: COLORS.routeBlueDark,
    label: COLORS.mapLabel,
    parkLabel: COLORS.mapParkLabel,
  },
  watch: {
    land: COLORS.wLand,
    block: '#E9E3D6',
    park: COLORS.wPark,
    parkPath: '#E0EED3',
    water: COLORS.wWater,
    road: COLORS.wRoad,
    casing: {minor: COLORS.wRoadCasing, major: COLORS.wMajorCasing, highway: '#D9A441'},
    fill: {minor: COLORS.wRoad, major: COLORS.wRoad, highway: '#F7D774'},
    route: COLORS.wRoute,
    routeCasing: COLORS.wRouteDark,
    label: '#4A4A4A',
    parkLabel: '#3D6B2F',
  },
} as const;

// Road widths in world units at roadScale 1.
const ROAD_W: Record<RoadKind, [number, number]> = {
  minor: [8, 5.5],
  major: [13, 10],
  highway: [16, 12],
};

const toPath = (pts: Pt[]) => pts.map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x.toFixed(1)} ${p.y.toFixed(1)}`).join(' ');

const normalizeAngle = (a: number) => {
  let r = ((a % 360) + 360) % 360; // 0..360
  if (r > 180) r -= 360; // -180..180
  if (r > 90) r -= 180;
  if (r <= -90) r += 180;
  return r;
};

export type MapCanvasProps = {
  width: number;
  height: number;
  center: Pt; // world point drawn at `anchor`
  zoom: number; // pixels per world unit
  rotation?: number; // degrees, applied to the map (use -bearing for heading-up)
  anchor?: Pt; // pixel position of `center` (defaults to the middle)
  theme: MapTheme;
  routeStart?: number; // distance already driven, hidden from the route line
  routePx: number; // route line width in pixels
  routeReveal?: number; // 0..1, draws the route line on
  roadScale?: number;
  labelPx?: number; // 0 hides labels
  labelFilter?: (text: string) => boolean;
  showStartDot?: boolean;
  showPin?: boolean;
  pinScale?: number;
  style?: React.CSSProperties;
};

export const MapCanvas: React.FC<MapCanvasProps> = ({
  width,
  height,
  center,
  zoom,
  rotation = 0,
  anchor,
  theme,
  routeStart = 0,
  routePx,
  routeReveal = 1,
  roadScale = 1,
  labelPx = 0,
  labelFilter,
  showStartDot = false,
  showPin = false,
  pinScale = 1,
  style,
}) => {
  const pal = PALETTES[theme];
  const a = anchor ?? {x: width / 2, y: height / 2};
  const px = (n: number) => n / zoom; // pixel size -> world units
  const route = routeFrom(routeStart);
  const routePath = toPath(route);

  return (
    <svg width={width} height={height} style={{display: 'block', ...style}}>
      <rect width={width} height={height} fill={pal.land} />
      <g transform={`translate(${a.x} ${a.y}) rotate(${rotation}) scale(${zoom}) translate(${-center.x} ${-center.y})`}>
        {BLOCKS.map((b, i) => (
          <path key={i} d={toPath(b) + 'Z'} fill={pal.block} />
        ))}
        <path d={WATER} fill={pal.water} />
        <path d={toPath(PARK) + 'Z'} fill={pal.park} />
        {SMALL_PARKS.map((p, i) => (
          <path key={i} d={toPath(p) + 'Z'} fill={pal.park} />
        ))}
        {PARK_PATHS.map((d, i) => (
          <path key={i} d={d} fill="none" stroke={pal.parkPath} strokeWidth={5 * roadScale} strokeLinecap="round" />
        ))}
        {PARK_LAKES.map((l, i) => (
          <ellipse key={i} cx={l.cx} cy={l.cy} rx={l.rx} ry={l.ry} fill={pal.water} />
        ))}

        {/* Road casings, then fills, so crossings merge cleanly. */}
        {(['minor', 'major', 'highway'] as RoadKind[]).map((kind) => (
          <g key={kind}>
            {ROADS.filter((r) => r.kind === kind).map((r, i) => (
              <path
                key={`c${i}`}
                d={toPath(r.pts)}
                fill="none"
                stroke={pal.casing[kind]}
                strokeWidth={ROAD_W[kind][0] * roadScale}
                strokeLinecap="round"
              />
            ))}
            {ROADS.filter((r) => r.kind === kind).map((r, i) => (
              <path
                key={`f${i}`}
                d={toPath(r.pts)}
                fill="none"
                stroke={pal.fill[kind]}
                strokeWidth={ROAD_W[kind][1] * roadScale}
                strokeLinecap="round"
              />
            ))}
          </g>
        ))}

        {/* Route line */}
        {routeReveal > 0.001 && (
          <g>
            <path
              d={routePath}
              pathLength={1}
              strokeDasharray={routeReveal < 1 ? `${routeReveal} 2` : undefined}
              fill="none"
              stroke={pal.routeCasing}
              strokeWidth={px(routePx + Math.max(2, routePx * 0.22))}
              strokeLinecap="round"
              strokeLinejoin="round"
            />
            <path
              d={routePath}
              pathLength={1}
              strokeDasharray={routeReveal < 1 ? `${routeReveal} 2` : undefined}
              fill="none"
              stroke={pal.route}
              strokeWidth={px(routePx)}
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </g>
        )}

        {/* Labels stay readable whatever the map rotation. */}
        {labelPx > 0 &&
          LABELS.filter(
            (l) => (!labelFilter || labelFilter(l.text)) && zoom >= (l.minZoom ?? 0) && zoom <= (l.maxZoom ?? Infinity),
          ).map((l, i) => {
            const local = l.angle === undefined ? -rotation : normalizeAngle(l.angle + rotation) - rotation;
            const size = px(l.kind === 'street' ? labelPx : l.kind === 'park' ? labelPx * 1.08 : labelPx * 0.9);
            return (
              <text
                key={i}
                transform={`translate(${l.at.x} ${l.at.y}) rotate(${local})`}
                fontFamily={FONT}
                fontSize={size}
                fontWeight={l.kind === 'street' ? 500 : 600}
                letterSpacing={l.kind === 'area' ? size * 0.12 : 0}
                fill={l.kind === 'park' ? pal.parkLabel : pal.label}
                stroke="#FFFFFF"
                strokeWidth={size * 0.28}
                strokeLinejoin="round"
                paintOrder="stroke"
                textAnchor="middle"
                dominantBaseline="central"
              >
                {l.text}
              </text>
            );
          })}

        {showStartDot && (
          <g transform={`translate(${START.x} ${START.y}) scale(${1 / zoom})`}>
            <circle r={16} fill="rgba(59,130,246,0.18)" />
            <circle r={8.5} fill={COLORS.routeBlue} stroke="#fff" strokeWidth={3} />
          </g>
        )}
        {showPin && (
          <g transform={`translate(${DESTINATION.x} ${DESTINATION.y}) rotate(${-rotation}) scale(${pinScale / zoom})`}>
            <ellipse cx={0} cy={1} rx={6} ry={2.5} fill="rgba(0,0,0,0.25)" />
            <path
              d="M0 0 C -4 -10, -14 -16, -14 -27 A 14 14 0 1 1 14 -27 C 14 -16, 4 -10, 0 0 Z"
              fill={COLORS.pinRed}
              stroke="#B3261E"
              strokeWidth={1.2}
            />
            <circle cx={0} cy={-27} r={5.2} fill="#7A1410" />
          </g>
        )}
      </g>
    </svg>
  );
};
