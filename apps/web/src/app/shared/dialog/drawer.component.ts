import {
  ChangeDetectionStrategy,
  Component,
  DOCUMENT,
  DestroyRef,
  ElementRef,
  afterRenderEffect,
  inject,
  input,
  output,
  viewChild,
} from '@angular/core';

const FOCUSABLE = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',');

/**
 * Where the panel appears: `right` (turns into a bottom sheet below 48rem), `top` (the mobile
 * menu sheet) or `center` (a modal dialog, used by `vd-confirm-dialog`).
 */
export type DrawerPlacement = 'right' | 'top' | 'center';

/** Open drawers, innermost last: only the innermost one answers Escape. */
const openDrawers: DrawerComponent[] = [];

/**
 * Modal drawer / sheet primitive (design hand-off §3, §6): `role="dialog"`, `aria-modal`,
 * labelled by the element id given in `labelledBy`, focus moved inside on open (the element
 * marked `data-initial-focus`, else the first focusable one), Tab trapped, Esc and scrim click
 * emit `closed`, the rest of the page is `inert` while open and focus returns to the trigger
 * on close. Escape is handled on the document while the drawer is open, so it closes the drawer
 * wherever focus is (also when it fell back to `body`); a control inside that consumes Escape
 * itself (`preventDefault` or `stopPropagation`) keeps it, and with nested drawers only the
 * innermost one closes. Slides in 200 ms; no motion with `prefers-reduced-motion`.
 *
 * ```html
 * <vd-drawer [open]="drawerOpen()" labelledBy="bd-title" (closed)="drawerOpen.set(false)">
 *   <h2 id="bd-title">…</h2>
 *   <button type="button" data-initial-focus (click)="drawerOpen.set(false)">…</button>
 * </vd-drawer>
 * ```
 */
@Component({
  selector: 'vd-drawer',
  template: `
    @if (open()) {
      <div #scrim class="scrim" aria-hidden="true"></div>
      <div
        #panel
        class="panel"
        [class.top]="placement() === 'top'"
        [class.center]="placement() === 'center'"
        role="dialog"
        aria-modal="true"
        [attr.aria-labelledby]="labelledBy()"
        tabindex="-1"
        (keydown)="onKeydown($event)"
      >
        <ng-content />
      </div>
    }
  `,
  styleUrl: './drawer.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DrawerComponent {
  private readonly document = inject(DOCUMENT);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly panel = viewChild<ElementRef<HTMLElement>>('panel');
  private readonly scrim = viewChild<ElementRef<HTMLElement>>('scrim');
  private readonly onScrimClick = (): void => this.closed.emit();
  private readonly onDocumentKeydown = (event: KeyboardEvent): void => {
    if (
      event.key !== 'Escape' ||
      event.defaultPrevented ||
      openDrawers[openDrawers.length - 1] !== this
    ) {
      return;
    }
    event.preventDefault();
    this.closed.emit();
  };

  readonly open = input.required<boolean>();
  /** Id of the element that names the dialog (its heading). */
  readonly labelledBy = input.required<string>();
  readonly placement = input<DrawerPlacement>('right');
  readonly closed = output();

  private wasOpen = false;
  private returnFocus: HTMLElement | null = null;
  private inerted: HTMLElement[] = [];

  constructor() {
    afterRenderEffect(() => {
      const open = this.open();
      const panel = this.panel()?.nativeElement;
      if (open && !this.wasOpen && panel) {
        this.wasOpen = true;
        this.activate(panel);
      } else if (!open && this.wasOpen) {
        this.wasOpen = false;
        this.deactivate();
      }
    });
    inject(DestroyRef).onDestroy(() => {
      if (this.wasOpen) {
        this.deactivate();
      }
    });
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (event.key !== 'Tab') {
      return;
    }
    const panel = this.panel()?.nativeElement;
    if (!panel) {
      return;
    }
    const items = this.focusables(panel);
    if (items.length === 0) {
      event.preventDefault();
      panel.focus();
      return;
    }
    const first = items[0];
    const last = items[items.length - 1];
    const active = this.document.activeElement;
    if (event.shiftKey && (active === first || active === panel)) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && active === last) {
      event.preventDefault();
      first.focus();
    }
  }

  private activate(panel: HTMLElement): void {
    const active = this.document.activeElement;
    this.returnFocus = active instanceof HTMLElement ? active : null;
    this.setBackgroundInert();
    this.scrim()?.nativeElement.addEventListener('click', this.onScrimClick);
    openDrawers.push(this);
    this.document.addEventListener('keydown', this.onDocumentKeydown);
    const initial =
      panel.querySelector<HTMLElement>('[data-initial-focus]') ??
      this.focusables(panel)[0] ??
      panel;
    initial.focus();
  }

  private deactivate(): void {
    this.document.removeEventListener('keydown', this.onDocumentKeydown);
    const index = openDrawers.indexOf(this);
    if (index >= 0) {
      openDrawers.splice(index, 1);
    }
    for (const element of this.inerted) {
      element.inert = false;
    }
    this.inerted = [];
    const target = this.returnFocus;
    this.returnFocus = null;
    if (target?.isConnected) {
      target.focus();
    }
  }

  /** Makes every element outside the drawer's ancestor chain inert. */
  private setBackgroundInert(): void {
    let node: HTMLElement | null = this.host.nativeElement;
    const body = this.document.body;
    while (node && node !== body) {
      const parent: HTMLElement | null = node.parentElement;
      if (!parent) {
        break;
      }
      for (const sibling of Array.from(parent.children)) {
        if (sibling !== node && sibling instanceof HTMLElement && !sibling.inert) {
          sibling.inert = true;
          this.inerted.push(sibling);
        }
      }
      node = parent;
    }
  }

  private focusables(root: HTMLElement): HTMLElement[] {
    return Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(
      (element) => !element.closest('[inert]') && !element.hasAttribute('hidden'),
    );
  }
}
