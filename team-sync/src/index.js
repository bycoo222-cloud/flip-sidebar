// Flip Sidebar team sync (Cloudflare Worker + D1). Calls:
//   POST /v1/flips  {"v":1,"tag":"<16 hex>","flips":[[id, c, nm, mm, flow, profit, buy, at], ...]}  (at most 500)
//   POST /v1/offers {"v":1,"tag":"<16 hex>","offers":[[k, item, buy, price, total, filled, spent, state, placed, first,
//                    last, lo, hi, l5, h5, lv5, hv5], ...]}  (at most 500; 2026-11-07, the big brain's data)
//   GET  /v1/prices/{latest|5m|1h|6h|24h|mapping|timeseries}  the OSRS Wiki's v2 prices, cached at the edge (2026-11-07)
//   GET  /v1/profiles   the big brain's per-item profiles (uploaded by the author's PC every 6 h)
//   POST /v1/profiles   upload them (Authorization: Bearer <PROFILES_TOKEN secret>)
//   GET  /v1/team   -> {"v":1,"at":<ms>,"flips":{"<tag>|<id>":[c, nm, mm, flow, profit, buy], ...}}  (last 30 days)
//   /v1/auth/*, /v1/me*   ACCOUNTS (2026-11-07): Discord login, a private page per user — see accounts.js
// Without an account: no names, no coins — a random tag per player and numbers about finished flips. Each player's plugin
// learns its floors from the team's flips (FlipLearner) - the server only collects and hands them out.
import * as acct from "./accounts.js";

const MAX_BATCH = 500;          // flips in one upload
const PER_TAG = 3000;           // flips kept per player
const TEAM_DAYS = 30;           // flips handed out: bought in the last 30 days
const TEAM_ROWS = 20000;        // and at most this many (newest)
const MIN_GAP_MS = 60 * 1000;   // one upload a minute per player
const CACHE_S = 600;            // the team list is cached 10 minutes

export default {
  async fetch(req, env, ctx) {
    const url = new URL(req.url);
    try {
      if (req.method === "OPTIONS" && (url.pathname.startsWith("/v1/me") || url.pathname.startsWith("/v1/auth/")))
        return new Response(null, { status: 204, headers: acct.cors(req) });
      if (req.method === "POST" && url.pathname === "/v1/auth/start") return await acct.start(req, env);
      if (req.method === "GET" && url.pathname === "/v1/auth/poll") return await acct.poll(req, url, env);
      if (req.method === "GET" && url.pathname === "/v1/auth/web") return await acct.web(req, env);
      if (req.method === "GET" && url.pathname === "/v1/auth/discord") return await acct.discord(req, url, env);
      if (req.method === "POST" && url.pathname === "/v1/auth/logout") return await acct.logout(req, env);
      if (req.method === "GET" && url.pathname === "/v1/me") return await acct.me(req, env);
      if (req.method === "DELETE" && url.pathname === "/v1/me") return await acct.forget(req, env);
      if (req.method === "GET" && url.pathname === "/v1/me/flips") return await acct.myFlips(req, env);
      if (req.method === "POST" && url.pathname === "/v1/me/flips") return await acct.putFlips(req, env);
      if (req.method === "POST" && url.pathname === "/v1/flips") return await upload(req, env);
      if (req.method === "POST" && url.pathname === "/v1/offers") return await offers(req, env);
      if (req.method === "GET" && url.pathname.startsWith("/v1/prices/")) return await prices(req, url, ctx);
      if (req.method === "GET" && url.pathname === "/v1/profiles") return await profilesGet(req, env, ctx);
      if (req.method === "POST" && url.pathname === "/v1/profiles") return await profilesPut(req, env);
      if (req.method === "GET" && url.pathname === "/v1/team") return await team(req, env, ctx);
      if (req.method === "GET" && url.pathname === "/") return text("Flip Sidebar team sync", 200);
      return text("not found", 404);
    } catch (e) {
      if (url.pathname.startsWith("/v1/me") || url.pathname.startsWith("/v1/auth/"))
        return new Response(JSON.stringify({ error: "server error" }), { status: 500, headers: { "content-type": "application/json", ...acct.cors(req) } });
      return text("error", 500);
    }
  },
};

function text(s, status) {
  return new Response(s, { status, headers: { "content-type": "text/plain" } });
}

