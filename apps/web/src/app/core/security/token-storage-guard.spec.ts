import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';

// Project specification §10.4 / §15.5: the access token lives in memory only and the refresh
// token only in the HttpOnly `vd_refresh` cookie. These checks scan the application sources so
// a later change cannot start persisting a token or putting one into a URL. Patterns are built
// at runtime so this file does not match itself.

const SRC = resolve(process.cwd(), 'src');
const STORAGE_FILE = join('app', 'core', 'state', 'preference-storage.ts');
/** Preference keys that may be written to browser storage (never anything security-relevant). */
const ALLOWED_PREFERENCE_KEYS = ['country', 'currency', 'lang'];

function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) {
      return sourceFiles(path);
    }
    return /\.(ts|html)$/.test(name) && !name.endsWith('.spec.ts') ? [path] : [];
  });
}

const FILES = sourceFiles(SRC).map((path) => ({
  path: relative(SRC, path),
  text: readFileSync(path, 'utf8'),
}));

const pattern = (...parts: string[]) => new RegExp(parts.join(''), 'i');

describe('token storage guard', () => {
  it('scans the application sources', () => {
    expect(FILES.length).toBeGreaterThan(50);
  });

  it('touches browser storage, cookies or IndexedDB only in PreferenceStorage', () => {
    const storage = pattern(
      '\\b(local',
      'Storage|session',
      'Storage|indexed',
      'DB|document\\.',
      'cookie)\\b',
    );
    const hits = FILES.filter((file) => storage.test(file.text)).map((file) => file.path);
    expect(hits).toEqual([STORAGE_FILE]);
  });

  it('writes only the allow-listed preference keys', () => {
    const writes = FILES.filter((file) => /\.storage\.write\(/.test(file.text));
    expect(writes.length).toBeGreaterThan(0);
    for (const file of writes) {
      const constants = new Map(
        [...file.text.matchAll(/const (\w+_KEY) = '([^']+)'/g)].map((m) => [m[1], m[2]]),
      );
      for (const call of file.text.matchAll(/\.storage\.write\(\s*([^,]+),/g)) {
        const key = constants.get(call[1].trim());
        expect(key, `${file.path}: ${call[0]}`).toBeDefined();
        expect(ALLOWED_PREFERENCE_KEYS, `${file.path}: ${call[0]}`).toContain(key);
      }
    }
  });

  it('never mentions a token next to a storage or cookie write', () => {
    const write = pattern('(set', 'Item|\\.write)\\([^)]*(token|jwt|bearer|refresh)');
    const hits = FILES.filter((file) => write.test(file.text)).map((file) => file.path);
    expect(hits).toEqual([]);
  });

  it('never puts a token into a query string or router query params', () => {
    const query = pattern('[?&](access_?|refresh_?)?', 'token=');
    const params = pattern('query', 'Params[^;]{0,200}\\b(access|refresh)?', 'token\\b');
    const hits = FILES.filter((file) => query.test(file.text) || params.test(file.text)).map(
      (file) => file.path,
    );
    expect(hits).toEqual([]);
  });

  it('keeps the access token in a private field of SessionStore only', () => {
    const session = FILES.find((file) => file.path.endsWith(join('auth', 'session.store.ts')));
    expect(session?.text).toMatch(/private token: string \| null = null;/);
    const holders = FILES.filter((file) => /\.accessToken\b(?!\()/.test(file.text)).map(
      (file) => file.path,
    );
    // Only the token response is read (`response.accessToken`): validated by the refresh
    // coordinator, kept by the session store.
    expect(holders.sort()).toEqual([
      join('app', 'core', 'auth', 'refresh-coordinator.ts'),
      join('app', 'core', 'auth', 'session.store.ts'),
    ]);
  });
});
