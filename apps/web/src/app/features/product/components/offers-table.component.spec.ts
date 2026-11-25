import { TestBed } from '@angular/core/testing';

import productEn from '../../../../../public/i18n/product/en.json';
import productTr from '../../../../../public/i18n/product/tr.json';
import { Offer } from '../../../core/api/api-types';
import { provideTestLocale } from '../../../testing/test-providers';
import { translocoTesting } from '../../../testing/transloco-testing';
import { productDetail } from '../testing/product.fixtures';
import { OffersTableComponent } from './offers-table.component';

describe('OffersTableComponent', () => {
  it('links only offers whose URL is an absolute http or https address', async () => {
    TestBed.configureTestingModule({
      imports: [translocoTesting({ scopes: { product: { en: productEn, tr: productTr } } })],
      providers: [provideTestLocale('en-US')],
    });
    const detail = productDetail();
    const base = detail.product.offers[0];
    const urls = [
      'https://shop.example.com/a',
      'HTTP://market.example.com/b',
      'mailto:orders@example.com',
      'javascript:alert(1)',
      'ftp://files.example.com/c',
      '/relative/path',
      'not a url',
    ];
    const offers: Offer[] = urls.map((url, index) => ({
      ...base,
      retailer: `Demo Store ${index + 1}`,
      url,
    }));
    const fixture = TestBed.createComponent(OffersTableComponent);
    fixture.componentRef.setInput('offers', offers);
    fixture.componentRef.setInput('meta', detail.meta);
    fixture.componentRef.setInput('countryName', 'Türkiye');
    await fixture.whenStable();
    const host = fixture.nativeElement as HTMLElement;

    const links = Array.from(host.querySelectorAll<HTMLAnchorElement>('tbody a'));
    expect(links.map((link) => link.getAttribute('href'))).toEqual([
      'https://shop.example.com/a',
      'http://market.example.com/b',
    ]);
    for (const link of links) {
      expect(link.rel.split(' ')).toEqual(expect.arrayContaining(['noopener', 'noreferrer']));
      expect(link.target).toBe('_blank');
    }
    const rows = Array.from(host.querySelectorAll('tbody tr'));
    expect(rows).toHaveLength(urls.length);
    for (const row of rows.slice(2)) {
      expect(row.querySelector('a')).toBeNull();
      expect(row.querySelector('[data-offer-link="none"]')).not.toBeNull();
    }
    expect(host.querySelector('a[href^="mailto:"], a[href^="javascript:"]')).toBeNull();
  });
});
