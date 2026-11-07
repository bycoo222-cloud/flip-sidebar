// Accounts (2026-11-07, user: "build accounts" — like Flipping Copilot: log in from the sidebar, it opens the website, log
// in there, and a private page shows every character's flips). Discord login, scope "identify": the Discord id, name and
// avatar — never an email, never a password.
//   POST   /v1/auth/start                   the plugin: {login, secret, url}; it opens url in the browser and polls
//   GET    /v1/auth/poll?login=&secret=     {pending:true} | {token, name} once (then forgotten) | 410 gone/expired
//   GET    /v1/auth/web                     the website's Log in button: straight to Discord
//   GET    /v1/auth/discord                 Discord's redirect back (code, state) → SITE/account#token=...
//   POST   /v1/auth/logout                  (Bearer) ends this login
//   GET    /v1/me                           (Bearer) {name, avatar, chars:[{tag, name, seen}]}
//   GET    /v1/me/flips                     (Bearer) {flips:[[tag, item, qty, buy, sell, profit, bought, sold], ...]}
//   POST   /v1/me/flips                     (Bearer, the plugin) {"v":1,"tag","who","flips":[[id, item, qty, buy, sell,
//                                           profit, bought, sold], ...]} — every finished flip, while logged in
//   DELETE /v1/me                           (Bearer) the account and everything stored for it
// Tokens are 32 random bytes; the database keeps only their SHA-256 (a stolen copy of the database logs no one in).
// Needs the Worker secret DISCORD_SECRET (the Discord application's OAuth2 client secret) and REDIRECT registered there.

const SITE = "https://flip-sidebar.pages.dev";
const CLIENT_ID = "1568660423266410567";            // the "flip sidebar" Discord application
const REDIRECT = "https://flip-sidebar-team.bycoo222.workers.dev/v1/auth/discord";
const LOGIN_MS = 10 * 60 * 1000;                    // a started login lasts 10 minutes
const WEB_IDLE_MS = 90 * 86400000;                  // a website login unused for 90 days ends
const SEEN_EVERY_MS = 86400000;                     // "last used" written at most once a day (a write saved)
const MAX_ROWS = 500;                               // flips in one upload
const FLIPS_SHOWN = 20000;                          // flips handed to the page, newest first
const ORIGINS = [SITE, "http://127.0.0.1:8799"];    // the site (and its local copy while it's being worked on)

export function cors(req) {
  const o = req.headers.get("Origin") || "";
  const ok = ORIGINS.includes(o) || /^https:\/\/[0-9a-f]{8}\.flip-sidebar\.pages\.dev$/.test(o);   // + preview builds
  return {
    "Access-Control-Allow-Origin": ok ? o : SITE, "Access-Control-Allow-Headers": "Authorization, Content-Type",
    "Access-Control-Allow-Methods": "GET, POST, DELETE, OPTIONS", "Access-Control-Max-Age": "86400", "Vary": "Origin",
  };
}

const enc = new TextEncoder();
function hex(bytes) {
  return [...bytes].map(b => b.toString(16).padStart(2, "0")).join("");
}
function random(n) {
  return hex(crypto.getRandomValues(new Uint8Array(n)));
}
async function sha(s) {
  return hex(new Uint8Array(await crypto.subtle.digest("SHA-256", enc.encode(s))));
}
const num = (x, lo, hi) => typeof x === "number" && Number.isFinite(x) && x >= lo && x <= hi;

function out(req, o, status) {
  return new Response(JSON.stringify(o), { status, headers: { "content-type": "application/json", ...cors(req) } });
}

// a small page for the browser tab the login happens in
function page(title, body, status) {
  const html = `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${title} · Flip Sidebar</title><style>body{margin:0;min-height:100vh;display:grid;place-items:center;background:#0d1213;color:#e6fff6;
font:16px system-ui,sans-serif}main{max-width:440px;padding:28px;text-align:center}h1{font-size:22px}a{color:#9effd6}p{color:#b9d6cf;line-height:1.5}</style>
</head><body><main><h1>${title}</h1><p>${body}</p><p><a href="${SITE}">flip-sidebar.pages.dev</a></p></main></body></html>`;
  return new Response(html, { status: status || 200, headers: { "content-type": "text/html; charset=utf-8" } });
}

function discordUrl(state) {
  return "https://discord.com/oauth2/authorize?client_id=" + CLIENT_ID + "&response_type=code&scope=identify"
    + "&redirect_uri=" + encodeURIComponent(REDIRECT) + "&state=" + state + "&prompt=none";
}

// ---- logging in ----

export async function start(req, env) {
  const now = Date.now(), id = random(16), secret = random(32);
  if (Math.random() < 0.1) await env.DB.prepare("DELETE FROM logins WHERE created < ?").bind(now - LOGIN_MS).run();
  await env.DB.prepare("INSERT INTO logins (id, secret, kind, created) VALUES (?, ?, 'plugin', ?)")
    .bind(id, await sha(secret), now).run();
  return out(req, { login: id, secret, url: discordUrl(id) }, 200);
}

