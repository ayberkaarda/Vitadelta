import {
  ChangeDetectionStrategy,
  Component,
  DOCUMENT,
  ElementRef,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
  viewChild,
  Injector,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Params, RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { ApiProblem } from '../../core/api/api-problem';
import { AlertTrigger, ProductDetailResponse, ProductFlag } from '../../core/api/api-types';
import { SessionStore } from '../../core/auth/session.store';
import { Formatter } from '../../core/format/formatter';
import { CurrencyStore } from '../../core/state/currency.store';
import { RegionStore } from '../../core/state/region.store';
import { DisclaimerComponent } from '../../shared/disclaimer/disclaimer.component';
import { FlagBadgesComponent } from '../../shared/flags/flag-badges.component';
import { IconComponent } from '../../shared/icon/icon.component';
import { FxNoticeComponent } from '../../shared/money/fx-notice.component';
import { NoticeComponent } from '../../shared/notice/notice.component';
import { ProblemNoticeComponent } from '../../shared/notice/problem-notice.component';
import { ProvenanceMarkerComponent } from '../../shared/provenance/provenance-marker.component';
import { EmptyStateComponent } from '../../shared/state/empty-state.component';
import { SkeletonComponent } from '../../shared/state/skeleton.component';
import { ProductActionButtonsComponent } from '../results/shared/product-action-buttons.component';
import { ProductActionNoticeComponent } from '../results/shared/product-action-notice.component';
import {
  ProductActionState,
  ProductActionsService,
} from '../results/shared/product-actions.service';
import { PRESELECT_PARAM } from '../results/shared/navigation-state';
import { ReferenceDataStore } from '../results/shared/reference-data.store';
import { AlertFormComponent } from './components/alert-form.component';
import { FormulationTableComponent, nutrientRowId } from './components/formulation-table.component';
import { OffersTableComponent } from './components/offers-table.component';

const COUNTRY_PATTERN = /^[A-Z]{2}$/;

interface ProductRequest {
  readonly id: string;
  readonly country: string;
  readonly displayCurrency: string | undefined;
}

type ViewState =
  | { readonly kind: 'loading' }
  | { readonly kind: 'ready'; readonly response: ProductDetailResponse }
  | { readonly kind: 'error'; readonly problem: ApiProblem };

/**
 * Product detail `/products/:id` (project specification §10.2, design hand-off §4.6):
 * formulation per serving and per day, availability, demo offers, flags, provenance, save and
 * alert actions and the not-medical-advice disclaimer. The country comes from the `country`
 * query parameter, else the visitor's selected country; the request waits for the session so
 * `saved` and `alerts` belong to the signed-in user.
 */
@Component({
  selector: 'vd-product-detail-page',
  imports: [
    AlertFormComponent,
    DisclaimerComponent,
    EmptyStateComponent,
    FlagBadgesComponent,
    FormulationTableComponent,
    FxNoticeComponent,
    IconComponent,
    NoticeComponent,
    OffersTableComponent,
    ProblemNoticeComponent,
    ProductActionButtonsComponent,
    ProductActionNoticeComponent,
    ProvenanceMarkerComponent,
    RouterLink,
    SkeletonComponent,
    TranslocoPipe,
  ],
  templateUrl: './product-detail-page.component.html',
  styleUrl: './product-detail-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductDetailPageComponent {
  private readonly api = inject(ApiClient);
  private readonly route = inject(ActivatedRoute);
  private readonly region = inject(RegionStore);
  private readonly currency = inject(CurrencyStore);
  private readonly reference = inject(ReferenceDataStore);
  private readonly document = inject(DOCUMENT);
  private readonly injector = inject(Injector);
  protected readonly session = inject(SessionStore);
  protected readonly actions = inject(ProductActionsService);
  protected readonly format = inject(Formatter);
  protected readonly quantity = inject(Formatter);

  /** Route parameter `:id`. */
  readonly id = input.required<string>();

  private readonly createAlertButton = viewChild<ElementRef<HTMLElement>>('createAlert');

  private readonly queryParams = toSignal(this.route.queryParams, {
    initialValue: this.route.snapshot.queryParams,
  });
  private readonly ready = signal(false);
  private lastKey: string | null = null;
  private sequence = 0;

  protected readonly view = signal<ViewState>({ kind: 'loading' });
  protected readonly actionState = signal<ProductActionState | null>(null);
  protected readonly alertFormOpen = signal(false);
  protected readonly alertCreated = signal(false);
  protected readonly highlighted = signal<readonly string[]>([]);
  protected readonly alertFormId = 'vd-product-alert-form';

  /** Country of the page: valid `country` query parameter, else the selected country. */
  protected readonly country = computed<string | null>(() => {
    const value: unknown = this.queryParams()['country'];
    if (typeof value === 'string' && COUNTRY_PATTERN.test(value)) {
      return value;
    }
    return this.region.countryCode();
  });

  private readonly request = computed<ProductRequest | null>(() => {
    const country = this.country();
    if (!this.ready() || !country) {
      return null;
    }
    return { id: this.id(), country, displayCurrency: this.currency.requestCurrency() };
  });

  protected readonly response = computed(() => {
    const view = this.view();
    return view.kind === 'ready' ? view.response : null;
  });

  protected readonly problem = computed(() => {
    const view = this.view();
    return view.kind === 'error' ? view.problem : null;
  });

  /** Display name of the response's country (the code when the list is not loaded). */
  protected readonly countryName = computed(() => {
    const code = this.response()?.meta.country ?? this.country() ?? '';
    const nameEn = this.region.countries().find((entry) => entry.code === code)?.nameEn;
    return this.format.countryName(code, nameEn);
  });

  /** The results page this product was opened from, when the URL carries its query. */
  protected readonly resultsParams = computed<Params | null>(() => {
    const params = this.queryParams();
    return Object.keys(params).some((key) => key !== 'country') ? params : null;
  });

  protected readonly compareParams = computed<Params>(() => ({
    ...this.queryParams(),
    country: this.response()?.meta.country ?? this.country(),
    [PRESELECT_PARAM]: this.id(),
  }));

  protected readonly converted = computed(() => {
    const meta = this.response()?.meta;
    return !!meta && meta.fx.status === 'OK' && meta.currency !== meta.nativeCurrency;
  });

  protected readonly todayPrice = computed(() => {
    const cost = this.response()?.product.cost;
    return cost ? this.format.money(cost.effectivePrice, cost.currency) : null;
  });

  constructor() {
    void this.reference.loadNutrients();
    void this.session.whenReady().then(() => this.ready.set(true));

    effect(() => {
      const request = this.request();
      // A new session generation loads the product again: saved and alert state are per account.
      const generation = this.session.generation();
      if (!request) {
        return;
      }
      const key = `${generation}:${JSON.stringify(request)}`;
      if (key === this.lastKey) {
        return;
      }
      this.lastKey = key;
      untracked(() => void this.load(request));
    });
  }

  protected retry(): void {
    const request = this.request();
    if (request) {
      void this.load(request);
    }
  }

  protected toggleAlertForm(): void {
    this.alertCreated.set(false);
    this.alertFormOpen.update((open) => !open);
  }

  protected onAlertDone(created: boolean): void {
    this.alertFormOpen.set(false);
    this.alertCreated.set(created);
    this.createAlertButton()?.nativeElement.focus();
  }

  /** `EXCEEDS_UL` link: highlights and focuses the affected formulation rows. */
  protected onFlag(flag: ProductFlag): void {
    if (flag.code !== 'EXCEEDS_UL' || flag.nutrientCodes.length === 0) {
      return;
    }
    this.highlighted.set(flag.nutrientCodes);
    afterNextRender(
      () => this.document.getElementById(nutrientRowId(flag.nutrientCodes[0]))?.focus(),
      { injector: this.injector },
    );
  }

  protected triggerKey(trigger: AlertTrigger): string {
    return `product.alert.trigger.${trigger}`;
  }

  private async load(request: ProductRequest): Promise<void> {
    const sequence = ++this.sequence;
    this.view.set({ kind: 'loading' });
    this.alertFormOpen.set(false);
    this.alertCreated.set(false);
    this.highlighted.set([]);
    try {
      const response = await firstValueFrom(
        this.api.getProduct(request.id, {
          country: request.country,
          ...(request.displayCurrency ? { displayCurrency: request.displayCurrency } : {}),
        }),
      );
      if (sequence !== this.sequence) {
        return;
      }
      this.currency.recordMeta(response.meta);
      const product = response.product;
      this.actionState.set(
        this.actions.create(product.id, response.meta.country, product.saved, product.alerts),
      );
      this.view.set({ kind: 'ready', response });
    } catch (error: unknown) {
      if (sequence === this.sequence) {
        this.view.set({ kind: 'error', problem: ApiProblem.from(error) });
      }
    }
  }
}
