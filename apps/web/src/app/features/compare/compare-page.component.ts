import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { ApiProblem } from '../../core/api/api-problem';
import { CompareRequest, CompareResponse, ComparedProduct } from '../../core/api/api-types';
import { SessionStore } from '../../core/auth/session.store';
import { Formatter } from '../../core/format/formatter';
import { CurrencyStore } from '../../core/state/currency.store';
import { QueryStore } from '../../core/state/query.store';
import { RegionStore } from '../../core/state/region.store';
import { DisclaimerComponent } from '../../shared/disclaimer/disclaimer.component';
import { IconComponent } from '../../shared/icon/icon.component';
import { IconName } from '../../shared/icon/icon-paths';
import { Tone } from '../../shared/flags/flag-style';
import { FxNoticeComponent } from '../../shared/money/fx-notice.component';
import { MoneyComponent } from '../../shared/money/money.component';
import { NoticeComponent } from '../../shared/notice/notice.component';
import { ProvenanceMarkerComponent } from '../../shared/provenance/provenance-marker.component';
import { EmptyStateComponent } from '../../shared/state/empty-state.component';
import { SkeletonComponent } from '../../shared/state/skeleton.component';
import { ReferenceDataStore, setLabel } from '../results/shared/reference-data.store';
import { DeltaTableComponent } from './components/delta-table.component';
import { VerdictCardComponent } from './components/verdict-card.component';

type ViewState =
  | { readonly kind: 'loading' }
  | { readonly kind: 'ready'; readonly response: CompareResponse }
  | { readonly kind: 'error'; readonly problem: ApiProblem };

/** How an error is rendered: texts are i18n keys with parameters. */
interface ErrorView {
  readonly tone: Tone;
  readonly icon: IconName;
  readonly titleKey: string;
  readonly bodyKey: string;
  readonly bodyParams: Readonly<Record<string, string>>;
  readonly productKey: string | null;
  readonly retry: boolean;
  readonly requestId: string | null;
}

const PAIR_CODES = new Set(['same_product', 'no_offer', 'no_stock']);
const RETRY_STATUSES = new Set([0, 500, 502, 503, 504]);

/**
 * `/compare/:baselineId/:candidateId` (project specification §10.2, design hand-off §4.5).
 * The market, stock filter and weights come from the URL query (QueryStore), so a comparison is
 * a shareable link. The server orders the pair (A is cheaper per day) and decides the verdict;
 * this page only renders the answer.
 */
