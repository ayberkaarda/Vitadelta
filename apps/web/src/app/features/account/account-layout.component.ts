import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';

import { SessionStore } from '../../core/auth/session.store';
import { Formatter } from '../../core/format/formatter';
import { UnreadCountStore } from '../../core/state/unread-count.store';

/**
 * Frame of `/account/**` (design hand-off §4.8): heading, e-mail and member-since line, and the
 * sub-navigation with the unread badge from the shell's unread count.
 */
@Component({
  selector: 'vd-account-layout',
  imports: [RouterLink, RouterLinkActive, RouterOutlet, TranslocoPipe],
  template: `
    <div class="page">
      <header class="stack-2">
        <h1 class="h1">{{ 'account.title' | transloco }}</h1>
        @if (session.user(); as user) {
          <p class="text-sm muted">
            {{ user.email }} ·
            {{ 'account.member_since' | transloco: { date: format.date(user.createdAt) } }}
          </p>
        }
      </header>
      <nav class="subnav" [attr.aria-label]="'account.nav.label' | transloco">
        <a
          routerLink="/account"
          routerLinkActive
          [routerLinkActiveOptions]="{ exact: true }"
          ariaCurrentWhenActive="page"
          >{{ 'account.nav.profile' | transloco }}</a
        >
        <a routerLink="/account/saved" routerLinkActive ariaCurrentWhenActive="page">{{
          'account.nav.saved' | transloco
        }}</a>
        <a routerLink="/account/alerts" routerLinkActive ariaCurrentWhenActive="page">{{
          'account.nav.alerts' | transloco
        }}</a>
        <a routerLink="/account/notifications" routerLinkActive ariaCurrentWhenActive="page">
          {{ 'account.nav.notifications' | transloco }}
          @if (unread.count(); as count) {
            <span class="badge t-info count">
              <span aria-hidden="true">{{ count > 99 ? '99+' : count }}</span>
              <span class="sr-only">{{ 'account.nav.unread' | transloco: { count } }}</span>
            </span>
          }
        </a>
      </nav>
      <router-outlet />
    </div>
  `,
  styles: `
    router-outlet {
      display: none;
    }
    .count {
      /* Keeps the visually hidden text inside the scrolling sub-navigation (no page overflow). */
      position: relative;
      margin-left: var(--vd-space-1);
      min-height: 1.25rem;
      padding: 0 6px;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AccountLayoutComponent {
  protected readonly session = inject(SessionStore);
  protected readonly format = inject(Formatter);
  protected readonly unread = inject(UnreadCountStore);
}
