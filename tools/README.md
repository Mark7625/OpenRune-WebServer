# Verification and benchmark scripts

Plain Node, no dependencies. They talk to a running server (`./gradlew bootOldschool`), default
`http://localhost:8090`.

## `verify-changes-vs-content.mjs`

Walks `/diff/config/{type}/changes` page by page, reassembles the result and compares it field for
field against the single `/diff/config/{type}/content` response — the equivalence the website's
paged diff rendering depends on. Covers bodies, the removed-id list and gameval names, and exits
non-zero on any difference.

```
node tools/verify-changes-vs-content.mjs items 1 241
node tools/verify-changes-vs-content.mjs varbit 240 241   # a pair containing removals
```

## `bench-first-paint.mjs`

Times the whole `/content` response against the first `/changes` page and against a full page walk,
over HTTP, including serialisation and transfer. Produces the table in `docs/BENCHMARKS.md`.

```
node tools/bench-first-paint.mjs
```

## `verify-rendered-lines.mjs`

The stronger check: runs the website's own `configLinesFromDiffBody` over both forms and compares
the rendered `ConfigLine[]` element by element. This needs the website transpiled to plain ES
modules first, because the renderer lives in the Next.js app.

From the website repo (`openrune.github.io`, sibling of this one):

```
npx tsc -p tsconfig.transpile.json          # emits .tsout/
node --import ../OpenRune-WebServer/tools/register-alias.mjs \
     ../OpenRune-WebServer/tools/verify-rendered-lines.mjs items 1 241
```

It must run with the website repo as the working directory so `.tsout` resolves and the transpiled
modules can find `node_modules`. Override the output location with `OPENRUNE_TSOUT`.

`alias-loader.mjs` / `register-alias.mjs` are the module hook that maps the website's `@/` path
alias onto that transpiled output; they are not useful on their own.

## `check-sprite-ref-lines.mjs`

Checks that a config field pointing at a sprite gameval keeps the referenced sprite id on the
rendered line. Sprite gameval names are not unique — at revision 241, 136 distinct sprite ids are
all displayed as `sprites.mapfunction` — so the text view cannot recover the id from the label and
has to carry it on the line. Same setup as `verify-rendered-lines.mjs`:

```
node --import ../OpenRune-WebServer/tools/register-alias.mjs \
     ../OpenRune-WebServer/tools/check-sprite-ref-lines.mjs mapelement 241 1
```
