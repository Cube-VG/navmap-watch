import React from 'react';
import {Composition} from 'remotion';
import {loadFonts} from './fonts';
import {LAYOUTS} from './layouts';
import {NavMapDemo} from './NavMapDemo';
import {DURATION, FPS} from './timings';

loadFonts();

export const RemotionRoot: React.FC = () => (
  <>
    <Composition
      id="NavMapDemo"
      component={NavMapDemo}
      durationInFrames={DURATION}
      fps={FPS}
      width={LAYOUTS.landscape.width}
      height={LAYOUTS.landscape.height}
      defaultProps={{layout: 'landscape' as const}}
    />
    <Composition
      id="NavMapDemoVertical"
      component={NavMapDemo}
      durationInFrames={DURATION}
      fps={FPS}
      width={LAYOUTS.vertical.width}
      height={LAYOUTS.vertical.height}
      defaultProps={{layout: 'vertical' as const}}
    />
  </>
);
