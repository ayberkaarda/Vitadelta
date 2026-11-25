import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';

import { ApiProblem } from '../../core/api/api-problem';
import { safeReturnUrl } from '../../core/auth/return-url';
import { NoticeComponent } from './notice.component';

/**
 * Limit reached (`entitlement_exceeded`, `quota_exceeded`): warning notice whose text is the
 * server's `upgradeHint.messageKey` (with `used`, `limit`, `plan`) and whose primary link goes
 * to `upgradeHint.ctaPath`. The client never decides a limit; it renders the server answer.
 *
 * ```html
 * <vd-limit-notice [problem]="problem">
 *   <a vdNoticeActions class="btn btn-sm" routerLink="/account/saved">…</a>
 * </vd-limit-notice>
 * ```
 */
@Component({
  selector: 'vd-limit-notice',
  imports: [NoticeComponent, RouterLink, TranslocoPipe],
  template: `
    <vd-notice tone="warn" live="alert" [heading]="'limits.title' | transloco">
      <p>{{ messageKey() | transloco: params() }}</p>
      <div vdNoticeActions>
        <a class="btn btn-primary btn-sm" [routerLink]="ctaPath()">{{ ctaKey() | transloco }}</a>
        <ng-content select="[vdNoticeActions]" />
      </div>
    </vd-notice>
  `,
  styles: `
    [vdNoticeActions] {
      display: contents;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LimitNoticeComponent {
  readonly problem = input.required<ApiProblem>();

  protected readonly messageKey = computed(
    () => this.problem().upgradeHint?.messageKey ?? this.problem().messageKey + '.body',
  );
  protected readonly ctaPath = computed(() =>
    safeReturnUrl(this.problem().upgradeHint?.ctaPath ?? '/upgrade'),
  );
  protected readonly ctaKey = computed(() =>
    this.ctaPath().startsWith('/auth/register') ? 'results.locked_register' : 'limits.see_pro',
  );
  protected readonly params = computed(() => ({
    used: this.problem().used ?? '',
    limit: this.problem().limit ?? '',
    plan: this.problem().plan ?? '',
  }));
}
