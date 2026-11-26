interface Env {
  DOWNLOAD_KEY: string;
  APK_BUCKET: R2Bucket;
  R2_APK_KEY?: string;
  R2_METADATA_KEY?: string;
  APP_TITLE?: string;
}

interface ReleaseMetadata {
  tagName?: string;
  versionName?: string;
  versionCode?: number;
  updateLog?: string;
  downloadUrl?: string;
  fileName?: string;
  size?: number;
  sha256?: string;
  contentType?: string;
  updatedAt?: string;
}

const DEFAULT_APK_KEY = "legado-arm64-v8a.apk";
const DEFAULT_METADATA_KEY = "latest.json";
const APK_CONTENT_TYPE = "application/vnd.android.package-archive";
const VERSION_PAIR_REGEX = /(\d+(?:\.\d+)+)_(\d+)/;
const JSON_HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
};

class GateError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

interface KeyVerificationResult {
  ok: boolean;
  status: number;
  error: string;
}

let cachedMetadata: { value: ReleaseMetadata; expiresAt: number } | null = null;

// shortcut: Rate limiting for /latest and /download is delegated to Cloudflare WAF
// Rate Limiting rules configured in the Cloudflare dashboard in front of this Worker.
export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/") {
      return htmlResponse(renderHome(env));
    }

    if (request.method === "GET" && url.pathname === "/health") {
      return jsonResponse({ ok: true });
    }

    if (request.method === "GET" && url.pathname === "/latest") {
      return handleLatest(request, env);
    }

    if (
      (request.method === "POST" || request.method === "GET" || request.method === "HEAD") &&
      url.pathname === "/download"
    ) {
      return handleDownload(request, env);
    }

    return new Response("Not found", { status: 404 });
  },
};

async function handleLatest(request: Request, env: Env): Promise<Response> {
  if (!env.DOWNLOAD_KEY) {
    return jsonResponse({ error: "Worker secret DOWNLOAD_KEY is required." }, 500);
  }

  const auth = await verifyDownloadKey(request, env);
  if (!auth.ok) {
    return jsonResponse({ error: auth.error }, auth.status);
  }

  let metadata: ReleaseMetadata;
  try {
    metadata = await getReleaseMetadata(env);
  } catch (error) {
    if (error instanceof GateError) {
      return jsonResponse({ error: error.message }, error.status);
    }
    return jsonResponse({ error: "Failed to load release metadata." }, 502);
  }

  const downloadUrl = new URL(request.url);
  downloadUrl.pathname = "/download";
  downloadUrl.search = "";

  return jsonResponse({
    ...metadata,
    downloadUrl: downloadUrl.toString(),
  });
}

async function handleDownload(request: Request, env: Env): Promise<Response> {
  if (!env.DOWNLOAD_KEY) {
    return jsonResponse({ error: "Worker secret DOWNLOAD_KEY is required." }, 500);
  }

  const auth = await verifyDownloadKey(request, env);
  if (!auth.ok) {
    if (wantsJsonResponse(request)) {
      return jsonResponse({ error: auth.error }, auth.status);
    }
    return htmlResponse(renderHome(env, auth.error), auth.status);
  }

  const apkKey = env.R2_APK_KEY?.trim() || DEFAULT_APK_KEY;
  const metadata = await getReleaseMetadata(env).catch(() => undefined);
  const fileName = sanitizeFilename(metadata?.fileName || apkKey.split("/").pop() || DEFAULT_APK_KEY);

  if (request.method === "HEAD") {
    let headObject: R2Object | null;
    try {
      headObject = await env.APK_BUCKET.head(apkKey);
    } catch {
      return jsonResponse({ error: "Failed to read APK metadata from R2." }, 502);
    }
    if (!headObject) {
      return jsonResponse({ error: "APK object not found." }, 404);
    }
    return new Response(null, {
      status: 200,
      headers: {
        "content-type": headObject.httpMetadata?.contentType || APK_CONTENT_TYPE,
        "content-disposition": `attachment; filename="${fileName}"`,
        "accept-ranges": "bytes",
        "cache-control": "private, no-store",
        "x-content-type-options": "nosniff",
        ...(headObject.size > 0 ? { "content-length": String(headObject.size) } : {}),
      },
    });
  }

  const rangeHeader = request.headers.get("range");
  let object: R2ObjectBody | null;
  try {
    object = await env.APK_BUCKET.get(
      apkKey,
      rangeHeader ? { range: request.headers } : undefined,
    );
  } catch {
    return jsonResponse({ error: "Failed to read APK object from R2." }, 502);
  }
  if (!object?.body) {
    return jsonResponse({ error: "APK object not found." }, 404);
  }

  // Metadata may describe an older release, even if both APKs have the same size.
  // Response framing must describe this object; unbound release hashes/tags cannot.
  const totalSize = object.size;
  let status = 200;
  let contentLength = totalSize;
  let contentRange: string | undefined;

  if (rangeHeader && object.range) {
    const range = object.range as { offset?: number; length?: number; suffix?: number };
    let start: number | undefined;
    let length: number | undefined;
    if (typeof range.offset === "number" && typeof range.length === "number") {
      start = range.offset;
      length = range.length;
    } else if (typeof range.suffix === "number" && totalSize > 0) {
      length = Math.min(range.suffix, totalSize);
      start = totalSize - length;
    }
    if (start !== undefined && length !== undefined && length > 0 && totalSize > 0) {
      const end = start + length - 1;
      status = 206;
      contentLength = length;
      contentRange = `bytes ${start}-${end}/${totalSize}`;
    }
  }

  return new Response(object.body, {
    status,
    headers: {
      "content-type": object.httpMetadata?.contentType || APK_CONTENT_TYPE,
      "content-disposition": `attachment; filename="${fileName}"`,
      "accept-ranges": "bytes",
      "cache-control": "private, no-store",
      "x-content-type-options": "nosniff",
      ...(contentRange ? { "content-range": contentRange } : {}),
      ...(contentLength > 0 ? { "content-length": String(contentLength) } : {}),
    },
  });
}

