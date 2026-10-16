// Renders single frames straight from the source (no MP4 needed), for quick checks.
// Usage: node scripts/render-frames.mjs <NavMapDemo|NavMapDemoVertical> <outDir> <frame> [frame...]
import {bundle} from '@remotion/bundler';
import {renderStill, selectComposition} from '@remotion/renderer';
import path from 'node:path';
const [id, outDir, ...frames] = process.argv.slice(2);
const root = process.cwd();
const serveUrl = await bundle({entryPoint: path.join(root, 'src/index.ts')});
const composition = await selectComposition({serveUrl, id, inputProps: {}});
for (const f of frames) {
  const output = path.join(outDir, `${id}-${String(f).padStart(3, '0')}.png`);
  await renderStill({composition, serveUrl, output, frame: Number(f), inputProps: {}, overwrite: true});
  console.log(output);
}
