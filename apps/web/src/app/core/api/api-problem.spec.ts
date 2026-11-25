import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';

import { ApiProblem, parseRetryAfter, problemCodeOf } from './api-problem';

function httpError(status: number, body: unknown, headers: Record<string, string> = {}) {
  return new HttpErrorResponse({ status, error: body, headers: new HttpHeaders(headers) });
}

describe('ApiProblem', () => {
  it('maps a validation problem with field errors', () => {
    const problem = ApiProblem.from(
      httpError(422, {
        type: 'https://vitadelta.dev/problems/validation_failed',
        title: 'Validation failed',
        status: 422,
        requestId: 'req-1',
        errors: [
          { field: 'weights', code: 'weights_all_zero', message: 'All zero' },
          { field: 'requiredNutrients[0].unit', code: 'unit_requires_form', message: 'x' },
          { field: 'weights', code: 'out_of_range', message: 'duplicate field' },
          { field: 42, code: 'bad' },
        ],
      }),
    );
    expect(problem.kind).toBe('problem');
    expect(problem.code).toBe('validation_failed');
    expect(problem.status).toBe(422);
    expect(problem.requestId).toBe('req-1');
    expect(problem.errors).toHaveLength(3);
    expect(problem.fieldErrors().get('weights')?.code).toBe('weights_all_zero');
    expect(problem.messageKey).toBe('problem.validation_failed');
  });

  it('maps quota and entitlement extension members', () => {
    const quota = ApiProblem.from(
      httpError(403, {
        type: 'https://vitadelta.dev/problems/quota_exceeded',
        title: 'Query quota exceeded',
        status: 403,
        requestId: 'r',
        limit: 3,
        used: 3,
        action: 'SIGN_UP',
        upgradeHint: { messageKey: 'quota.exhausted', ctaPath: '/auth/register' },
      }),
    );
    expect(quota.code).toBe('quota_exceeded');
    expect(quota.limit).toBe(3);
    expect(quota.used).toBe(3);
    expect(quota.upgradeHint).toEqual({ messageKey: 'quota.exhausted', ctaPath: '/auth/register' });

    const entitlement = ApiProblem.from(
      httpError(403, {
        type: 'https://vitadelta.dev/problems/entitlement_exceeded',
        title: 'Plan limit reached',
        status: 403,
        requestId: 'r',
        limit: 5,
        used: 5,
        plan: 'FREE',
        upgradeTo: 'PRO',
        upgradeHint: { messageKey: 'limits.saved_products_reached', ctaPath: '/upgrade' },
      }),
    );
    expect(entitlement.plan).toBe('FREE');
    expect(entitlement.upgradeTo).toBe('PRO');
    expect(entitlement.upgradeHint?.ctaPath).toBe('/upgrade');
  });

  it('reads Retry-After seconds for rate limiting', () => {
    const problem = ApiProblem.from(
      httpError(
        429,
        {
          type: 'https://vitadelta.dev/problems/rate_limited',
          title: 'x',
          status: 429,
          requestId: 'r',
        },
        { 'Retry-After': '30' },
      ),
    );
    expect(problem.code).toBe('rate_limited');
    expect(problem.retryAfterSeconds).toBe(30);
  });

  it('treats about:blank and bodies without a known type as generic HTTP errors', () => {
    const blank = ApiProblem.from(
      httpError(404, { type: 'about:blank', title: 'Not Found', status: 404, requestId: 'r2' }),
    );
    expect(blank.kind).toBe('http');
    expect(blank.code).toBeNull();
    expect(blank.messageKey).toBe('problem.not_found');

    const unknown = ApiProblem.from(httpError(500, 'oops', { 'X-Request-Id': 'from-header' }));
    expect(unknown.code).toBeNull();
    expect(unknown.requestId).toBe('from-header');
    expect(unknown.messageKey).toBe('problem.generic');
  });

  it('parses a problem body delivered as text', () => {
    const problem = ApiProblem.from(
      httpError(
        401,
        JSON.stringify({
          type: 'https://vitadelta.dev/problems/invalid_credentials',
          title: 'x',
          status: 401,
          requestId: 'r',
        }),
      ),
    );
    expect(problem.code).toBe('invalid_credentials');
    expect(ApiProblem.from(httpError(400, '{not json')).code).toBeNull();
  });

  it('maps network failures and foreign errors', () => {
    const network = ApiProblem.from(httpError(0, new ProgressEvent('error')));
    expect(network.kind).toBe('network');
    expect(network.messageKey).toBe('problem.network');

    const foreign = ApiProblem.from(new Error('boom'));
    expect(foreign.kind).toBe('network');
    expect(ApiProblem.from(foreign)).toBe(foreign);
  });

  it('resolves problem codes only for known contract types', () => {
    expect(problemCodeOf('https://vitadelta.dev/problems/promo_invalid')).toBe('promo_invalid');
    expect(problemCodeOf('https://vitadelta.dev/problems/not_a_code')).toBeNull();
    expect(problemCodeOf('about:blank')).toBeNull();
    expect(problemCodeOf(7)).toBeNull();
  });

  it('parses Retry-After as seconds or HTTP date', () => {
    const now = Date.parse('2026-11-25T10:00:00Z');
    expect(parseRetryAfter(null, now)).toBeNull();
    expect(parseRetryAfter(' 12 ', now)).toBe(12);
    expect(parseRetryAfter('Wed, 25 Nov 2026 10:00:45 GMT', now)).toBe(45);
    expect(parseRetryAfter('Wed, 25 Nov 2026 09:00:00 GMT', now)).toBe(0);
    expect(parseRetryAfter('soon', now)).toBeNull();
  });
});