async function getReleaseMetadata(env: Env): Promise<ReleaseMetadata> {
  const now = Date.now();
  if (cachedMetadata && cachedMetadata.expiresAt > now) {
    return cachedMetadata.value;
  }

  const apkKey = env.R2_APK_KEY?.trim() || DEFAULT_APK_KEY;
  const metadataKey = env.R2_METADATA_KEY?.trim() || DEFAULT_METADATA_KEY;

  let metadataObject: R2ObjectBody | null;
  try {
    metadataObject = await env.APK_BUCKET.get(metadataKey);
  } catch {
    throw new GateError(502, "Failed to read release metadata from R2.");
  }

  if (metadataObject) {
    let metadata: ReleaseMetadata;
    try {
      metadata = (await metadataObject.json()) as ReleaseMetadata;
    } catch {
      throw new GateError(502, "Invalid release metadata JSON.");
    }
    if (!metadata || typeof metadata !== "object" || Array.isArray(metadata)) {
      throw new GateError(502, "Invalid release metadata JSON.");
    }
    const normalized = normalizeMetadata(metadata, apkKey);
    cachedMetadata = { value: normalized, expiresAt: now + 15_000 };
    return normalized;
  }

  let apkObject: R2Object | null;
  try {
    apkObject = await env.APK_BUCKET.head(apkKey);
  } catch {
    throw new GateError(502, "Failed to read APK metadata from R2.");
  }
  if (!apkObject) {
    throw new GateError(404, `R2 object not found: ${apkKey}`);
  }

  const normalized = normalizeMetadata(
    {
      fileName: apkKey.split("/").pop() || DEFAULT_APK_KEY,
      size: apkObject.size,
      contentType: apkObject.httpMetadata?.contentType || APK_CONTENT_TYPE,
      updatedAt: apkObject.uploaded?.toISOString(),
    },
    apkKey,
  );
  cachedMetadata = { value: normalized, expiresAt: now + 15_000 };
  return normalized;
}

function normalizeMetadata(metadata: ReleaseMetadata, apkKey: string): ReleaseMetadata {
  const fileName = metadata.fileName || apkKey.split("/").pop() || DEFAULT_APK_KEY;
  return {
    tagName: metadata.tagName || "",
    versionName: metadata.versionName || versionNameFromFileName(fileName) || "",
    versionCode: Number.isFinite(metadata.versionCode) ? metadata.versionCode : versionCodeFromFileName(fileName),
    updateLog: metadata.updateLog || (metadata.tagName ? `Private release ${metadata.tagName}` : "Private release"),
    fileName,
    size: metadata.size || 0,
    sha256: metadata.sha256 || "",
    contentType: metadata.contentType || APK_CONTENT_TYPE,
    updatedAt: metadata.updatedAt || "",
  };
}

function constantTimeEqual(left: string, right: string): boolean {
  const encoder = new TextEncoder();
  const leftBytes = encoder.encode(left);
  const rightBytes = encoder.encode(right);
  const maxLength = Math.max(leftBytes.length, rightBytes.length);
  let diff = leftBytes.length ^ rightBytes.length;

  for (let index = 0; index < maxLength; index += 1) {
    diff |= (leftBytes[index] ?? 0) ^ (rightBytes[index] ?? 0);
  }

  return diff === 0;
}

function wantsJsonResponse(request: Request): boolean {
  if (request.headers.has("x-download-key")) {
    return true;
  }
  const accept = (request.headers.get("accept") || "").toLowerCase();
  return accept.includes("application/json");
}

