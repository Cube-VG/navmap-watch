import React from 'react';
import {Audio, Sequence, staticFile} from 'remotion';
import {SOUNDS} from '../timings';

// Soft UI sounds generated with ffmpeg (scripts/make-sounds.sh).
export const Sounds: React.FC = () => (
  <>
    {SOUNDS.map((s, i) => (
      <Sequence key={i} from={s.frame} durationInFrames={45} layout="none">
        <Audio src={staticFile(s.file)} volume={s.volume} />
      </Sequence>
    ))}
  </>
);
