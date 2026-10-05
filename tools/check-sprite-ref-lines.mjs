// Checks that a config field pointing at a sprite gameval keeps the referenced sprite id on the
// rendered line, so the text view can show the sprite and open it. Run from the website repo the
// same way as tools/verify-rendered-lines.mjs (see tools/README.md).

import { pathToFileURL } from "node:url";
import { resolve as resolvePath, join } from "node:path";

const tsout = resolvePath(process.env.OPENRUNE_TSOUT ?? ".tsout");
const { configLinesFromCachePayload, configLinesFromDiffBody } = await import(
  pathToFileURL(join(tsout, "components/diff/diff-config-content.js")).href
);

const [, , type = "mapelement", rev = "241", base = "1", origin = "http://localhost:8090"] = process.argv;

async function json(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`${res.status} for ${url}`);
  return res.json();
}

let problems = 0;

function report(label, lines) {
  const spriteLines = lines.filter((l) => /^sprite\d*=/.test(l.line));
  const withId = spriteLines.filter((l) => l.refId != null);
  console.log(`${label}: ${lines.length} lines, ${spriteLines.length} sprite lines, ${withId.length} with an id`);
  for (const l of spriteLines.slice(0, 5)) {
    console.log(`  ${l.line.padEnd(28)} refGroup=${l.refGroup} refId=${l.refId}`);
  }
  const missing = spriteLines.filter((l) => l.refId == null);
  if (missing.length > 0) {
    console.log(`  ${missing.length} sprite lines with no ref id, e.g. ${missing[0].line}`);
    problems++;
    return;
  }
  // The point of carrying the id: the displayed name alone does not identify the sprite.
  const distinctNames = new Set(spriteLines.map((l) => l.line));
  const distinctIds = new Set(withId.map((l) => l.refId));
  if (distinctIds.size > distinctNames.size) {
    console.log(`  ${distinctIds.size} distinct ids behind only ${distinctNames.size} distinct labels`);
  }
}

report(`${type}@${rev} (cache)`, configLinesFromCachePayload(await json(`${origin}/cache?type=${type}&rev=${rev}&offset=0&limit=25`), type));
report(`${type} ${base}->${rev} (diff)`, configLinesFromDiffBody(await json(`${origin}/diff/config/${type}/content?base=${base}&rev=${rev}`)));

console.log(problems === 0 ? "OK — every sprite line carries its id" : `${problems} checks failed`);
process.exit(problems === 0 ? 0 : 2);
