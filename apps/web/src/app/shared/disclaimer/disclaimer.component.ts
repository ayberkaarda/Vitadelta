import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { IconComponent } from '../icon/icon.component';

/**
 * `disclaimer.not_medical_advice` (project specification §0.1 rule 9). Required on results,
 * compare, product detail and saved-products pages.
 *
 * ```html
 * <vd-disclaimer />
 * <vd-disclaimer variant="inline" />
 * ```
 */
@Component({
  selector: 'vd-disclaimer',
  imports: [IconComponent, TranslocoPipe],
  template: `
    @if (variant() === 'box') {
      <p class="disclaimer" data-key="disclaimer.not_medical_advice">
        <vd-icon name="info" />
        <span>{{ 'disclaimer.not_medical_advice' | transloco }}</span>
      </p>
    } @else {
      <p class="text-xs muted" data-key="disclaimer.not_medical_advice">
        {{ 'disclaimer.not_medical_advice' | transloco }}
      </p>
    }
  `,
  styles: ':host { display: block; }',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DisclaimerComponent {
  readonly variant = input<'box' | 'inline'>('box');
}
