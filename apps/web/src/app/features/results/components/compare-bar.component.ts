import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { IconComponent } from '../../../shared/icon/icon.component';

/** A product picked for the comparison. */
export interface SelectedProduct {
  readonly id: string;
  readonly name: string;
}

/**
 * Sticky selection bar (design hand-off §4.3): "2 selected", one removable pill per product and
 * the Compare button, enabled with exactly two products.
 */
@Component({
  selector: 'vd-compare-bar',
  imports: [IconComponent, TranslocoPipe],
  template: `
    @if (selection().length > 0) {
      <section class="bar" [attr.aria-label]="'results.selection.label' | transloco">
        <span class="strong">{{
          'results.selection.count' | transloco: { count: selection().length }
        }}</span>
        <ul role="list" class="pills">
          @for (entry of selection(); track entry.id) {
            <li class="pill">
              <span>{{ entry.name }}</span>
              <button
                type="button"
                [attr.aria-label]="'results.selection.remove' | transloco: { name: entry.name }"
                (click)="remove.emit(entry.id)"
              >
                <vd-icon name="close" size="sm" />
              </button>
            </li>
          }
        </ul>
        <button
          type="button"
          class="btn btn-sm go"
          [disabled]="selection().length !== 2"
          (click)="compare.emit()"
        >
          <vd-icon name="cmp" />{{ 'results.selection.compare' | transloco }}
        </button>
      </section>
    }
  `,
  styles: `
    :host {
      position: sticky;
      bottom: var(--vd-space-4);
      z-index: 10;
      display: flex;
      justify-content: center;
      pointer-events: none;
    }
    .bar {
      pointer-events: auto;
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--vd-space-3);
      max-width: 100%;
      padding: var(--vd-space-3) var(--vd-space-4);
      border-radius: var(--vd-radius-lg);
      background: var(--vd-color-text);
      color: var(--vd-color-bg);
      box-shadow: var(--vd-shadow-overlay);
      font-size: var(--vd-font-size-sm);
    }
    .pills {
      display: flex;
      flex-wrap: wrap;
      gap: var(--vd-space-2);
      margin: 0;
    }
    .pill {
      display: inline-flex;
      align-items: center;
      gap: var(--vd-space-1);
      padding: 2px 4px 2px 10px;
      border-radius: var(--vd-radius-pill);
      background: color-mix(in srgb, var(--vd-color-bg) 16%, transparent);
    }
    .pill button {
      display: grid;
      place-items: center;
      width: 1.75rem;
      height: 1.75rem;
      border: 0;
      border-radius: 50%;
      background: none;
      color: inherit;
      cursor: pointer;
    }
    .pill button:focus-visible,
    .go:focus-visible {
      outline-color: var(--vd-color-bg);
    }
    .go:not([disabled]) {
      background: var(--vd-color-bg);
      border-color: var(--vd-color-bg);
      color: var(--vd-color-text);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CompareBarComponent {
  readonly selection = input.required<readonly SelectedProduct[]>();
  readonly remove = output<string>();
  readonly compare = output();
}