export async function web(req, env) {
  const id = random(16);
  await env.DB.prepare("INSERT INTO logins (id, secret, kind, created) VALUES (?, '', 'web', ?)").bind(id, Date.now()).run();
  return Response.redirect(discordUrl(id), 302);
}

export async function poll(req, url, env) {
  const id = url.searchParams.get("login") || "", secret = url.searchParams.get("secret") || "";
  if (!/^[0-9a-f]{32}$/.test(id) || !/^[0-9a-f]{64}$/.test(secret)) return out(req, { error: "bad request" }, 400);
  const row = await env.DB.prepare("SELECT secret, created, token, name FROM logins WHERE id = ? AND kind = 'plugin'").bind(id).first();
  if (!row) return out(req, { gone: true }, 410);
  if (row.secret !== await sha(secret)) return out(req, { error: "wrong secret" }, 403);
  if (Date.now() - row.created > LOGIN_MS) {
    await env.DB.prepare("DELETE FROM logins WHERE id = ?").bind(id).run();
    return out(req, { gone: true }, 410);
  }
  if (!row.token) return out(req, { pending: true }, 200);
  await env.DB.prepare("DELETE FROM logins WHERE id = ?").bind(id).run();   // handed out once
  return out(req, { token: row.token, name: row.name }, 200);
}

async function newSession(env, user, kind, now) {
  const token = random(32);
  await env.DB.prepare("INSERT INTO sessions (token, user, kind, created, seen) VALUES (?, ?, ?, ?, ?)")
    .bind(await sha(token), user, kind, now, now).run();
  return token;
}

export async function discord(req, url, env) {
  const state = url.searchParams.get("state") || "", code = url.searchParams.get("code") || "";
  const now = Date.now();
  const login = /^[0-9a-f]{32}$/.test(state)
    ? await env.DB.prepare("SELECT kind, created FROM logins WHERE id = ?").bind(state).first() : null;
  if (!login || now - login.created > LOGIN_MS) {
    return page("That login link has expired", "Start again: click Log in in the Flip Sidebar panel, or on the website.", 400);
  }
  if (url.searchParams.get("error") || !code) {
    await env.DB.prepare("DELETE FROM logins WHERE id = ?").bind(state).run();
    return page("Login cancelled", "Nothing was changed. You can close this tab.", 200);
  }
  const tr = await fetch("https://discord.com/api/oauth2/token", {
    method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ client_id: CLIENT_ID, client_secret: env.DISCORD_SECRET || "", grant_type: "authorization_code",
      code, redirect_uri: REDIRECT }),
  });
  if (!tr.ok) return page("Discord didn't confirm the login", "Please try again in a minute.", 502);
  const access = (await tr.json()).access_token;
  const ur = await fetch("https://discord.com/api/users/@me", { headers: { Authorization: "Bearer " + access } });
  if (!ur.ok) return page("Discord didn't confirm the login", "Please try again in a minute.", 502);
  const d = await ur.json();
  const name = String(d.global_name || d.username || "you").slice(0, 40);
  const avatar = d.avatar ? `https://cdn.discordapp.com/avatars/${d.id}/${d.avatar}.png?size=64` : "";
  const u = await env.DB.prepare("INSERT INTO users (discord, name, avatar, created) VALUES (?, ?, ?, ?) ON CONFLICT (discord) "
    + "DO UPDATE SET name = excluded.name, avatar = excluded.avatar RETURNING id").bind(String(d.id), name, avatar, now).first();
  const webToken = await newSession(env, u.id, "web", now);
  if (login.kind === "plugin") {
    // the plugin's own login, picked up by its next poll; this browser is logged in to the website too
    const pluginToken = await newSession(env, u.id, "plugin", now);
    await env.DB.prepare("UPDATE logins SET token = ?, name = ? WHERE id = ?").bind(pluginToken, name, state).run();
  } else {
    await env.DB.prepare("DELETE FROM logins WHERE id = ?").bind(state).run();
  }
  return Response.redirect(SITE + "/account#token=" + webToken + (login.kind === "plugin" ? "&linked=1" : ""), 302);
}

// ---- who is asking ----

async function auth(req, env) {
  const h = req.headers.get("Authorization") || "";
  const m = /^Bearer ([0-9a-f]{64})$/.exec(h);
  if (!m) return null;
  const hash = await sha(m[1]), now = Date.now();
  const s = await env.DB.prepare("SELECT user, kind, seen FROM sessions WHERE token = ?").bind(hash).first();
  if (!s) return null;
  if (s.kind === "web" && now - s.seen > WEB_IDLE_MS) {
    await env.DB.prepare("DELETE FROM sessions WHERE token = ?").bind(hash).run();
    return null;
  }
  if (now - s.seen > SEEN_EVERY_MS) await env.DB.prepare("UPDATE sessions SET seen = ? WHERE token = ?").bind(now, hash).run();
  return { user: s.user, kind: s.kind, hash };
}

export async function logout(req, env) {
  const a = await auth(req, env);
  if (a) await env.DB.prepare("DELETE FROM sessions WHERE token = ?").bind(a.hash).run();
  return out(req, { ok: true }, 200);
}