@Component({
  selector: 'vd-compare-page',
  imports: [
    DeltaTableComponent,
    DisclaimerComponent,
    EmptyStateComponent,
    FxNoticeComponent,
    IconComponent,
    MoneyComponent,
    NoticeComponent,
    ProvenanceMarkerComponent,
    RouterLink,
    SkeletonComponent,
    TranslocoPipe,
    VerdictCardComponent,
  ],
  templateUrl: './compare-page.component.html',
  styleUrl: './compare-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ComparePageComponent {
  private readonly api = inject(ApiClient);
  private readonly session = inject(SessionStore);
  private readonly currency = inject(CurrencyStore);
  private readonly region = inject(RegionStore);
  private readonly reference = inject(ReferenceDataStore);
  protected readonly query = inject(QueryStore);
  protected readonly format = inject(Formatter);

  /** Route parameter: the product the visitor opened first. */
  readonly baselineId = input.required<string>();
  /** Route parameter: the product to compare with. */
  readonly candidateId = input.required<string>();

  private readonly queryParamMap = toSignal(inject(ActivatedRoute).queryParamMap);
  private readonly ready = signal(false);
  private lastKey: string | null = null;
  private lastRequest: CompareRequest | null = null;
  private sequence = 0;
  private timer: ReturnType<typeof setInterval> | null = null;

  protected readonly view = signal<ViewState>({ kind: 'loading' });
  protected readonly secondsLeft = signal(0);
  protected readonly engine = this.reference.engine;

  protected readonly response = computed(() => {
    const view = this.view();
    return view.kind === 'ready' ? view.response : null;
  });

  protected readonly backParams = computed(() => this.query.queryParams());

  protected readonly countryName = computed(() => {
    const code = this.response()?.meta.country ?? this.query.country();
    return this.region.countries().find((country) => country.code === code)?.nameEn ?? code ?? '';
  });

  protected readonly referenceSet = computed(() => {
    const code = this.response()?.meta.country;
    const country = this.region.countries().find((entry) => entry.code === code);
    return country ? setLabel(country.referenceSetCode) : '';
  });

  /** h1: "Is B worth +x more per day?" or "A or B?" for equal-cost verdicts. */
  protected readonly title = computed(() => {
    const r = this.response();
    if (!r) {
      return null;
    }
    if (r.verdict === 'DOMINATES' || r.verdict === 'EQUIVALENT') {
      return { key: 'compare.title_or', params: { a: r.productA.name, b: r.productB.name } };
    }
    return {
      key: 'compare.title_question',
      params: { b: r.productB.name, cost: this.format.money(r.deltaCost, r.meta.currency) },
    };
  });

  protected readonly errorView = computed<ErrorView | null>(() => {
    const view = this.view();
    return view.kind === 'error' ? this.describe(view.problem) : null;
  });

  constructor() {
    void this.session.whenReady().then(() => this.ready.set(true));
    void this.reference.loadDefaults();
    inject(DestroyRef).onDestroy(() => this.stopTimer());

    effect(() => {
      const map = this.queryParamMap();
      const ready = this.ready();
      const currency = this.currency.requestCurrency();
      const baseline = this.baselineId();
      const candidate = this.candidateId();
      this.query.country();
      untracked(() => {
        if (map) {
          this.query.restore(map);
        }
        if (!ready) {
          return;
        }
        const request = this.query.toCompareRequest(baseline, candidate, currency);
        if (!request) {
          return;
        }
        const key = JSON.stringify(request);
        if (key !== this.lastKey) {
          this.lastKey = key;
          void this.load(request);
        }
      });
    });
  }

  protected retry(): void {
    if (this.lastRequest) {
      void this.load(this.lastRequest);
    }
  }

  protected productParams(): Record<string, string> {
    return this.query.queryParams();
  }

  protected packParams(product: ComparedProduct): Record<string, string> {
    const days = product.cost.daysSupply;
    return {
      price: this.format.money(product.cost.effectivePrice, product.cost.currency),
      days: this.format.amount(days, Number.isInteger(days) ? 0 : 2),
    };
  }

  private async load(request: CompareRequest): Promise<void> {
    const sequence = ++this.sequence;
    this.lastRequest = request;
    this.stopTimer();
    this.view.set({ kind: 'loading' });
    try {
      const response = await firstValueFrom(this.api.compareProducts(request));
      if (sequence !== this.sequence) {
        return;
      }
      this.currency.recordMeta(response.meta);
      this.view.set({ kind: 'ready', response });
    } catch (error: unknown) {
      if (sequence !== this.sequence) {
        return;
      }
      const problem = ApiProblem.from(error);
      this.view.set({ kind: 'error', problem });
      this.startCountdown(problem);
    }
  }

  private describe(problem: ApiProblem): ErrorView {
    const base = {
      bodyParams: {},
      productKey: null,
      requestId: problem.status >= 500 ? problem.requestId : null,
    };
    if (problem.code === 'validation_failed') {
      const pairError = problem.errors.find((error) => PAIR_CODES.has(error.code));
      if (pairError) {
        const productKey =
          pairError.field === 'baselineProductId'
            ? 'compare.error.baseline'
            : pairError.field === 'candidateProductId'
              ? 'compare.error.candidate'
              : 'compare.error.either';
        return {
          ...base,
          tone: 'warn',
          icon: pairError.code === 'same_product' ? 'swap' : 'nostock',
          titleKey: 'compare.error.title',
          bodyKey: `compare.error.${pairError.code}`,
          productKey: pairError.code === 'same_product' ? null : productKey,
          retry: false,
        };
      }
    }
    if (problem.code === 'rate_limited') {
      return {
        ...base,
        tone: 'warn',
        icon: 'clock',
        titleKey: 'problem.rate_limited.title',
        bodyKey: 'problem.rate_limited.body',
        retry: true,
      };
    }
    const notFound =
      problem.code === 'product_not_found' || problem.code === 'product_not_available_in_country';
    return {
      ...base,
      tone: notFound ? 'neutral' : problem.code === 'validation_failed' ? 'warn' : 'bad',
      icon: notFound ? 'search' : 'warn',
      titleKey: `${problem.messageKey}.title`,
      bodyKey: `${problem.messageKey}.body`,
      retry: RETRY_STATUSES.has(problem.status),
    };
  }

  private startCountdown(problem: ApiProblem): void {
    const seconds = problem.code === 'rate_limited' ? (problem.retryAfterSeconds ?? 0) : 0;
    this.secondsLeft.set(seconds);
    if (seconds <= 0) {
      return;
    }
    this.timer = setInterval(() => {
      const next = Math.max(0, this.secondsLeft() - 1);
      this.secondsLeft.set(next);
      if (next === 0) {
        this.stopTimer();
      }
    }, 1000);
  }

  private stopTimer(): void {
    if (this.timer !== null) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }
}
