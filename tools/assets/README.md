# Demo Asset Generator

Generate deterministic, offline SVG assets for the self-hostable storefront demo.

## Run

```bash
node tools/assets/generate-assets.mjs
```

The script reads `tools/assets/assets.config.json` and writes:

- Brand logos to `src/main/resources/static/images/brands/<slug>.svg`
- Promo banners to `src/main/resources/static/images/banners/<slug>.svg`
- Category tiles to `src/main/resources/static/images/categories/<slug>.svg`

It uses only Node.js built-ins. It makes no network calls, uses no npm packages,
and writes the same bytes for the same config. Existing files are only rewritten
when their generated content changes.

## Extend

Add an object to one of the arrays in `assets.config.json`:

- `brands`: use a real seeded catalog `name` and `slug` from the Flyway seed SQL.
  `wordmark` controls the generic SVG text style; it must not copy a real logo.
- `banners`: keep titles generic and avoid real-brand impersonation.
- `categories`: choose a stable `slug`, Korean `label`, and one of the supported
  geometric icons: `droplet`, `sparkle`, `wave`, `bubble`, `mask`, `leaf`.

Then rerun the command above and review the generated SVG diff.
