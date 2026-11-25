// Fails when a component template contains user-facing literal text outside i18n.
//
// Scans every `*.html` template and every inline `template:` of a `*.ts` file under src/app
// (spec files excluded). After removing comments, interpolations (`{{ … }}`), tags, Angular
// control-flow blocks and HTML entities, any remaining text that contains a letter is a hit.
// Literal values of user-facing attributes (aria-label, title, placeholder, alt, …) are hits
// too; bind them instead (`[attr.aria-label]="'key' | transloco"`).
//
// Usage: node scripts/check-i18n.mjs [root]

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = fileURLToPath(new URL('.', import.meta.url));
const root = resolve(process.argv[2] ?? join(here, '../src/app'));

const LETTER = /\p{L}/u;
const TEXT_ATTRIBUTES = [
  'aria-label',
  'aria-description',
  'aria-roledescription',
  'aria-valuetext',
  'aria-placeholder',
  'title',
  'placeholder',
  'alt',
  'label',
];
const ATTRIBUTE_PATTERN = new RegExp(
  `(?<![\\w.\\[(-])(${TEXT_ATTRIBUTES.join('|')})\\s*=\\s*("([^"]*)"|'([^']*)')`,
  'g',
);
const TAG_PATTERN = /<\/?[A-Za-z][^"'>]*(?:(?:"[^"]*"|'[^']*')[^"'>]*)*\/?>/g;
const BLOCK_PATTERN =
  /@(?:else\s+if|if|else|for|switch|case|default|defer|placeholder|loading|error|empty)\b[^{]*\{/g;
const LET_PATTERN = /@let\s[^;]*;/g;

/** Replaces a match with its newlines only, so line numbers stay correct. */
function blank(match) {
  return match.replace(/[^\n]/g, '');
}

function lineOf(text, index) {
  return text.slice(0, index).split('\n').length;
}

function checkTemplate(template) {
  const hits = [];
  const withoutComments = template.replace(/<!--[\s\S]*?-->/g, blank);

  for (const match of withoutComments.matchAll(ATTRIBUTE_PATTERN)) {
    const value = (match[3] ?? match[4] ?? '').replace(/\{\{[\s\S]*?\}\}/g, '');
    if (LETTER.test(value)) {
      hits.push({ line: lineOf(withoutComments, match.index), text: match[0] });
    }
  }

  const text = withoutComments
    .replace(/\{\{[\s\S]*?\}\}/g, blank)
    .replace(TAG_PATTERN, blank)
    .replace(LET_PATTERN, blank)
    .replace(BLOCK_PATTERN, blank)
    .replace(/&[A-Za-z]+;|&#\d+;|&#x[0-9a-f]+;/gi, '')
    .replace(/[{}]/g, '');

  text.split('\n').forEach((line, index) => {
    if (LETTER.test(line)) {
      hits.push({ line: index + 1, text: line.trim() });
    }
  });
  return hits;
}

function inlineTemplates(source) {
  const templates = [];
  for (const match of source.matchAll(/\btemplate\s*:\s*`([\s\S]*?)`/g)) {
    const start = match.index + match[0].indexOf('`') + 1;
    templates.push({ offset: lineOf(source, start) - 1, body: match[1] });
  }
  return templates;
}

function files(dir) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) {
      return files(path);
    }
    return [path];
  });
}

const problems = [];
let scanned = 0;
for (const file of files(root)) {
  if (file.endsWith('.html')) {
    scanned += 1;
    for (const hit of checkTemplate(readFileSync(file, 'utf8'))) {
      problems.push(`${relative(root, file)}:${hit.line}: ${hit.text}`);
    }
  } else if (file.endsWith('.ts') && !file.endsWith('.spec.ts')) {
    for (const template of inlineTemplates(readFileSync(file, 'utf8'))) {
      scanned += 1;
      for (const hit of checkTemplate(template.body)) {
        problems.push(`${relative(root, file)}:${template.offset + hit.line}: ${hit.text}`);
      }
    }
  }
}

if (problems.length > 0) {
  console.error(`Literal user-facing text outside i18n (${problems.length}):`);
  for (const problem of problems) {
    console.error(`  ${problem}`);
  }
  process.exit(1);
}
console.log(`i18n check passed: ${scanned} templates without literal text.`);
