import { promises as fs } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const repoRoot = path.resolve(__dirname, '../..');
const inputMdPath = path.join(repoRoot, 'ONEPAGER.md');
const outDir = path.join(repoRoot, 'docs', 'onepager');
const outHtmlPath = path.join(outDir, 'ChainAssassin-OnePager-v1.html');
const outPdfPath = path.join(outDir, 'ChainAssassin-OnePager-v1.pdf');

function escapeHtml(text) {
  return text
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

function inlineMarkdown(input) {
  let text = escapeHtml(input);

  text = text.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
  text = text.replace(/`([^`]+)`/g, '<code>$1</code>');
  text = text.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2">$1</a>');
  text = text.replace(/&lt;(https?:\/\/[^&]+)&gt;/g, '<a href="$1">$1</a>');

  return text;
}

function markdownToHtml(markdown) {
  const lines = markdown.replace(/\r\n/g, '\n').split('\n');
  const html = [];
  let listType = null;
  let paragraph = [];

  const flushParagraph = () => {
    if (paragraph.length > 0) {
      html.push(`<p>${inlineMarkdown(paragraph.join(' '))}</p>`);
      paragraph = [];
    }
  };

  const closeList = () => {
    if (listType) {
      html.push(listType === 'ol' ? '</ol>' : '</ul>');
      listType = null;
    }
  };

  for (const rawLine of lines) {
    const trimmed = rawLine.trim();

    if (!trimmed) {
      flushParagraph();
      closeList();
      continue;
    }

    const headingMatch = /^(#{1,6})\s+(.+)$/.exec(trimmed);
    if (headingMatch) {
      flushParagraph();
      closeList();
      const level = headingMatch[1].length;
      html.push(`<h${level}>${inlineMarkdown(headingMatch[2])}</h${level}>`);
      continue;
    }

    const olMatch = /^\d+\.\s+(.+)$/.exec(trimmed);
    if (olMatch) {
      flushParagraph();
      if (listType !== 'ol') {
        closeList();
        html.push('<ol>');
        listType = 'ol';
      }
      html.push(`<li>${inlineMarkdown(olMatch[1])}</li>`);
      continue;
    }

    const ulMatch = /^-\s+(.+)$/.exec(trimmed);
    if (ulMatch) {
      flushParagraph();
      if (listType !== 'ul') {
        closeList();
        html.push('<ul>');
        listType = 'ul';
      }
      html.push(`<li>${inlineMarkdown(ulMatch[1])}</li>`);
      continue;
    }

    paragraph.push(trimmed);
  }

  flushParagraph();
  closeList();

  return html.join('\n');
}

function wrapHtml(bodyHtml) {
  const generatedAt = new Date().toISOString().replace('T', ' ').replace('Z', ' UTC');

  return `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Chain Assassin One-Pager</title>
  <style>
    @page {
      size: A4;
      margin: 10mm;
    }

    * { box-sizing: border-box; }

    body {
      margin: 0;
      font-family: "Inter", "Segoe UI", Roboto, sans-serif;
      color: #151a2f;
      background: #ffffff;
      font-size: 10.25pt;
      line-height: 1.36;
    }

    .sheet {
      width: 100%;
      min-height: 100%;
      border: 1px solid #d8dfef;
      border-radius: 12px;
      padding: 10mm;
      background:
        radial-gradient(circle at top right, rgba(0, 255, 136, 0.09), transparent 45%),
        #ffffff;
    }

    .top {
      display: flex;
      justify-content: space-between;
      align-items: baseline;
      border-bottom: 1px solid #e4e9f5;
      padding-bottom: 8px;
      margin-bottom: 10px;
    }

    .brand {
      font-family: "JetBrains Mono", "SFMono-Regular", Menlo, monospace;
      font-size: 9.8pt;
      letter-spacing: 1.5px;
      color: #1d2441;
    }

    .brand b { color: #00a861; }

    .stamp {
      color: #5d6a92;
      font-size: 8.3pt;
    }

    h1, h2, h3, h4 {
      margin: 0 0 5px;
      line-height: 1.22;
      page-break-after: avoid;
      color: #131a35;
    }

    h1 { font-size: 17pt; margin-bottom: 7px; }
    h2 {
      font-size: 11pt;
      margin-top: 10px;
      padding-top: 5px;
      border-top: 1px solid #e8ecf7;
    }
    h3 { font-size: 10.2pt; }
    h4 { font-size: 9.7pt; }

    p { margin: 0 0 6px; color: #2c3559; }

    ul, ol {
      margin: 0 0 6px 16px;
      padding: 0;
      color: #2c3559;
    }

    li { margin: 2px 0; }

    strong { color: #1b2344; }

    code {
      font-family: "JetBrains Mono", "SFMono-Regular", Menlo, monospace;
      font-size: 8.7pt;
      background: #edf1fd;
      border: 1px solid #d9e1f7;
      border-radius: 4px;
      padding: 0.06rem 0.26rem;
      color: #2a3768;
    }

    a {
      color: #0d57d6;
      text-decoration: none;
    }
  </style>
</head>
<body>
  <main class="sheet">
    <div class="top">
      <div class="brand">CHAIN <b>ASSASSIN</b></div>
      <div class="stamp">One-Pager v1.0 · ${generatedAt}</div>
    </div>
    ${bodyHtml}
  </main>
</body>
</html>`;
}

async function main() {
  await fs.mkdir(outDir, { recursive: true });

  const markdown = await fs.readFile(inputMdPath, 'utf8');
  const bodyHtml = markdownToHtml(markdown);
  const html = wrapHtml(bodyHtml);
  await fs.writeFile(outHtmlPath, html, 'utf8');

  const browser = await chromium.launch({ headless: true });
  try {
    const page = await browser.newPage();
    await page.goto(`file://${outHtmlPath}`, { waitUntil: 'networkidle' });
    await page.pdf({
      path: outPdfPath,
      format: 'A4',
      printBackground: true,
      preferCSSPageSize: true,
    });
  } finally {
    await browser.close();
  }

  console.log(`HTML: ${outHtmlPath}`);
  console.log(`PDF:  ${outPdfPath}`);
}

main().catch((err) => {
  console.error('Failed to build one-pager PDF.');
  console.error(err);
  process.exit(1);
});
