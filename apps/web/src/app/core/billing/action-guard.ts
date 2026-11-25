import { DestroyRef, InjectionToken, computed, signal } from '@angular/core';

import { ApiProblem } from '../api/api-problem';

/**
 * Seconds a billing button waits after `409 conflict_retry` (another checkout of the same user
 * was being created at that moment) before it can be pressed again. A short, bounded pause; the
 * request is never repeated on its own.
 */
export const BILLING_CONFLICT_PAUSE_SECONDS = new InjectionToken<number>(
  'BILLING_CONFLICT_PAUSE_SECONDS',
  { providedIn: 'root', factory: () => 3 },
);

/**
 * Lifetime and rate-limit state of a button that opens a hosted provider page.
 *
 * - {@link signal} is aborted when the owning component is destroyed (the user left the page or
 *   the route changed), so a late session answer never moves the browser.
 * - After `429 rate_limited` with `Retry-After`, {@link coolingDown} stays `true` until the
 *   server's delay has passed, the same countdown the problem notice shows, so the primary button
 *   cannot bypass the limit. After `409 conflict_retry` it holds the button for the short
 *   conflict pause the same way.
 */
export class ProviderActionGuard {
  private readonly controller = new AbortController();
  private readonly secondsLeft = signal(0);
  private timer: ReturnType<typeof setInterval> | null = null;

  /** Aborted once the owning component is destroyed. */
  readonly signal: AbortSignal = this.controller.signal;
  readonly coolingDown = computed(() => this.secondsLeft() > 0);

  constructor(
    destroyRef: DestroyRef,
    private readonly conflictPauseSeconds = 3,
  ) {
    destroyRef.onDestroy(() => {
      this.controller.abort();
      this.stop();
    });
  }

  /** Starts the countdown of a rate-limit or conflict problem; other problems clear it. */
  apply(problem: ApiProblem): void {
    this.stop();
    const seconds =
      problem.code === 'rate_limited'
        ? (problem.retryAfterSeconds ?? 0)
        : problem.code === 'conflict_retry'
          ? this.conflictPauseSeconds
          : 0;
    this.secondsLeft.set(seconds);
    if (seconds > 0) {
      this.timer = setInterval(() => {
        const next = Math.max(0, this.secondsLeft() - 1);
        this.secondsLeft.set(next);
        if (next === 0) {
          this.stop();
        }
      }, 1000);
    }
  }

  private stop(): void {
    if (this.timer !== null) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }
}
