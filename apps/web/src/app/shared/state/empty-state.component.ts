import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { Tone, toneClass } from '../flags/flag-style';
import { IconComponent } from '../icon/icon.component';
import { IconName } from '../icon/icon-paths';

/**
 * Empty, error or gate state (design hand-off §3 "Empty/error state"): round icon well tinted
 * by tone, heading, muted text and projected actions (`vdStateActions`).
 *
 * ```html
 * <vd-empty-state icon="search" [heading]="'results.empty_title' | transloco" headingLevel="2">
 *   <p>{{ 'results.empty_text' | transloco }}</p>
 *   <div vdStateActions><button class="btn btn-primary" type="button">…</button></div>
 * </vd-empty-state>
 * ```
 */
@Component({
  selector: 'vd-empty-state',
  imports: [IconComponent],
  template: `
    <div class="state" [class]="classes()">
      <span class="ic" aria-hidden="true"><vd-icon [name]="icon()" /></span>
      @if (headingLevel() === '2') {
        <h2 class="h3">{{ heading() }}</h2>
      } @else {
        <h3 class="h4">{{ heading() }}</h3>
      }
      <ng-content />
      <div class="acts">
        <ng-content select="[vdStateActions]" />
      </div>
    </div>
  `,
  styles: `
    .acts:empty {
      display: none;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EmptyStateComponent {
  readonly icon = input<IconName>('info');
  readonly tone = input<Tone>('neutral');
  /** Translated heading text. */
  readonly heading = input.required<string>();
  readonly headingLevel = input<'2' | '3'>('2');

  protected readonly classes = computed(() => `state ${toneClass(this.tone())}`);
}
