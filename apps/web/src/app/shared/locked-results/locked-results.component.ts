import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';

import { Visibility } from '../../core/api/api-types';
import { IconComponent } from '../icon/icon.component';

let nextId = 0;

interface LockedRow {
  readonly rank: number;
}

/**
 * Locked results of an anonymous ranking (project specification §8.4, §10.3; design hand-off
 * §4.3). Renders exclusively from `meta.visibility`: one row per locked position (rank number
 * and lock icon, no product data), then the sign-up call to action with
 * `results.locked_cta { locked }`. Nothing is rendered when `visibility.locked` is 0.
 *
 * Locked positions follow the visible ones: ranks `shown + 1 … shown + locked`. At most
 * `maxRows` rows are listed; the rest collapse into one "6–8 · 3 more locked" row. Below 48rem
 * only the first two rows are shown and the tail starts after them (design hand-off §7).
 *
 * ```html
 * <vd-locked-results [visibility]="response.meta.visibility" [returnUrl]="router.url" />
 * ```
 */
@Component({
  selector: 'vd-locked-results',
  imports: [IconComponent, RouterLink, TranslocoPipe],
  templateUrl: './locked-results.component.html',
  styleUrl: './locked-results.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LockedResultsComponent {
  readonly visibility = input.required<Visibility>();
  protected readonly titleId = `vd-locked-cta-${nextId++}`;
  /** Rows listed before the tail collapses. */
  readonly maxRows = input(5);
  /** App-relative URL the login link returns to. */
  readonly returnUrl = input<string | null>(null);

  /** Rows listed below 48rem before the tail collapses. */
  protected readonly compactRows = 2;

  protected readonly locked = computed(() => Math.max(0, this.visibility().locked));

  protected readonly rows = computed<readonly LockedRow[]>(() => {
    const { shown } = this.visibility();
    const count = Math.min(this.locked(), Math.max(0, this.maxRows()));
    return Array.from({ length: count }, (_, index) => ({ rank: shown + index + 1 }));
  });

  protected readonly tail = computed(() => {
    const { shown } = this.visibility();
    const listed = this.rows().length;
    const remaining = this.locked() - listed;
    if (remaining <= 0) {
      return null;
    }
    return { from: shown + listed + 1, to: shown + this.locked(), count: remaining };
  });

  /** The phone variant of {@link tail}; `null` when it would equal the desktop rendering. */
  protected readonly compactTail = computed(() => {
    const { shown } = this.visibility();
    const listed = Math.min(this.rows().length, this.compactRows);
    const remaining = this.locked() - listed;
    if (remaining <= 0 || listed === this.rows().length) {
      return null;
    }
    return { from: shown + listed + 1, to: shown + this.locked(), count: remaining };
  });

  protected readonly loginParams = computed(() => {
    const url = this.returnUrl();
    return url ? { returnUrl: url } : {};
  });
}
