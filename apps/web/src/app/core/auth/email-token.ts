import { FragmentTokenEnvironment, readFragmentToken } from './fragment-token';

/** Pages that receive a single-use e-mail token in the URL fragment (`#token=…`). */
export type EmailTokenPath = '/auth/verify' | '/auth/reset';

const TOKEN_PATHS: readonly EmailTokenPath[] = ['/auth/verify', '/auth/reset'];

function browserEnvironment(): FragmentTokenEnvironment {
  return { location: window.location, history: window.history };
}

function tokenPath(pathname: string): EmailTokenPath | null {
  const path = pathname.length > 1 ? pathname.replace(/\/$/, '') : pathname;
  return TOKEN_PATHS.find((candidate) => candidate === path) ?? null;
}

/**
 * The token taken from the address bar before the application started, kept in memory only
 * (never in storage) until the page of its path takes it.
 */
let held: { readonly path: EmailTokenPath; readonly token: string } | null = null;

/**
 * Pre-bootstrap step (called from `main.ts` before the application starts, so before its
 * first HTTP request): on `/auth/verify` and `/auth/reset` the fragment is read and removed
 * from the address bar and the history entry, and a well-formed token is held in memory for
 * the page. Other paths and their fragments (in-page anchors) are left untouched.
 */
export function captureEmailToken(env: FragmentTokenEnvironment = browserEnvironment()): void {
  const path = tokenPath(env.location.pathname);
  if (path === null || !env.location.hash) {
    return;
  }
  const token = readFragmentToken(env);
  held = token === null ? null : { path, token };
}

/**
 * Hands the token of `path` to its page once and clears the holder. A link that was opened
 * inside the running application (no pre-bootstrap capture) still carries its fragment; it
 * is read and removed from the address bar here. Returns `null` without a usable token.
 */
export function takeEmailToken(
  path: EmailTokenPath,
  env: FragmentTokenEnvironment = browserEnvironment(),
): string | null {
  const current = held;
  held = null;
  if (current !== null && current.path === path) {
    return current.token;
  }
  return readFragmentToken(env);
}
