import {Easing, interpolate, spring} from 'remotion';
import {FPS} from '../timings';

export const CLAMP = {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'} as const;

export const EASE_OUT = Easing.bezier(0.22, 1, 0.36, 1);
export const EASE_IN_OUT = Easing.bezier(0.65, 0, 0.35, 1);
export const EASE_IN = Easing.bezier(0.55, 0, 1, 0.45);

// Eased 0→1 (or a→b) between two frames, clamped.
export const ease = (
  frame: number,
  start: number,
  end: number,
  from = 0,
  to = 1,
  easing: (t: number) => number = EASE_IN_OUT,
) => interpolate(frame, [start, end], [from, to], {...CLAMP, easing});

// Critically damped spring by default: smooth, no bounce.
export const smooth = (frame: number, start: number, durationInFrames?: number) =>
  spring({frame: frame - start, fps: FPS, config: {damping: 200}, durationInFrames});

// A spring with a little overshoot, for UI that pops in.
export const bouncy = (frame: number, start: number, damping = 14, mass = 0.8, stiffness = 140) =>
  spring({frame: frame - start, fps: FPS, config: {damping, mass, stiffness}});

// Piecewise keyframes with easing on every segment.
export const keyframes = (
  frame: number,
  keys: {frame: number; value: number}[],
  easing: (t: number) => number = EASE_IN_OUT,
) => {
  if (keys.length === 1) return keys[0].value;
  return interpolate(
    frame,
    keys.map((k) => k.frame),
    keys.map((k) => k.value),
    {...CLAMP, easing},
  );
};

export const mix = (a: number, b: number, t: number) => a + (b - a) * t;
