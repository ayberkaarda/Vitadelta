import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';

// Project specification §15.5: no HTML injection sinks and no sanitizer bypass anywhere in
// `src`. The patterns are assembled at runtime so this file does not match itself.
const FORBIDDEN: readonly { readonly name: string; readonly pattern: RegExp }[] = [
  { name: '[inner' + 'HTML] binding', pattern: new RegExp('\\[\\s*inner' + 'HTML\\s*\\]', 'i') },
  { name: '[outer' + 'HTML] binding', pattern: new RegExp('\\[\\s*outer' + 'HTML\\s*\\]', 'i') },
  { name: 'bypass' + 'SecurityTrust', pattern: new RegExp('bypass' + 'SecurityTrust') },
  { name: 'Dom' + 'Sanitizer', pattern: new RegExp('Dom' + 'Sanitizer') },
];

const SRC = resolve(process.cwd(), 'src');

function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) {
      return sourceFiles(path);
    }
    return /\.(ts|html)$/.test(name) ? [path] : [];
  });
}

describe('XSS guard', () => {
  const files = sourceFiles(SRC);

  it('scans the application sources', () => {
    expect(files.length).toBeGreaterThan(20);
  });

  it.each(FORBIDDEN)('finds no $name in src', ({ pattern }) => {
    const hits = files
      .filter((file) => pattern.test(readFileSync(file, 'utf8')))
      .map((file) => relative(SRC, file));
    expect(hits).toEqual([]);
  });
});
