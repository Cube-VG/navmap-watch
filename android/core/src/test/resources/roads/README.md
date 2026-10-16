# Road test vectors

`tools/roads.py` is the reference for the roads message, and NavBridge's `RoadArea` must match it exactly. These
files come from it, for a 2400 m square around central Bangalore (12.9716, 77.5946):

- `blr-lines.json.gz`: the decoded tile lines, in z18 pixels (`--lines`).
- `blr-messages.json.gz`: the four roads messages built from them (`--json`).

Map data © OpenStreetMap contributors (ODbL), from OpenFreeMap's tiles in the OpenMapTiles schema, version
20261011_080001_pt.

## Regenerating

After a change to `tools/roads.py`, from the repository root:

```sh
tools/roads.py build 12.9716 77.5946 --lines blr-lines.json --json blr-messages.json
for f in blr-lines blr-messages; do
  python3 -c 'import json,sys; json.dump(json.load(sys.stdin), sys.stdout, separators=(",", ":"))' < $f.json \
    | gzip -9n > android/core/src/test/resources/roads/$f.json.gz
done
```

## More squares, from real tiles

`RoadAreaTest.buildsFromTilesLikeRoadsPy` runs the whole pipeline, from tiles to messages, and compares it with
roads.py. It needs the tiles roads.py downloaded, and it takes more squares from a directory of
`<name>-lines.json` and `<name>-messages.json` pairs:

```sh
mkdir -p /tmp/spots
tools/roads.py build 51.5074 -0.1278 --lines /tmp/spots/london-lines.json --json /tmp/spots/london-messages.json
cd android
NAVMAP_ROADS_TILES=~/.cache/navmap-roads/20261011_080001_pt NAVMAP_ROADS_SPOTS=/tmp/spots \
  ./gradlew :core:cleanTest :core:test
```

Gradle doesn't count environment variables as test inputs, so without `cleanTest` it may report an earlier run.
On 2026-10-16 this matched for ten squares: six in Bangalore, plus London, Sydney, New York and Oslo.
