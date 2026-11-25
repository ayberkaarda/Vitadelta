import { ApiProblem } from '../../core/api/api-problem';
import { FieldError, SERVER_MESSAGE_KEYS } from '../../core/api/api-types';

/**
 * `FieldError.code` values with their own message under `validation.<code>` in the root
 * translation files: the codes the contract lists for `FieldError.code`, the bean-validation
 * codes of the API (`invalid_format`) and the password policy codes (`too_short`,
 * `breached_password`). Any other code renders `validation.generic`.
 */
export const FIELD_ERROR_CODES = [
  'required',
  'out_of_range',
  'value_out_of_range',
  'invalid_format',
  'weights_all_zero',
  'unit_requires_form',
  'unknown_nutrient_code',
  'lambda_requires_custom',
  'duplicate_nutrient',
  'same_product',
  'no_offer',
  'no_stock',
  'threshold_not_allowed',
  'not_in_future',
  'too_short',
  'breached_password',
] as const;

const KNOWN_FIELD_CODES: ReadonlySet<string> = new Set(FIELD_ERROR_CODES);

/** i18n key for a server `FieldError.code`. */
export function fieldErrorKey(code: string): string {
  return KNOWN_FIELD_CODES.has(code) ? `validation.${code}` : 'validation.generic';
}

/** One entry of an error summary: the control to focus and the i18n key of its message. */
export interface FieldErrorEntry {
  readonly controlId: string;
  readonly messageKey: string;
}

/**
 * Maps the field errors of a `validation_failed` problem to control ids.
 * `controlIdFor` translates a JSON path (`requiredNutrients[0].unit`) to the id of the control
 * that edits it, or `null` when the page has no such control (the error is then attached to
 * `fallbackControlId`).
 */
export function mapFieldErrors(
  problem: ApiProblem,
  controlIdFor: (field: string) => string | null,
  fallbackControlId: string,
): FieldErrorEntry[] {
  const seen = new Set<string>();
  const entries: FieldErrorEntry[] = [];
  for (const error of problem.errors) {
    const controlId = controlIdFor(error.field) ?? fallbackControlId;
    if (seen.has(controlId)) {
      continue;
    }
    seen.add(controlId);
    entries.push({ controlId, messageKey: fieldErrorKey(error.code) });
  }
  return entries;
}

/** Field errors of one form: i18n key per control id plus the entries of the error summary. */
export interface FormErrors {
  readonly fields: Readonly<Record<string, string>>;
  readonly summary: readonly FieldErrorEntry[];
}

export const NO_FORM_ERRORS: FormErrors = { fields: {}, summary: [] };

/** Builds {@link FormErrors} from `[controlId, i18nKey]` pairs (first error per control wins). */
export function formErrors(entries: readonly (readonly [string, string])[]): FormErrors {
  const fields: Record<string, string> = {};
  const summary: FieldErrorEntry[] = [];
  for (const [controlId, messageKey] of entries) {
    if (controlId in fields) {
      continue;
    }
    fields[controlId] = messageKey;
    summary.push({ controlId, messageKey });
  }
  return { fields, summary };
}

/**
 * {@link FormErrors} of a `validation_failed` problem. `controlIds` maps the JSON path of a
 * field (`password`, `durationDays`) to the id of its control; unknown paths are attached to
 * `fallbackControlId`. `messageFor` may return a more specific i18n key for one field error
 * (for example the e-mail wording of `invalid_format`); otherwise {@link fieldErrorKey} applies.
 */
export function problemFormErrors(
  problem: ApiProblem,
  controlIds: Readonly<Record<string, string>>,
  fallbackControlId: string,
  messageFor: (error: FieldError) => string | null = () => null,
): FormErrors {
  return formErrors(
    problem.errors.map(
      (error) =>
        [
          controlIds[error.field] ?? fallbackControlId,
          messageFor(error) ?? fieldErrorKey(error.code),
        ] as const,
    ),
  );
}

type ServerMessageKey = (typeof SERVER_MESSAGE_KEYS)[number];

const KNOWN_MESSAGE_KEYS: ReadonlySet<string> = new Set(SERVER_MESSAGE_KEYS);

/**
 * The `messageKey` of an `AcceptedMessage` when it is one of the known server message keys,
 * otherwise `fallback`. Server data never selects an arbitrary translation key.
 */
export function serverMessageKey(
  key: string | null | undefined,
  fallback: ServerMessageKey,
): string {
  return key && KNOWN_MESSAGE_KEYS.has(key) ? key : fallback;
}
