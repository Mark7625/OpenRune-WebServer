// Resolves the website's `@/...` path alias, and the extensionless relative specifiers tsc emits,
// against a transpiled copy of the website source. See tools/README.md.
import { existsSync } from "node:fs";
import { fileURLToPath, pathToFileURL } from "node:url";
import { dirname, join, resolve as resolvePath } from "node:path";

const root = resolvePath(process.env.OPENRUNE_TSOUT ?? ".tsout");

function firstExisting(base) {
  for (const candidate of [`${base}.js`, join(base, "index.js")]) {
    if (existsSync(candidate)) return { url: pathToFileURL(candidate).href, shortCircuit: true };
  }
  return null;
}

export function resolve(specifier, context, next) {
  if (specifier.startsWith("@/")) {
    const hit = firstExisting(join(root, specifier.slice(2)));
    if (hit) return hit;
  }
  if (specifier.startsWith(".") && !specifier.endsWith(".js") && context.parentURL) {
    const hit = firstExisting(join(dirname(fileURLToPath(context.parentURL)), specifier));
    if (hit) return hit;
  }
  return next(specifier, context);
}
