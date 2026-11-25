import { DEFAULT_RETURN_URL, isSafeReturnUrl, loginQueryParams, safeReturnUrl } from './return-url';

describe('safeReturnUrl', () => {
  it.each([
    '/',
    '/results?country=TR&mode=VALUE',
    '/compare/a/b?w=VITAMIN:1',
    '/account/saved#email-alerts',
    '/products/3b2f0c4e?country=TR',
  ])('accepts the app-relative path %s', (value) => {
    expect(safeReturnUrl(value)).toBe(value);
    expect(isSafeReturnUrl(value)).toBe(true);
  });

  it.each([
    'https://evil.example',
    'https://evil.example/results',
    '//evil.example',
    '//evil.example/path',
    '/\\evil.example',
    '/\\/evil.example',
    '\\\\evil.example',
    'javascript:alert(1)',
    'JaVaScRiPt:alert(1)',
    ' javascript:alert(1)',
    'data:text/html,hi',
    'results',
    '',
    '/results\u0000',
    '/results\nSet-Cookie: x',
    '/results\u0085',
    '/' + 'a'.repeat(2048),
  ])('rejects %j', (value) => {
    expect(safeReturnUrl(value)).toBe(DEFAULT_RETURN_URL);
    expect(isSafeReturnUrl(value)).toBe(false);
  });

  it.each([null, undefined, 42, ['/results'], { url: '/' }])(
    'rejects non-strings (%j)',
    (value) => {
      expect(safeReturnUrl(value)).toBe(DEFAULT_RETURN_URL);
    },
  );
});

describe('loginQueryParams', () => {
  it('carries a safe current URL and the reason', () => {
    expect(loginQueryParams('/account/saved', 'session_expired')).toEqual({
      returnUrl: '/account/saved',
      reason: 'session_expired',
    });
  });

  it('omits the home page, auth pages and unsafe values', () => {
    expect(loginQueryParams('/')).toEqual({});
    expect(loginQueryParams('/auth/login?returnUrl=/x')).toEqual({});
    expect(loginQueryParams('//evil.example')).toEqual({});
  });
});
