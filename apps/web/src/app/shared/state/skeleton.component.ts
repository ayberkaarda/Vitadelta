import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * Loading placeholder (design hand-off §3 "Skeleton"): grey bars, no spinner over content. The
 * region is `aria-busy` and carries a status line for screen readers.
 *
 * ```html
 * <vd-skeleton [rows]="6" [status]="'results.loading' | transloco" />
 * ```
 */
@Component({
  selector: 'vd-skeleton',
  template: `
    <div class="skeleton" aria-busy="true">
      <p class="sr-only" role="status">{{ status() }}</p>
      @for (row of rowList(); track row) {
        <div class="line" aria-hidden="true">
          <span class="skel short"></span>
          <span class="skel"></span>
          <span class="skel mid"></span>
        </div>
      }
    </div>
  `,
  styles: `
    .skeleton {
      display: flex;
      flex-direction: column;
      gap: var(--vd-space-4);
      padding: var(--vd-space-4);
    }
    .line {
      display: grid;
      grid-template-columns: 2rem minmax(0, 3fr) minmax(0, 1fr);
      gap: var(--vd-space-4);
      align-items: center;
    }
    .short {
      height: 2rem;
    }
    .mid {
      width: 80%;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SkeletonComponent {
  readonly rows = input(5);
  /** Translated status text ("Ranking products…"). */
  readonly status = input.required<string>();

  protected readonly rowList = computed(() =>
    Array.from({ length: Math.max(1, this.rows()) }, (_, index) => index),
  );
}
