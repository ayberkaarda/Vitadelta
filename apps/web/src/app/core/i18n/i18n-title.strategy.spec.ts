import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Title } from '@angular/platform-browser';
import { Router, TitleStrategy, provideRouter } from '@angular/router';
import { TranslocoService } from '@jsverse/transloco';

import { translocoTesting } from '../../testing/transloco-testing';
import { featureRoutes } from './i18n-scope';
import { I18nTitleStrategy } from './i18n-title.strategy';

@Component({ template: '' })
class BlankComponent {}

describe('I18nTitleStrategy and featureRoutes', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [
        translocoTesting({
          scopes: {
            demo: { en: { page_title: 'Demo page' }, tr: { page_title: 'Demo sayfası' } },
          },
        }),
      ],
      providers: [
        provideRouter([
          { path: 'missing', title: 'not_found.page_title', component: BlankComponent },
          ...featureRoutes('demo', [
            { path: 'demo', title: 'demo.page_title', component: BlankComponent },
          ]),
        ]),
        { provide: TitleStrategy, useExisting: I18nTitleStrategy },
      ],
    });
  });

  const settle = () => new Promise((resolve) => setTimeout(resolve, 0));

  it('translates root title keys and re-renders on language change', async () => {
    await TestBed.inject(Router).navigateByUrl('/missing');
    await settle();
    expect(TestBed.inject(Title).getTitle()).toBe('Page not found · VitaDelta');
    TestBed.inject(TranslocoService).setActiveLang('tr');
    await settle();
    expect(TestBed.inject(Title).getTitle()).toBe('Sayfa bulunamadı · VitaDelta');
  });

  it('loads the feature scope of the route for its title', async () => {
    await TestBed.inject(Router).navigateByUrl('/demo');
    await settle();
    expect(TestBed.inject(I18nTitleStrategy).current()).toEqual({
      key: 'demo.page_title',
      scope: 'demo',
    });
    expect(TestBed.inject(Title).getTitle()).toBe('Demo page · VitaDelta');
  });

  it('wraps feature routes with the scope provider and data', () => {
    const [route] = featureRoutes('results', []);
    expect(route.data).toEqual({ i18nScope: 'results' });
    expect(route.providers).toHaveLength(1);
    expect(route.children).toEqual([]);
  });
});
