import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../api/api-client';
import { SessionStore } from '../auth/session.store';

/** Poll interval of the notifications bell while signed in (project specification §10.2). */
export const UNREAD_POLL_INTERVAL_MS = 60_000;

/**
 * Unread notification count shown by the header bell. The shell polls it; pages that change
 * read state (the inbox) call {@link refresh} or {@link set} with the server's answer.
 */
@Injectable({ providedIn: 'root' })
export class UnreadCountStore {
  private readonly api = inject(ApiClient);
  private readonly session = inject(SessionStore);
  private readonly countSignal = signal<number | null>(null);

  /** Unread count from the server; `null` when unknown or signed out. */
  readonly count = this.countSignal.asReadonly();

  async refresh(): Promise<void> {
    if (!this.session.isAuthenticated()) {
      this.countSignal.set(null);
      return;
    }
    try {
      const result = await firstValueFrom(this.api.getUnreadNotificationCount());
      this.countSignal.set(result.unreadCount);
    } catch {
      this.countSignal.set(null);
    }
  }

  set(count: number | null): void {
    this.countSignal.set(count);
  }
}
