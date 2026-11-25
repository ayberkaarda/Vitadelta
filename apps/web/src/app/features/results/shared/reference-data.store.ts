import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { DefaultWeights, EngineParameters, Nutrient } from '../../../core/api/api-types';

/**
 * Reference data shared by the query, results, compare and product pages: the nutrient registry
 * (`GET /nutrients`) and the default weights with their ranges and the read-only engine
 * parameters (`GET /weights/defaults`). Each list is requested once per page view; a failed
 * request is retried on the next `load`.
 */
@Injectable({ providedIn: 'root' })
export class ReferenceDataStore {
  private readonly api = inject(ApiClient);

  private nutrientsRequest: Promise<void> | null = null;
  private defaultsRequest: Promise<void> | null = null;

  private readonly nutrientsSignal = signal<readonly Nutrient[]>([]);
  private readonly defaultsSignal = signal<DefaultWeights | null>(null);
  private readonly failedSignal = signal(false);

  /** Registry nutrients ordered by `sortOrder`, then code. */
  readonly nutrients = this.nutrientsSignal.asReadonly();
  readonly defaults = this.defaultsSignal.asReadonly();
  /** `true` after a request failed (the page can offer a retry). */
  readonly failed = this.failedSignal.asReadonly();
  /** Engine parameters; `null` while unknown (scale and ceiling line are hidden then). */
  readonly engine = computed<EngineParameters | null>(() => this.defaultsSignal()?.engine ?? null);

  private readonly byCode = computed(
    () => new Map(this.nutrientsSignal().map((nutrient) => [nutrient.code, nutrient])),
  );

  nutrient(code: string): Nutrient | null {
    return this.byCode().get(code) ?? null;
  }

  /** Loads both lists (once each). */
  load(): Promise<void> {
    return Promise.all([this.loadNutrients(), this.loadDefaults()]).then(() => undefined);
  }

  loadNutrients(): Promise<void> {
    this.nutrientsRequest ??= firstValueFrom(this.api.listNutrients()).then(
      (list) => {
        const items = [...list.items].sort(
          (a, b) => a.sortOrder - b.sortOrder || a.code.localeCompare(b.code),
        );
        this.nutrientsSignal.set(items);
      },
      () => {
        this.nutrientsRequest = null;
        this.failedSignal.set(true);
      },
    );
    return this.nutrientsRequest;
  }

  loadDefaults(): Promise<void> {
    this.defaultsRequest ??= firstValueFrom(this.api.getDefaultWeights()).then(
      (defaults) => this.defaultsSignal.set(defaults),
      () => {
        this.defaultsRequest = null;
        this.failedSignal.set(true);
      },
    );
    return this.defaultsRequest;
  }
}

/**
 * Coverage ratio at which utility reaches the ceiling `u_max`:
 * `exp((u_max - 1) / beta)` (≈ 4.17 for the default configuration). Display only; `null` when
 * the engine parameters are unknown.
 */
export function ceilingRatio(engine: EngineParameters | null): number | null {
  if (!engine || !(engine.diminishingBeta > 0) || !(engine.utilityCeiling >= 1)) {
    return null;
  }
  return Math.exp((engine.utilityCeiling - 1) / engine.diminishingBeta);
}

/** Human-readable reference set code, `EU_NRV` → `EU NRV`. */
export function setLabel(code: string): string {
  return code.replace(/_/g, ' ');
}
