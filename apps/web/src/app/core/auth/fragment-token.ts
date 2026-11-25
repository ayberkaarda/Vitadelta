/** base64url token as issued by the API (256 bits, unpadded; generous upper bound). */
const TOKEN_PATTERN = /^[A-Za-z0-9_-]{16,512}$/;

export interface FragmentTokenEnvironment {
  readonly location: Pick<Location, 'hash' | 'pathname' | 'search'>;
  readonly history: Pick<History, 'replaceState' | 'state'>;
}

/**
 * Reads the e-mail token from the URL fragment (`#token=...`) of `/auth/verify` and
 * `/auth/reset`, and removes the fragment from the address bar and the history entry with
 * `history.replaceState` before the caller sends any request. The token is only ever sent in
 * the POST body.
 *
 * Returns `null` when no well-formed token is present; the fragment is removed in that case too.
 */
export function readFragmentToken(
  env: FragmentTokenEnvironment = { location: window.location, history: window.history },
): string | null {
  const hash = env.location.hash;
  if (!hash || hash === '#') {
    return null;
  }
  const token = new URLSearchParams(hash.slice(1)).get('token');
  env.history.replaceState(env.history.state, '', env.location.pathname + env.location.search);
  return token !== null && TOKEN_PATTERN.test(token) ? token : null;
}
