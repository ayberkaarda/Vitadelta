import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { ApiProblem } from '../../core/api/api-problem';
import {
  AlertSubscription,
  AlertTrigger,
  SavedProduct,
  SavedProductList,
} from '../../core/api/api-types';
import { SessionStore } from '../../core/auth/session.store';
import { Formatter } from '../../core/format/formatter';
import { CurrencyStore } from '../../core/state/currency.store';
import { RegionStore } from '../../core/state/region.store';
import { DisclaimerComponent } from '../../shared/disclaimer/disclaimer.component';
import { FlagBadgesComponent } from '../../shared/flags/flag-badges.component';
import { IconComponent } from '../../shared/icon/icon.component';
import { MoneyComponent } from '../../shared/money/money.component';
import { FxNoticeComponent } from '../../shared/money/fx-notice.component';
import { LimitNoticeComponent } from '../../shared/notice/limit-notice.component';
import { NoticeComponent } from '../../shared/notice/notice.component';
import { ProblemNoticeComponent } from '../../shared/notice/problem-notice.component';
import { ProvenanceMarkerComponent } from '../../shared/provenance/provenance-marker.component';
import { EmptyStateComponent } from '../../shared/state/empty-state.component';
import { SkeletonComponent } from '../../shared/state/skeleton.component';
import { activationChange } from '../results/shared/product-actions.service';
import { ALERT_TRIGGERS, parseThreshold, withoutKey } from './account-format';

type LoadState =
  | { readonly kind: 'loading' }
  | { readonly kind: 'ready'; readonly list: SavedProductList }
  | { readonly kind: 'error'; readonly problem: ApiProblem };

/**
 * `/account/saved` (project specification §8.5, §10.2; design hand-off §4.8): saved products of
 * the selected country with the current price, stock state, flags (including
 * `UNAVAILABLE_IN_COUNTRY`), alert toggles and removal. Removal and switching an alert off are
 * optimistic and roll back when the API refuses. Limits are rendered from `/me` and from the
 * server's `upgradeHint`, never computed here.
 */
