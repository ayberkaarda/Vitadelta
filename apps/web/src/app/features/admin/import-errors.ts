import { ImportJobError } from '../../core/api/api-types';
import { ImportKind } from './import-jobs.store';

/**
 * Error codes of the import jobs that have their own translated text (`admin.imports.error.<code>`),
 * from the products document rules (`contracts/schemas/import-products.schema.json`) and the
 * offers CSV format (`contracts/schemas/offers-csv.columns.md`). Any other code is shown with the
 * generic text.
 */
export const IMPORT_ERROR_CODES = [
  'checked_at_in_future',
  'currency_mismatch',
  'duplicate_availability',
  'duplicate_nutrient',
  'duplicate_offer_key',
  'duplicate_product_code',
  'field_too_long',
  'form_nutrient_mismatch',
  'import_failed',
  'invalid_amount',
  'invalid_checked_at',
  'invalid_csv',
  'invalid_encoding',
  'invalid_form_code',
  'invalid_form_label',
  'invalid_header',
  'invalid_in_stock',
  'invalid_json',
  'invalid_offer_url',
  'invalid_price',
  'invalid_ul_amount',
  'invalid_unit',
  'iu_not_allowed',
  'missing_provenance',
  'missing_value',
  'no_conversion',
  'schema_violation',
  'too_many_errors',
  'too_many_records',
  'unknown_country',
  'unknown_nutrient',
  'unknown_product_code',
  'units_per_container_too_small',
  'unsupported_iu_form',
  'wrong_column_count',
] as const;

export type ImportErrorCode = (typeof IMPORT_ERROR_CODES)[number];

/** Offers CSV column that a row error code refers to. */
const CSV_COLUMNS: Readonly<Partial<Record<ImportErrorCode, string>>> = {
  unknown_product_code: 'product_code',
  unknown_country: 'country_code',
  invalid_price: 'price',
  currency_mismatch: 'currency',
  invalid_in_stock: 'in_stock',
  invalid_offer_url: 'url',
  invalid_checked_at: 'checked_at',
  checked_at_in_future: 'checked_at',
};

const CODE_PATTERN = /^[a-z][a-z0-9_]{0,63}$/;
const CODE_PREFIX = /^([a-z][a-z0-9_]{0,63}):/;
/** A JSON path such as `products[2].nutrients[0].unit` (nothing else is shown from a message). */
const JSON_PATH =
  /^[A-Za-z_][A-Za-z0-9_]{0,63}(?:\[\d{1,6}\]|\.[A-Za-z_][A-Za-z0-9_]{0,63}){0,12}$/;
const LOCATION = /\(([^()]{1,200})\)\s*$/;

/** What the error table shows for one job error: never the server's message text. */
export interface ImportErrorView {
  readonly rowNumber: number;
  /** Machine-readable code (validated), or `null` when none could be read. */
  readonly code: string | null;
  /** CSV column or JSON path the error refers to, or `null`. */
  readonly field: string | null;
  /** i18n key of the explanation. */
  readonly key: string;
}

function isKnown(code: string | null): code is ImportErrorCode {
  return code !== null && (IMPORT_ERROR_CODES as readonly string[]).includes(code);
}

/**
 * Turns a job error into table cells from allow-listed parts only: the code (the explicit
 * field, else the `code:` prefix of the message), the row number and the field (the CSV column
 * of the code, or the JSON path in the message's trailing location). The free text of the
 * message is never shown.
 */
export function describeImportError(error: ImportJobError, kind: ImportKind): ImportErrorView {
  const explicit = error.code !== undefined && CODE_PATTERN.test(error.code) ? error.code : null;
  const code = explicit ?? CODE_PREFIX.exec(error.message)?.[1] ?? null;
  const location = LOCATION.exec(error.message)?.[1]?.trim() ?? null;
  const field =
    kind === 'OFFERS_CSV'
      ? isKnown(code)
        ? (CSV_COLUMNS[code] ?? null)
        : null
      : location !== null && JSON_PATH.test(location)
        ? location
        : null;
  return {
    rowNumber: error.rowNumber,
    code,
    field,
    key: isKnown(code) ? `admin.imports.error.${code}` : 'admin.imports.error.unknown',
  };
}
