import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { ApiProblem } from '../../core/api/api-problem';
import { AlertListItem, UpdateAlertRequest } from '../../core/api/api-types';
import { SessionStore } from '../../core/auth/session.store';
import { Formatter } from '../../core/format/formatter';
import { RegionStore } from '../../core/state/region.store';
import { FlagBadgeComponent } from '../../shared/flags/flag-badge.component';
import { IconComponent } from '../../shared/icon/icon.component';
import { LimitNoticeComponent } from '../../shared/notice/limit-notice.component';
import { ProblemNoticeComponent } from '../../shared/notice/problem-notice.component';
import { EmptyStateComponent } from '../../shared/state/empty-state.component';
import { SkeletonComponent } from '../../shared/state/skeleton.component';
import { parseThreshold, withoutKey } from './account-format';

type LoadState =
  | { readonly kind: 'loading' }
  | { readonly kind: 'ready'; readonly items: readonly AlertListItem[] }
  | { readonly kind: 'error'; readonly problem: ApiProblem };

/**
 * `/account/alerts` (project specification §8.6, §10.2; design hand-off §4.8): the caller's
 * subscriptions over every country. The active switch, the PRICE_DROP threshold and delete
 * are applied optimistically and rolled back when the API refuses; limit and verification
 * problems are rendered from the server's answer under the row.
 */
@Component({
  selector: 'vd-alerts-page',
  imports: [
    EmptyStateComponent,
    FlagBadgeComponent,
    IconComponent,
    LimitNoticeComponent,
    ProblemNoticeComponent,
    RouterLink,
    SkeletonComponent,
    TranslocoPipe,
  ],
  templateUrl: './alerts-page.component.html',
  styleUrl: './account-lists.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AlertsPageComponent {
  private readonly api = inject(ApiClient);
  protected readonly session = inject(SessionStore);
  protected readonly format = inject(Formatter);
  private readonly region = inject(RegionStore);

  protected readonly state = signal<LoadState>({ kind: 'loading' });
  protected readonly rowProblems = signal<Readonly<Record<string, ApiProblem>>>({});
  /** i18n key of a threshold input error per alert id. */
  protected readonly thresholdErrors = signal<Readonly<Record<string, string>>>({});
  protected readonly pending = signal<ReadonlySet<string>>(new Set());
  protected readonly announcement = signal<string | null>(null);

  protected readonly usage = computed(() => this.session.usage()?.alertSubscriptions ?? null);

  constructor() {
    void this.region.loadCountries();
    void this.load();
  }

  protected async load(): Promise<void> {
    this.state.set({ kind: 'loading' });
    try {
      const list = await firstValueFrom(this.api.listAlerts());
      this.state.set({ kind: 'ready', items: list.items });
    } catch (error: unknown) {
      this.state.set({ kind: 'error', problem: ApiProblem.from(error) });
    }
  }

  protected countryName(code: string): string {
    const nameEn = this.region.countries().find((country) => country.code === code)?.nameEn;
    return this.format.countryName(code, nameEn);
  }

  protected thresholdText(value: number | null): string {
    return value === null ? '' : this.format.plain(value, 2);
  }

  protected async toggleActive(item: AlertListItem): Promise<void> {
    const active = !item.active;
    const announce = active ? 'account.alerts.turned_on' : 'account.alerts.turned_off';
    await this.apply(item, { active }, { ...item, active }, announce);
  }

  protected async changeThreshold(item: AlertListItem, text: string): Promise<void> {
    const value = parseThreshold(text);
    if (value === null) {
      this.setThresholdError(item.id, 'account.alerts.threshold_invalid');
      return;
    }
    this.setThresholdError(item.id, null);
    if (value === item.thresholdPercent) {
      return;
    }
    await this.apply(
      item,
      { thresholdPercent: value },
      { ...item, thresholdPercent: value },
      'account.alerts.threshold_saved',
    );
  }

  protected async remove(item: AlertListItem): Promise<void> {
    const current = this.state();
    if (current.kind !== 'ready') {
      return;
    }
    const index = current.items.findIndex((entry) => entry.id === item.id);
    this.clearRowProblem(item.id);
    this.state.set({ kind: 'ready', items: current.items.filter((entry) => entry.id !== item.id) });
    try {
      await firstValueFrom(this.api.deleteAlert(item.id));
      this.announcement.set('account.alerts.deleted');
      await this.session.reloadMe();
    } catch (error: unknown) {
      const now = this.state();
      if (now.kind === 'ready') {
        const items = now.items.filter((entry) => entry.id !== item.id);
        items.splice(Math.max(0, Math.min(index, items.length)), 0, item);
        this.state.set({ kind: 'ready', items });
      }
      this.setRowProblem(item.id, ApiProblem.from(error));
    }
  }

  /** Optimistic PATCH: show `optimistic` at once, keep the server's answer, roll back on error. */
  private async apply(
    item: AlertListItem,
    body: UpdateAlertRequest,
    optimistic: AlertListItem,
    announceKey: string,
  ): Promise<void> {
    if (this.pending().has(item.id)) {
      return;
    }
    this.clearRowProblem(item.id);
    this.replace(optimistic);
    this.pending.update((ids) => new Set([...ids, item.id]));
    try {
      const updated = await firstValueFrom(this.api.updateAlert(item.id, body));
      this.replace({ ...optimistic, ...updated, product: item.product });
      this.announcement.set(announceKey);
      await this.session.reloadMe();
    } catch (error: unknown) {
      this.replace(item);
      const problem = ApiProblem.from(error);
      if (
        body.thresholdPercent !== undefined &&
        problem.code === 'validation_failed' &&
        problem.errors.length > 0
      ) {
        this.setThresholdError(item.id, 'account.alerts.threshold_invalid');
      } else {
        this.setRowProblem(item.id, problem);
      }
    } finally {
      this.pending.update((ids) => new Set([...ids].filter((id) => id !== item.id)));
    }
  }

  private replace(item: AlertListItem): void {
    const current = this.state();
    if (current.kind === 'ready') {
      this.state.set({
        kind: 'ready',
        items: current.items.map((entry) => (entry.id === item.id ? item : entry)),
      });
    }
  }

  private setThresholdError(id: string, key: string | null): void {
    this.thresholdErrors.update((errors) => {
      return key === null ? withoutKey(errors, id) : { ...errors, [id]: key };
    });
  }

  private setRowProblem(id: string, problem: ApiProblem): void {
    this.rowProblems.update((problems) => ({ ...problems, [id]: problem }));
  }

  private clearRowProblem(id: string): void {
    this.rowProblems.update((problems) => {
      return withoutKey(problems, id);
    });
  }
}
