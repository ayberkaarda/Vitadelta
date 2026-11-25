import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, resolve, sep } from 'node:path';

import { TestBed } from '@angular/core/testing';
import { TRANSLOCO_TRANSPILER, Translation, TranslocoService } from '@jsverse/transloco';

import { appConfig } from '../../app.config';
import { HOSTILE_VALUES, placeholders, renderedWith } from '../../testing/hostile-text';
import { ScopeTranslations, translocoTesting } from '../../testing/transloco-testing';
import { LiteralTranspiler } from './literal-transpiler';

// Guard for catalog and other server-supplied text passed as translation parameters
// (project specification §10.3, §15.5): such text must be rendered verbatim, never resolved
// against the parameters, the prototype chain or the dictionary, and never looped on.

// Tests run with the web app folder as working directory.
const APP_ROOT = resolve(process.cwd(), 'src/app');
const I18N_ROOT = resolve(process.cwd(), 'public/i18n');
const THIS_FILE = 'core/i18n/translation-params.guard.spec.ts';

function walk(dir: string): string[] {
  return readdirSync(dir).flatMap((entry) => {
    const path = join(dir, entry);
    return statSync(path).isDirectory() ? walk(path) : [path];
  });
}

const SOURCES = walk(APP_ROOT)
  .filter((path) => /\.(ts|html)$/.test(path) && !path.endsWith('.d.ts'))
  .map((path) => ({
    file: relative(APP_ROOT, path).split(sep).join('/'),
    text: readFileSync(path, 'utf8'),
  }))
  .filter((source) => source.file !== THIS_FILE);

/** Files allowed to set up Transloco or its transpiler; everything else goes through them. */
const ALLOWED: Readonly<Record<string, readonly string[]>> = {
  'provideTransloco(': ['core/i18n/provide-i18n.ts'],
  TranslocoTestingModule: ['testing/transloco-testing.ts'],
  provideTranslocoTranspiler: ['core/i18n/provide-i18n.ts', 'testing/transloco-testing.ts'],
  'provide: TRANSLOCO_TRANSPILER': [],
  DefaultTranspiler: [],
  FunctionalTranspiler: [],
  // Object translations are not transpiled by the literal transpiler (strings only).
  translateObject: [],
  selectTranslateObject: [],
  translateObjectSignal: [],
};

/** One `| transloco: <params>` call site of a template. */
interface ParamSite {
  readonly file: string;
  /** The literal key in front of the pipe, or null for a computed key. */
  readonly key: string | null;
  /** The parameter names of an object literal argument, or null for a computed argument. */
  readonly names: readonly string[] | null;
  /** Parameter names whose value is read from server-supplied data. */
  readonly untrusted: readonly string[];
}

/** Reads the balanced `{ ... }` starting at `start`. */
function objectLiteral(text: string, start: number): string {
  let depth = 0;
  for (let index = start; index < text.length; index++) {
    if (text[index] === '{') {
      depth++;
    } else if (text[index] === '}') {
      depth--;
      if (depth === 0) {
        return text.slice(start + 1, index);
      }
    }
  }
  throw new Error(`unbalanced parameter object at ${start}`);
}

/** Splits `a: x, b: f(y, z), c` into its top-level entries. */
function entries(body: string): string[] {
  const parts: string[] = [];
  let depth = 0;
  let current = '';
  for (const char of body) {
    if ('([{'.includes(char)) {
      depth++;
    } else if (')]}'.includes(char)) {
      depth--;
    }
    if (char === ',' && depth === 0) {
      parts.push(current);
      current = '';
    } else {
      current += char;
    }
  }
  parts.push(current);
  return parts.map((part) => part.trim()).filter((part) => part !== '');
}

/**
 * Values read from API data: catalog names and codes, profile and account data, payload text.
 * Translation keys, formatted numbers and dates built by the app are not matched.
 */
