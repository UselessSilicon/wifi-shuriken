// Generates the offline MapLibre styles (light and dark) for the Protomaps basemap schema v4.
// Usage: node tools/gen-map-style.mjs app/src/main/assets/map
// Installs @protomaps/basemaps@5.7.2 into a temporary directory (nothing is added to the repo).
// The PMTiles source URL is the placeholder pmtiles://MAP_FILE, replaced at run time with the
// local copy of the owner's map file; glyphs and sprites are bundled assets. No http(s) URLs.
import { execFileSync } from "node:child_process";
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

const out = process.argv[2];
if (!out) {
  console.error("usage: node tools/gen-map-style.mjs <output-dir>");
  process.exit(2);
}
const work = mkdtempSync(join(tmpdir(), "basemaps-"));
try {
  execFileSync("npm", ["install", "--silent", "--no-audit", "--no-fund", "--prefix", work, "@protomaps/basemaps@5.7.2"], { stdio: "inherit" });
  const pkgDir = join(work, "node_modules", "@protomaps", "basemaps");
  const pkg = JSON.parse(readFileSync(join(pkgDir, "package.json"), "utf8"));
  const pick = (e) => (typeof e === "string" ? e : e && (pick(e.import) || pick(e.default) || pick(e.require)));
  const entry = pick(pkg.exports?.["."]) || pkg.module || pkg.main;
  const basemaps = await import(pathToFileURL(join(pkgDir, entry)).href);
  mkdirSync(out, { recursive: true });
  const fonts = new Set();
  for (const flavor of ["light", "dark"]) {
    const layers = basemaps.layers("protomaps", basemaps.namedFlavor(flavor), { lang: "en" });
    const collect = (v) => (typeof v === "string" ? (v.startsWith("Noto ") && fonts.add(v)) : Array.isArray(v) && v.forEach(collect));
    for (const l of layers) collect(l.layout?.["text-font"]);
    const style = {
      version: 8,
      glyphs: "asset://map/fonts/{fontstack}/{range}.pbf",
      sprite: `asset://map/sprites/v4/${flavor}`,
      sources: {
        protomaps: {
          type: "vector",
          url: "pmtiles://MAP_FILE",
          attribution: "Map data OpenStreetMap contributors, Protomaps",
        },
      },
      layers,
    };
    const text = JSON.stringify(style, null, 1);
    if (/https?:\/\//.test(text)) throw new Error("style contains a network URL");
    writeFileSync(join(out, `style-${flavor}.json`), text + "\n");
  }
  console.log(`wrote styles to ${out}; fonts: ${[...fonts].sort().join(", ")}`);
} finally {
  rmSync(work, { recursive: true, force: true });
}
