import { Directive, ElementRef, afterNextRender, inject } from '@angular/core';

/**
 * Moves focus to the host element after its first render. Used on the heading of a state that
 * replaces a form (for example "Check your e-mail"), so screen readers announce the new state.
 * The host needs `tabindex="-1"`.
 */
@Directive({ selector: '[vdFocusOnInit]' })
export class FocusOnInitDirective {
  constructor() {
    const host = inject<ElementRef<HTMLElement>>(ElementRef);
    afterNextRender(() => host.nativeElement.focus());
  }
}
