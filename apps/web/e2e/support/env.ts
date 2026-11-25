import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/** Settings of the e2e run: environment variables first, then the stack's root `.env`. */
export interface E2eEnv {
  readonly baseURL: string;
  readonly apiBase: string;
  readonly mailpitURL: string;
  readonly adminEmail: string;
  readonly adminPassword: string;
}

const ROOT_ENV_FILE = resolve(__dirname, '..', '..', '..', '..', '.env');

function readRootEnv(): Record<string, string> {
  if (!existsSync(ROOT_ENV_FILE)) {
    return {};
  }
  const values: Record<string, string> = {};
  for (const raw of readFileSync(ROOT_ENV_FILE, 'utf8').split(/\r?\n/)) {
    const match = /^([A-Z0-9_]+)=(.*)$/.exec(raw.trim());
    if (match?.[1] && match[2] !== undefined) {
      values[match[1]] = match[2];
    }
  }
  return values;
}

function value(name: string, fallback?: string): string | undefined {
  const own = process.env[name];
  return own !== undefined && own !== '' ? own : fallback;
}

let cached: E2eEnv | null = null;

/**
 * The bootstrap admin is read from `E2E_ADMIN_EMAIL` / `E2E_ADMIN_PASSWORD`, else from
 * `VITADELTA_BOOTSTRAP_ADMIN_EMAIL` / `VITADELTA_BOOTSTRAP_ADMIN_PASSWORD` of the root `.env`
 * that started the stack.
 */
export function e2eEnv(): E2eEnv {
  if (cached) {
    return cached;
  }
  const root = readRootEnv();
  const baseURL = (value('E2E_BASE_URL') ?? 'http://localhost:8080').replace(/\/+$/, '');
  const adminEmail = value('E2E_ADMIN_EMAIL', root['VITADELTA_BOOTSTRAP_ADMIN_EMAIL']);
  const adminPassword = value('E2E_ADMIN_PASSWORD', root['VITADELTA_BOOTSTRAP_ADMIN_PASSWORD']);
  if (!adminEmail || !adminPassword) {
    throw new Error(
      'The e2e suite needs the bootstrap admin: set E2E_ADMIN_EMAIL and E2E_ADMIN_PASSWORD, or ' +
        'VITADELTA_BOOTSTRAP_ADMIN_EMAIL and VITADELTA_BOOTSTRAP_ADMIN_PASSWORD in the root .env ' +
        'before the stack starts.',
    );
  }
  cached = {
    baseURL,
    apiBase: `${baseURL}/api/v1`,
    mailpitURL: (value('E2E_MAILPIT_URL') ?? 'http://127.0.0.1:8025').replace(/\/+$/, ''),
    adminEmail,
    adminPassword,
  };
  return cached;
}
