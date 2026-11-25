/** Fallback target for every rejected `returnUrl`. */
export const DEFAULT_RETURN_URL = '/';

const MAX_LENGTH = 2048;
const PROBE_ORIGIN = 'https://app.invalid';

/** C0 controls, DEL and C1 controls. */
function hasControlCharacter(value: string): boolean {
  for (let index = 0; index < value.length; index++) {
    const code = value.charCodeAt(index);
    if (code <= 0x1f || (code >= 0x7f && code <= 0x9f)) {
      return true;
    }
  }
  return false;
}

/**
 * Returns `value` when it is an app-relative URL, otherwise {@link DEFAULT_RETURN_URL}.
 *
 * Accepted: exactly one leading `/`, no `//` or `/\` prefix, no backslash anywhere, no scheme,
 * no control characters, at most 2048 characters, and the URL resolves to the app's own origin.
 * Callers navigate with `router.navigateByUrl`, never with `location.href`.
 */
export function safeReturnUrl(value: unknown): string {
  if (typeof value !== 'string' || value.length === 0 || value.length > MAX_LENGTH) {
    return DEFAULT_RETURN_URL;
  }
  if (!value.startsWith('/') || value.startsWith('//') || value.includes('\\')) {
    return DEFAULT_RETURN_URL;
  }
  if (hasControlCharacter(value)) {
    return DEFAULT_RETURN_URL;
  }
  let resolved: URL;
  try {
    resolved = new URL(value, PROBE_ORIGIN);
  } catch {
    return DEFAULT_RETURN_URL;
  }
  if (resolved.origin !== PROBE_ORIGIN) {
    return DEFAULT_RETURN_URL;
  }
  return value;
}

/** Whether {@link safeReturnUrl} would accept `value` unchanged. */
export function isSafeReturnUrl(value: unknown): value is string {
  return typeof value === 'string' && safeReturnUrl(value) === value && value.length > 0;
}

/** Query parameters for a login link that returns to `currentUrl` afterwards. */
export function loginQueryParams(
  currentUrl: string,
  reason?: 'session_expired',
): Record<string, string> {
  const params: Record<string, string> = {};
  const target = safeReturnUrl(currentUrl);
  if (target !== DEFAULT_RETURN_URL && !target.startsWith('/auth/')) {
    params['returnUrl'] = target;
  }
  if (reason) {
    params['reason'] = reason;
  }
  return params;
}
