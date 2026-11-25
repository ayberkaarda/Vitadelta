/**
 * Returns `value` as a normalised absolute URL when it parses and its protocol is exactly
 * `http:` or `https:`, else `null`. Used before rendering a server-provided URL as a link, so
 * `mailto:`, `javascript:`, `data:`, relative and malformed values never become clickable.
 */
export function webUrl(value: string | null | undefined): string | null {
  if (typeof value !== 'string' || value.trim() === '') {
    return null;
  }
  let parsed: URL;
  try {
    parsed = new URL(value);
  } catch {
    return null;
  }
  return parsed.protocol === 'http:' || parsed.protocol === 'https:' ? parsed.href : null;
}
