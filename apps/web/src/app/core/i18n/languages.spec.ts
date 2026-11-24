import { resolveInitialLang } from './languages';

describe('resolveInitialLang', () => {
  it('uses a supported browser language', () => {
    expect(resolveInitialLang('tr')).toBe('tr');
    expect(resolveInitialLang('en')).toBe('en');
  });

  it('falls back to English for unsupported or missing languages', () => {
    expect(resolveInitialLang('de')).toBe('en');
    expect(resolveInitialLang(undefined)).toBe('en');
  });
});
