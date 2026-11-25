import { FieldError } from '../../core/api/api-types';

/** Minimum password length of the registration and reset rules (re-checked by the API). */
export const MIN_PASSWORD_LENGTH = 10;

const EMAIL_SHAPE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/** Rough client-side shape check; the API decides what a valid address is. */
export function isEmailShaped(value: string): boolean {
  return EMAIL_SHAPE.test(value.trim());
}

/** E-mail wording for a server `invalid_format` on an `email` field; other errors use the shared keys. */
export function authFieldMessage(error: FieldError): string | null {
  return error.field === 'email' && error.code === 'invalid_format' ? 'auth.errors.email' : null;
}
