import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';

// The production CSP (infra/nginx) is `script-src 'self'` with no inline scripts allowed. These
// checks keep the sources compatible with it: no inline script, no inline event handler, no
// `javascript:` URL, no literal `style` attribute and no critical-CSS inlining (whose `onload`
// handler would need 'unsafe-inline' for scripts). Angular injects component styles as
// `<style>` elements at runtime, which is why the policy still needs `style-src 'unsafe-inline'`
// (or a per-request nonce); `[style.x]` bindings use the CSSOM and are not affected by it.
// Patterns are built at runtime so this file does not match itself.

const ROOT = process.cwd();
const SRC = resolve(ROOT, 'src');

function files(dir: string, test: RegExp): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) {
      return files(path, test);
    }
    return test.test(name) && !name.endsWith('.spec.ts') ? [path] : [];
  });
}

/** Every component template: `*.html` files plus the inline `template:` of `*.ts` files. */
function templates(): { path: string; text: string }[] {
  const html = files(SRC, /\.html$/).map((path) => ({
    path: relative(SRC, path),
    text: readFileSync(path, 'utf8'),
  }));
  const inline = files(join(SRC, 'app'), /\.ts$/).flatMap((path) =>
    [...readFileSync(path, 'utf8').matchAll(/\btemplate\s*:\s*`([\s\S]*?)`/g)].map((match) => ({
      path: relative(SRC, path),
      text: match[1],
    })),
  );
  return [...html, ...inline];
}

const TEMPLATES = templates();
const re = (...parts: string[]) => new RegExp(parts.join(''), 'i');

describe('CSP guard', () => {
  it('scans index.html and every component template', () => {
    expect(TEMPLATES.some((t) => t.path === 'index.html')).toBe(true);
    expect(TEMPLATES.length).toBeGreaterThan(60);
  });

  it.each([
    ['an inline <script>', re('<scr', 'ipt\\b')],
    ['an inline event handler attribute', re('\\s', 'on[a-z]+\\s*=\\s*["\']')],
    ['a javascript: URL', re('java', 'script:')],
    ['a literal style attribute', re('\\s', 'style\\s*=\\s*["\']')],
    ['a [attr.style] binding', re('\\[attr\\.', 'style\\]')],
    ['an inline <style> element', re('<sty', 'le\\b')],
  ])('finds no %s in templates', (_name, pattern) => {
    expect(TEMPLATES.filter((t) => pattern.test(t.text)).map((t) => t.path)).toEqual([]);
  });

  it('builds without critical-CSS inlining and without source maps in production', () => {
    const angular = JSON.parse(readFileSync(resolve(ROOT, 'angular.json'), 'utf8')) as {
      projects: {
        web: {
          architect: {
            build: {
              configurations: {
                production: {
                  sourceMap: boolean;
                  optimization: { styles: { inlineCritical: boolean } };
                };
              };
            };
          };
        };
      };
    };
    const production = angular.projects.web.architect.build.configurations.production;
    expect(production.optimization.styles.inlineCritical).toBe(false);
    expect(production.sourceMap).toBe(false);
  });
});
