import { DOCUMENT, Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../api/api-client';
import { ApiProblem } from '../api/api-problem';

/** Hosted pages of the payment provider live on subdomains of this domain (ADR-0014). */
const PROVIDER_DOMAIN = 'stripe.com';
const MAX_LENGTH = 4096;

/**
 * Returns `value` as a normalised URL when it is a hosted page of the payment provider: an
 * absolute `https:` URL on a subdomain of `stripe.com` (Checkout on `checkout.stripe.com`, the
 * Billing Portal on `billing.stripe.com`), without credentials or an explicit port. Anything
 * else (another host, `http:`, `javascript:`, a relative or malformed value) is `null`, so a
 * tampered or unexpected API answer never moves the browser elsewhere.
 */
export function providerPageUrl(value: unknown): string | null {
  if (typeof value !== 'string' || value.length === 0 || value.length > MAX_LENGTH) {
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
    parsed.port !== ''
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
