import { TestBed } from '@angular/core/testing';

import { provideTestLocale } from '../../testing/test-providers';
import { EN, translocoTesting } from '../../testing/transloco-testing';
import { ProvenanceMarkerComponent } from './provenance-marker.component';

describe('ProvenanceMarkerComponent', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [translocoTesting()],
      providers: [provideTestLocale()],
    }),
  );

  async function render(inputs: Record<string, unknown>) {
    const fixture = TestBed.createComponent(ProvenanceMarkerComponent);
    for (const [name, value] of Object.entries(inputs)) {
      fixture.componentRef.setInput(name, value);
    }
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  const text = (host: HTMLElement) => host.textContent?.replace(/\s+/g, ' ').trim();

  it('labels synthetic products', async () => {
    const host = await render({
      provenance: { synthetic: true, source: null, sourceRef: null, sourceEntryDate: null },
    });
    expect(host.querySelector('[data-kind="synthetic"]')).not.toBeNull();
    expect(text(host)).toBe(EN.provenance.synthetic);
  });

  it('labels a US label snapshot with its date and, on detail, the identification note', async () => {
    const host = await render({
      provenance: {
        synthetic: false,
        source: 'DSLD',
        sourceRef: '239077',
        sourceEntryDate: '2020-11-21',
      },
      detail: true,
    });
    expect(host.querySelector('[data-kind="snapshot"]')).not.toBeNull();
    expect(text(host)).toContain('US label snapshot · Nov 21, 2020');
    expect(text(host)).toContain(EN.provenance.identification_only);
  });

  it('labels a snapshot without date', async () => {
    const host = await render({
      provenance: { synthetic: false, source: 'DSLD', sourceRef: null, sourceEntryDate: null },
    });
    expect(text(host)).toBe(EN.provenance.label_snapshot_undated);
  });

  it('renders the demo price markers', async () => {
    expect(text(await render({ kind: 'demo-price' }))).toBe(EN.provenance.demo_price);
    expect(text(await render({ kind: 'demo-prices' }))).toBe(EN.provenance.demo_prices);
  });

  it('renders nothing without provenance', async () => {
    expect(text(await render({}))).toBe('');
  });
});
