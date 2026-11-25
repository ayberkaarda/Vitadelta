import { DOCUMENT, Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../api/api-client';
import { ApiProblem } from '../api/api-problem';

/** Hosted pages of the payment provider live on subdomains of this domain (ADR-0014). */
const PROVIDER_DOMAIN = 'stripe.com';
const MAX_LENGTH = 4096;
/**
 * Scheme and authority as written: `https://` then a host of letters, digits, dots and hyphens
 * and nothing else (no user info, no port, not even `:443`), followed by the end, a path, a
 * query or a fragment. Checked on the raw string because `URL` normalises some of these away.
 */
const RAW_PREFIX = /^https:\/\/([a-z0-9.-]+)(?:[/?#]|$)/i;
/** Whitespace, control characters and backslashes, which `URL` silently drops or rewrites. */
// eslint-disable-next-line no-control-regex -- control characters are exactly what is refused
const UNSAFE_CHARACTERS = /[\s\\\u0000-\u001f\u007f-\u009f]/;

/**
 * Returns `value` as a normalised URL when it is a hosted page of the payment provider: an
 * absolute `https:` URL on a subdomain of `stripe.com` (Checkout on `checkout.stripe.com`, the
 * Billing Portal on `billing.stripe.com`), without credentials or any explicit port. The raw
 * string must already have that shape (see {@link RAW_PREFIX}) and contain no whitespace, control
 * character or backslash. Anything else (another host, `http:`, `javascript:`, a relative or
 * malformed value) is `null`, so a tampered or unexpected API answer never moves the browser
 * elsewhere.
 */
export function providerPageUrl(value: unknown): string | null {
  if (typeof value !== 'string' || value.length === 0 || value.length > MAX_LENGTH) {
    return null;
  }
  const raw = RAW_PREFIX.exec(value);
  if (raw === null || UNSAFE_CHARACTERS.test(value)) {
    return null;
  }
  let parsed: URL;
  try {
    parsed = new URL(value);
  } catch {
    return null;
  }
  if (
    parsed.protocol !== 'https:' ||
    !parsed.hostname.toLowerCase().endsWith(`.${PROVIDER_DOMAIN}`) ||
    parsed.username !== '' ||
    parsed.password !== '' ||
    parsed.port !== '' ||
    parsed.hostname !== raw[1].toLowerCase()
  ) {
    return null;
  }
  return parsed.href;
}

/** Leaves the single-page app for an external URL (a seam for tests). */
@Injectable({ providedIn: 'root' })
export class ExternalNavigation {
  private readonly document = inject(DOCUMENT);

  /** Loads `url` in the current tab; the caller has validated it. */
  assign(url: string): void {
    this.document.location.assign(url);
  }
}

/** Which hosted page of the payment provider to open. */
export type ProviderPage = 'checkout' | 'portal';

/**
 * Opens the payment provider's hosted checkout or billing portal (ADR-0014): asks the API for
 * a session, checks the returned URL with {@link providerPageUrl} and sends the browser there.
 * The URL is never rendered. Fails with an {@link ApiProblem}: the API's problem, or a generic
 * one when the answer is not a provider URL.
 */
@Injectable({ providedIn: 'root' })
export class BillingNavigator {
  private readonly api = inject(ApiClient);
  private readonly navigation = inject(ExternalNavigation);

  async open(page: ProviderPage): Promise<void> {
    const redirect = await firstValueFrom(
      page === 'checkout'
        ? this.api.createCheckoutSession()
        : this.api.createBillingPortalSession(),
    );
    const url = providerPageUrl(redirect.url);
    if (url === null) {
      throw new ApiProblem({ kind: 'http', status: 502, code: null });
    }
    this.navigation.assign(url);
  }
}
