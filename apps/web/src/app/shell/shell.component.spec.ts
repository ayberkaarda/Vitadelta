import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Title } from '@angular/platform-browser';
import { TranslocoService, TranslocoTestingModule } from '@jsverse/transloco';

import en from '../../../public/i18n/en.json';
import tr from '../../../public/i18n/tr.json';
import { HEALTH_URL } from '../core/health/health.service';
import { ShellComponent } from './shell.component';

describe('ShellComponent', () => {
  let fixture: ComponentFixture<ShellComponent>;
  let http: HttpTestingController;

  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;

  const statusRegion = (): HTMLElement => {
    const region = host().querySelector<HTMLElement>('[role="status"]');
    if (!region) {
      throw new Error('Status region not rendered');
    }
    return region;
  };

  const retryButton = (): HTMLButtonElement => {
    const button = host().querySelector('button');
    if (!button) {
      throw new Error('Retry button not rendered');
    }
    return button;
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [
        ShellComponent,
        TranslocoTestingModule.forRoot({
          langs: { en, tr },
          translocoConfig: { availableLangs: ['en', 'tr'], defaultLang: 'en' },
          preloadLangs: true,
        }),
      ],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ShellComponent);
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  it('renders the product name as the page heading', () => {
    http.expectOne(HEALTH_URL);
    expect(host().querySelector('h1')?.textContent?.trim()).toBe(en.shell.title);
  });

  it('announces the loading state in a polite live region', () => {
    http.expectOne(HEALTH_URL);
    const region = statusRegion();
    expect(region.getAttribute('aria-live')).toBe('polite');
    expect(region.getAttribute('aria-busy')).toBe('true');
    expect(region.textContent?.trim()).toBe(en.shell.health.loading);
    expect(retryButton().disabled).toBe(true);
  });

  it.each(['UP', 'DEGRADED', 'DOWN'] as const)('shows the %s status', async (status) => {
    http.expectOne(HEALTH_URL).flush({ status });
    await fixture.whenStable();

    const region = statusRegion();
    expect(region.textContent?.trim()).toBe(en.shell.health.status[status]);
    expect(region.getAttribute('aria-busy')).toBe('false');
    expect(region.querySelector('[data-status]')?.getAttribute('data-status')).toBe(status);
  });

  it('shows the unreachable state when the request fails', async () => {
    http.expectOne(HEALTH_URL).error(new ProgressEvent('error'));
    await fixture.whenStable();

    const region = statusRegion();
    expect(region.textContent?.trim()).toBe(en.shell.health.unreachable);
    expect(region.getAttribute('data-state')).toBe('unreachable');
    expect(retryButton().disabled).toBe(false);
  });

  it('checks the health again when retry is pressed', async () => {
    http.expectOne(HEALTH_URL).flush(null, { status: 502, statusText: 'Bad Gateway' });
    await fixture.whenStable();
    expect(statusRegion().textContent?.trim()).toBe(en.shell.health.unreachable);

    retryButton().click();
    await fixture.whenStable();
    expect(statusRegion().textContent?.trim()).toBe(en.shell.health.loading);

    http.expectOne(HEALTH_URL).flush({ status: 'UP' });
    await fixture.whenStable();
    expect(statusRegion().textContent?.trim()).toBe(en.shell.health.status.UP);
  });

  it('switches copy, document language and title when the language changes', async () => {
    http.expectOne(HEALTH_URL).flush({ status: 'DEGRADED' });
    TestBed.inject(TranslocoService).setActiveLang('tr');
    await fixture.whenStable();

    expect(statusRegion().textContent?.trim()).toBe(tr.shell.health.status.DEGRADED);
    expect(document.documentElement.lang).toBe('tr');
    expect(TestBed.inject(Title).getTitle()).toBe(tr.shell.title);
  });
});
