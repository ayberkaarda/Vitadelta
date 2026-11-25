import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

// Tests run with the web app folder as working directory.
const CONTRACT = resolve(process.cwd(), '../../contracts/public-api.openapi.yaml');
const SCHEMA = resolve(process.cwd(), 'src/app/core/api/schema.d.ts');

describe('generated API types', () => {
  it('were generated from the current public contract (run `npm run api:types`)', () => {
    const contract = readFileSync(CONTRACT, 'utf8').replace(/\r\n/g, '\n');
    const digest = createHash('sha256').update(contract, 'utf8').digest('hex');
    const header = readFileSync(SCHEMA, 'utf8').split('\n', 1)[0];
    expect(header.trim()).toBe(`// contract-sha256: ${digest}`);
  });
});
