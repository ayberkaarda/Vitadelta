// Fails when the initial bundle of the production build exceeds its gzipped budget.
//
// The initial files are the ones `index.html` loads before any lazy route: module scripts
// (main, polyfills when present), module preloads of shared initial chunks and stylesheets.
// Each file is gzipped with the zlib default level (the level of the `gzip` command line tool)
// and the sizes are summed. 1 kB = 1000 bytes, as in the Angular build output.
//
// Usage: node scripts/check-bundle-size.mjs [browser-dir] [--limit-kb=380]

import { existsSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';

/** Budget of the project specification for the initial bundle, in kB gzipped. */
export const DEFAULT_LIMIT_KB = 380;

const SCRIPT = /<script\b[^>]*\bsrc="([^"]+)"[^>]*>/gi;
const LINK = /<link\b[^>]*>/gi;
const REL = /\brel="([^"]+)"/i;
const HREF = /\bhref="([^"]+)"/i;
const INITIAL_RELS = new Set(['stylesheet', 'modulepreload']);

function isLocal(path) {
  return !/^(?:[a-z][a-z0-9+.-]*:)?\/\//i.test(path) && !path.startsWith('data:');
}

/** Local script, module preload and stylesheet paths referenced by `index.html`, in order. */
export function initialFiles(html) {
  const files = [];
  for (const match of html.matchAll(SCRIPT)) {
    files.push(match[1]);
  }
  for (const match of html.matchAll(LINK)) {
    const rel = REL.exec(match[0])?.[1]?.toLowerCase();
    const href = HREF.exec(match[0])?.[1];
    if (rel && href && INITIAL_RELS.has(rel)) {
      files.push(href);
    }
  }
  return [...new Set(files.filter(isLocal).map((file) => file.replace(/^\.?\//, '')))];
}

/** Gzipped size of `content` in bytes. */
export function gzipSize(content) {
  return gzipSync(content).length;
}

/** Sums the measured sizes and compares them with the limit (kB, 1000 bytes). */
export function evaluate(measured, limitKb = DEFAULT_LIMIT_KB) {
  if (measured.length === 0) {
    throw new Error('No initial files found in index.html.');
  }
  const totalBytes = measured.reduce((sum, file) => sum + file.gzipBytes, 0);
  const limitBytes = limitKb * 1000;
  return { totalBytes, limitBytes, ok: totalBytes <= limitBytes };
}

export function formatKb(bytes) {
  return `${(bytes / 1000).toFixed(2)} kB`;
}

/** Measures the initial files of a built `browser` directory. */
export function measure(browserDir) {
  const indexPath = join(browserDir, 'index.html');
  if (!existsSync(indexPath)) {
    throw new Error(`No index.html in ${browserDir}; run the production build first.`);
  }
  return initialFiles(readFileSync(indexPath, 'utf8')).map((file) => {
    const path = join(browserDir, file);
    if (!existsSync(path)) {
      throw new Error(`index.html references ${file}, which is not in the build output.`);
    }
    const content = readFileSync(path);
    return { file, rawBytes: content.length, gzipBytes: gzipSize(content) };
  });
}

function main(args) {
  const here = fileURLToPath(new URL('.', import.meta.url));
  const limitArg = args.find((arg) => arg.startsWith('--limit-kb='));
  const limitKb = limitArg ? Number(limitArg.slice('--limit-kb='.length)) : DEFAULT_LIMIT_KB;
  if (!Number.isFinite(limitKb) || limitKb <= 0) {
    throw new Error(`Invalid limit: ${limitArg}`);
  }
  const dirArg = args.find((arg) => !arg.startsWith('--'));
  const browserDir = resolve(dirArg ?? join(here, '../dist/web/browser'));

  const measured = measure(browserDir);
  const result = evaluate(measured, limitKb);
  for (const file of measured) {
    console.log(
      `${file.file.padEnd(32)} ${formatKb(file.rawBytes).padStart(12)} raw ${formatKb(file.gzipBytes).padStart(12)} gzip`,
    );
  }
  console.log(
    `Initial bundle: ${formatKb(result.totalBytes)} gzipped (limit ${formatKb(result.limitBytes)})`,
  );
  if (!result.ok) {
    console.error(
      `Initial bundle exceeds the limit by ${formatKb(result.totalBytes - result.limitBytes)}.`,
    );
    process.exitCode = 1;
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2));
}
