# Vitadelta web

Angular single-page application for Vitadelta. In Phase 0 it renders the application shell and
the result of the public API health check (`GET /api/v1/health`, same origin).

## Requirements

- Node.js 22.23 or newer
- npm (the committed `package-lock.json` is authoritative)

## Commands

| Command          | Purpose                                                   |
| ---------------- | --------------------------------------------------------- |
| `npm ci`         | Install the locked dependencies                           |
| `npm start`      | Development server on `http://localhost:4200`             |
| `npm run lint`   | ESLint (angular-eslint) and Prettier check                |
| `npm run format` | Apply Prettier formatting                                 |
| `npm test`       | Unit tests with Vitest (jsdom, single run)                |
| `npm run build`  | Production build into `dist/web/browser` (no source maps) |

## Notes

- Change detection is zoneless; `zone.js` is not a dependency.
- User-facing copy lives in `public/i18n/en.json` and `public/i18n/tr.json` and is loaded at
  runtime by Transloco from `/i18n/<lang>.json`. Both files must keep identical key sets; a unit
  test enforces this.
- Design tokens (spacing, type scale, semantic colours, light and dark themes) are defined in
  `src/styles/_tokens.scss`.
- The production `index.html` contains no inline scripts or event handlers (critical CSS
  inlining is disabled), so the app runs under the strict Content Security Policy served by
  nginx.
