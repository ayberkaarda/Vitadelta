import { DOCUMENT } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';

import { ApiClient } from '../api/api-client';
import { ApiProblem } from '../api/api-problem';
import { BillingNavigator, ExternalNavigation, providerPageUrl } from './billing-navigator';

const CHECKOUT_URL = 'https://checkout.stripe.com/c/pay/cs_test_a1B2c3D4e5F6g7H8';
const PORTAL_URL = 'https://billing.stripe.com/p/session/test_YWNjdF8xUXdFcnR5';

describe('providerPageUrl', () => {
  it.each([CHECKOUT_URL, PORTAL_URL, 'https://CHECKOUT.Stripe.com/c/pay/cs_test_x#fragment'])(
    'accepts the hosted provider page %s',
    (url) => {
      expect(providerPageUrl(url)).toBe(new URL(url).href);
    },
  );

  it.each([
    ['plain http', 'http://checkout.stripe.com/c/pay/cs_test_x'],
    ['another host', 'https://checkout.example.com/c/pay/cs_test_x'],
    ['a look-alike host', 'https://checkout.stripe.com.example.com/c/pay'],
    ['a suffix without a dot', 'https://evilstripe.com/c/pay'],
    ['the bare domain', 'https://stripe.com/c/pay'],
    ['a trailing-dot host', 'https://checkout.stripe.com./c/pay'],
    ['credentials', 'https://user:secret@checkout.stripe.com/c/pay'],
    ['a user name before the provider host', 'https://checkout.stripe.com@example.com/c/pay'],
    ['an explicit port', 'https://checkout.stripe.com:8443/c/pay'],
    ['the explicit default port', 'https://checkout.stripe.com:443/c/pay/cs_test_x'],
    ['an empty port', 'https://checkout.stripe.com:/c/pay'],
    ['a backslash after the host', 'https://checkout.stripe.com\\@example.com/c/pay'],
    ['a backslash in the path', 'https://checkout.stripe.com/c\\pay'],
    ['a leading space', ' https://checkout.stripe.com/c/pay'],
    ['an embedded tab', 'https://checkout.stripe.com/c/\tpay'],
    ['an embedded line break', 'https://checkout.stri\npe.com/c/pay'],
    ['an embedded space', 'https://checkout.stripe.com/c/p ay'],
    ['an empty user name', 'https://@checkout.stripe.com/c/pay'],
    ['too many slashes', 'https:///checkout.stripe.com/c/pay'],
    ['a missing slash', 'https:/checkout.stripe.com/c/pay'],
    ['a script URL', 'javascript:alert(1)//checkout.stripe.com'],
    ['a data URL', 'data:text/html,checkout.stripe.com'],
    ['a relative path', '/upgrade?checkout=success'],
    ['a protocol-relative URL', '//checkout.stripe.com/c/pay'],
    ['a malformed value', 'https://'],
    ['an empty value', ''],
    ['an overlong value', `https://checkout.stripe.com/${'a'.repeat(4096)}`],
  ])('rejects %s', (_name, url) => {
    expect(providerPageUrl(url)).toBeNull();
  });

  it.each([null, undefined, 42, { url: CHECKOUT_URL }])('rejects the non-string %s', (value) => {
    expect(providerPageUrl(value)).toBeNull();
  });
});

describe('BillingNavigator', () => {
  let api: {
    createCheckoutSession: ReturnType<typeof vi.fn>;
    createBillingPortalSession: ReturnType<typeof vi.fn>;
  };
  let assign: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    api = {
      createCheckoutSession: vi.fn(() => of({ url: CHECKOUT_URL })),
      createBillingPortalSession: vi.fn(() => of({ url: PORTAL_URL })),
    };
    assign = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        { provide: ApiClient, useValue: api },
        { provide: ExternalNavigation, useValue: { assign } },
      ],
    });
  });

  const navigator = () => TestBed.inject(BillingNavigator);

  it('sends the browser to the hosted checkout the API created', async () => {
    await navigator().open('checkout');
    expect(api.createCheckoutSession).toHaveBeenCalledTimes(1);
    expect(api.createBillingPortalSession).not.toHaveBeenCalled();
    expect(assign).toHaveBeenCalledWith(CHECKOUT_URL);
  });

  it('sends the browser to the billing portal the API created', async () => {
    await navigator().open('portal');
    expect(api.createBillingPortalSession).toHaveBeenCalledTimes(1);
    expect(assign).toHaveBeenCalledWith(PORTAL_URL);
  });

  it('never follows an answer that is not a provider page', async () => {
    api.createCheckoutSession.mockReturnValue(of({ url: 'https://phish.example.com/pay' }));
    const failure = navigator().open('checkout');
    await expect(failure).rejects.toBeInstanceOf(ApiProblem);
    await expect(failure).rejects.toMatchObject({ code: null, messageKey: 'problem.generic' });
    expect(assign).not.toHaveBeenCalled();
  });

  it('passes the API problem on and stays in the app', async () => {
    const problem = new ApiProblem({ kind: 'problem', status: 503, code: 'billing_unavailable' });
    api.createBillingPortalSession.mockReturnValue(throwError(() => problem));
    await expect(navigator().open('portal')).rejects.toBe(problem);
    expect(assign).not.toHaveBeenCalled();
  });
});

describe('ExternalNavigation', () => {
  it('loads the URL in the current tab', () => {
    const assign = vi.fn();
    TestBed.configureTestingModule({
      providers: [{ provide: DOCUMENT, useValue: { location: { assign } } }],
    });
    TestBed.inject(ExternalNavigation).assign(CHECKOUT_URL);
    expect(assign).toHaveBeenCalledWith(CHECKOUT_URL);
  });
});
