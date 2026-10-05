// Runs the website's own `configLinesFromDiffBody` over (a) the whole `/diff/config/{type}/content`
// body and (b) the pages from `/diff/config/{type}/changes` reshaped the way the browser reshapes
// them, and checks the rendered lines come out identical. See tools/README.md for the setup.
//
//   node --import ../OpenRune-WebServer/tools/register-alias.mjs \
//        ../OpenRune-WebServer/tools/verify-rendered-lines.mjs items 1 241

import { pathToFileURL } from "node:url";
import { resolve as resolvePath, join } from "node:path";

const tsout = resolvePath(process.env.OPENRUNE_TSOUT ?? ".tsout");
const { configLinesFromDiffBody } = await import(
  pathToFileURL(join(tsout, "components/diff/diff-config-content.js")).href
);
const { diffChangesPageToContentBody } = await import(
  pathToFileURL(join(tsout, "lib/diff-changes.js")).href
);

const [, , type = "items", base = "240", rev = "241", origin = "http://localhost:8090"] = process.argv;

async function json(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`${res.status} for ${url}`);
  return res.json();
}

// Mirrors streamConfigDiffLines: kinds in presentation order, ids ascending within a kind.
async function pagedLines() {
  const out = [];
  for (const kind of ["added", "changed", "removed"]) {
    let after;
    for (;;) {
      const qs = new URLSearchParams({ base, rev, kind, limit: "250" });
      if (after != null) qs.set("after", String(after));
      const page = await json(`${origin}/diff/config/${type}/changes?${qs}`);
      if (page.rows.length > 0) {
        out.push(...configLinesFromDiffBody(diffChangesPageToContentBody(page, page.rows)));
      }
      if (!page.hasMore || page.nextCursor == null) break;
      after = page.nextCursor;
    }
  }
  return out;
}

const whole = configLinesFromDiffBody(
  await json(`${origin}/diff/config/${type}/content?base=${base}&rev=${rev}`),
);
const paged = await pagedLines();

console.log(`${type} ${base}->${rev}: whole=${whole.length} lines, paged=${paged.length} lines`);

let problems = whole.length === paged.length ? 0 : 1;
let shown = 0;
for (let i = 0; i < Math.min(whole.length, paged.length); i++) {
  const a = JSON.stringify(whole[i]);
  const b = JSON.stringify(paged[i]);
  if (a !== b) {
    if (shown++ < 5) console.log(`  line ${i}:\n    whole=${a}\n    paged=${b}`);
    problems++;
  }
}
console.log(problems === 0 ? "  OK — rendered lines identical" : `  ${problems} differences`);
process.exit(problems === 0 ? 0 : 2);
