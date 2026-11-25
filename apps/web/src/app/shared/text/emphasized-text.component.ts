import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/** Private-use characters that delimit an emphasised part; never part of translations. */
const START = '';
const END = '';

/**
 * Marks a translation parameter for emphasis. Pass the result as the parameter value and render
 * the translated text with `vd-emphasized-text`.
 */
export function emphasize(value: string): string {
  return `${START}${value}${END}`;
}

export interface TextSegment {
  readonly text: string;
  readonly strong: boolean;
}

/** Splits a translated text into plain and emphasised segments; markers never reach the DOM. */
export function emphasisSegments(text: string): TextSegment[] {
  const segments: TextSegment[] = [];
  let rest = text;
  while (rest.length > 0) {
    const start = rest.indexOf(START);
    if (start === -1) {
      segments.push({ text: rest.replaceAll(END, ''), strong: false });
      break;
    }
    if (start > 0) {
      segments.push({ text: rest.slice(0, start).replaceAll(END, ''), strong: false });
    }
    const end = rest.indexOf(END, start + 1);
    const stop = end === -1 ? rest.length : end;
    const strong = rest.slice(start + 1, stop).replaceAll(START, '');
    if (strong.length > 0) {
      segments.push({ text: strong, strong: true });
    }
    rest = end === -1 ? '' : rest.slice(end + 1);
  }
  return segments;
}

/**
 * Renders a translated sentence whose parameters were wrapped with {@link emphasize}: the
 * parameters (numbers, amounts) appear in `<strong>`, the rest as plain text. Everything is
 * text interpolation, so translations and server values can never inject markup.
 */
@Component({
  selector: 'vd-emphasized-text',
  // Each interpolation sits alone in its element: whitespace-only text nodes are dropped by the
  // compiler, so no space appears between segments that the translation does not contain.
  template: `@for (s of segments(); track $index) {
    @if (s.strong) {
      <strong>{{ s.text }}</strong>
    } @else {
      <ng-container>{{ s.text }}</ng-container>
    }
  }`,
  styles: `
    strong {
      font-weight: var(--vd-font-weight-bold);
      font-variant-numeric: tabular-nums;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EmphasizedTextComponent {
  /** Translated text containing emphasis markers. */
  readonly text = input.required<string>();

  protected readonly segments = computed(() => emphasisSegments(this.text()));
}
