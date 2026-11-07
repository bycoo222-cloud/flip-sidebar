-- One row per player's measured flip: what the item looked like just before the buy, and what the flip made.
-- tag = a random id made by each player's plugin (never a name); id = the plugin's flip id ("offer@boughtAt").
CREATE TABLE IF NOT EXISTS flips (
  tag    TEXT    NOT NULL,
  id     TEXT    NOT NULL,
  c      REAL    NOT NULL,  -- contiguity: share of 5-minute periods it traded in
  nm     REAL    NOT NULL,  -- share of the last 4h with no margin
  mm     REAL    NOT NULL,  -- median margin, % of price
  flow   REAL    NOT NULL,  -- slower side, units an hour
  profit INTEGER NOT NULL,  -- what the flip made after tax
  buy    INTEGER NOT NULL,  -- buy price each
  at     INTEGER NOT NULL,  -- when it was bought (epoch ms)
  got    INTEGER NOT NULL,  -- when the server got it (epoch ms)
  PRIMARY KEY (tag, id)
);
CREATE INDEX IF NOT EXISTS flips_at ON flips (at);
CREATE INDEX IF NOT EXISTS flips_tag_got ON flips (tag, got);

-- 2026-11-07: one row per player's GE offer (Data sync, OfferShare): its life and the market when it was placed.
-- k = the plugin's offer key ("placed:slot:item:price:total"); state = BUYING/BOUGHT/SELLING/SOLD/CANCELLED_*.
CREATE TABLE IF NOT EXISTS offers (
  tag    TEXT    NOT NULL,
  k      TEXT    NOT NULL,
  item   INTEGER NOT NULL,
  buy    INTEGER NOT NULL,  -- 1 buy, 0 sell
  price  INTEGER NOT NULL,
  total  INTEGER NOT NULL,
  filled INTEGER NOT NULL,
  spent  INTEGER NOT NULL,
  state  TEXT    NOT NULL,
  placed INTEGER NOT NULL,  -- epoch ms; 0 = placed before the plugin saw it
  first  INTEGER NOT NULL,  -- first fill, epoch ms; 0 = none
  last   INTEGER NOT NULL,  -- last change, epoch ms
  lo     INTEGER NOT NULL,  -- market at placement (0 = unknown): last insta-sell, last insta-buy,
  hi     INTEGER NOT NULL,  --   5-minute insta-sell / insta-buy averages, 5-minute insta-sold / insta-bought units
  l5     INTEGER NOT NULL,
  h5     INTEGER NOT NULL,
  lv5    INTEGER NOT NULL,
  hv5    INTEGER NOT NULL,
  got    INTEGER NOT NULL,  -- when the server got it (epoch ms)
  PRIMARY KEY (tag, k)
);
CREATE INDEX IF NOT EXISTS offers_item_last ON offers (item, last);
CREATE INDEX IF NOT EXISTS offers_tag_got ON offers (tag, got);

-- 2026-11-07: the big brain's profiles (name = 'profiles'), uploaded by the author's PC every 6 h; served at GET /v1/profiles.
CREATE TABLE IF NOT EXISTS blobs (
  name TEXT    PRIMARY KEY,
  body TEXT    NOT NULL,
  at   INTEGER NOT NULL      -- when it was built (epoch ms)
);

-- 2026-11-07: GE offers as ONE ROW PER UPLOAD (D1's free plan: 100,000 rows written a day). body = JSON of the checked
-- rows [k, item, buy, price, total, filled, spent, state, placed, first, last, lo, hi, l5, h5, lv5, hv5]. The older
-- per-offer table `offers` stays for what it already holds.
CREATE TABLE IF NOT EXISTS offer_batches (
  tag  TEXT    NOT NULL,
  got  INTEGER NOT NULL,
  n    INTEGER NOT NULL,
  body TEXT    NOT NULL
);
CREATE INDEX IF NOT EXISTS offer_batches_tag_got ON offer_batches (tag, got);
CREATE INDEX IF NOT EXISTS offer_batches_got ON offer_batches (got);

-- ACCOUNTS (2026-11-07, src/accounts.js): a Discord login (id, name, avatar — no email, no password); a user's
-- characters are their plugin's per-character Data sync tags; every finished flip, uploaded only while logged in.
CREATE TABLE IF NOT EXISTS users (
  id       INTEGER PRIMARY KEY AUTOINCREMENT,
  discord  TEXT NOT NULL UNIQUE,
  name     TEXT NOT NULL,
  avatar   TEXT NOT NULL DEFAULT '',
  created  INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS sessions (
  token    TEXT PRIMARY KEY,          -- SHA-256 of the bearer token; the token itself is never stored
  user     INTEGER NOT NULL,
  kind     TEXT NOT NULL,             -- 'plugin' | 'web'
  created  INTEGER NOT NULL,
  seen     INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS sessions_user ON sessions (user);
CREATE TABLE IF NOT EXISTS logins (
  id       TEXT PRIMARY KEY,          -- random; Discord's "state"
  secret   TEXT NOT NULL,             -- SHA-256 of what the plugin polls with ('' for a website login)
  kind     TEXT NOT NULL,             -- 'plugin' | 'web'
  created  INTEGER NOT NULL,
  token    TEXT,                      -- the plugin's token once Discord said yes, until its poll takes it (minutes)
  name     TEXT
);
CREATE TABLE IF NOT EXISTS chars (
  tag      TEXT PRIMARY KEY,          -- the plugin's per-character Data sync tag
  user     INTEGER NOT NULL,
  name     TEXT NOT NULL,             -- the character's name, sent only while logged in
  seen     INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS chars_user ON chars (user);
CREATE TABLE IF NOT EXISTS acct_flips (
  tag      TEXT NOT NULL,
  id       TEXT NOT NULL,             -- the plugin's flip id (its sell offer)
  item     INTEGER NOT NULL,
  qty      INTEGER NOT NULL,
  buy      INTEGER NOT NULL,          -- each
  sell     INTEGER NOT NULL,          -- each
  profit   INTEGER NOT NULL,          -- after the GE tax
  bought   INTEGER NOT NULL,          -- epoch ms, 0 = unknown
  sold     INTEGER NOT NULL,          -- epoch ms of the last fill, 0 = before the plugin kept it
  PRIMARY KEY (tag, id)
);
CREATE INDEX IF NOT EXISTS acct_flips_tag_sold ON acct_flips (tag, sold);
