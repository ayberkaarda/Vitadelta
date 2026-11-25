import { Injectable } from '@angular/core';
import { TranslocoTranspiler, TranspileParams } from '@jsverse/transloco';

/** Interpolation parameters of one translation call. */
type Params = TranspileParams['params'];

/**
 * A placeholder of a translation template: `{{ name }}` with a plain parameter name. Anything
 * else between braces is ordinary text of the template.
 */
const PLACEHOLDER = /\{\{\s*([A-Za-z0-9_]+)\s*\}\}/g;

/**
 * Replaces the placeholders of a translation template with the parameter values, in one pass.
 *
 * The template comes from the translation files; the values are data (product, brand and
 * retailer names, notification payload text, any API string). A value is inserted as written
 * and never scanned again, so interpolation markers inside it stay verbatim and a value that
 * contains its own placeholder cannot loop. A placeholder name is looked up as an own property
 * of `params` only: never through dotted paths, the prototype chain or the translation
 * dictionary. A placeholder without a value renders as empty text.
 */
export function substitute(template: string, params: Params): string {
  return template.replace(PLACEHOLDER, (_marker, name: string) => {
    if (!params || !Object.hasOwn(params, name)) {
      return '';
    }
    const value: unknown = params[name];
    return value === undefined || value === null ? '' : String(value);
  });
}

/**
 * The application's translation transpiler (provided by `provideI18n`). Unlike Transloco's
 * default transpiler it never re-interprets parameter values and never resolves placeholders
 * against the dictionary or object properties; see {@link substitute}.
 *
 * Only string translations are transpiled. The application translates leaf keys only, so
 * object and array values are returned unchanged.
 */
@Injectable()
export class LiteralTranspiler implements TranslocoTranspiler {
  transpile({ value, params }: TranspileParams): unknown {
    return typeof value === 'string' ? substitute(value, params) : value;
  }
}
