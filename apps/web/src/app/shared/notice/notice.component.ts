import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { Tone, toneClass } from '../flags/flag-style';
import { IconComponent } from '../icon/icon.component';
import { IconName } from '../icon/icon-paths';

const DEFAULT_ICON: Readonly<Record<Tone, IconName>> = {
  info: 'info',
  warn: 'warn',
  bad: 'warn',
  ok: 'ok',
  neutral: 'info',
};

/**
 * Inline notice (design hand-off §3 "Notices"): tinted box, icon in tone, bold heading, body and
 * an optional action row. `live` makes it a `status` (info/success after an action) or `alert`
 * (errors) region.
 *
 * ```html
 * <vd-notice tone="ok" live="status" [heading]="'account.promo.success_title' | transloco">
 *   <p>{{ 'account.promo.success_body' | transloco }}</p>
 *   <div vdNoticeActions><a class="btn btn-sm" routerLink="/upgrade">…</a></div>
 * </vd-notice>
 * ```
 */
@Component({
  selector: 'vd-notice',
  imports: [IconComponent],
  template: `
    <div class="notice" [class]="classes()" [attr.role]="live()" [attr.data-tone]="tone()">
      <vd-icon [name]="iconName()" />
      <div class="body">
        @if (heading()) {
          <p class="nt">{{ heading() }}</p>
        }
        <ng-content />
        <div class="acts">
          <ng-content select="[vdNoticeActions]" />
        </div>
      </div>
    </div>
  `,
  styles: `
    :host {
      display: block;
    }
    .acts:empty {
      display: none;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NoticeComponent {
  readonly tone = input<Tone>('info');
  /** Translated heading text. */
  readonly heading = input<string | null>(null);
  readonly icon = input<IconName | null>(null);
  readonly live = input<'status' | 'alert' | null>(null);

  protected readonly classes = computed(() => `notice ${toneClass(this.tone())}`);
  protected readonly iconName = computed(() => this.icon() ?? DEFAULT_ICON[this.tone()]);
}
