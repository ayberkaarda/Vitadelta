// Self-test of the bundle size check: `node --test scripts/check-bundle-size.test.mjs`.

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import { gunzipSync, gzipSync } from 'node:zlib';

import {
  DEFAULT_LIMIT_KB,
  evaluate,
  formatKb,
  gzipSize,
  initialFiles,
  measure,
} from './check-bundle-size.mjs';

const SCRIPT_PATH = fileURLToPath(new URL('./check-bundle-size.mjs', import.meta.url));

const INDEX = `<!doctype html>
<html lang="en">
  <head>
    <base href="/"/>
    <link rel="icon" type="image/x-icon" href="favicon.ico"/>
    <link rel="preconnect" href="https://fonts.example.test"/>
    <link rel="modulepreload" href="chunk-SHARED.js"/>
    <link rel="stylesheet" href="styles-ABC.css"></head>
  <body>
    <script src="polyfills-DEF.js" type="module"></script>
    <script src="main-GHI.js" type="module"></script>
    <script src="https://cdn.example.test/remote.js"></script>
  </body>
</html>`;

function browserDir(files) {
  const dir = mkdtempSync(join(tmpdir(), 'bundle-check-'));
  for (const [name, content] of Object.entries(files)) {
    writeFileSync(join(dir, name), content);
  }
  return dir;
}

/** Deterministic, poorly compressible content of `bytes` length. */
function noise(bytes) {
  const buffer = Buffer.alloc(bytes);
  let state = 0x2545f491;
  for (let i = 0; i < bytes; i++) {
    state ^= state << 13;
    state ^= state >>> 17;
    state ^= state << 5;
    buffer[i] = state & 0xff;
  }
  return buffer;
}

describe('initialFiles', () => {
  it('lists local scripts, module preloads and stylesheets, nothing else', () => {
    assert.deepEqual(initialFiles(INDEX), [
      'polyfills-DEF.js',
      'main-GHI.js',
      'chunk-SHARED.js',
      'styles-ABC.css',
    ]);
  });

  it('normalizes relative prefixes and removes duplicates', () => {
    const html = '<script src="./main.js"></script><link rel="modulepreload" href="/main.js">';
    assert.deepEqual(initialFiles(html), ['main.js']);
  });
});

describe('size logic', () => {
  it('measures the gzipped length', () => {
    const content = Buffer.from('a'.repeat(10_000));
    assert.equal(gzipSize(content), gzipSync(content).length);
    assert.ok(gzipSize(content) < 200);
    assert.equal(gunzipSync(gzipSync(content)).length, 10_000);
  });

  it('passes at the limit and fails one byte above it (1 kB = 1000 bytes)', () => {
    assert.equal(DEFAULT_LIMIT_KB, 380);
    const at = evaluate([{ file: 'main.js', rawBytes: 1, gzipBytes: 380_000 }]);
    assert.deepEqual(at, { totalBytes: 380_000, limitBytes: 380_000, ok: true });
    const above = evaluate([
      { file: 'main.js', rawBytes: 1, gzipBytes: 300_000 },
      { file: 'styles.css', rawBytes: 1, gzipBytes: 80_001 },
    ]);
    assert.deepEqual(above, { totalBytes: 380_001, limitBytes: 380_000, ok: false });
    assert.equal(evaluate([{ file: 'a.js', rawBytes: 1, gzipBytes: 1_500 }], 1).ok, false);
  });

  it('refuses an empty initial set', () => {
    assert.throws(() => evaluate([]), /No initial files/);
  });

  it('formats kB with two decimals', () => {
    assert.equal(formatKb(101_234), '101.23 kB');
  });
});

describe('measure and command line', () => {
  it('sums the gzipped initial files of a build directory', () => {
    const main = noise(50_000);
    const styles = Buffer.from('body{margin:0}'.repeat(200));
    const dir = browserDir({
      'index.html': '<link rel="stylesheet" href="styles.css"><script src="main.js"></script>',
      'main.js': main,
      'styles.css': styles,
      'chunk-LAZY.js': noise(900_000),
    });
    try {
      const measured = measure(dir);
      assert.deepEqual(
        measured.map((file) => file.file),
        ['main.js', 'styles.css'],
      );
      assert.equal(measured[0].gzipBytes, gzipSync(main).length);
      assert.equal(measured[1].rawBytes, styles.length);

      const pass = spawnSync(process.execPath, [SCRIPT_PATH, dir], { encoding: 'utf8' });
      assert.equal(pass.status, 0, pass.stderr);
      assert.match(pass.stdout, /Initial bundle: \d+\.\d{2} kB gzipped \(limit 380\.00 kB\)/);

      const fail = spawnSync(process.execPath, [SCRIPT_PATH, dir, '--limit-kb=10'], {
        encoding: 'utf8',
      });
      assert.equal(fail.status, 1);
      assert.match(fail.stderr, /exceeds the limit by/);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it('fails when index.html references a missing file', () => {
    const dir = browserDir({ 'index.html': '<script src="main-MISSING.js"></script>' });
    try {
      assert.throws(() => measure(dir), /main-MISSING\.js/);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});
