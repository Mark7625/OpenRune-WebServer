// Checks that walking `/diff/config/{type}/changes` page by page reconstructs exactly the body
// `/diff/config/{type}/content` returns in one response — the assumption the website's paged
// diff rendering relies on.
//
//   node build/verify-changes-vs-content.mjs items 1 241 [http://localhost:8090]

const [, , type = "items", base = "1", rev = "241", origin = "http://localhost:8090"] = process.argv;
const LIMIT = 500;

async function json(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`${res.status} ${res.statusText} for ${url}`);
  return res.json();
}

async function walk() {
  const added = {};
  const changed = {};
  const removed = [];
  const gamevals = {};
  let requests = 0;

  for (const kind of ["added", "changed", "removed"]) {
    let after;
    for (;;) {
      const qs = new URLSearchParams({ base, rev, kind, limit: String(LIMIT) });
      if (after != null) qs.set("after", String(after));
      const page = await json(`${origin}/diff/config/${type}/changes?${qs}`);
      requests++;
      for (const row of page.rows) {
        if (row.gameval) gamevals[row.id] = row.gameval;
        if (row.kind === "added") added[row.id] = row.fields ?? {};
        else if (row.kind === "changed") changed[row.id] = row.fields ?? {};
        else removed.push(row.id);
      }
      if (!page.hasMore || page.nextCursor == null) break;
      after = page.nextCursor;
    }
  }
  return { added, changed, removed, gamevals, requests };
}

// `/content` folds the gameval into each added entity's body and reports names separately; compare
// only the parts both forms carry.
function normalizeAdded(body) {
  const out = {};
  for (const [id, fields] of Object.entries(body)) {
    const copy = { ...fields };
    delete copy.gameval;
    out[id] = copy;
  }
  return out;
}

function diffKeys(a, b, label) {
  const ka = new Set(Object.keys(a));
  const kb = new Set(Object.keys(b));
  const onlyA = [...ka].filter((k) => !kb.has(k));
  const onlyB = [...kb].filter((k) => !ka.has(k));
  if (onlyA.length || onlyB.length) {
    console.log(`  ${label}: content-only=${onlyA.slice(0, 5)} changes-only=${onlyB.slice(0, 5)}`);
    return 1;
  }
  let mismatched = 0;
  for (const k of ka) {
    const x = JSON.stringify(a[k]);
    const y = JSON.stringify(b[k]);
    if (x !== y) {
      if (mismatched < 3) console.log(`  ${label} id ${k}:\n    content=${x}\n    changes=${y}`);
      mismatched++;
    }
  }
  if (mismatched) console.log(`  ${label}: ${mismatched} bodies differ`);
  return mismatched > 0 ? 1 : 0;
}

const t0 = Date.now();
const content = await json(`${origin}/diff/config/${type}/content?base=${base}&rev=${rev}`);
const tContent = Date.now() - t0;

const t1 = Date.now();
const paged = await walk();
const tPaged = Date.now() - t1;

console.log(`${type} ${base}->${rev}`);
console.log(`  /content   ${tContent} ms, one request`);
console.log(`  /changes   ${tPaged} ms, ${paged.requests} requests`);

let problems = 0;
problems += diffKeys(normalizeAdded(content.added ?? {}), paged.added, "added");
problems += diffKeys(content.changed ?? {}, paged.changed, "changed");

const removedContent = [...(content.removed ?? [])].sort((a, b) => a - b);
const removedPaged = [...paged.removed].sort((a, b) => a - b);
if (JSON.stringify(removedContent) !== JSON.stringify(removedPaged)) {
  console.log(`  removed differ: content=${removedContent.length} changes=${removedPaged.length}`);
  problems++;
}

// `/content` ships every gameval at the revision; `/changes` ships one per row. Compare only the
// ids taking part in the diff, and require presence to match too — a removed entity losing its
// name would otherwise slip through as a generated `[type_id]` title on the website.
const gvContent = content.gamevals ?? {};
const diffIds = [
  ...Object.keys(content.added ?? {}),
  ...Object.keys(content.changed ?? {}),
  ...removedContent.map(String),
];
let gvMismatch = 0;
for (const id of diffIds) {
  const expected = gvContent[id];
  const actual = paged.gamevals[id];
  if ((expected ?? null) !== (actual ?? null)) {
    if (gvMismatch < 5) console.log(`  gameval id ${id}: content=${expected} changes=${actual}`);
    gvMismatch++;
  }
}
if (gvMismatch) {
  console.log(`  ${gvMismatch} of ${diffIds.length} gameval names differ`);
  problems++;
}

console.log(
  `  counts: added ${Object.keys(paged.added).length}, changed ${Object.keys(paged.changed).length}, removed ${removedPaged.length}`,
);
console.log(problems === 0 ? "  OK — identical" : `  ${problems} differences`);
process.exit(problems === 0 ? 0 : 2);
