import { promises as fs } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const repoRoot = path.resolve(__dirname, '../..');
const inputMdPath = path.join(repoRoot, 'WHITEPAPER_LITE.md');
const outDir = path.join(repoRoot, 'docs', 'whitepaper');
const outHtmlPath = path.join(outDir, 'ChainAssassin-Litepaper-v1.html');
const outPdfPath = path.join(outDir, 'ChainAssassin-Litepaper-v1.pdf');

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
  let inCode = false;
  let codeLang = '';
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
    const line = rawLine;
    const trimmed = line.trim();

    if (inCode) {
      if (trimmed.startsWith('```')) {
        html.push('</code></pre>');
        inCode = false;
        codeLang = '';
      } else {
        html.push(`${escapeHtml(line)}\n`);
      }
      continue;
    }

    if (trimmed.startsWith('```')) {
      flushParagraph();
      closeList();
      codeLang = trimmed.slice(3).trim();
      const cls = codeLang ? ` class="code-block language-${escapeHtml(codeLang)}"` : ' class="code-block"';
      html.push(`<pre${cls}><code>`);
      inCode = true;
      continue;
    }

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
      const content = inlineMarkdown(headingMatch[2]);
      html.push(`<h${level}>${content}</h${level}>`);
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
  <title>Chain Assassin Litepaper</title>
  <style>
    @page {
      size: A4;
      margin: 16mm 14mm 18mm 14mm;
    }

    * { box-sizing: border-box; }

    body {
      margin: 0;
      font-family: "Inter", "Segoe UI", Roboto, sans-serif;
      color: #1b1e2e;
      background: #fff;
      line-height: 1.54;
      font-size: 11.2pt;
    }

    .cover {
      min-height: 247mm;
      border: 1px solid #dfe3ef;
      border-radius: 16px;
      padding: 28mm 20mm;
      background: radial-gradient(circle at top right, rgba(0, 255, 136, 0.08), transparent 45%), #fff;
      position: relative;
      page-break-after: always;
    }

    .logo {
      font-family: "JetBrains Mono", "SFMono-Regular", Menlo, monospace;
      letter-spacing: 2px;
      font-size: 11pt;
      margin-bottom: 16mm;
      color: #1e2238;
    }

    .logo b { color: #00b866; }

    .title {
      font-size: 30pt;
      line-height: 1.08;
      margin: 0;
      color: #11152a;
      font-weight: 800;
    }

    .subtitle {
      margin-top: 10mm;
      color: #444d70;
      font-size: 12.5pt;
      max-width: 110mm;
    }

    .meta {
      position: absolute;
      left: 20mm;
      bottom: 20mm;
      color: #566084;
      font-size: 10pt;
    }

    .pill {
      display: inline-block;
      margin-top: 8mm;
      padding: 4px 10px;
      border-radius: 999px;
      background: #ecfff6;
      border: 1px solid #b5f0d3;
      color: #107749;
      font-size: 9pt;
      font-weight: 700;
      letter-spacing: 0.3px;
      text-transform: uppercase;
    }

    .content {
      max-width: 180mm;
      margin: 0 auto;
    }

    h1, h2, h3, h4 {
      color: #131933;
      line-height: 1.25;
      margin: 14pt 0 8pt;
      page-break-after: avoid;
    }

    h1 { font-size: 21pt; margin-top: 0; }
    h2 {
      font-size: 15pt;
      margin-top: 22pt;
      padding-top: 8pt;
      border-top: 1px solid #e3e7f3;
    }
    h3 { font-size: 12.8pt; margin-top: 16pt; }
    h4 { font-size: 11.8pt; margin-top: 13pt; }

    p { margin: 7pt 0; color: #2a314f; }

    ul, ol {
      margin: 8pt 0 8pt 18pt;
      padding: 0;
      color: #2a314f;
    }

    li { margin: 4pt 0; }

    code {
      font-family: "JetBrains Mono", "SFMono-Regular", Menlo, monospace;
      font-size: 9.5pt;
      background: #eef2ff;
      border: 1px solid #d6def8;
      border-radius: 4px;
      padding: 0.06rem 0.28rem;
      color: #29315a;
    }

    .code-block {
      background: #0f1323;
      border: 1px solid #212841;
      border-radius: 10px;
      color: #e9eeff;
      padding: 10px 12px;
      overflow: hidden;
      white-space: pre-wrap;
      font-size: 8.8pt;
      line-height: 1.42;
      page-break-inside: avoid;
    }

    a { color: #0c57dd; text-decoration: none; }

    .footer {
      margin-top: 20pt;
      border-top: 1px solid #e3e7f3;
      padding-top: 8pt;
      color: #69739a;
      font-size: 8.9pt;
    }
  </style>
</head>
<body>
  <section class="cover">
    <div class="logo">CHAIN <b>ASSASSIN</b></div>
    <h1 class="title">Chain Assassin<br />Litepaper</h1>
    <div class="pill">Fast protocol overview</div>
    <p class="subtitle">A concise brief of architecture, trust model, economics, and roadmap for the Chain Assassin game protocol.</p>
    <div class="meta">
      Version 1.0<br />
      Generated: ${generatedAt}
    </div>
  </section>

  <main class="content">
    ${bodyHtml}
    <div class="footer">Chain Assassin Litepaper v1.0</div>
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
      margin: {
        top: '10mm',
        right: '8mm',
        bottom: '12mm',
        left: '8mm',
      },
      preferCSSPageSize: true,
    });
  } finally {
    await browser.close();
  }

  console.log(`HTML: ${outHtmlPath}`);
  console.log(`PDF:  ${outPdfPath}`);
}

main().catch((err) => {
  console.error('Failed to build litepaper PDF.');
  console.error(err);
  process.exit(1);
});
