import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { Verdict } from '../../core/api/api-types';
import { IconComponent } from '../icon/icon.component';
import { VERDICT_STYLE } from './verdict-style';

/**
 * Verdict label pill: icon + text in the verdict tone (`--vd-verdict-*`). The verdict is the
 * server's; the client never computes one.
 *
 * ```html
 * <vd-verdict-badge [verdict]="response.verdict" />
 * <vd-verdict-badge [verdict]="v" size="sm" />
 * ```
 */
@Component({
  selector: 'vd-verdict-badge',
  imports: [IconComponent, TranslocoPipe],
  template: `
    <span
      class="vlabel"
      [class]="style().modifier"
      [class.sm]="size() === 'sm'"
      [attr.data-verdict]="verdict()"
    >
      <vd-icon [name]="style().icon" />
      <span>{{ style().labelKey | transloco }}</span>
    </span>
  `,
  styleUrl: './verdict-badge.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class VerdictBadgeComponent {
  readonly verdict = input.required<Verdict>();
  readonly size = input<'md' | 'sm'>('md');

  protected readonly style = computed(() => VERDICT_STYLE[this.verdict()]);
}
