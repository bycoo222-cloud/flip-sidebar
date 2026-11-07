/*
 * Copyright (c) 2026, bycoo222
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.flipsidebar;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * The OSRS Wiki real-time prices API (prices.runescape.wiki) — the same source the wiki's own
 * GE pages use. Only called when the user has turned on "Live prices" (FlipSidebarConfig), which
 * carries RuneLite's third-party-server warning. The plain calls BLOCK: only the plugin's own
 * engine thread uses them, never the client thread. {@link #getAsync} runs on OkHttp's own pool.
 */
class WikiPrices
{
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WikiPrices.class);

	/**
	 * The wiki's v2 API (1.60.47; v1 "is still available but may be removed in the future" — RuneScape:Real-time_Prices,
	 * read 2026-11-07). v2: averages may have two decimals (Research.num rounds them), /timeseries takes a lookback
	 * (seriesPath) instead of a timestep; /latest, /mapping, /5m, /1h (and their ?timestamp=) as in v1.
	 */
	private static final String BASE = "https://prices.runescape.wiki/api/v2/osrs";
	/** The wiki asks every tool to identify itself. */
	/**
	 * Identifiable, with contact info — the wiki's ask. OSRS Wiki Discord #api-discussion, 2026-11-07 10:16 (Jayden, to the
	 * user's question about a server that fetches /latest /5m /1h for every plugin user): "This is fine, try and limit to 1
	 * rps max and use an identifiable user agent with contact info. The pricing data is not under the same license as
	 * normal wiki content."
	 */
	static final String USER_AGENT = "Flip Sidebar RuneLite plugin - github.com/bycoo222-cloud/flip-sidebar - Discord @bycoo222";

	private final OkHttpClient http;
	private final Gson gson;

	@Inject
	WikiPrices(OkHttpClient http, Gson gson)
	{
		this.http = http;
		this.gson = gson;
	}

	/** Item names, ids and GE buy limits. */
	JsonArray mapping() throws IOException
	{
		return get("/mapping").getAsJsonArray();
	}

	/** {@code /1h}, {@code /5m} or {@code /latest}: the "data" object keyed by item id. */
	JsonObject data(String endpoint) throws IOException
	{
		return get(endpoint).getAsJsonObject().getAsJsonObject("data");
	}

	/** A whole answer: for /5m, {timestamp, data} — the block's start time with every item (MarketMemory). */
	JsonObject block(String endpoint) throws IOException
	{
		return get(endpoint).getAsJsonObject();
	}

	/**
	 * An item's history in {@code step} buckets ("5m", "1h", "6h", "24h"), oldest first — CACHED for about one bucket
	 * (5m: 5 min, 1h: 30 min, 6h: 3 h, 24h: 12 h; 1.60.43): a scan every 3 minutes asked for the same series again and
	 * the wiki's answer cannot change before its next bucket. At most {@link #SERIES_CACHE} kept, the least used go.
	 */
	JsonArray timeseries(int itemId, String step) throws IOException
	{
		String path = seriesPath(itemId, step);
		long now = System.currentTimeMillis();
		synchronized (series)
		{
			Object[] had = series.get(path);
			if (had != null && now < (long) had[0])
			{
				return (JsonArray) had[1];
			}
		}
		JsonArray data = get(path).getAsJsonObject().getAsJsonArray("data");
		long ttl = "5m".equals(step) ? 5 * 60_000L : "1h".equals(step) ? 30 * 60_000L : "6h".equals(step) ? 3 * 3_600_000L
			: 12 * 3_600_000L;
		synchronized (series)
		{
			series.put(path, new Object[]{now + ttl, data});
		}
		return data;
	}

	/**
	 * The v2 /timeseries request for an item in {@code step} buckets: the wiki's own mapping — lookback 24h ≈ 5m buckets,
	 * 7d ≈ 1h, 6m ≈ 6h, 1y ≈ 24h (checked 2026-11-07: 24h → 288 × 300 s, 1y → 365 × 86,400 s). The bucket size "is NOT
	 * guaranteed" by the wiki; the answer says its timestep.
	 */
	static String seriesPath(int itemId, String step)
	{
		String lookback = "5m".equals(step) ? "24h" : "1h".equals(step) ? "7d" : "6h".equals(step) ? "6m" : "1y";
		return "/timeseries?lookback=" + lookback + "&id=" + itemId;
	}

	/** Series kept in the cache at most (timeseries). */
	static final int SERIES_CACHE = 600;
	private final Map<String, Object[]> series = new LinkedHashMap<String, Object[]>(64, 0.75f, true)
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Object[]> eldest)
		{
			return size() > SERIES_CACHE;
		}
	};

	/** The same request on OkHttp's thread pool — for the chart, which must not wait for a scan. */
	/** One async request to {@code base}; the plugin's server failing falls back to the wiki directly. */
	private void enqueue(String path, String base, CompletableFuture<JsonElement> f)
	{
		http.newCall(request(base, path)).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				if (base.equals(SERVER_BASE))
				{
					enqueue(path, BASE, f);
				}
				else
				{
					f.completeExceptionally(e);
				}
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try
				{
					f.complete(read(path, response));
				}
				catch (IOException | RuntimeException e)
				{
					if (base.equals(SERVER_BASE))
					{
						enqueue(path, BASE, f);
					}
					else
					{
						f.completeExceptionally(e);
					}
				}
			}
		});
	}

	CompletableFuture<JsonElement> getAsync(String path)
	{
		CompletableFuture<JsonElement> f = new CompletableFuture<>();
		enqueue(path, viaServer ? SERVER_BASE : BASE, f);
		return f;
	}

	private JsonElement get(String path) throws IOException
	{
		if (viaServer)
		{
			try
			{
				return read(path, http.newCall(request(SERVER_BASE, path)).execute());
			}
			catch (IOException | RuntimeException e)
			{
				log.debug("Flip Sidebar: the prices server failed for {} - the wiki directly", path, e);
			}
		}
		return read(path, http.newCall(request(BASE, path)).execute());
	}

	/**
	 * ⭐ PRICES THROUGH THE PLUGIN'S SERVER (1.60.52; the "serverPrices" setting, off by default): the Worker's cached copy
	 * of the wiki's v2 API (team-sync /v1/prices/…, edge-cached per period) — the wiki sees about one request a period per
	 * Cloudflare location instead of one per player. Approved by the wiki 2026-11-07 ("This is fine, try and limit to 1 rps
	 * max …"). Any failure there: the wiki directly, the same request. Set by FlipSidebarPlugin (rulesTick).
	 */
	static volatile boolean viaServer;
	static final String SERVER_BASE = TeamSync.BASE + "/v1/prices";

	private Request request(String base, String path)
	{
		return new Request.Builder().url(base + path).header("User-Agent", USER_AGENT).build();
	}

	private JsonElement read(String path, Response response) throws IOException
	{
		try (Response r = response)
		{
			ResponseBody body = r.body();
			if (!r.isSuccessful() || body == null)
			{
				throw new IOException("prices.runescape.wiki " + path + " -> HTTP " + r.code());
			}
			return gson.fromJson(body.charStream(), JsonElement.class);
		}
	}
}
