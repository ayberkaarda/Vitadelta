import { EnvironmentProviders } from '@angular/core';
import { TranslocoOptions, provideTransloco, provideTranslocoTranspiler } from '@jsverse/transloco';

import { LiteralTranspiler } from './literal-transpiler';

/**
 * Transloco with the application's literal transpiler. Every Transloco set-up of the
 * application and of its tests goes through this function (a guard test enforces it), so no
 * call site can render parameter values with the default, re-interpreting transpiler.
 */
export function provideI18n(options: TranslocoOptions): EnvironmentProviders[] {
  // Registered after provideTransloco, so it replaces the default transpiler of that set.
  return [...provideTransloco(options), provideTranslocoTranspiler(LiteralTranspiler)];
}
