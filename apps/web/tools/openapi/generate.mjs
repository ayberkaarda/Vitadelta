// Generates src/app/core/api/schema.d.ts from the public API contract.
//
// Usage: node generate.mjs [--check]
//   --check  regenerates in memory and exits with status 1 when the committed file differs.
//
// The first line of the output records the SHA-256 of the contract (line endings normalised to
// LF), so a unit test of the web app can detect a contract change without running this tool.

import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { dirname, relative, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

import openapiTS, { astToString } from 'openapi-typescript';

const here = dirname(fileURLToPath(import.meta.url));
const contractPath = resolve(here, '../../../../contracts/public-api.openapi.yaml');
const outputPath = resolve(here, '../../src/app/core/api/schema.d.ts');

async function render() {
  const contract = (await readFile(contractPath, 'utf8')).replace(/\r\n/g, '\n');
  const digest = createHash('sha256').update(contract, 'utf8').digest('hex');
  const ast = await openapiTS(pathToFileURL(contractPath));
  const body = astToString(ast).replace(/\r\n/g, '\n');
  return [
    `// contract-sha256: ${digest}`,
    '// Types of the public API, derived from contracts/public-api.openapi.yaml.',
    '// Do not edit by hand: run `npm run api:types` in apps/web after changing the contract.',
    '',
    body.trimEnd(),
    '',
  ].join('\n');
}

const check = process.argv.includes('--check');
const generated = await render();

if (check) {
  let current = '';
  try {
    current = (await readFile(outputPath, 'utf8')).replace(/\r\n/g, '\n');
  } catch (error) {
    console.error(`Cannot read ${outputPath}: ${error.message}`);
    process.exit(1);
  }
  if (current !== generated) {
    console.error('schema.d.ts is out of date with the contract. Run `npm run api:types`.');
    process.exit(1);
  }
  console.log('schema.d.ts matches the contract.');
} else {
  await writeFile(outputPath, generated, 'utf8');
  console.log(`Wrote ${relative(process.cwd(), outputPath)}`);
}
