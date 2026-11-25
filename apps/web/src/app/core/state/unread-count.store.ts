import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../api/api-client';
import { SessionStore } from '../auth/session.store';

/** Poll interval of the notifications bell while signed in (project specification §10.2). */
export const UNREAD_POLL_INTERVAL_MS = 60_000;

/**
 * Unread notification count shown by the header bell. The shell polls it; pages that change
 * read state (the inbox) call {@link refresh} or {@link set} with the server's answer.
 *
 * The count belongs to the session generation it was loaded for: after a sign-out or an account
 * switch it reads `null` at once, and an answer requested under an older generation is dropped.
 */
@Injectable({ providedIn: 'root' })
export class UnreadCountStore {
  private readonly api = inject(ApiClient);
  private readonly session = inject(SessionStore);
  private readonly state = signal<{ readonly generation: number; readonly count: number | null }>({
    generation: Number.NaN,
    count: null,
  });

  /** Unread count from the server; `null` when unknown or signed out. */
  readonly count = computed(() => {
    const state = this.state();
    return state.generation === this.session.generation() ? state.count : null;
  });

  async refresh(): Promise<void> {
    const generation = this.session.generation();
    if (!this.session.isAuthenticated()) {
      this.state.set({ generation, count: null });
      return;
    }
    try {
      const result = await firstValueFrom(this.api.getUnreadNotificationCount());
      this.store(generation, result.unreadCount);
    } catch {
      this.store(generation, null);
    }
  }

  set(count: number | null): void {
    this.state.set({ generation: this.session.generation(), count });
  }

  private store(generation: number, count: number | null): void {
    if (generation === this.session.generation()) {
      this.state.set({ generation, count });
    }
  }
}
