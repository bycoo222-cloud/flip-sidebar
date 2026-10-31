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
	private static final String BASE = "https://prices.runescape.wiki/api/v1/osrs";
	/** The wiki asks every tool to identify itself. */
	private static final String USER_AGENT = "Flip Sidebar RuneLite plugin - github.com/bycoo222-cloud/flip-sidebar";

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

	/** An item's history in {@code step} buckets ("5m", "1h", "6h", "24h"), oldest first. */
	JsonArray timeseries(int itemId, String step) throws IOException
	{
		return get("/timeseries?timestep=" + step + "&id=" + itemId).getAsJsonObject().getAsJsonArray("data");
	}

	/** The same request on OkHttp's thread pool — for the chart, which must not wait for a scan. */
	CompletableFuture<JsonElement> getAsync(String path)
	{
		CompletableFuture<JsonElement> f = new CompletableFuture<>();
		http.newCall(request(path)).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				f.completeExceptionally(e);
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
					f.completeExceptionally(e);
				}
			}
		});
		return f;
	}

	private JsonElement get(String path) throws IOException
	{
		return read(path, http.newCall(request(path)).execute());
	}

	private Request request(String path)
	{
		return new Request.Builder().url(BASE + path).header("User-Agent", USER_AGENT).build();
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
