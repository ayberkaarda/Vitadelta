import { captureEmailToken } from './core/auth/email-token';
import { FragmentTokenEnvironment } from './core/auth/fragment-token';

/**
 * Starts the application. Steps that must happen before any request of the application run
 * first: an e-mail token in the URL fragment is taken out of the address bar (see
 * `captureEmailToken`), then `bootstrap` starts Angular.
 */
export function startApplication<T>(
  bootstrap: () => Promise<T>,
  env?: FragmentTokenEnvironment,
): Promise<T> {
  captureEmailToken(env);
  return bootstrap();
}
