import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { Offer, SelectedOffer } from '../../../core/api/api-types';
import { Formatter } from '../../../core/format/formatter';
import { MoneyContext } from '../../../core/state/currency.store';
import { IconComponent } from '../../../shared/icon/icon.component';
import { MoneyComponent } from '../../../shared/money/money.component';
import { webUrl } from '../../../shared/text/web-url';

/**
 * Offers of the product in the selected country (design hand-off §4.6): retailer with the
 * "used for cost" marker on the offer behind the cost, the demo price (with native amount and
 * rate in the tooltip), stock state, the date the price was checked with the stale marker, and
 * an external link. Every price is a demo value. Only an absolute `http:` or `https:` URL is
 * rendered as a link; any other value is shown as plain text.
 */
@Component({
  selector: 'vd-offers-table',
  imports: [IconComponent, MoneyComponent, TranslocoPipe],
  template: `
    <div class="tbl-wrap">
      <table class="tbl">
        <caption class="sr-only">
          {{
            'product.offers.caption' | transloco
          }}
        </caption>
        <thead>
          <tr>
            <th scope="col">{{ 'product.offers.retailer' | transloco }}</th>
            <th scope="col" class="r">{{ 'product.offers.price' | transloco }}</th>
            <th scope="col">{{ 'product.offers.stock' | transloco }}</th>
            <th scope="col">{{ 'product.offers.checked' | transloco }}</th>
            <th scope="col" class="r">{{ 'product.offers.link' | transloco }}</th>
          </tr>
        </thead>
        <tbody>
          @for (offer of offers(); track offer.url + offer.retailer) {
            <tr [attr.data-retailer]="offer.retailer">
              <td>
                <span class="row-2">
                  <span class="strong">{{ offer.retailer }}</span>
                  @if (isSelected(offer)) {
                    <span class="badge t-info" data-marker="used-for-cost">{{
                      'product.offers.used_for_cost' | transloco
                    }}</span>
                  }
                </span>
              </td>
              <td class="r">
                <vd-money
                  [amount]="offer.price"
                  [currency]="offer.currency"
                  [nativeAmount]="offer.priceNative"
                  [nativeCurrency]="meta().nativeCurrency"
                  [fx]="meta().fx"
                  [price]="offer.price"
                  [retailer]="offer.retailer"
                  [checkedAt]="offer.checkedAt"
                />
              </td>
              <td>
                @if (offer.inStock) {
                  <span class="badge t-ok" data-stock="in">
                    <vd-icon name="ok" />
                    <span>{{ 'product.offers.in_stock' | transloco }}</span>
                  </span>
                } @else {
                  <span class="badge" data-stock="out">
                    <vd-icon name="nostock" />
                    <span>{{ 'product.offers.out_of_stock' | transloco }}</span>
                  </span>
                }
              </td>
              <td>
                <span class="row-2">
                  <span class="nowrap">{{ format.date(offer.checkedAt) }}</span>
                  @if (offer.priceStale) {
                    <span class="badge" data-code="PRICE_STALE">
                      <vd-icon name="clock" />
                      <span>{{ 'flag.PRICE_STALE' | transloco }}</span>
                    </span>
                  }
                </span>
              </td>
              <td class="r">
                @if (webUrl(offer.url); as href) {
                  <a
                    class="btn btn-ghost btn-sm"
                    [href]="href"
                    target="_blank"
                    rel="noopener noreferrer nofollow"
                    [attr.aria-label]="
                      'product.offers.visit_label' | transloco: { retailer: offer.retailer }
                    "
                  >
                    {{ 'product.offers.visit' | transloco }}
                    <vd-icon name="ext" />
                  </a>
                } @else {
                  <span class="text-xs muted offer-url" data-offer-link="none" translate="no">{{
                    offer.url
                  }}</span>
                }
              </td>
            </tr>
          } @empty {
            <tr>
              <td colspan="5" class="muted">
                {{ 'product.offers.empty' | transloco: { country: countryName() } }}
              </td>
            </tr>
          }
        </tbody>
      </table>
    </div>
  `,
  styles: `
    :host {
      display: block;
    }
    .offer-url {
      display: inline-block;
      max-width: 16rem;
      overflow-wrap: anywhere;
      text-align: left;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OffersTableComponent {
  protected readonly format = inject(Formatter);

  readonly offers = input.required<readonly Offer[]>();
  readonly selectedOffer = input<SelectedOffer | null>(null);
  /** Money context (`meta`) of the product response. */
  readonly meta = input.required<MoneyContext>();
  readonly countryName = input.required<string>();

  /** The offer URL when it parses as an absolute `http:` or `https:` URL; `null` otherwise. */
  protected webUrl(url: string): string | null {
    return webUrl(url);
  }

  protected isSelected(offer: Offer): boolean {
    const selected = this.selectedOffer();
    return selected !== null && selected.retailer === offer.retailer && selected.url === offer.url;
  }
}
