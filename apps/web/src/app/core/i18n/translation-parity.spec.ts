import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';

import {
  DELTA_ROW_FLAGS,
  DOSAGE_FORMS,
  EXPLANATION_KEYS,
  NUTRIENT_CATEGORIES,
  PLAN_CODES,
  PRODUCT_FLAG_CODES,
  PROBLEM_CODES,
  SERVER_MESSAGE_KEYS,
  VERDICTS,
} from '../api/api-types';
import { AVAILABLE_LANGS } from './languages';

type Tree = Readonly<Record<string, unknown>>;

// Tests run with the web app folder as working directory.
const I18N_ROOT = resolve(process.cwd(), 'public/i18n');

function flatten(tree: Tree, prefix = ''): Map<string, unknown> {
  const entries = new Map<string, unknown>();
  for (const [key, value] of Object.entries(tree)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (typeof value === 'object' && value !== null && !Array.isArray(value)) {
      flatten(value as Tree, path).forEach((leaf, leafPath) => entries.set(leafPath, leaf));
    } else {
      entries.set(path, value);
    }
  }
  return entries;
}

function load(path: string): Map<string, unknown> {
  return flatten(JSON.parse(readFileSync(path, 'utf8')) as Tree);
}

function params(value: unknown): string[] {
  return [...String(value).matchAll(/\{\{\s*([A-Za-z0-9_]+)\s*\}\}/g)].map((m) => m[1]).sort();
}

/** One translation set: the root files or one feature scope folder. */
interface TranslationSet {
  readonly scope: string | null;
  readonly files: Readonly<Record<string, Map<string, unknown>>>;
}

function discover(): TranslationSet[] {
  const sets: TranslationSet[] = [];
  const fileSet = (dir: string) =>
    Object.fromEntries(
      readdirSync(dir)
        .filter((name) => name.endsWith('.json'))
        .map((name) => [name.replace(/\.json$/, ''), load(join(dir, name))]),
    );
  sets.push({ scope: null, files: fileSet(I18N_ROOT) });
  for (const entry of readdirSync(I18N_ROOT)) {
    const dir = join(I18N_ROOT, entry);
    if (statSync(dir).isDirectory()) {
      sets.push({ scope: entry, files: fileSet(dir) });
    }
  }
  return sets;
}

const SETS = discover();
const ROOT = SETS[0];
const label = (set: TranslationSet) => set.scope ?? '(root)';

describe('translation files', () => {
  it.each(SETS.map((set) => [label(set), set] as const))(
    '%s has exactly one file per available language',
    (_name, set) => {
      expect(Object.keys(set.files).sort()).toEqual([...AVAILABLE_LANGS].sort());
    },
  );

  it.each(SETS.map((set) => [label(set), set] as const))(
    '%s has identical key sets in every language',
    (_name, set) => {
      const [first, ...others] = AVAILABLE_LANGS;
      const reference = [...set.files[first].keys()].sort();
      for (const lang of others) {
        expect([...set.files[lang].keys()].sort(), lang).toEqual(reference);
      }
    },
  );

  it.each(SETS.map((set) => [label(set), set] as const))(
    '%s contains only non-empty strings with matching parameters',
    (_name, set) => {
      const reference = set.files[AVAILABLE_LANGS[0]];
      for (const lang of AVAILABLE_LANGS) {
        for (const [key, value] of set.files[lang]) {
          expect(typeof value, `${lang}:${key}`).toBe('string');
          expect(String(value).trim(), `${lang}:${key}`).not.toBe('');
          expect(params(value), `${lang}:${key} parameters`).toEqual(params(reference.get(key)));
        }
      }
    },
  );

  it('scope folders use lower-case names and never redefine a root key', () => {
    const rootKeys = new Set(ROOT.files['en'].keys());
    for (const set of SETS.slice(1)) {
      expect(set.scope).toMatch(/^[a-z][a-z0-9-]*$/);
      for (const key of set.files['en'].keys()) {
        expect(rootKeys.has(`${set.scope}.${key}`), `${set.scope}.${key}`).toBe(false);
      }
    }
  });

  it('root file covers every contract code the shared UI renders', () => {
    const keys = ROOT.files['en'];
    const required = [
      'disclaimer.not_medical_advice',
      'disclaimer.footer',
      'verdict.indicative_note',
      'verdict.market_fallback',
      'results.locked_cta',
      'results.locked_row',
      'quota.remaining',
      'quota.exhausted',
      'problem.generic.title',
      'problem.network.title',
      'problem.not_found.title',
      ...PROBLEM_CODES.flatMap((code) => [`problem.${code}.title`, `problem.${code}.body`]),
      ...PRODUCT_FLAG_CODES.map((code) => `flag.${code}`),
      ...DELTA_ROW_FLAGS.map((code) => `delta.flag.${code}`),
      ...VERDICTS.map((verdict) => `verdict.${verdict}`),
      ...EXPLANATION_KEYS,
      ...NUTRIENT_CATEGORIES.map((category) => `category.${category}`),
      ...DOSAGE_FORMS.map((form) => `dosage.${form}`),
      ...PLAN_CODES.map((plan) => `plan.${plan}`),
      ...SERVER_MESSAGE_KEYS,
    ];
    for (const key of required) {
      expect(keys.has(key), key).toBe(true);
    }
  });

  it('explanation keys accept every parameter the API sends', () => {
    const allowed = [
      'currency',
      'deltaContent',
      'deltaCostPerDay',
      'mRef',
      'marginalRate',
      'ratio',
    ];
    for (const key of EXPLANATION_KEYS) {
      for (const name of params(ROOT.files['en'].get(key))) {
        expect(allowed, `${key} uses {{ ${name} }}`).toContain(name);
      }
    }
  });
});
