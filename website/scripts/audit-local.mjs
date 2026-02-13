import { mkdir, writeFile } from "fs/promises";
import { dirname, resolve } from "path";
import { chromium } from "playwright";

const targetUrl = process.argv[2] || "http://127.0.0.1:5173";
const outJson = resolve(process.argv[3] || ".playwright/audit-report.json");
const outPng = resolve(process.argv[4] || ".playwright/audit-screenshot.png");
const settleMs = Number(process.env.AUDIT_WAIT_MS || "6000");

const report = {
  targetUrl,
  startedAt: new Date().toISOString(),
  finalUrl: "",
  title: "",
  navigation: {
    ok: false,
    status: null,
    statusText: null,
    error: null,
  },
  console: [],
  pageErrors: [],
  requestFailures: [],
  abortedRequests: [],
  httpErrors: [],
  apiCalls: {
    gamesList: 0,
    gameDetail: 0,
    gameDetailByPath: {},
  },
};

function pushLimited(arr, item, limit = 500) {
  if (arr.length < limit) arr.push(item);
}

const browser = await chromium.launch({ headless: true });
const context = await browser.newContext();
const page = await context.newPage();

page.on("console", (msg) => {
  pushLimited(report.console, {
    type: msg.type(),
    text: msg.text(),
    location: msg.location(),
  });
});

page.on("pageerror", (err) => {
  pushLimited(report.pageErrors, {
    name: err.name,
    message: err.message,
    stack: err.stack || "",
  });
});

page.on("requestfailed", (request) => {
  const failure = request.failure();
  const item = {
    method: request.method(),
    url: request.url(),
    failure,
    resourceType: request.resourceType(),
  };

  if (failure?.errorText === "net::ERR_ABORTED") {
    pushLimited(report.abortedRequests, item);
    return;
  }
  pushLimited(report.requestFailures, item);
});

page.on("request", (request) => {
  let pathname = "";
  try {
    pathname = new URL(request.url()).pathname;
  } catch {
    return;
  }

  if (pathname === "/api/games") {
    report.apiCalls.gamesList += 1;
    return;
  }

  if (/^\/api\/games\/\d+$/.test(pathname)) {
    report.apiCalls.gameDetail += 1;
    report.apiCalls.gameDetailByPath[pathname] = (report.apiCalls.gameDetailByPath[pathname] || 0) + 1;
  }
});

page.on("response", (response) => {
  if (response.status() >= 400) {
    pushLimited(report.httpErrors, {
      status: response.status(),
      statusText: response.statusText(),
      url: response.url(),
      method: response.request().method(),
      resourceType: response.request().resourceType(),
    });
  }
});

try {
  const response = await page.goto(targetUrl, { waitUntil: "networkidle", timeout: 30000 });
  report.navigation.ok = true;
  report.navigation.status = response ? response.status() : null;
  report.navigation.statusText = response ? response.statusText() : null;
} catch (err) {
  report.navigation.error = err instanceof Error ? err.message : String(err);
}

await page.waitForTimeout(settleMs);
report.finalUrl = page.url();
report.title = await page.title();
report.finishedAt = new Date().toISOString();

await mkdir(dirname(outJson), { recursive: true });
await writeFile(outJson, JSON.stringify(report, null, 2));
await mkdir(dirname(outPng), { recursive: true });
await page.screenshot({ path: outPng, fullPage: true });

await browser.close();

const summary = {
  navigationOk: report.navigation.ok,
  navigationStatus: report.navigation.status,
  consoleMessages: report.console.length,
  pageErrors: report.pageErrors.length,
  requestFailures: report.requestFailures.length,
  abortedRequests: report.abortedRequests.length,
  httpErrors: report.httpErrors.length,
  apiGamesListCalls: report.apiCalls.gamesList,
  apiGameDetailCalls: report.apiCalls.gameDetail,
  apiGameDetailByPath: report.apiCalls.gameDetailByPath,
  reportFile: outJson,
  screenshotFile: outPng,
};

console.log(JSON.stringify(summary, null, 2));
