import { FieldError, InputUnit, RequiredNutrient } from '../../core/api/api-types';
import { FieldErrorEntry, fieldErrorKey } from '../../shared/form/field-errors';
import { IU_NUTRIENT, RequiredRow } from './components/required-nutrients-editor.component';

/** Control ids of the query form; server `FieldError.field` paths map onto them. */
export const CONTROL = {
  country: 'q-country',
  stock: 'q-stock-IN_STOCK_ONLY',
  lambda: 'q-lambda',
  weightsReset: 'q-weights-reset',
  overridesAdd: 'q-overrides-add',
  requiredAdd: 'q-req-add',
  ul: 'q-ul',
  submit: 'q-submit',
  mode: (mode: string) => `q-mode-${mode}`,
  category: (category: string) => `q-w-${category}`,
  override: (code: string) => `q-o-btn-${code}`,
  required: (index: number, part: 'code' | 'min' | 'unit') => `q-req-${index}-${part}`,
} as const;

let rowKey = 0;

/** Editor rows for the required nutrients of a restored query. */
export function rowsFromRequired(required: readonly RequiredNutrient[]): RequiredRow[] {
  return required.map((entry) => ({
    key: rowKey++,
    code: entry.code,
    min: entry.minAmountPerDay !== undefined ? String(entry.minAmountPerDay) : '',
    unit: entry.minAmountPerDay !== undefined && entry.unit ? entry.unit : '',
  }));
}

/** Parses a minimum typed by the user ("1,5" is accepted as 1.5). */
export function parseMinimum(raw: string): number | null {
  const text = raw.trim().replace(',', '.');
  if (text === '') {
    return null;
  }
  const value = Number(text);
  return Number.isFinite(value) ? value : Number.NaN;
}

/**
 * Client-side checks that mirror the contract for required nutrients (`minAmountPerDay > 0`,
 * a minimum needs a unit, `IU` only for vitamin D). The server stays the authority and answers
 * `validation_failed` for anything else (for example `weights_all_zero`).
 */
export function validateRows(rows: readonly RequiredRow[]): FieldErrorEntry[] {
  const entries: FieldErrorEntry[] = [];
  rows.forEach((row, index) => {
    const min = parseMinimum(row.min);
    if (min !== null && !(min > 0)) {
      entries.push({
        controlId: CONTROL.required(index, 'min'),
        messageKey: 'query.errors.min_positive',
      });
    }
    // A missing unit is reported once the minimum itself is valid (one message per row).
    if (min !== null && min > 0 && row.unit === '') {
      entries.push({
        controlId: CONTROL.required(index, 'unit'),
        messageKey: 'query.errors.unit_required',
      });
    }
    if (row.unit === 'IU' && row.code !== IU_NUTRIENT) {
      entries.push({
        controlId: CONTROL.required(index, 'unit'),
        messageKey: 'query.errors.iu_only_vit_d',
      });
    }
  });
  return entries;
}

/** Request entries of valid rows (a minimum is sent only together with its unit). */
export function toRequired(rows: readonly RequiredRow[]): RequiredNutrient[] {
  return rows.map((row) => {
    const min = parseMinimum(row.min);
    return min !== null && min > 0 && row.unit !== ''
      ? { code: row.code, minAmountPerDay: min, unit: row.unit as InputUnit }
      : { code: row.code };
  });
}

/** Maps a server field path (`requiredNutrients[0].unit`) to the control that edits it. */
export function controlIdForField(field: string, mode: string): string | null {
  const required = /^requiredNutrients\[(\d+)\](?:\.(\w+))?/.exec(field);
  if (required) {
    const index = Number(required[1]);
    const part =
      required[2] === 'minAmountPerDay' ? 'min' : required[2] === 'unit' ? 'unit' : 'code';
    return CONTROL.required(index, part);
  }
  if (field.startsWith('requiredNutrients')) {
    return CONTROL.requiredAdd;
  }
  const category = /^weights\.categories\.(\w+)/.exec(field);
  if (category) {
    return CONTROL.category(category[1]);
  }
  const override = /^weights\.overrides\.(\w+)/.exec(field);
  if (override) {
    return CONTROL.override(override[1]);
  }
  if (field.startsWith('weights')) {
    return CONTROL.weightsReset;
  }
  if (field.startsWith('ranking.lambda')) {
    return mode === 'CUSTOM' ? CONTROL.lambda : CONTROL.mode(mode);
  }
  if (field.startsWith('ranking')) {
    return CONTROL.mode(mode);
  }
  if (field === 'country' || field === 'displayCurrency') {
    return CONTROL.country;
  }
  if (field.startsWith('stockFilter')) {
    return CONTROL.stock;
  }
  if (field.startsWith('excludeExceedingUl')) {
    return CONTROL.ul;
  }
  return null;
}

/** Server field errors as summary entries (one per control, contract order). */
export function serverEntries(errors: readonly FieldError[], mode: string): FieldErrorEntry[] {
  const seen = new Set<string>();
  const entries: FieldErrorEntry[] = [];
  for (const error of errors) {
    const controlId = controlIdForField(error.field, mode) ?? CONTROL.submit;
    if (!seen.has(controlId)) {
      seen.add(controlId);
      entries.push({ controlId, messageKey: fieldErrorKey(error.code) });
    }
  }
  return entries;
}

/** Narrows navigation state to the field errors the results page passed along. */
export function readFieldErrors(state: unknown, key: string): FieldError[] {
  if (typeof state !== 'object' || state === null) {
    return [];
  }
  const value = (state as Record<string, unknown>)[key];
  if (!Array.isArray(value)) {
    return [];
  }
  return value.filter(
    (entry): entry is FieldError =>
      typeof entry === 'object' &&
      entry !== null &&
      typeof (entry as FieldError).field === 'string' &&
      typeof (entry as FieldError).code === 'string',
  );
}
