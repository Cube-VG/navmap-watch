import {loadFont} from '@remotion/fonts';
import inter400 from '@fontsource/inter/files/inter-latin-400-normal.woff2';
import inter500 from '@fontsource/inter/files/inter-latin-500-normal.woff2';
import inter600 from '@fontsource/inter/files/inter-latin-600-normal.woff2';
import inter700 from '@fontsource/inter/files/inter-latin-700-normal.woff2';
import inter800 from '@fontsource/inter/files/inter-latin-800-normal.woff2';

// Inter is bundled from @fontsource so renders never depend on the network.
const WEIGHTS: [string, string][] = [
  ['400', inter400],
  ['500', inter500],
  ['600', inter600],
  ['700', inter700],
  ['800', inter800],
];

export const loadFonts = () =>
  Promise.all(WEIGHTS.map(([weight, url]) => loadFont({family: 'Inter', url, weight, format: 'woff2'})));
