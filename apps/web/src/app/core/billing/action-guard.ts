import { DestroyRef, computed, signal } from '@angular/core';

import { ApiProblem } from '../api/api-problem';

/**
 * Lifetime and rate-limit state of a button that opens a hosted provider page.
 *
 * - {@link signal} is aborted when the owning component is destroyed (the user left the page or
 *   the route changed), so a late session answer never moves the browser.
 * - After `429 rate_limited` with `Retry-After`, {@link coolingDown} stays `true` until the
 *   server's delay has passed, the same countdown the problem notice shows, so the primary button
 *   cannot bypass the limit.
 */
export class ProviderActionGuard {
  private readonly controller = new AbortController();
  private readonly secondsLeft = signal(0);
  private timer: ReturnType<typeof setInterval> | null = null;

  /** Aborted once the owning component is destroyed. */
  readonly signal: AbortSignal = this.controller.signal;
  readonly coolingDown = computed(() => this.secondsLeft() > 0);

  constructor(destroyRef: DestroyRef) {
    destroyRef.onDestroy(() => {
      this.controller.abort();
      this.stop();
    });
  }

  /** Starts the countdown of a rate-limit problem; other problems clear it. */
  apply(problem: ApiProblem): void {
    this.stop();
    const seconds = problem.code === 'rate_limited' ? (problem.retryAfterSeconds ?? 0) : 0;
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
