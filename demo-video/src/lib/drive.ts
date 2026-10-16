import {Easing} from 'remotion';
import {DRIVE, T} from '../timings';
import {ease} from './anim';
import {FT_PER_UNIT} from './mapData';

// World units driven by a frame. The car waits at the start until the watch
// map is open, then moves off gently.
export const travelAt = (frame: number) =>
  ease(frame, T.driveStart, DRIVE.until, 0, DRIVE.creepFt / FT_PER_UNIT, Easing.bezier(0.45, 0, 0.55, 1));

// Distance to the first turn as navigation apps show it: in 50 ft steps.
export const distanceFtAt = (frame: number) =>
  Math.round((DRIVE.turnDistanceFt - travelAt(frame) * FT_PER_UNIT) / 50) * 50;