function json(o, status, extra) {
  return new Response(JSON.stringify(o), { status, headers: { "content-type": "application/json", ...(extra || {}) } });
}

const num = (x, lo, hi) => typeof x === "number" && Number.isFinite(x) && x >= lo && x <= hi;

// one flip row, or null when any number is out of range (a bad or made-up row is dropped, not the batch)
function row(f, now) {
  if (!Array.isArray(f) || f.length !== 8 || typeof f[0] !== "string" || f[0].length < 3 || f[0].length > 80) return null;
  const [id, c, nm, mm, flow, profit, buy, at] = f;
  if (!num(c, 0, 1) || !num(nm, 0, 1) || !num(mm, -100, 100) || !num(flow, 0, 1e9)) return null;
  if (!num(profit, -2.2e9, 2.2e9) || !num(buy, 1, 2.2e9) || !num(at, 1.7e12, now + 86400000)) return null;
  return [id, c, nm, mm, flow, Math.round(profit), Math.round(buy), Math.round(at)];
}

async function upload(req, env) {
  if (Number(req.headers.get("content-length") || 0) > 200000) return text("too big", 413);
  const body = await req.json().catch(() => null);
  if (!body || body.v !== 1 || typeof body.tag !== "string" || !/^[0-9a-f]{16}$/.test(body.tag)
    || !Array.isArray(body.flips) || body.flips.length > MAX_BATCH) return text("bad request", 400);
  const now = Date.now();
  const last = await env.DB.prepare("SELECT MAX(got) AS g FROM flips WHERE tag = ?").bind(body.tag).first();
  if (last && last.g && now - last.g < MIN_GAP_MS) return text("too soon", 429);
  const rows = body.flips.map(f => row(f, now)).filter(Boolean);
  if (rows.length) {
    const ins = env.DB.prepare("INSERT INTO flips (tag, id, c, nm, mm, flow, profit, buy, at, got) VALUES (?,?,?,?,?,?,?,?,?,?) "
      + "ON CONFLICT (tag, id) DO UPDATE SET c=excluded.c, nm=excluded.nm, mm=excluded.mm, flow=excluded.flow, "
      + "profit=excluded.profit, buy=excluded.buy, at=excluded.at, got=excluded.got");
    await env.DB.batch(rows.map(r => ins.bind(body.tag, ...r, now)));
    // a player's oldest beyond PER_TAG go
    await env.DB.prepare("DELETE FROM flips WHERE tag = ? AND rowid NOT IN "
      + "(SELECT rowid FROM flips WHERE tag = ? ORDER BY at DESC LIMIT ?)").bind(body.tag, body.tag, PER_TAG).run();
  }
  return json({ v: 1, kept: rows.length, dropped: body.flips.length - rows.length }, 200);
}

// The team list is BUILT at most every TEAM_BUILD_MS into blobs('team') (2026-11-07, for the free plan: up to 20,000
// rows read once, then one row a request) and edge-cached CACHE_S on top.
const TEAM_BUILD_MS = 30 * 60 * 1000;

async function team(req, env, ctx) {
  const cache = caches.default;
  const key = new Request(new URL("/v1/team", req.url).toString(), { method: "GET" });
  const hit = await cache.match(key);
  if (hit) return hit;
  const now = Date.now();
  let body;
  const row = await env.DB.prepare("SELECT body, at FROM blobs WHERE name = 'team'").first();
  if (row && now - row.at < TEAM_BUILD_MS) {
    body = row.body;
  } else {
    const since = now - TEAM_DAYS * 86400000;
    const { results } = await env.DB.prepare("SELECT tag, id, c, nm, mm, flow, profit, buy FROM flips WHERE at >= ? "
      + "ORDER BY at DESC LIMIT ?").bind(since, TEAM_ROWS).all();
    const flips = {};
    for (const r of results) flips[r.tag + "|" + r.id] = [r.c, r.nm, r.mm, r.flow, r.profit, r.buy];
    body = JSON.stringify({ v: 1, at: now, players: new Set(results.map(r => r.tag)).size, flips });
    await env.DB.prepare("INSERT INTO blobs (name, body, at) VALUES ('team', ?, ?) ON CONFLICT (name) DO UPDATE SET "
      + "body = excluded.body, at = excluded.at").bind(body, now).run();
  }
  const res = new Response(body, { status: 200, headers: { "content-type": "application/json",
    "cache-control": "public, max-age=" + CACHE_S } });
  ctx.waitUntil(cache.put(key, res.clone()));
  return res;
}

