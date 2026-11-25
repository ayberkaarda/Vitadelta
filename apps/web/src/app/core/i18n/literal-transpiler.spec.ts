import { TestBed } from '@angular/core/testing';
import { TRANSLOCO_TRANSPILER, TranslocoService } from '@jsverse/transloco';

import accountEn from '../../../../public/i18n/account/en.json';
import accountTr from '../../../../public/i18n/account/tr.json';
import resultsEn from '../../../../public/i18n/results/en.json';
import resultsTr from '../../../../public/i18n/results/tr.json';
import { HOSTILE_VALUES } from '../../testing/hostile-text';
import { translocoTesting } from '../../testing/transloco-testing';
import { LiteralTranspiler, substitute } from './literal-transpiler';

describe('substitute', () => {
  it('replaces each placeholder once with the value as written', () => {
    expect(substitute('A {{ a }}, B {{b}} and A {{  a  }}', { a: 'x', b: 2 })).toBe(
      'A x, B 2 and A x',
    );
  });

  it.each(HOSTILE_VALUES.map((value) => [value]))('keeps %j verbatim', (value) => {
    expect(substitute('Score breakdown of {{ name }}', { name: value })).toBe(
      `Score breakdown of ${value}`,
    );
    expect(substitute('{{ name }}|{{ other }}', { name: value, other: value })).toBe(
      `${value}|${value}`,
    );
  });

  it('terminates for a value that repeats its own placeholder many times', () => {
    const value = '{{ name }}'.repeat(10_000);
    const started = performance.now();
    expect(substitute('{{ name }} and {{ name }}', { name: value })).toBe(`${value} and ${value}`);
    expect(performance.now() - started).toBeLessThan(1_000);
  });

  it('never looks a placeholder up on the prototype chain, through paths or in the dictionary', () => {
    expect(substitute('[{{ constructor }}][{{ toString }}][{{ __proto__ }}]', {})).toBe('[][][]');
    expect(substitute('[{{ constructor }}]', undefined)).toBe('[]');
    expect(substitute('[{{ a.b }}][{{ app.name }}]', { a: { b: 'deep' }, 'app.name': 'X' })).toBe(
      '[{{ a.b }}][{{ app.name }}]',
    );
    const inherited: Record<string, unknown> = Object.create({ name: 'inherited' });
    expect(substitute('[{{ name }}]', inherited)).toBe('[]');
  });

  it('renders missing, null and undefined values as empty text and other values as strings', () => {
    expect(
      substitute('[{{ a }}][{{ b }}][{{ c }}][{{ d }}][{{ e }}]', {
        b: null,
        c: undefined,
        d: 0,
        e: false,
      }),
    ).toBe('[][][][0][false]');
  });
});

describe('LiteralTranspiler', () => {
  const transpiler = new LiteralTranspiler();

  it('transpiles strings and returns other values unchanged', () => {
    const translation = { 'a.b': 'dictionary text' };
    expect(
      transpiler.transpile({
        value: 'Hi {{ name }}',
        params: { name: '{{ a.b }}' },
        translation,
        key: 'k',
      }),
    ).toBe('Hi {{ a.b }}');
    const object = { title: 'Hi {{ name }}' };
    expect(
      transpiler.transpile({ value: object, params: { name: 'x' }, translation, key: 'k' }),
    ).toBe(object);
    expect(transpiler.transpile({ value: 7, translation, key: 'k' })).toBe(7);
  });
});

describe('translation parameters through TranslocoService', () => {
  let transloco: TranslocoService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [
        translocoTesting({
          scopes: {
            results: { en: resultsEn, tr: resultsTr },
            account: { en: accountEn, tr: accountTr },
          },
        }),
      ],
    });
    transloco = TestBed.inject(TranslocoService);
  });

  it('uses the literal transpiler in the testing set-up', () => {
    expect(TestBed.inject(TRANSLOCO_TRANSPILER)).toBeInstanceOf(LiteralTranspiler);
  });

  it('shows a product name with interpolation markers verbatim in a scoped message', () => {
    const name = 'Adversarial C {{ constructor.name }} {{ results.table.breakdown }} {{ missing }}';
    expect(transloco.translate('results.table.breakdown_label', { name })).toBe(
      `Score breakdown of ${name}`,
    );
  });

  it('shows a notification payload name verbatim in the inbox title', () => {
    const name = "Hostile {{constructor.constructor('window.__xss=1')()}}";
    expect(transloco.translate('account.inbox.price_drop_title', { name })).toBe(
      `Price drop: ${name}`,
    );
  });

  it('terminates for a product name that contains its own placeholder', () => {
    const name = 'Adversarial D placeholder {{ name }}';
    expect(transloco.translate('results.table.breakdown_label', { name })).toBe(
      `Score breakdown of ${name}`,
    );
    expect(transloco.translate('results.table.breakdown_label', { name }, 'tr')).toContain(name);
  });
});
