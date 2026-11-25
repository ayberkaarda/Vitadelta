import { captureEmailToken, takeEmailToken } from './email-token';
import { FragmentTokenEnvironment } from './fragment-token';

const TOKEN = 'q1W2e3R4t5Y6u7I8o9P0a_S-d1F2g3H4j5K6l7Z8x9C';

/** A fake address bar: `replaceState` really changes the hash, as in a browser. */
function environment(pathname: string, hash: string) {
  const location = { hash, pathname, search: '' };
  const replaceState = vi.fn((_state: unknown, _unused: string, url?: string | URL | null) => {
    location.hash = String(url ?? '').includes('#')
      ? String(url).slice(String(url).indexOf('#'))
      : '';
  });
  const env: FragmentTokenEnvironment = { location, history: { replaceState, state: null } };
  return { env, location, replaceState };
}

describe('e-mail token capture', () => {
  afterEach(() => {
    // Leaves the module holder empty for the next test.
    takeEmailToken('/auth/verify', environment('/auth/verify', '').env);
  });

  it.each(['/auth/verify', '/auth/reset', '/auth/verify/'])(
    'takes the token out of the address bar of %s and hands it over once',
    (pathname) => {
      const { env, location, replaceState } = environment(pathname, `#token=${TOKEN}`);
      captureEmailToken(env);
      expect(replaceState).toHaveBeenCalledTimes(1);
      expect(location.hash).toBe('');
      const path = pathname.startsWith('/auth/reset') ? '/auth/reset' : '/auth/verify';
      expect(takeEmailToken(path, env)).toBe(TOKEN);
      expect(takeEmailToken(path, env)).toBeNull();
    },
  );

  it('does not hand the token of one page to the other and clears it anyway', () => {
    const verify = environment('/auth/verify', `#token=${TOKEN}`);
    captureEmailToken(verify.env);
    const reset = environment('/auth/reset', '');
    expect(takeEmailToken('/auth/reset', reset.env)).toBeNull();
    expect(takeEmailToken('/auth/verify', verify.env)).toBeNull();
  });

  it('removes a malformed token and holds nothing', () => {
    const { env, location } = environment('/auth/verify', '#token=<script>');
    captureEmailToken(env);
    expect(location.hash).toBe('');
    expect(takeEmailToken('/auth/verify', env)).toBeNull();
  });

  it('leaves the fragment of other pages alone', () => {
    const { env, location, replaceState } = environment('/privacy', '#rights');
    captureEmailToken(env);
    expect(replaceState).not.toHaveBeenCalled();
    expect(location.hash).toBe('#rights');
  });

  it('reads and removes the fragment of a link opened inside the running application', () => {
    const { env, location } = environment('/auth/reset', `#token=${TOKEN}`);
    expect(takeEmailToken('/auth/reset', env)).toBe(TOKEN);
    expect(location.hash).toBe('');
  });
});
