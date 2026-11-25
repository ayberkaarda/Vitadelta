import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { ICONS, IconName, IconShape } from './icon-paths';

export type IconSize = 'md' | 'sm';

/**
 * Decorative stroke icon (`aria-hidden`); meaning is always carried by adjacent text.
 *
 * ```html
 * <vd-icon name="lock" />
 * <vd-icon name="warn" size="sm" [spin]="busy()" />
 * ```
 */
@Component({
  selector: 'vd-icon',
  template: `
    <svg
      class="i"
      [class.i-sm]="size() === 'sm'"
      [class.spin]="spin()"
      viewBox="0 0 20 20"
      aria-hidden="true"
      focusable="false"
    >
      @for (shape of shapes(); track $index) {
        @if (shape.kind === 'path') {
          <path [attr.d]="shape.d" />
        } @else if (shape.kind === 'circle') {
          <circle
            [attr.cx]="shape.cx"
            [attr.cy]="shape.cy"
            [attr.r]="shape.r"
            [attr.stroke-dasharray]="shape.dashed ? '2.4 2.4' : null"
          />
        } @else {
          <rect
            [attr.x]="shape.x"
            [attr.y]="shape.y"
            [attr.width]="shape.width"
            [attr.height]="shape.height"
            [attr.rx]="shape.rx"
          />
        }
      }
    </svg>
  `,
  styles: ':host { display: contents; }',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class IconComponent {
  readonly name = input.required<IconName>();
  readonly size = input<IconSize>('md');
  readonly spin = input(false);

  protected readonly shapes = computed(() =>
    (ICONS[this.name()] as readonly IconShape[]).map((shape) => {
      if ('d' in shape) {
        return { kind: 'path' as const, d: shape.d };
      }
      if ('circle' in shape) {
        const [cx, cy, r] = shape.circle;
        return { kind: 'circle' as const, cx, cy, r, dashed: shape.dashed === true };
      }
      const [x, y, width, height, rx] = shape.rect;
      return { kind: 'rect' as const, x, y, width, height, rx };
    }),
  );
}
