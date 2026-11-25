import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';

import { UnreadCountStore } from '../core/state/unread-count.store';
import { IconComponent } from '../shared/icon/icon.component';

/** Header bell linking to the inbox, with the server's unread count (capped at "99+"). */
@Component({
  selector: 'vd-notification-bell',
  imports: [IconComponent, RouterLink, TranslocoPipe],
  template: `
    <a
      class="bell"
      routerLink="/account/notifications"
      [attr.aria-label]="
        count() > 0
          ? ('shell.notifications.label_unread' | transloco: { count: count() })
          : ('shell.notifications.label' | transloco)
      "
    >
      <vd-icon name="bell" />
      @if (count() > 0) {
        <span class="count" aria-hidden="true">{{
          count() > 99 ? ('shell.notifications.overflow' | transloco) : count()
        }}</span>
      }
    </a>
  `,
  styles: `
    .bell {
      position: relative;
      display: inline-grid;
      place-items: center;
      width: var(--vd-control-height);
      height: var(--vd-control-height);
      border: 1px solid var(--vd-color-border);
      border-radius: var(--vd-radius-md);
      background: var(--vd-color-surface);
      color: var(--vd-color-text);
    }
    .bell:hover {
      background: var(--vd-color-surface-subtle);
      color: var(--vd-color-text);
    }
    .count {
      position: absolute;
      top: -6px;
      right: -6px;
      min-width: 1.25rem;
      height: 1.25rem;
      padding: 0 4px;
      border-radius: var(--vd-radius-pill);
      background: var(--vd-color-accent);
      color: var(--vd-color-on-accent);
      font-size: 11px;
      font-weight: var(--vd-font-weight-bold);
      line-height: 1rem;
      text-align: center;
      border: 2px solid var(--vd-color-surface);
      font-variant-numeric: tabular-nums;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NotificationBellComponent {
  private readonly unread = inject(UnreadCountStore);
  protected readonly count = computed(() => this.unread.count() ?? 0);
}
