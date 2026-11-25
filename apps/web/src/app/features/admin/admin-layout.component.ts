import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { ApiProblem } from '../../core/api/api-problem';
import { FxRefreshResult } from '../../core/api/api-types';
import { SessionStore } from '../../core/auth/session.store';
import { IconComponent } from '../../shared/icon/icon.component';
import { NoticeComponent } from '../../shared/notice/notice.component';
import { ProblemNoticeComponent } from '../../shared/notice/problem-notice.component';

/**
 * Frame of `/admin/**` (project specification §8.9; design hand-off §4.10): heading, the
 * "Refresh exchange rates" action (`POST /admin/fx/refresh`) and the sub-navigation. Access is
 * decided by `adminGuard` from `/me` and re-checked by the API on every call. The routed page
 * is created anew for every session generation and removed without the role.
 */
@Component({
  selector: 'vd-admin-layout',
  imports: [
    IconComponent,
    NoticeComponent,
    ProblemNoticeComponent,
    RouterLink,
    RouterLinkActive,
    RouterOutlet,
    TranslocoPipe,
  ],
  template: `
    <div class="page">
      <div class="page-head">
        <div class="stack-1">
          <p class="eyebrow">{{ 'admin.eyebrow' | transloco }}</p>
          <h1 class="h1">{{ 'admin.title' | transloco }}</h1>
        </div>
        <button type="button" class="btn" [attr.aria-busy]="refreshing()" (click)="refreshFx()">
          <vd-icon name="refresh" [spin]="refreshing()" />{{ 'admin.fx.refresh' | transloco }}
        </button>
      </div>
      <div class="live-region" aria-live="polite">
        @if (fxResult(); as result) {
          @if (result.failedBases.length === 0) {
            <vd-notice tone="ok" data-fx-result>
              <p>{{ 'admin.fx.done' | transloco: { count: result.upsertedRows } }}</p>
            </vd-notice>
          } @else {
            <vd-notice tone="warn" data-fx-result>
              <p>
                {{
                  'admin.fx.partial'
                    | transloco
                      : { count: result.upsertedRows, bases: result.failedBases.join(', ') }
                }}
              </p>
            </vd-notice>
          }
        }
      </div>
      @if (fxProblem(); as p) {
        <vd-problem-notice [problem]="p" (retry)="refreshFx()" />
      }
      <nav class="subnav" [attr.aria-label]="'admin.nav.label' | transloco">
        <a routerLink="/admin/imports" routerLinkActive ariaCurrentWhenActive="page">{{
          'admin.nav.imports' | transloco
        }}</a>
        <a routerLink="/admin/promo-codes" routerLinkActive ariaCurrentWhenActive="page">{{
          'admin.nav.promo_codes' | transloco
        }}</a>
        <a routerLink="/admin/users" routerLinkActive ariaCurrentWhenActive="page">{{
          'admin.nav.users' | transloco
        }}</a>
      </nav>
      @if (session.isAdmin()) {
        <!-- One outlet per session generation: a new account gets freshly loaded pages. -->
        @for (generation of [session.generation()]; track generation) {
          <router-outlet />
        }
      }
    </div>
  `,
  styles: `
    router-outlet {
      display: none;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdminLayoutComponent {
  private readonly api = inject(ApiClient);
  protected readonly session = inject(SessionStore);

  protected readonly refreshing = signal(false);
  protected readonly fxResult = signal<FxRefreshResult | null>(null);
  protected readonly fxProblem = signal<ApiProblem | null>(null);

  protected async refreshFx(): Promise<void> {
    if (this.refreshing()) {
      return;
    }
    this.refreshing.set(true);
    this.fxResult.set(null);
    this.fxProblem.set(null);
    try {
      this.fxResult.set(await firstValueFrom(this.api.refreshFxRates()));
    } catch (error: unknown) {
      this.fxProblem.set(ApiProblem.from(error));
    } finally {
      this.refreshing.set(false);
    }
  }
}
