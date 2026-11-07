# Flip Sidebar — Data sync server (Cloudflare Worker + D1)

The plugin's **Data sync** setting (off by default, RuneLite's third-party warning) uploads each player's measured flips
— what the item looked like just before the buy and what the flip made; a random 16-hex tag, no names, no coins — and
downloads everyone's. Two calls (`src/index.js`): `POST /v1/flips`, `GET /v1/team` (last 30 days, cached 10 min).
Limits: 500 flips an upload, one upload a minute per tag, 3,000 flips kept per tag, out-of-range rows dropped.

Free tier is enough (D1: 5 GB, 100k writes a day). Node: `~/.var/app/com.visualstudio.code/data/node_modules/bin`.

## Deploy (needs the user's free Cloudflare account)
```sh
export PATH=$HOME/.var/app/com.visualstudio.code/data/node_modules/bin:$PATH
cd ~/flip-finder/team-sync
npx wrangler login                                  # the user approves in the browser
npx wrangler d1 create flip-sidebar-team            # copy the database_id into wrangler.toml
npx wrangler d1 execute flip-sidebar-team --remote --file schema.sql
npx wrangler deploy                                 # prints https://flip-sidebar-team.<subdomain>.workers.dev
```
Then in the plugin: `TeamSync.BASE` = that URL, remove `hidden = true` from the `dataSync` setting, README + Hub PR text
mention it (third-party server, opt-in), release as an update.

## Test locally (no account)
```sh
npx wrangler d1 execute flip-sidebar-team --local --file schema.sql
npx wrangler dev --local --port 8787 &
# harness: DataSyncCheck http://127.0.0.1:8787   (2026-11-04: ALL PASS — 400 real flips up, 429 within a minute,
# a bad row dropped, a name as the tag refused, 2 players / 401 flips down, a new player learned floor 0.85)
```
Stop it with `pkill -f "[w]orkerd"` (the brackets keep pkill from matching its own shell).
