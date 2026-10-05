// Time to the bytes the website needs for its first screen of a config diff: the whole `/content`
// response against the first `/changes` page, plus the full page walk for comparison.
//
//   node build/bench-first-paint.mjs [http://localhost:8090]

const origin = process.argv[2] ?? "http://localhost:8090";
const CASES = [
  ["items", 1, 241],
  ["objects", 1, 241],
  ["npcs", 1, 241],
  ["interfaces", 1, 241],
  ["items", 240, 241],
  ["varbit", 240, 241],
];
const FIRST_PAGE = 100;

async function timed(url) {
  const t = Date.now();
  const res = await fetch(url);
  const body = await res.arrayBuffer();
  return { ms: Date.now() - t, bytes: body.byteLength, body };
}

async function walkAll(type, base, rev) {
  const t = Date.now();
  let bytes = 0;
  let requests = 0;
  for (const kind of ["added", "changed", "removed"]) {
    let after;
    for (;;) {
      const qs = new URLSearchParams({ base: String(base), rev: String(rev), kind, limit: "500" });
      if (after != null) qs.set("after", String(after));
      const res = await fetch(`${origin}/diff/config/${type}/changes?${qs}`);
      const text = await res.text();
      bytes += text.length;
      requests++;
      const page = JSON.parse(text);
      if (!page.hasMore || page.nextCursor == null) break;
      after = page.nextCursor;
    }
  }
  return { ms: Date.now() - t, bytes, requests };
}

const mb = (n) => `${(n / 1024 / 1024).toFixed(2)} MB`;

console.log("| diff | /content | first /changes page | full page walk |");
console.log("|------|----------|---------------------|----------------|");
for (const [type, base, rev] of CASES) {
  // Warm both paths so neither pays for a cold plan cache.
  await fetch(`${origin}/diff/config/${type}/changes?base=${base}&rev=${rev}&kind=added&limit=${FIRST_PAGE}`);
  const first = await timed(
    `${origin}/diff/config/${type}/changes?base=${base}&rev=${rev}&kind=added&limit=${FIRST_PAGE}`,
  );
  const whole = await timed(`${origin}/diff/config/${type}/content?base=${base}&rev=${rev}`);
  const walk = await walkAll(type, base, rev);
  console.log(
    `| ${type} ${base}→${rev} | ${whole.ms} ms, ${mb(whole.bytes)} | ${first.ms} ms, ${mb(first.bytes)} | ${walk.ms} ms, ${mb(walk.bytes)}, ${walk.requests} requests |`,
  );
}
