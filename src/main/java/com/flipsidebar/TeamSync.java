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
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.inject.Inject;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * ⭐ TEAM SYNC — the team brain without sending files (user, 2026-11-03: "cloudflare for sure want it automatic"). With
 * the "Data sync" setting on (off by default, RuneLite's third-party warning) the plugin uploads this account's measured
 * flips (FlipLearner.ownFlips: what the item looked like just before the buy and what the flip made — no names, no
 * coins, under a random tag) to the team's Cloudflare Worker (~/flip-finder/team-sync), and downloads everyone's, which
 * go into what the plugin learns (FlipLearner.team). Calls run on OkHttp's own pool (enqueue), never the client thread.
 */
final class TeamSync
{
	/**
	 * The team's Worker (~/flip-finder/team-sync, the plugin author's Cloudflare account, deployed 2026-11-04). Empty =
	 * nothing is sent or fetched (FlipSidebarPlugin.teamTick).
	 */
	static final String BASE = "https://flip-sidebar-team.bycoo222.workers.dev";
	private static final String USER_AGENT = WikiPrices.USER_AGENT;
	private static final MediaType JSON = MediaType.parse("application/json");
	/** The Worker takes at most this many in one upload. */
	static final int MAX_BATCH = 500;

	private final OkHttpClient http;
	private final Gson gson;

	@Inject
	TeamSync(OkHttpClient http, Gson gson)
	{
		this.http = http;
		this.gson = gson;
	}

	/** The upload's body: {"v":1,"tag":...,"flips":[[id, c, nm, mm, flow, profit, buy, boughtAt], ...]} (at most MAX_BATCH). */
	static JsonObject body(String tag, Map<String, double[]> flips)
	{
		JsonArray rows = new JsonArray();
		for (Map.Entry<String, double[]> e : flips.entrySet())
		{
			double[] v = e.getValue();
			long at = boughtAt(e.getKey());
			if (v.length != 6 || at <= 0)
			{
				continue;
			}
			JsonArray r = new JsonArray();
			r.add(e.getKey());
			for (int i = 0; i < 4; i++)
			{
				r.add(v[i]);
			}
			r.add((long) v[4]);
			r.add((long) v[5]);
			r.add(at);
			rows.add(r);
			if (rows.size() >= MAX_BATCH)
			{
				break;
			}
		}
		JsonObject o = new JsonObject();
		o.addProperty("v", 1);
		o.addProperty("tag", tag);
		o.add("flips", rows);
		return o;
	}

	/** When a flip's buy was, from its id ("offer@boughtAt"); 0 = not known. */
	static long boughtAt(String flipId)
	{
		int at = flipId.lastIndexOf('@');
		try
		{
			return at < 0 ? 0 : Long.parseLong(flipId.substring(at + 1));
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	/** Upload this account's flips; {@code done} hears whether the Worker took them (any thread). */
	void upload(String tag, Map<String, double[]> flips, Consumer<Boolean> done)
	{
		Request req = new Request.Builder().url(BASE + "/v1/flips").header("User-Agent", USER_AGENT)
			.post(RequestBody.create(JSON, gson.toJson(body(tag, flips)))).build();
		http.newCall(req).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				done.accept(false);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					done.accept(r.isSuccessful());
				}
			}
		});
	}

	/** The Worker takes at most this many offers in one upload (OfferShare). */
	static final int MAX_OFFERS = 500;

	/**
	 * The player's GE offers (OfferShare.take rows) to POST /v1/offers: {"v":1,"tag":...,"offers":[[...], ...]}.
	 * {@code done} hears whether the server took them (any thread).
	 */
	void uploadOffers(String tag, List<JsonArray> rows, Consumer<Boolean> done)
	{
		JsonObject b = new JsonObject();
		b.addProperty("v", 1);
		b.addProperty("tag", tag);
		JsonArray a = new JsonArray();
		rows.forEach(a::add);
		b.add("offers", a);
		Request req = new Request.Builder().url(BASE + "/v1/offers").header("User-Agent", USER_AGENT)
			.post(RequestBody.create(JSON, gson.toJson(b))).build();
		http.newCall(req).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				done.accept(false);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					done.accept(r.isSuccessful());
				}
			}
		});
	}

	/** The team's flips ("tag|flip id" -> {c, nm, mm, flow, profit, buy}); {@code done} hears null on a failure (any thread). */
	void download(Consumer<Map<String, double[]>> done)
	{
		Request req = new Request.Builder().url(BASE + "/v1/team").header("User-Agent", USER_AGENT).build();
		http.newCall(req).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				done.accept(null);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					ResponseBody b = r.body();
					done.accept(r.isSuccessful() && b != null ? parse(gson, b.string()) : null);
				}
				catch (IOException | JsonParseException | IllegalStateException e)
				{
					done.accept(null);
				}
			}
		});
	}

	/** The Worker's team answer, read; rows that are not six numbers are left out. */
	static Map<String, double[]> parse(Gson gson, String text)
	{
		Map<String, double[]> out = new LinkedHashMap<>();
		JsonObject o = gson.fromJson(text, JsonObject.class);
		JsonElement flips = o == null ? null : o.get("flips");
		if (flips == null || !flips.isJsonObject())
		{
			return out;
		}
		for (Map.Entry<String, JsonElement> e : flips.getAsJsonObject().entrySet())
		{
			JsonElement v = e.getValue();
			if (!v.isJsonArray() || v.getAsJsonArray().size() != 6 || e.getKey().indexOf('|') < 0)
			{
				continue;
			}
			double[] d = new double[6];
			for (int i = 0; i < 6; i++)
			{
				d[i] = v.getAsJsonArray().get(i).getAsDouble();
			}
			out.put(e.getKey(), d);
		}
		return out;
	}
}