// ---- the player's GE offers (2026-11-07) ----
const MAX_OFFERS = 500;         // offers in one upload
const OFFER_GAP_MS = 60 * 1000; // one upload a minute per player
const STATES = new Set(["BUYING", "BOUGHT", "SELLING", "SOLD", "CANCELLED_BUY", "CANCELLED_SELL"]);

function offerRow(f, now) {
  if (!Array.isArray(f) || f.length !== 17 || typeof f[0] !== "string" || f[0].length < 5 || f[0].length > 80) return null;
  const [k, item, buy, price, total, filled, spent, state, placed, first, last, lo, hi, l5, h5, lv5, hv5] = f;
  if (!num(item, 1, 1e6) || !(buy === 0 || buy === 1) || !num(price, 1, 2.2e9) || !num(total, 1, 2.2e9)) return null;
  if (!num(filled, 0, total) || !num(spent, 0, 4e18) || typeof state !== "string" || !STATES.has(state)) return null;
  if (!num(placed, 0, now + 86400000) || !num(first, 0, now + 86400000) || !num(last, 1.7e12, now + 86400000)) return null;
  for (const v of [lo, hi, l5, h5, lv5, hv5]) if (!num(v, 0, 1e12)) return null;
  return [k, item, buy, price, total, filled, Math.round(spent), state, placed, first, last, lo, hi, l5, h5, lv5, hv5];
}

// ONE ROW PER UPLOAD (2026-11-07: "if we can be fully functional on our free plan then thats what id like"): D1's free plan
// allows 100,000 rows written a day; a row per offer was ~600 a day per player (~165 players), a row per upload ~20-60.
// The rows are checked one by one as before; the analysis (the author's PC) reads the batches.
const BATCHES_PER_TAG = 3000;   // uploads kept per player (the newest; one per 10 minutes played = ~500 hours of play)

async function offers(req, env) {
  if (Number(req.headers.get("content-length") || 0) > 400000) return text("too big", 413);
  const body = await req.json().catch(() => null);
  if (!body || body.v !== 1 || typeof body.tag !== "string" || !/^[0-9a-f]{16}$/.test(body.tag)
    || !Array.isArray(body.offers) || body.offers.length > MAX_OFFERS) return text("bad request", 400);
  const now = Date.now();
  const last = await env.DB.prepare("SELECT MAX(got) AS g FROM offer_batches WHERE tag = ?").bind(body.tag).first();
  if (last && last.g && now - last.g < OFFER_GAP_MS) return text("too soon", 429);
  const rows = body.offers.map(f => offerRow(f, now)).filter(Boolean);
  if (rows.length) {
    await env.DB.prepare("INSERT INTO offer_batches (tag, got, n, body) VALUES (?, ?, ?, ?)")
      .bind(body.tag, now, rows.length, JSON.stringify(rows)).run();
    // the oldest beyond BATCHES_PER_TAG go — checked about one upload in twenty (a write saved)
    if (Math.random() < 0.05) {
      await env.DB.prepare("DELETE FROM offer_batches WHERE tag = ? AND got < (SELECT got FROM offer_batches WHERE tag = ? "
        + "ORDER BY got DESC LIMIT 1 OFFSET ?)").bind(body.tag, body.tag, BATCHES_PER_TAG).run();
    }
  }
  return json({ v: 1, kept: rows.length, dropped: body.offers.length - rows.length }, 200);
}

// ---- the OSRS Wiki's prices, cached (2026-11-07) ----
// The wiki said yes (OSRS Wiki Discord #api-discussion, 2026-11-07, Jayden): "This is fine, try and limit to 1 rps max and
// use an identifiable user agent with contact info." Each response is cached at the edge for its period (Cloudflare's
// fetch cache + the Cache API), so the wiki sees about one request per period per Cloudflare location, however many
// plugins ask.
const WIKI = "https://prices.runescape.wiki/api/v2/osrs";
const WIKI_UA = "Flip Sidebar server (Cloudflare Worker) - github.com/bycoo222-cloud/flip-sidebar - Discord @bycoo222";
const LOOKBACKS = { "6h": 300, "24h": 300, "7d": 3600, "30d": 3600, "6m": 21600, "1y": 86400 };
const ROUTES = { latest: 30, "5m": 60, "1h": 300, "6h": 1800, "24h": 3600, mapping: 21600, timeseries: 0 };

