import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { ApiProblem } from '../../core/api/api-problem';
import { AdminUser, AdminUserPage } from '../../core/api/api-types';
import { SessionStore } from '../../core/auth/session.store';
import { Formatter } from '../../core/format/formatter';
import { IconComponent } from '../../shared/icon/icon.component';
import { NoticeComponent } from '../../shared/notice/notice.component';
import { ProblemNoticeComponent } from '../../shared/notice/problem-notice.component';
import { PaginationComponent } from '../../shared/pagination/pagination.component';
import { EmptyStateComponent } from '../../shared/state/empty-state.component';
import { SkeletonComponent } from '../../shared/state/skeleton.component';
import { withoutKey } from '../account/account-format';

export const USERS_PAGE_SIZE = 20;
const SEARCH_DELAY_MS = 300;
const DAYS_PATTERN = /^[1-9]\d{0,3}$/;

type LoadState =
  | { readonly kind: 'loading' }
  | { readonly kind: 'ready'; readonly page: AdminUserPage }
  | { readonly kind: 'error'; readonly problem: ApiProblem };

/** Inline action open on one row. */
type RowAction =
  | { readonly kind: 'grant'; readonly userId: string }
  | { readonly kind: 'disable'; readonly userId: string };

/**
 * `/admin/users` (project specification §8.9; design hand-off §4.10): search by e-mail, page,
 * change the status (disabling asks for an inline confirmation) and grant PRO manually for a
 * number of days. The admin's own row has no actions.
 */
@Component({
  selector: 'vd-admin-users-page',
  imports: [
    EmptyStateComponent,
    IconComponent,
    NoticeComponent,
    PaginationComponent,
    ProblemNoticeComponent,
    SkeletonComponent,
    TranslocoPipe,
  ],
  templateUrl: './admin-users-page.component.html',
  styleUrl: './admin-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdminUsersPageComponent {
  private readonly api = inject(ApiClient);
  private readonly session = inject(SessionStore);
  protected readonly format = inject(Formatter);
  private searchTimer: ReturnType<typeof setTimeout> | null = null;

  protected readonly state = signal<LoadState>({ kind: 'loading' });
  protected readonly query = signal('');
  protected readonly page = signal(1);
  protected readonly action = signal<RowAction | null>(null);
  protected readonly grantDays = signal('30');
  protected readonly grantError = signal<string | null>(null);
  protected readonly busy = signal<string | null>(null);
  protected readonly rowProblems = signal<Readonly<Record<string, ApiProblem>>>({});
  protected readonly message = signal<{ readonly key: string; readonly params: object } | null>(
    null,
  );

  constructor() {
    inject(DestroyRef).onDestroy(() => this.clearTimer());
    void this.load(1);
  }

  protected isSelf(user: AdminUser): boolean {
    return this.session.user()?.id === user.id;
  }

  protected onSearchInput(value: string): void {
    this.query.set(value);
    this.clearTimer();
    this.searchTimer = setTimeout(() => void this.load(1), SEARCH_DELAY_MS);
  }

  protected search(event?: Event): void {
    event?.preventDefault();
    this.clearTimer();
    void this.load(1);
  }

  protected async load(page: number): Promise<void> {
    this.page.set(page);
    this.action.set(null);
    this.state.set({ kind: 'loading' });
    const q = this.query().trim();
    try {
      const result = await firstValueFrom(
        this.api.listUsers({ page, pageSize: USERS_PAGE_SIZE, ...(q ? { q } : {}) }),
      );
      this.state.set({ kind: 'ready', page: result });
    } catch (error: unknown) {
      this.state.set({ kind: 'error', problem: ApiProblem.from(error) });
    }
  }

  protected openGrant(user: AdminUser): void {
    this.grantDays.set('30');
    this.grantError.set(null);
    this.action.set({ kind: 'grant', userId: user.id });
  }

  protected async grant(user: AdminUser, event?: Event): Promise<void> {
    event?.preventDefault();
    const days = this.grantDays().trim();
    if (!DAYS_PATTERN.test(days)) {
      this.grantError.set('admin.promo.whole_number');
      return;
    }
    this.grantError.set(null);
    await this.run(user, async () => {
      const subscription = await firstValueFrom(
        this.api.grantSubscription(user.id, { planCode: 'PRO', durationDays: Number(days) }),
      );
      this.replace({ ...user, plan: subscription.planCode });
      this.message.set({
        key: 'admin.users.granted',
        params: {
          email: user.email,
          date: subscription.currentPeriodEnd
            ? this.format.date(subscription.currentPeriodEnd)
            : '',
        },
      });
    });
  }

  protected async setStatus(user: AdminUser, status: AdminUser['status']): Promise<void> {
    await this.run(user, async () => {
      const updated = await firstValueFrom(this.api.setUserStatus(user.id, { status }));
      this.replace(updated);
      this.message.set({
        key: status === 'DISABLED' ? 'admin.users.disabled_done' : 'admin.users.enabled_done',
        params: { email: updated.email },
      });
    });
  }

  private async run(user: AdminUser, work: () => Promise<void>): Promise<void> {
    if (this.busy() !== null) {
      return;
    }
    this.busy.set(user.id);
    this.message.set(null);
    this.rowProblems.update((problems) => {
      return withoutKey(problems, user.id);
    });
    try {
      await work();
      this.action.set(null);
    } catch (error: unknown) {
      const problem = ApiProblem.from(error);
      this.rowProblems.update((problems) => ({ ...problems, [user.id]: problem }));
    } finally {
      this.busy.set(null);
    }
  }

  private replace(user: AdminUser): void {
    const current = this.state();
    if (current.kind === 'ready') {
      this.state.set({
        kind: 'ready',
        page: {
          ...current.page,
          items: current.page.items.map((entry) => (entry.id === user.id ? user : entry)),
        },
      });
    }
  }

  private clearTimer(): void {
    if (this.searchTimer !== null) {
      clearTimeout(this.searchTimer);
      this.searchTimer = null;
    }
  }
}
