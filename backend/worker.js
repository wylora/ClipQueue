/**
 * Минимальный бэкенд OAuth для ClipQueue (Cloudflare Workers, бесплатный тариф).
 * Хранит client_secret на сервере — в APK его быть не должно.
 *
 * Переменные окружения (wrangler secret put ...):
 *   TIKTOK_CLIENT_KEY, TIKTOK_CLIENT_SECRET
 *
 * Маршруты:
 *   GET  /callback  — redirect_uri для TikTok; возвращает пользователя в приложение
 *   POST /token     — {code, redirect_uri} → токены TikTok
 *   POST /refresh   — {refresh_token}      → новые токены TikTok
 *   POST /revoke    — {token}              → отзыв доступа приложения к аккаунту
 */
const TOKEN_URL = "https://open.tiktokapis.com/v2/oauth/token/";
const REVOKE_URL = "https://open.tiktokapis.com/v2/oauth/revoke/";

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/callback") {
      const target = new URL("clipqueue://oauth");
      for (const k of ["code", "state", "error", "error_description"]) {
        const v = url.searchParams.get(k);
        if (v) target.searchParams.set(k, v);
      }
      const href = target.toString().replace(/"/g, "&quot;");
      return new Response(
        `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ClipQueue</title>
<body style="font-family:sans-serif;padding:32px;text-align:center">
<p>Вход в TikTok выполнен.</p>
<p><a href="${href}" style="font-size:18px">Вернуться в ClipQueue</a></p>
<script>location.href=${JSON.stringify(target.toString())}</script>
</body>`,
        { headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" } }
      );
    }

    if (request.method === "POST" && url.pathname === "/revoke") {
      let body;
      try { body = await request.json(); } catch { return json({ error: "bad_json" }, 400); }
      if (!body.token) return json({ error: "missing_token" }, 400);
      const r = await fetch(REVOKE_URL, {
        method: "POST",
        headers: { "content-type": "application/x-www-form-urlencoded", "cache-control": "no-cache" },
        body: new URLSearchParams({
          client_key: env.TIKTOK_CLIENT_KEY,
          client_secret: env.TIKTOK_CLIENT_SECRET,
          token: body.token,
        }),
      });
      return new Response(await r.text() || "{}", {
        status: r.status,
        headers: { "content-type": "application/json", "cache-control": "no-store" },
      });
    }

    if (request.method === "POST" && (url.pathname === "/token" || url.pathname === "/refresh")) {
      let body;
      try { body = await request.json(); } catch { return json({ error: "bad_json" }, 400); }
      const form = new URLSearchParams({
        client_key: env.TIKTOK_CLIENT_KEY,
        client_secret: env.TIKTOK_CLIENT_SECRET,
      });
      if (url.pathname === "/token") {
        if (!body.code) return json({ error: "missing_code" }, 400);
        form.set("grant_type", "authorization_code");
        form.set("code", body.code);
        form.set("redirect_uri", `${url.origin}/callback`);
      } else {
        if (!body.refresh_token) return json({ error: "missing_refresh_token" }, 400);
        form.set("grant_type", "refresh_token");
        form.set("refresh_token", body.refresh_token);
      }
      const r = await fetch(TOKEN_URL, {
        method: "POST",
        headers: { "content-type": "application/x-www-form-urlencoded", "cache-control": "no-cache" },
        body: form,
      });
      return new Response(await r.text(), {
        status: r.status,
        headers: { "content-type": "application/json", "cache-control": "no-store" },
      });
    }

    return json({ error: "not_found" }, 404);
  },
};

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json" } });
}
