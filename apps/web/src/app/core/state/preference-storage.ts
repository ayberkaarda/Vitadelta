import { Injectable } from '@angular/core';

const PREFIX = 'vd.';

/**
 * Per-browser convenience preferences (country, language, display currency of anonymous
 * visitors). Storage can be unavailable (private mode, blocked site data); every access is
 * guarded and the app works without it. Nothing security-relevant is ever stored here.
 */
@Injectable({ providedIn: 'root' })
export class PreferenceStorage {
  read(key: string): string | null {
    try {
      return globalThis.localStorage?.getItem(PREFIX + key) ?? null;
    } catch {
      return null;
    }
  }

  write(key: string, value: string | null): void {
    try {
      if (value === null) {
        globalThis.localStorage?.removeItem(PREFIX + key);
      } else {
        globalThis.localStorage?.setItem(PREFIX + key, value);
      }
    } catch {
      // Storage is optional: the preference simply lasts for this page view only.
      return;
    }
  }
}