const SERVER_TEXT = /\b(name|nameEn|email|retailer|title|code|kind|requestId|label)\b|nameOf\(/;

function paramSites(): ParamSite[] {
  const sites: ParamSite[] = [];
  const pipe = /\|\s*transloco\s*:\s*/g;
  for (const { file, text } of SOURCES) {
    if (file.endsWith('.spec.ts')) {
      continue;
    }
    for (const match of text.matchAll(pipe)) {
      const before = text.slice(Math.max(0, match.index - 200), match.index);
      const key = /'([A-Za-z0-9_.]+)'\s*$/.exec(before)?.[1] ?? null;
      const argument = match.index + match[0].length;
      if (text[argument] !== '{') {
        sites.push({ file, key, names: null, untrusted: [] });
        continue;
      }
      const pairs = entries(objectLiteral(text, argument)).map((entry) => {
        const colon = entry.indexOf(':');
        return colon < 0
          ? { name: entry, value: entry }
          : { name: entry.slice(0, colon).trim(), value: entry.slice(colon + 1).trim() };
      });
      sites.push({
        file,
        key,
        names: pairs.map((pair) => pair.name),
        untrusted: pairs.filter((pair) => SERVER_TEXT.test(pair.value)).map((pair) => pair.name),
      });
    }
  }
  return sites;
}

/** Root files and every feature scope folder, in the shape translocoTesting expects. */
function loadScopes(): Record<string, ScopeTranslations> {
  const scopes: Record<string, ScopeTranslations> = {};
  const read = (path: string) => JSON.parse(readFileSync(path, 'utf8')) as Translation;
  for (const entry of readdirSync(I18N_ROOT)) {
    const dir = join(I18N_ROOT, entry);
    if (statSync(dir).isDirectory()) {
      scopes[entry] = { en: read(join(dir, 'en.json')), tr: read(join(dir, 'tr.json')) };
    }
  }
  return scopes;
}

function flatten(tree: Translation, prefix: string, into: Map<string, string>): void {
  for (const [key, value] of Object.entries(tree)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (typeof value === 'string') {
      into.set(path, value);
    } else if (typeof value === 'object' && value !== null) {
      flatten(value as Translation, path, into);
    }
  }
}

/** Every translation string of a language, keyed as the templates use it. */
function dictionary(
  scopes: Record<string, ScopeTranslations>,
  lang: 'en' | 'tr',
): Map<string, string> {
  const all = new Map<string, string>();
  const root = JSON.parse(readFileSync(join(I18N_ROOT, `${lang}.json`), 'utf8')) as Translation;
  flatten(root, '', all);
  for (const [scope, files] of Object.entries(scopes)) {
    flatten(files[lang], scope, all);
  }
  return all;
}

describe('translation parameter guard', () => {
  it('sets up Transloco and its transpiler only through provideI18n and translocoTesting', () => {
    for (const [needle, allowed] of Object.entries(ALLOWED)) {
      const users = SOURCES.filter((source) => source.text.includes(needle)).map((s) => s.file);
      expect(
        users.filter((file) => !allowed.includes(file)),
        needle,
      ).toEqual([]);
    }
  });

  it('the application configuration uses the literal transpiler', () => {
    TestBed.configureTestingModule({ providers: appConfig.providers });
    expect(TestBed.inject(TRANSLOCO_TRANSPILER)).toBeInstanceOf(LiteralTranspiler);
  });

  describe('with every translation file loaded', () => {
    const scopes = loadScopes();
    const dictionaries = { en: dictionary(scopes, 'en'), tr: dictionary(scopes, 'tr') } as const;
    let transloco: TranslocoService;

    beforeEach(() => {
      TestBed.configureTestingModule({ imports: [translocoTesting({ scopes })] });
      transloco = TestBed.inject(TranslocoService);
    });

    it('finds the template call sites that pass server-supplied text as parameters', () => {
      const sites = paramSites();
      const untrusted = sites.filter((site) => site.untrusted.length > 0);
      const keys = new Set(untrusted.map((site) => site.key));
      // Known sites prove the walker sees templates (external and inline) and their keys.
      for (const key of [
        'results.table.breakdown_label',
        'results.drawer.title',
        'compare.loaded',
        'product.offers.visit_label',
        'account.alerts.threshold_aria',
        'admin.users.confirm_disable',
      ]) {
        expect(keys.has(key), key).toBe(true);
      }
      expect(untrusted.length).toBeGreaterThan(10);
      expect(sites.some((site) => site.names === null)).toBe(true);
    });

    it('renders server-supplied parameters of every literal-key call site verbatim', () => {
      const sites = paramSites().filter((site) => site.key !== null && site.untrusted.length > 0);
      expect(sites.length).toBeGreaterThan(0);
      for (const site of sites) {
        const key = site.key ?? '';
        for (const lang of ['en', 'tr'] as const) {
          const template = dictionaries[lang].get(key);
          expect(template, `${site.file}: ${lang}:${key} exists`).toBeDefined();
          for (const value of HOSTILE_VALUES) {
            const params = Object.fromEntries((site.names ?? []).map((name) => [name, value]));
            const used = placeholders(template ?? '');
            const text = transloco.translate(key, params, lang);
            expect(text, `${site.file}: ${lang}:${key}`).toBe(renderedWith(template ?? '', value));
            for (const name of site.untrusted.filter((n) => used.includes(n))) {
              expect(text, `${site.file}: ${lang}:${key} keeps ${name} verbatim`).toContain(value);
            }
          }
        }
      }
    });

    it.each(['en', 'tr'] as const)(
      'renders hostile values verbatim in every %s message with parameters, in bounded time',
      (lang) => {
        const started = performance.now();
        let checked = 0;
        for (const [key, template] of dictionaries[lang]) {
          const names = placeholders(template);
          if (names.length === 0) {
            continue;
          }
          for (const value of HOSTILE_VALUES) {
            const params = Object.fromEntries(names.map((name) => [name, value]));
            expect(transloco.translate(key, params, lang), `${lang}:${key}`).toBe(
              renderedWith(template, value),
            );
            checked++;
          }
        }
        expect(checked).toBeGreaterThan(100);
        expect(performance.now() - started).toBeLessThan(10_000);
      },
    );
  });
});
