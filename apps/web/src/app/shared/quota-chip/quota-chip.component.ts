import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';

import { IconComponent } from '../icon/icon.component';

export type QuotaChipState = 'available' | 'last' | 'exhausted';

/**
 * Anonymous trial quota chip (project specification §10.2, §10.3; design hand-off §4.1). Renders
 * the server counters (`queriesUsed`, `queriesLimit` from `/session/quota` or `meta.principal`):
 * pips plus "2/3 free queries left"; warning tone for the last query; once used up it turns into
 * the `quota.exhausted` sign-up link to `/auth/register`. `role="status"` announces changes.
 *
 * ```html
 * <vd-quota-chip [used]="quota.queriesUsed" [limit]="quota.queriesLimit" />
 * <vd-quota-chip [used]="q.queriesUsed" [limit]="q.queriesLimit" [compact]="true" />
 * ```
 */
@Component({
  selector: 'vd-quota-chip',
  imports: [IconComponent, RouterLink, TranslocoPipe],
  template: `
    <span role="status" class="wrap">
      @if (state() === 'exhausted') {
        <a class="quota is-out" routerLink="/auth/register" [attr.data-state]="state()">
          <vd-icon name="lock" size="sm" />
          <span>{{ (compact() ? 'quota.exhausted_short' : 'quota.exhausted') | transloco }}</span>
        </a>
      } @else {
        <span class="quota" [class.is-last]="state() === 'last'" [attr.data-state]="state()">
          <span class="pips" aria-hidden="true">
            @for (pip of pips(); track $index) {
              <span class="pip" [class.on]="pip"></span>
            }
          </span>
          <span>{{ labelKey() | transloco: { left: left(), limit: limit() } }}</span>
        </span>
      }
    </span>
  `,
  styleUrl: './quota-chip.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class QuotaChipComponent {
  readonly used = input.required<number>();
  readonly limit = input.required<number>();
  /** Short label for the mobile header ("2/3 left"). */
  readonly compact = input(false);

  protected readonly left = computed(() => Math.max(0, this.limit() - this.used()));

  readonly state = computed<QuotaChipState>(() => {
    const left = this.left();
    if (left <= 0) {
      return 'exhausted';
    }
    return left === 1 ? 'last' : 'available';
  });

  protected readonly pips = computed(() => {
    const limit = Math.max(0, Math.min(this.limit(), 10));
    return Array.from({ length: limit }, (_, index) => index < this.left());
  });

  protected readonly labelKey = computed(() => {
    if (this.compact()) {
      return 'quota.remaining_short';
    }
    return this.left() === 1 ? 'quota.remaining_one' : 'quota.remaining';
  });
}
