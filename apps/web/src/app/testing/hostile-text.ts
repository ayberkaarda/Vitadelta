/**
 * Synthetic hostile catalog and notification text for translation tests: interpolation
 * markers (including the placeholder of the message itself), prototype members, dictionary
 * keys, string replacement patterns and markup. Every value must be rendered verbatim.
 */
export const HOSTILE_VALUES: readonly string[] = [
  'Adversarial D placeholder {{ name }}',
  '{{name}}{{ name }}{{  name  }}',
  'Adversarial C {{ constructor.name }} {{ results.table.breakdown }} {{ missing }}',
  "Hostile {{constructor.constructor('window.__xss=1')()}}",
  '{{ __proto__ }} {{ toString }} {{ hasOwnProperty }} {{ valueOf }}',
  '{{ app.name }} {{ app.title_with_page }} {{ disclaimer.not_medical_advice }}',
  '{{ {{ name }} }} {{ {{ }} }} }}{{',
  "$& $1 $$ $` $' $<name>",
  '<img src=x onerror="window.__xss=1"> &amp; &lt;',
];

const MARKER = /(\{\{\s*[A-Za-z0-9_]+\s*\}\})/;

/** Placeholder names of a translation template, e.g. `['count', 'name']`. */
export function placeholders(template: string): string[] {
  return template
    .split(MARKER)
    .filter((part) => MARKER.test(part))
    .map((marker) => marker.replace(/[{}\s]/g, ''));
}

/**
 * The expected rendering of a template with every placeholder replaced by `value`, built by
 * splitting and joining (no second scan of the inserted text).
 */
export function renderedWith(template: string, value: string): string {
  return template
    .split(MARKER)
    .map((part) => (MARKER.test(part) ? value : part))
    .join('');
}
