import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';

import {
  FieldError,
  PROBLEM_CODES,
  PROBLEM_TYPE_PREFIX,
  PlanCode,
  ProblemCode,
  UpgradeHint,
} from './api-types';

/** How the failure reached the client. */
export type ProblemKind =
  /** An `application/problem+json` body with a known problem type. */
  | 'problem'
  /** An HTTP error without a contract problem code (`about:blank` or no body). */
  | 'http'
  /** No HTTP response at all (offline, DNS, aborted connection). */
  | 'network';

const KNOWN_CODES: ReadonlySet<string> = new Set(PROBLEM_CODES);

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function asNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function asString(value: unknown): string | null {
  return typeof value === 'string' && value.length > 0 ? value : null;
}

function readFieldErrors(value: unknown): FieldError[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.filter(isRecord).flatMap((entry) => {
    const field = asString(entry['field']);
    const code = asString(entry['code']);
    const message = typeof entry['message'] === 'string' ? entry['message'] : '';
    return field !== null && code !== null ? [{ field, code, message }] : [];
  });
}

function readUpgradeHint(value: unknown): UpgradeHint | null {
  if (!isRecord(value)) {
    return null;
  }
  const messageKey = asString(value['messageKey']);
  const ctaPath = asString(value['ctaPath']);
  return messageKey !== null && ctaPath !== null ? { messageKey, ctaPath } : null;
}

function readPlan(value: unknown): PlanCode | null {
  return value === 'FREE' || value === 'PRO' ? value : null;
}

/**
 * Parses `Retry-After` as delay seconds or as an HTTP date (relative to `now`).
 * Returns `null` when the header is absent or unusable.
 */
export function parseRetryAfter(value: string | null, now: number = Date.now()): number | null {
  if (value === null) {
    return null;
  }
  const trimmed = value.trim();
  if (/^\d{1,9}$/.test(trimmed)) {
    return Number(trimmed);
  }
  const date = Date.parse(trimmed);
  if (Number.isNaN(date)) {
    return null;
  }
  return Math.max(0, Math.ceil((date - now) / 1000));
}

/** Problem code of a `Problem.type` URI, or `null` for `about:blank` and unknown types. */
export function problemCodeOf(type: unknown): ProblemCode | null {
  if (typeof type !== 'string' || !type.startsWith(PROBLEM_TYPE_PREFIX)) {
    return null;
  }
  const code = type.slice(PROBLEM_TYPE_PREFIX.length);
  return KNOWN_CODES.has(code) ? (code as ProblemCode) : null;
}

export interface ApiProblemInit {
  readonly kind: ProblemKind;
  readonly status: number;
  readonly code: ProblemCode | null;
  readonly title?: string | null;
  readonly detail?: string | null;
  readonly requestId?: string | null;
  readonly errors?: readonly FieldError[];
  readonly limit?: number | null;
  readonly used?: number | null;
  readonly plan?: PlanCode | null;
  readonly upgradeTo?: PlanCode | null;
  readonly upgradeHint?: UpgradeHint | null;
  readonly retryAfterSeconds?: number | null;
}

/**
 * Typed view of an RFC 9457 problem returned by the API. Every `ApiClient` call fails with an
 * `ApiProblem`, never with a raw `HttpErrorResponse`. Pages pick their rendering from `code`
 * (and `status` for generic HTTP errors); the server text in `title`/`detail` is never shown.
 */
export class ApiProblem extends Error {
  readonly kind: ProblemKind;
  readonly status: number;
  readonly code: ProblemCode | null;
  readonly title: string | null;
  readonly detail: string | null;
  readonly requestId: string | null;
  readonly errors: readonly FieldError[];
  readonly limit: number | null;
  readonly used: number | null;
  readonly plan: PlanCode | null;
  readonly upgradeTo: PlanCode | null;
  readonly upgradeHint: UpgradeHint | null;
  readonly retryAfterSeconds: number | null;

  constructor(init: ApiProblemInit) {
    super(init.code ?? `http_${init.status}`);
    this.name = 'ApiProblem';
    this.kind = init.kind;
    this.status = init.status;
    this.code = init.code;
    this.title = init.title ?? null;
    this.detail = init.detail ?? null;
    this.requestId = init.requestId ?? null;
    this.errors = init.errors ?? [];
    this.limit = init.limit ?? null;
    this.used = init.used ?? null;
    this.plan = init.plan ?? null;
    this.upgradeTo = init.upgradeTo ?? null;
    this.upgradeHint = init.upgradeHint ?? null;
    this.retryAfterSeconds = init.retryAfterSeconds ?? null;
  }

  /** Maps any error thrown by `HttpClient` (or an existing `ApiProblem`) to an `ApiProblem`. */
  static from(error: unknown, now: number = Date.now()): ApiProblem {
    if (error instanceof ApiProblem) {
      return error;
    }
    if (!(error instanceof HttpErrorResponse)) {
      return new ApiProblem({ kind: 'network', status: 0, code: null });
    }
    if (error.status === 0) {
      return new ApiProblem({
        kind: 'network',
        status: 0,
        code: null,
        requestId: headerRequestId(error.headers),
      });
    }
    const body = parseBody(error.error);
    const code = body ? problemCodeOf(body['type']) : null;
    return new ApiProblem({
      kind: code ? 'problem' : 'http',
      status: error.status,
      code,
      title: body ? asString(body['title']) : null,
      detail: body ? asString(body['detail']) : null,
      requestId: (body ? asString(body['requestId']) : null) ?? headerRequestId(error.headers),
      errors: body ? readFieldErrors(body['errors']) : [],
      limit: body ? asNumber(body['limit']) : null,
      used: body ? asNumber(body['used']) : null,
      plan: body ? readPlan(body['plan']) : null,
      upgradeTo: body ? readPlan(body['upgradeTo']) : null,
      upgradeHint: body ? readUpgradeHint(body['upgradeHint']) : null,
      retryAfterSeconds: parseRetryAfter(error.headers.get('Retry-After'), now),
    });
  }

  /** Field errors keyed by their JSON path (`requiredNutrients[0].unit`). */
  fieldErrors(): ReadonlyMap<string, FieldError> {
    const map = new Map<string, FieldError>();
    for (const entry of this.errors) {
      if (!map.has(entry.field)) {
        map.set(entry.field, entry);
      }
    }
    return map;
  }

  /** i18n key of the problem heading and body (`problem.<code>.title` / `.body`). */
  get messageKey(): string {
    if (this.code) {
      return `problem.${this.code}`;
    }
    if (this.kind === 'network') {
      return 'problem.network';
    }
    if (this.status === 404) {
      return 'problem.not_found';
    }
    return 'problem.generic';
  }
}

function headerRequestId(headers: HttpHeaders | null | undefined): string | null {
  return headers?.get('X-Request-Id') ?? null;
}

function parseBody(body: unknown): Record<string, unknown> | null {
  if (isRecord(body)) {
    return body;
  }
  if (typeof body === 'string' && body.trim().startsWith('{')) {
    try {
      const parsed: unknown = JSON.parse(body);
      return isRecord(parsed) ? parsed : null;
    } catch {
      return null;
    }
  }
  return null;
}
