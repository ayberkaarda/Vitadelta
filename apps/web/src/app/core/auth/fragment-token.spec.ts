import { FragmentTokenEnvironment, readFragmentToken } from './fragment-token';

const TOKEN = 'q1W2e3R4t5Y6u7I8o9P0a_S-d1F2g3H4j5K6l7Z8x9C';

function environment(hash: string) {
  const replaceState = vi.fn();
  const env: FragmentTokenEnvironment = {
    location: { hash, pathname: '/auth/verify', search: '?lang=tr' },
    history: { replaceState, state: { navigationId: 1 } },
  };
  return { env, replaceState };
}

describe('readFragmentToken', () => {
  it('returns the token and removes the fragment from the history entry', () => {
    const { env, replaceState } = environment(`#token=${TOKEN}`);
    expect(readFragmentToken(env)).toBe(TOKEN);
    expect(replaceState).toHaveBeenCalledTimes(1);
    expect(replaceState).toHaveBeenCalledWith({ navigationId: 1 }, '', '/auth/verify?lang=tr');
  });

  it('removes a fragment with a malformed token and returns null', () => {
    const { env, replaceState } = environment('#token=<script>alert(1)</script>');
    expect(readFragmentToken(env)).toBeNull();
    expect(replaceState).toHaveBeenCalledWith({ navigationId: 1 }, '', '/auth/verify?lang=tr');
  });

  it('returns null without touching history when there is no fragment', () => {
    const { env, replaceState } = environment('');
    expect(readFragmentToken(env)).toBeNull();
    expect(replaceState).not.toHaveBeenCalled();
  });

  it('ignores other fragment parameters', () => {
    const { env } = environment(`#other=1&token=${TOKEN}`);
    expect(readFragmentToken(env)).toBe(TOKEN);
  });

  it('works against the real window location and history', () => {
    window.history.replaceState(null, '', `/auth/reset#token=${TOKEN}`);
    expect(readFragmentToken()).toBe(TOKEN);
    expect(window.location.hash).toBe('');
    expect(window.location.pathname).toBe('/auth/reset');
  });
});
