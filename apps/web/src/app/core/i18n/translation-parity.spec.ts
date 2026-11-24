import en from '../../../../public/i18n/en.json';
import tr from '../../../../public/i18n/tr.json';

import { AVAILABLE_LANGS, AppLang } from './languages';

type TranslationTree = Readonly<Record<string, unknown>>;

function flatten(tree: TranslationTree, prefix = ''): Map<string, unknown> {
  const entries = new Map<string, unknown>();
  for (const [key, value] of Object.entries(tree)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (typeof value === 'object' && value !== null && !Array.isArray(value)) {
      flatten(value as TranslationTree, path).forEach((leaf, leafPath) =>
        entries.set(leafPath, leaf),
      );
    } else {
      entries.set(path, value);
    }
  }
  return entries;
}

const translations: Record<AppLang, Map<string, unknown>> = {
  en: flatten(en),
  tr: flatten(tr),
};

describe('translation files', () => {
  it('provide a file for every available language', () => {
    expect(Object.keys(translations).sort()).toEqual([...AVAILABLE_LANGS].sort());
  });

  it('have identical key sets', () => {
    const enKeys = [...translations.en.keys()].sort();
    const trKeys = [...translations.tr.keys()].sort();
    expect(trKeys).toEqual(enKeys);
  });

  it.each(AVAILABLE_LANGS)('contain only non-empty strings in %s', (lang) => {
    for (const [key, value] of translations[lang]) {
      expect(typeof value, key).toBe('string');
      expect(String(value).trim(), key).not.toBe('');
    }
  });

  it('cover the copy required by the shell page', () => {
    const required = [
      'shell.title',
      'shell.tagline',
      'shell.health.label',
      'shell.health.loading',
      'shell.health.unreachable',
      'shell.health.retry',
      'shell.health.status.UP',
      'shell.health.status.DEGRADED',
      'shell.health.status.DOWN',
    ];
    for (const key of required) {
      expect(translations.en.has(key), key).toBe(true);
    }
  });
});