async function verifyDownloadKey(request: Request, env: Env): Promise<KeyVerificationResult> {
  const headerKey = request.headers.get("x-download-key");
  if (headerKey !== null) {
    return constantTimeEqual(headerKey, env.DOWNLOAD_KEY)
      ? { ok: true, status: 200, error: "" }
      : { ok: false, status: 401, error: "Invalid download key." };
  }

  if (request.method !== "POST") {
    return { ok: false, status: 401, error: "Invalid download key." };
  }

  const contentType = (request.headers.get("content-type") || "").split(";")[0].trim().toLowerCase();
  let providedKey = "";

  if (contentType === "application/json") {
    try {
      const body = (await request.json()) as { key?: unknown } | null;
      if (!body || typeof body !== "object" || Array.isArray(body)) {
        return { ok: false, status: 400, error: "Invalid JSON request body." };
      }
      providedKey = typeof body.key === "string" ? body.key : "";
    } catch {
      return { ok: false, status: 400, error: "Invalid JSON request body." };
    }
  } else if (
    contentType === "application/x-www-form-urlencoded" ||
    contentType === "multipart/form-data"
  ) {
    try {
      const form = await request.formData();
      const rawKey = form.get("key");
      providedKey = typeof rawKey === "string" ? rawKey : "";
    } catch {
      return { ok: false, status: 400, error: "Invalid form request body." };
    }
  } else {
    return { ok: false, status: 400, error: "Unsupported Content-Type." };
  }

  return constantTimeEqual(providedKey, env.DOWNLOAD_KEY)
    ? { ok: true, status: 200, error: "" }
    : { ok: false, status: 401, error: "Invalid download key." };
}

function versionNameFromFileName(name: string): string | null {
  return VERSION_PAIR_REGEX.exec(name)?.[1] ?? null;
}

function versionCodeFromFileName(name: string): number {
  const raw = VERSION_PAIR_REGEX.exec(name)?.[2];
  return raw ? Number.parseInt(raw, 10) || 0 : 0;
}

function sanitizeFilename(name: string): string {
  return name.replace(/["\\\r\n]/g, "_");
}

function htmlResponse(body: string, status = 200): Response {
  return new Response(body, {
    status,
    headers: {
      "content-type": "text/html; charset=utf-8",
      "cache-control": "no-store",
      "x-content-type-options": "nosniff",
      "referrer-policy": "no-referrer",
    },
  });
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: JSON_HEADERS,
  });
}

function renderHome(env: Env, error?: string): string {
  const title = escapeHtml(env.APP_TITLE || "Private APK Download");
  const apkKey = escapeHtml(env.R2_APK_KEY?.trim() || DEFAULT_APK_KEY);
  const errorHtml = error ? `<p class="error">${escapeHtml(error)}</p>` : "";

  return `<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>${title}</title>
    <style>
      :root {
        color-scheme: light dark;
        font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
      }
      body {
        min-height: 100vh;
        margin: 0;
        display: grid;
        place-items: center;
        background: #f4f5f7;
        color: #16181d;
      }
      main {
        width: min(420px, calc(100vw - 32px));
        box-sizing: border-box;
        padding: 28px;
        border: 1px solid #d9dde5;
        border-radius: 8px;
        background: #ffffff;
        box-shadow: 0 16px 48px rgb(20 24 32 / 10%);
      }
      h1 {
        margin: 0 0 8px;
        font-size: 22px;
        line-height: 1.25;
        letter-spacing: 0;
      }
      .meta {
        margin: 0 0 24px;
        color: #5c6675;
        font-size: 13px;
        line-height: 1.5;
        overflow-wrap: anywhere;
      }
      label {
        display: block;
        margin-bottom: 8px;
        font-size: 14px;
        font-weight: 600;
      }
      input {
        width: 100%;
        height: 44px;
        box-sizing: border-box;
        border: 1px solid #b9c0cc;
        border-radius: 6px;
        padding: 0 12px;
        font: inherit;
        background: transparent;
      }
      button {
        width: 100%;
        height: 44px;
        margin-top: 16px;
        border: 0;
        border-radius: 6px;
        background: #1565c0;
        color: #fff;
        font: inherit;
        font-weight: 700;
        cursor: pointer;
      }
      button:hover {
        background: #0d56a8;
      }
      .error {
        margin: 0 0 16px;
        padding: 10px 12px;
        border-radius: 6px;
        background: #feecec;
        color: #a11212;
        font-size: 14px;
      }
      @media (prefers-color-scheme: dark) {
        body {
          background: #101318;
          color: #f2f4f8;
        }
        main {
          border-color: #303846;
          background: #181d24;
          box-shadow: none;
        }
        .meta {
          color: #aab4c2;
        }
        input {
          border-color: #586274;
          color: #f2f4f8;
        }
        .error {
          background: #3a1717;
          color: #ffb8b8;
        }
      }
    </style>
  </head>
  <body>
    <main>
      <h1>${title}</h1>
      <p class="meta">R2 object: ${apkKey}</p>
      ${errorHtml}
      <form method="post" action="/download">
        <label for="key">Download key</label>
        <input id="key" name="key" type="password" autocomplete="current-password" required autofocus>
        <button type="submit">Download ARM64 APK</button>
      </form>
    </main>
  </body>
</html>`;
}

function escapeHtml(value: string): string {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}