export async function me(req, env) {
  const a = await auth(req, env);
  if (!a) return out(req, { error: "not logged in" }, 401);
  const u = await env.DB.prepare("SELECT name, avatar FROM users WHERE id = ?").bind(a.user).first();
  const { results } = await env.DB.prepare("SELECT tag, name, seen FROM chars WHERE user = ? ORDER BY seen DESC").bind(a.user).all();
  return out(req, { name: u ? u.name : "", avatar: u ? u.avatar : "", chars: results }, 200);
}

export async function myFlips(req, env) {
  const a = await auth(req, env);
  if (!a) return out(req, { error: "not logged in" }, 401);
  const { results } = await env.DB.prepare("SELECT f.tag, f.item, f.qty, f.buy, f.sell, f.profit, f.bought, f.sold FROM acct_flips f "
    + "JOIN chars c ON c.tag = f.tag WHERE c.user = ? ORDER BY f.sold DESC LIMIT ?").bind(a.user, FLIPS_SHOWN).all();
  return out(req, { flips: results.map(r => [r.tag, r.item, r.qty, r.buy, r.sell, r.profit, r.bought, r.sold]) }, 200);
}

// one flip from the plugin, or null when anything is out of range (that row is dropped, not the upload)
function flipRow(f, now) {
  if (!Array.isArray(f) || f.length !== 8 || typeof f[0] !== "string" || f[0].length < 3 || f[0].length > 80) return null;
  const [id, item, qty, buy, sell, profit, bought, sold] = f;
  if (!Number.isInteger(item) || !num(item, 1, 1e6) || !Number.isInteger(qty) || !num(qty, 1, 2.2e9)) return null;
  if (!num(buy, 0, 2.2e9) || !num(sell, 0, 2.2e9) || !num(profit, -1e13, 1e13)) return null;
  if (!(bought === 0 || num(bought, 1.5e12, now + 86400000)) || !(sold === 0 || num(sold, 1.5e12, now + 86400000))) return null;
  return [id, item, qty, Math.round(buy), Math.round(sell), Math.round(profit), Math.round(bought), Math.round(sold)];
}

export async function putFlips(req, env) {
  const a = await auth(req, env);
  if (!a) return out(req, { error: "not logged in" }, 401);
  if (Number(req.headers.get("content-length") || 0) > 200000) return out(req, { error: "too big" }, 413);
  const body = await req.json().catch(() => null);
  if (!body || body.v !== 1 || typeof body.tag !== "string" || !/^[0-9a-f]{16}$/.test(body.tag)
    || typeof body.who !== "string" || !/^[\w \- ]{1,20}$/.test(body.who)
    || !Array.isArray(body.flips) || body.flips.length > MAX_ROWS) return out(req, { error: "bad request" }, 400);
  const now = Date.now();
  // this character is this user's (a character moved to another account goes with its new owner)
  await env.DB.prepare("INSERT INTO chars (tag, user, name, seen) VALUES (?, ?, ?, ?) ON CONFLICT (tag) DO UPDATE SET "
    + "user = excluded.user, name = excluded.name, seen = excluded.seen").bind(body.tag, a.user, body.who, now).run();
  const rows = body.flips.map(f => flipRow(f, now)).filter(Boolean);
  if (rows.length) {
    // a flip still filling grows: written again only when it changed
    const ins = env.DB.prepare("INSERT INTO acct_flips (tag, id, item, qty, buy, sell, profit, bought, sold) VALUES "
      + "(?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (tag, id) DO UPDATE SET qty = excluded.qty, buy = excluded.buy, "
      + "sell = excluded.sell, profit = excluded.profit, bought = excluded.bought, sold = excluded.sold "
      + "WHERE qty != excluded.qty OR profit != excluded.profit OR sold != excluded.sold");
    await env.DB.batch(rows.map(r => ins.bind(body.tag, ...r)));
  }
  return out(req, { v: 1, kept: rows.length, dropped: body.flips.length - rows.length }, 200);
}

// Delete my account: the account (Discord id, name, picture), its logins and its characters' names go. The flips STAY for
// the plugin's analysis (user, 2026-11-07: "i dont want to lose the flip data for the brain. it can remove where it came
// from but that data is valid as data we should be able to use"), each character's moved to a new random id that nothing
// points to: "gone-" + 16 hex, which no plugin can upload to (its tags are 16 hex) and a later login can't pick back up.
export async function forget(req, env) {
  const a = await auth(req, env);
  if (!a) return out(req, { error: "not logged in" }, 401);
  const { results } = await env.DB.prepare("SELECT tag FROM chars WHERE user = ?").bind(a.user).all();
  await env.DB.batch([
    ...results.map(c => env.DB.prepare("UPDATE acct_flips SET tag = ? WHERE tag = ?").bind("gone-" + random(8), c.tag)),
    env.DB.prepare("DELETE FROM chars WHERE user = ?").bind(a.user),
    env.DB.prepare("DELETE FROM sessions WHERE user = ?").bind(a.user),
    env.DB.prepare("DELETE FROM users WHERE id = ?").bind(a.user),
  ]);
  return out(req, { ok: true }, 200);
}
