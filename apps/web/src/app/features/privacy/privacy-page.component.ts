import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { ApiProblem } from '../../core/api/api-problem';
import { IconComponent } from '../../shared/icon/icon.component';
import { ProblemNoticeComponent } from '../../shared/notice/problem-notice.component';

/** Sections of the notice in reading order; ids are the URL fragments of the table of contents. */
export const PRIVACY_SECTIONS = [
  'who',
  'data',
  'purposes',
  'retention',
  'recipients',
  'cookies',
  'rights',
] as const;

type ContactState =
  | { readonly kind: 'loading' }
  | { readonly kind: 'ready'; readonly contact: string }
  | { readonly kind: 'error'; readonly problem: ApiProblem };

/**
 * `/privacy` (project specification §15.9; design hand-off §4.11): the privacy notice under
 * KVKK (Law No. 6698) and the GDPR. The controller contact comes from `GET /legal`
 * (`privacyContact`) and is shown as selectable text with a copy button; every other text is
 * an i18n key of the `privacy` scope.
 */
@Component({
  selector: 'vd-privacy-page',
  imports: [IconComponent, ProblemNoticeComponent, RouterLink, TranslocoPipe],
  templateUrl: './privacy-page.component.html',
  styleUrl: './privacy-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PrivacyPageComponent {
  private readonly api = inject(ApiClient);

  protected readonly sections = PRIVACY_SECTIONS;
  /** Technical cookie names (identifiers, not copy). */
  protected readonly cookieNames = { anon: 'vd_anon', refresh: 'vd_refresh' } as const;
  protected readonly dataItems = ['account', 'prefs', 'lists', 'plan', 'tokens', 'anon', 'ip'];
  protected readonly purposeRows = ['account', 'lists', 'mail', 'plan', 'quota', 'security'];
  protected readonly retentionItems = [
    'account',
    'unverified',
    'anon',
    'tokens',
    'sessions',
    'outbox',
    'ip',
    'backups',
  ];
  protected readonly rightItems = [
    'access',
    'rectify',
    'erase',
    'restrict',
    'portability',
    'complain',
  ];

  protected readonly fragment = toSignal(inject(ActivatedRoute).fragment, { initialValue: null });
  protected readonly contact = signal<ContactState>({ kind: 'loading' });
  protected readonly copied = signal<boolean | null>(null);

  constructor() {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.contact.set({ kind: 'loading' });
    try {
      const legal = await firstValueFrom(this.api.getLegal());
      this.contact.set({ kind: 'ready', contact: legal.privacyContact });
    } catch (error: unknown) {
      this.contact.set({ kind: 'error', problem: ApiProblem.from(error) });
    }
  }

  protected async copy(address: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(address);
      this.copied.set(true);
    } catch {
      this.copied.set(false);
    }
  }
}