async function prices(req, url, ctx) {
  const route = url.pathname.slice("/v1/prices/".length);
  if (!(route in ROUTES)) return text("not found", 404);
  const q = new URLSearchParams();
  const id = url.searchParams.get("id");
  const ts = url.searchParams.get("timestamp");
  const lookback = url.searchParams.get("lookback");
  if (id !== null) {
    if (!/^[0-9]{1,7}$/.test(id) || !(route === "latest" || route === "timeseries")) return text("bad request", 400);
    q.set("id", id);
  }
  if (ts !== null) {
    if (!/^[0-9]{9,11}$/.test(ts) || !["5m", "1h", "6h", "24h"].includes(route)) return text("bad request", 400);
    q.set("timestamp", ts);
  }
  if (route === "timeseries") {
    if (id === null || !(lookback in LOOKBACKS)) return text("bad request", 400);
    q.set("lookback", lookback);
  } else if (lookback !== null) return text("bad request", 400);
  // a past period never changes: kept a week; else for about its own period
  const ttl = ts !== null ? 7 * 86400 : route === "timeseries" ? LOOKBACKS[lookback] : ROUTES[route];
  const target = WIKI + "/" + route + (q.toString() ? "?" + q.toString() : "");
  const key = new Request(new URL("/v1/prices/" + route + (q.toString() ? "?" + q.toString() : ""), req.url).toString());
  const cache = caches.default;
  const hit = await cache.match(key);
  if (hit) return hit;
  const r = await fetch(target, { headers: { "User-Agent": WIKI_UA }, cf: { cacheTtl: ttl, cacheEverything: true } });
  if (!r.ok) return new Response("wiki " + r.status, { status: 502, headers: { "access-control-allow-origin": "*" } });
  // readable by the website (any origin: public prices)
  const res = new Response(await r.text(), { status: 200, headers: { "content-type": "application/json",
    "cache-control": "public, max-age=" + ttl, "access-control-allow-origin": "*" } });
  ctx.waitUntil(cache.put(key, res.clone()));
  return res;
}

// ---- the big brain's profiles (2026-11-07) ----
// One row in D1 (blobs): the author's PC POSTs profiles.json after each rebuild (~120 KB, every 6 h); plugins GET it.
const PROFILES_MAX = 1500000;

async function profilesGet(req, env, ctx) {
  const cache = caches.default;
  const key = new Request(new URL("/v1/profiles", req.url).toString());
  const hit = await cache.match(key);
  if (hit) return hit;
  const row = await env.DB.prepare("SELECT body, at FROM blobs WHERE name = 'profiles'").first();
  if (!row) return text("no profiles yet", 404);
  const res = new Response(row.body, { status: 200, headers: { "content-type": "application/json",
    "cache-control": "public, max-age=600", "x-built-at": String(row.at) } });
  ctx.waitUntil(cache.put(key, res.clone()));
  return res;
}

async function profilesPut(req, env) {
  const auth = req.headers.get("authorization") || "";
  if (!env.PROFILES_TOKEN || auth !== "Bearer " + env.PROFILES_TOKEN) return text("forbidden", 403);
  const len = Number(req.headers.get("content-length") || 0);
  if (len > PROFILES_MAX) return text("too big", 413);
  const body = await req.text();
  if (body.length > PROFILES_MAX) return text("too big", 413);
  let o;
  try { o = JSON.parse(body); } catch (e) { return text("bad json", 400); }
  if (!o || typeof o !== "object" || typeof o.items !== "object" || typeof o.built !== "number") return text("bad profiles", 400);
  await env.DB.prepare("INSERT INTO blobs (name, body, at) VALUES ('profiles', ?, ?) ON CONFLICT (name) DO UPDATE SET "
    + "body = excluded.body, at = excluded.at").bind(body, o.built).run();
  return json({ v: 1, items: Object.keys(o.items).length, built: o.built }, 200);
}
