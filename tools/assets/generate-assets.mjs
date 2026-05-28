import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const currentDir = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(currentDir, '..', '..');
const configPath = join(currentDir, 'assets.config.json');

// Keep generated paths fixed so the config controls content, not destinations.
const OUT = {
  brands: 'src/main/resources/static/images/brands',
  banners: 'src/main/resources/static/images/banners',
  categories: 'src/main/resources/static/images/categories'
};

const xmlEscape = (value) =>
  String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;');

const loadConfig = async () => JSON.parse(await readFile(configPath, 'utf8'));

const rel = (filePath) => relative(repoRoot, filePath);

// Idempotency: do not rewrite identical bytes on repeated runs.
const writeIfChanged = async (filePath, content) => {
  let existing = null;
  try {
    existing = await readFile(filePath, 'utf8');
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }

  if (existing === content) {
    console.log(`unchanged ${rel(filePath)}`);
    return;
  }

  await mkdir(dirname(filePath), { recursive: true });
  await writeFile(filePath, content, 'utf8');
  console.log(`wrote ${rel(filePath)}`);
};

// Each SVG is standalone and uses only text/shapes, so no external assets load.
const svg = (width, height, fontFamily, body) => `<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img" font-family="${xmlEscape(fontFamily)}">
${body}
</svg>
`;

const logoSvg = (brand, tokens) => {
  const name = xmlEscape(brand.name);
  const wordmark = xmlEscape(brand.wordmark);
  const fontSize = brand.wordmark.length > 9 ? 30 : 36;

  return svg(320, 160, tokens.fontFamily, `  <title>${name} demo brand logo</title>
  <rect width="320" height="160" rx="28" fill="${tokens.brandGreen}" opacity="0.14"/>
  <rect x="16" y="16" width="288" height="128" rx="24" fill="${tokens.white}" stroke="${tokens.lineGrey}" stroke-width="2"/>
  <circle cx="61" cy="58" r="28" fill="${tokens.brandGreen}" opacity="0.24"/>
  <circle cx="82" cy="93" r="18" fill="${tokens.darkGreen}" opacity="0.18"/>
  <path d="M249 43h26v26h-26z" fill="${tokens.brandGreen}" opacity="0.2"/>
  <path d="M263 90l22 22h-44z" fill="${tokens.saleRed}" opacity="0.13"/>
  <text x="160" y="81" text-anchor="middle" fill="${tokens.ink}" font-size="${fontSize}" font-weight="800" letter-spacing="0">${wordmark}</text>
  <text x="160" y="110" text-anchor="middle" fill="${tokens.grey}" font-size="17" font-weight="700" letter-spacing="0">${name}</text>`);
};

const bannerSvg = (banner, index, tokens) => {
  const title = xmlEscape(banner.title);
  const subtitle = xmlEscape(banner.subtitle);
  const tag = xmlEscape(banner.tag);
  const redAccent = index === 0 ? tokens.saleRed : tokens.brandGreen;
  const darkAccent = index === 2 ? tokens.saleRed : tokens.darkGreen;

  return svg(1200, 400, tokens.fontFamily, `  <title>${title} demo banner</title>
  <defs>
    <linearGradient id="soft" x1="0" x2="1" y1="0" y2="1">
      <stop offset="0" stop-color="${tokens.white}"/>
      <stop offset="1" stop-color="${tokens.softGrey}"/>
    </linearGradient>
  </defs>
  <rect width="1200" height="400" fill="url(#soft)"/>
  <rect x="0" y="0" width="1200" height="400" fill="${tokens.brandGreen}" opacity="0.09"/>
  <circle cx="1005" cy="82" r="112" fill="${tokens.brandGreen}" opacity="0.22"/>
  <circle cx="1092" cy="224" r="148" fill="${redAccent}" opacity="0.12"/>
  <rect x="823" y="236" width="254" height="74" rx="37" fill="${tokens.white}" stroke="${tokens.lineGrey}" stroke-width="2"/>
  <path d="M910 124c74 0 136 51 152 120H758c16-69 78-120 152-120z" fill="${tokens.white}" opacity="0.72"/>
  <path d="M807 224h207v18H807z" fill="${tokens.brandGreen}" opacity="0.35"/>
  <text x="92" y="118" fill="${darkAccent}" font-size="22" font-weight="800" letter-spacing="0">${tag}</text>
  <text x="92" y="199" fill="${tokens.ink}" font-size="58" font-weight="900" letter-spacing="0">${title}</text>
  <text x="96" y="254" fill="${tokens.grey}" font-size="27" font-weight="700" letter-spacing="0">${subtitle}</text>
  <rect x="96" y="295" width="190" height="10" rx="5" fill="${tokens.brandGreen}"/>
  <rect x="304" y="295" width="78" height="10" rx="5" fill="${tokens.saleRed}" opacity="0.85"/>`);
};

