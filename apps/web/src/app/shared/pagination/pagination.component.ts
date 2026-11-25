import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { IconComponent } from '../icon/icon.component';

/**
 * "Showing 1–5 of 12" with Previous / Next buttons for the server's `page`, `pageSize` and
 * `total`. Emits the requested 1-based page; the page fetches it.
 *
 * ```html
 * <vd-pagination [page]="meta.page" [pageSize]="meta.pageSize" [total]="meta.total" (pageChange)="load($event)" />
 * ```
 */
@Component({
  selector: 'vd-pagination',
  imports: [IconComponent, TranslocoPipe],
  template: `
    @if (total() > 0) {
      <nav class="pager" [attr.aria-label]="'pagination.label' | transloco">
        <p class="text-sm muted" aria-live="polite">
          {{ 'pagination.showing' | transloco: { from: from(), to: to(), total: total() } }}
        </p>
        <div class="row-2">
          <button
            type="button"
            class="btn btn-sm"
            [disabled]="page() <= 1"
            (click)="pageChange.emit(page() - 1)"
          >
            <vd-icon name="back" />{{ 'pagination.previous' | transloco }}
          </button>
          <button
            type="button"
            class="btn btn-sm"
            [disabled]="page() >= lastPage()"
            (click)="pageChange.emit(page() + 1)"
          >
            {{ 'pagination.next' | transloco }}<vd-icon name="arrow" />
          </button>
        </div>
      </nav>
    }
  `,
  styles: `
    .pager {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--vd-space-3);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PaginationComponent {
  readonly page = input.required<number>();
  readonly pageSize = input.required<number>();
  readonly total = input.required<number>();
  readonly pageChange = output<number>();

  protected readonly lastPage = computed(() =>
    Math.max(1, Math.ceil(this.total() / Math.max(1, this.pageSize()))),
  );
  protected readonly from = computed(() =>
    Math.min(this.total(), (this.page() - 1) * this.pageSize() + 1),
  );
  protected readonly to = computed(() => Math.min(this.total(), this.page() * this.pageSize()));
}
