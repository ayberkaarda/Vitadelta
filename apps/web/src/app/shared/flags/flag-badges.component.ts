import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { ProductFlag, ProductFlagCode } from '../../core/api/api-types';
import { IconComponent } from '../icon/icon.component';
import { FlagBadgeComponent } from './flag-badge.component';
import { METHOD_NOTE_FLAGS } from './flag-style';

/**
 * The flags of one product as a wrapping list, in server order.
 *
 * - `omit`: codes shown elsewhere (for example `FX_STALE` once in the results toolbar).
 * - `collapseMethodNotes`: dense lists fold the info-tone method flags into one
 *   "N method notes" badge.
 * - `interactiveCodes`: codes rendered as buttons that emit `activate` (e.g. `EXCEEDS_UL`).
 */
@Component({
  selector: 'vd-flag-badges',
  imports: [FlagBadgeComponent, IconComponent, TranslocoPipe],
  template: `
    @if (visible().length > 0 || methodNotes() > 0) {
      <ul role="list" class="flags" [attr.aria-label]="'flag.list_label' | transloco">
        @for (flag of visible(); track flag.code) {
          <li>
            <vd-flag-badge
              [code]="flag.code"
              [nutrients]="flag.nutrientCodes"
              [currency]="currency()"
              [country]="country()"
              [interactive]="interactiveCodes().includes(flag.code)"
              (activate)="activate.emit(flag)"
            />
          </li>
        }
        @if (methodNotes() > 0) {
          <li>
            <span class="badge t-info" data-code="METHOD_NOTES">
              <vd-icon name="info" />
              <span>{{
                (methodNotes() === 1 ? 'flag.method_notes_one' : 'flag.method_notes')
                  | transloco: { count: methodNotes() }
              }}</span>
            </span>
          </li>
        }
      </ul>
    }
  `,
  styles: `
    .flags {
      display: flex;
      flex-wrap: wrap;
      gap: var(--vd-space-1);
      margin: 0;
      padding: 0;
      list-style: none;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FlagBadgesComponent {
  readonly flags = input.required<readonly ProductFlag[]>();
  readonly omit = input<readonly ProductFlagCode[]>([]);
  readonly collapseMethodNotes = input(false);
  readonly interactiveCodes = input<readonly ProductFlagCode[]>([]);
  readonly currency = input<string | null>(null);
  readonly country = input<string | null>(null);
  readonly activate = output<ProductFlag>();

  private readonly shown = computed(() =>
    this.flags().filter((flag) => !this.omit().includes(flag.code)),
  );
  protected readonly visible = computed(() =>
    this.collapseMethodNotes()
      ? this.shown().filter((flag) => !METHOD_NOTE_FLAGS.has(flag.code))
      : this.shown(),
  );
  protected readonly methodNotes = computed(() =>
    this.collapseMethodNotes()
      ? this.shown().filter((flag) => METHOD_NOTE_FLAGS.has(flag.code)).length
      : 0,
  );
}