const icon = (kind, tokens) => {
  const fill = tokens.brandGreen;
  const stroke = tokens.darkGreen;
  const red = tokens.saleRed;
  const parts = {
    droplet: `<path d="M100 45c27 31 40 54 40 77 0 25-17 43-40 43s-40-18-40-43c0-23 13-46 40-77z" fill="${fill}" opacity="0.32"/><path d="M82 128c5 11 14 17 27 17" fill="none" stroke="${stroke}" stroke-width="8" stroke-linecap="round"/>`,
    sparkle: `<path d="M100 36l14 42 43 14-43 14-14 43-14-43-43-14 43-14z" fill="${fill}" opacity="0.34"/><circle cx="144" cy="55" r="12" fill="${red}" opacity="0.26"/><circle cx="59" cy="140" r="10" fill="${stroke}" opacity="0.2"/>`,
    wave: `<path d="M42 95c20-22 39-22 58 0s38 22 58 0" fill="none" stroke="${stroke}" stroke-width="12" stroke-linecap="round"/><path d="M42 126c20-22 39-22 58 0s38 22 58 0" fill="none" stroke="${fill}" stroke-width="12" stroke-linecap="round" opacity="0.72"/>`,
    bubble: `<circle cx="83" cy="103" r="36" fill="${fill}" opacity="0.3"/><circle cx="124" cy="77" r="23" fill="${red}" opacity="0.14"/><circle cx="129" cy="127" r="17" fill="${stroke}" opacity="0.18"/>`,
    mask: `<rect x="56" y="57" width="88" height="98" rx="28" fill="${fill}" opacity="0.28"/><path d="M78 92h44M80 122c12 10 28 10 40 0" fill="none" stroke="${stroke}" stroke-width="8" stroke-linecap="round"/>`,
    leaf: `<path d="M57 128c7-53 40-82 94-78-2 57-35 88-94 78z" fill="${fill}" opacity="0.34"/><path d="M72 120c24-21 48-39 72-55" fill="none" stroke="${stroke}" stroke-width="8" stroke-linecap="round"/><circle cx="69" cy="67" r="13" fill="${red}" opacity="0.18"/>`
  };

  return parts[kind] ?? parts.sparkle;
};

const categorySvg = (category, tokens) => {
  const label = xmlEscape(category.label);

  return svg(200, 200, tokens.fontFamily, `  <title>${label} demo category tile</title>
  <rect width="200" height="200" rx="24" fill="${tokens.white}"/>
  <rect width="200" height="200" rx="24" fill="${tokens.brandGreen}" opacity="0.12"/>
  <rect x="12" y="12" width="176" height="176" rx="22" fill="${tokens.white}" opacity="0.88" stroke="${tokens.lineGrey}" stroke-width="2"/>
  <circle cx="150" cy="48" r="28" fill="${tokens.brandGreen}" opacity="0.14"/>
  ${icon(category.icon, tokens)}
  <text x="100" y="178" text-anchor="middle" fill="${tokens.ink}" font-size="22" font-weight="800" letter-spacing="0">${label}</text>`);
};

// Fail early on accidental output collisions.
const assertUnique = (items, label) => {
  const slugs = new Set();
  for (const item of items) {
    if (slugs.has(item.slug)) throw new Error(`Duplicate ${label} slug: ${item.slug}`);
    slugs.add(item.slug);
  }
};

const main = async () => {
  const config = await loadConfig();
  const { tokens } = config;
  assertUnique(config.brands, 'brand');
  assertUnique(config.banners, 'banner');
  assertUnique(config.categories, 'category');

  for (const brand of config.brands) {
    await writeIfChanged(join(repoRoot, OUT.brands, `${brand.slug}.svg`), logoSvg(brand, tokens));
  }
  for (const [index, banner] of config.banners.entries()) {
    await writeIfChanged(join(repoRoot, OUT.banners, `${banner.slug}.svg`), bannerSvg(banner, index, tokens));
  }
  for (const category of config.categories) {
    await writeIfChanged(join(repoRoot, OUT.categories, `${category.slug}.svg`), categorySvg(category, tokens));
  }
};

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