@Component({
  selector: 'vd-saved-products-page',
  imports: [
    DisclaimerComponent,
    EmptyStateComponent,
    FlagBadgesComponent,
    FxNoticeComponent,
    IconComponent,
    LimitNoticeComponent,
    MoneyComponent,
    NoticeComponent,
    ProblemNoticeComponent,
    ProvenanceMarkerComponent,
    RouterLink,
    SkeletonComponent,
    TranslocoPipe,
  ],
  templateUrl: './saved-products-page.component.html',
  styleUrl: './account-lists.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SavedProductsPageComponent {
  private readonly api = inject(ApiClient);
  protected readonly session = inject(SessionStore);
  protected readonly region = inject(RegionStore);
  private readonly currency = inject(CurrencyStore);
  protected readonly format = inject(Formatter);

  private loadSequence = 0;

  protected readonly triggers = ALERT_TRIGGERS;
  protected readonly state = signal<LoadState>({ kind: 'loading' });
  /** Problem of the last action per product id (shown under the row). */
  protected readonly rowProblems = signal<Readonly<Record<string, ApiProblem>>>({});
  protected readonly removedName = signal<string | null>(null);

  /** Product whose "Add alert" form is open, with the form values. */
  protected readonly adding = signal<string | null>(null);
  protected readonly newTrigger = signal<AlertTrigger>('PRICE_DROP');
  protected readonly newThreshold = signal('5');
  protected readonly thresholdError = signal<string | null>(null);
  protected readonly creating = signal(false);

  protected readonly usage = computed(() => this.session.usage()?.savedProducts ?? null);
  protected readonly limitReached = computed(() => {
    const usage = this.usage();
    return usage !== null && usage.limit !== null && usage.used >= usage.limit;
  });

  constructor() {
    void this.region.loadCountries();
    effect(() => {
      const country = this.region.countryCode();
      const displayCurrency = this.currency.requestCurrency();
      if (country) {
        untracked(() => void this.load(country, displayCurrency));
      }
    });
  }

  protected reload(): void {
    const country = this.region.countryCode();
    if (country) {
      void this.load(country, this.currency.requestCurrency());
    }
  }

  /** Loads the list; an answer of a superseded load (another country or currency) is ignored. */
  private async load(country: string, displayCurrency: string | undefined): Promise<void> {
    const sequence = ++this.loadSequence;
    this.state.set({ kind: 'loading' });
    this.adding.set(null);
    this.rowProblems.set({});
    this.removedName.set(null);
    try {
      const list = await firstValueFrom(
        this.api.listSavedProducts({ country, ...(displayCurrency ? { displayCurrency } : {}) }),
      );
      if (sequence !== this.loadSequence) {
        return;
      }
      this.currency.recordMeta(list.meta);
      this.state.set({ kind: 'ready', list });
    } catch (error: unknown) {
      if (sequence === this.loadSequence) {
        this.state.set({ kind: 'error', problem: ApiProblem.from(error) });
      }
    }
  }

  protected thresholdText(value: number | null): string {
    return value === null ? '' : this.format.plain(value, 2);
  }

  protected async remove(item: SavedProduct): Promise<void> {
    const current = this.state();
    if (current.kind !== 'ready') {
      return;
    }
    const index = current.list.items.findIndex((entry) => entry.productId === item.productId);
    this.clearRowProblem(item.productId);
    this.removedName.set(null);
    this.setItems(current.list.items.filter((entry) => entry.productId !== item.productId));
    try {
      await firstValueFrom(this.api.removeSavedProduct(item.productId, item.countryCode));
      this.removedName.set(item.name);
      await this.session.reloadMe();
    } catch (error: unknown) {
      this.restoreItem(item, index);
      this.setRowProblem(item.productId, ApiProblem.from(error));
    }
  }

  protected async alertOff(item: SavedProduct, alert: AlertSubscription): Promise<void> {
    this.clearRowProblem(item.productId);
    this.patchItem(item, (entry) => ({
      ...entry,
      alerts: entry.alerts.filter((existing) => existing.id !== alert.id),
    }));
    try {
      await firstValueFrom(this.api.updateAlert(alert.id, { active: false }));
      await this.session.reloadMe();
    } catch (error: unknown) {
      this.patchItem(item, (entry) => ({
        ...entry,
        alerts: sortAlerts([...entry.alerts, alert]),
      }));
      this.setRowProblem(item.productId, ApiProblem.from(error));
    }
  }

  protected toggleAdd(item: SavedProduct): void {
    if (this.adding() === item.productId) {
      this.adding.set(null);
      return;
    }
    this.newTrigger.set(
      this.triggers.find((trigger) => !item.alerts.some((a) => a.trigger === trigger)) ??
        'PRICE_DROP',
    );
    this.newThreshold.set('5');
    this.thresholdError.set(null);
    this.clearRowProblem(item.productId);
    this.adding.set(item.productId);
  }

  protected onTrigger(value: string): void {
    if ((ALERT_TRIGGERS as readonly string[]).includes(value)) {
      this.newTrigger.set(value as AlertTrigger);
    }
  }

  protected async createAlert(item: SavedProduct, event?: Event): Promise<void> {
    event?.preventDefault();
    if (this.creating()) {
      return;
    }
    const trigger = this.newTrigger();
    let thresholdPercent: number | null = null;
    if (trigger === 'PRICE_DROP') {
      thresholdPercent = parseThreshold(this.newThreshold());
      if (thresholdPercent === null) {
        this.thresholdError.set('account.alerts.threshold_invalid');
        return;
      }
    }
    this.thresholdError.set(null);
    this.clearRowProblem(item.productId);
    this.creating.set(true);
    try {
      const answered = await firstValueFrom(
        this.api.createAlert({
          productId: item.productId,
          countryCode: item.countryCode,
          trigger,
          ...(thresholdPercent !== null ? { thresholdPercent } : {}),
        }),
      );
      // POST answers an existing subscription unchanged, active or not: it is activated (with
      // the requested threshold) before the row lists it.
      const change = activationChange(answered, thresholdPercent);
      const created = change
        ? await firstValueFrom(this.api.updateAlert(answered.id, change))
        : answered;
      this.patchItem(item, (entry) => ({
        ...entry,
        alerts: sortAlerts([
          ...entry.alerts.filter((existing) => existing.id !== created.id),
          created,
        ]),
      }));
      this.adding.set(null);
      await this.session.reloadMe();
    } catch (error: unknown) {
      const problem = ApiProblem.from(error);
      if (problem.code === 'validation_failed' && problem.errors.length > 0) {
        this.thresholdError.set('account.alerts.threshold_invalid');
      } else {
        this.setRowProblem(item.productId, problem);
      }
    } finally {
      this.creating.set(false);
    }
  }

  private setItems(items: SavedProduct[]): void {
    const current = this.state();
    if (current.kind === 'ready') {
      this.state.set({ kind: 'ready', list: { ...current.list, items } });
    }
  }

  /** Puts a row back after a refused removal, unless the list now shows another country. */
  private restoreItem(item: SavedProduct, index: number): void {
    const current = this.state();
    if (current.kind !== 'ready' || current.list.meta.country !== item.countryCode) {
      return;
    }
    const items = current.list.items.filter((entry) => entry.productId !== item.productId);
    items.splice(Math.max(0, Math.min(index, items.length)), 0, item);
    this.setItems(items);
  }

  /** Changes the row of `item` when the list still shows its country. */
  private patchItem(item: SavedProduct, change: (item: SavedProduct) => SavedProduct): void {
    const current = this.state();
    if (current.kind === 'ready' && current.list.meta.country === item.countryCode) {
      this.setItems(
        current.list.items.map((entry) =>
          entry.productId === item.productId ? change(entry) : entry,
        ),
      );
    }
  }

  private setRowProblem(productId: string, problem: ApiProblem): void {
    this.rowProblems.update((problems) => ({ ...problems, [productId]: problem }));
  }

  private clearRowProblem(productId: string): void {
    this.rowProblems.update((problems) => {
      return withoutKey(problems, productId);
    });
  }
}

function sortAlerts(alerts: AlertSubscription[]): AlertSubscription[] {
  return [...alerts].sort(
    (a, b) => ALERT_TRIGGERS.indexOf(a.trigger) - ALERT_TRIGGERS.indexOf(b.trigger),
  );
}
