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
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
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
 * ⭐ ACCOUNTS (user, 2026-11-07: "build accounts" — like Flipping Copilot, "I log into mine right from the side bar. It
 * opens their website and I log in there and all my in game characters flips show up"). The panel's Log in button: the
 * Worker gives a login link (Discord, scope "identify"), the browser opens it, and the plugin asks every few seconds
 * until the Worker hands it its own login token. Logged in (and with Data sync on), every finished flip goes up with the
 * character's name, for the user's private page on the website (~/flip-finder/team-sync/src/accounts.js).
 */
class Account
{
	static final String BASE = TeamSync.BASE;
	/** The website's private page. */
	static final String PAGE = "https://flip-sidebar.pages.dev/account";
	/** The Worker takes at most this many flips in one upload. */
	static final int MAX_FLIPS = 500;
	private static final MediaType JSON = MediaType.parse("application/json");
	private static final String USER_AGENT = WikiPrices.USER_AGENT;

	private final OkHttpClient http;
	private final Gson gson;

	@Inject
	Account(OkHttpClient http, Gson gson)
	{
		this.http = http;
		this.gson = gson;
	}

	/** A login started: {login id, secret, the Discord link to open}; {@code done} hears null on a failure (any thread). */
	void start(Consumer<String[]> done)
	{
		Request req = new Request.Builder().url(BASE + "/v1/auth/start").header("User-Agent", USER_AGENT)
			.post(RequestBody.create(JSON, "{}")).build();
		call(req, (code, o) ->
		{
			String login = str(o, "login"), secret = str(o, "secret"), url = str(o, "url");
			done.accept(code == 200 && login != null && secret != null && url != null && url.startsWith("https://discord.com/")
				? new String[]{login, secret, url} : null);
		});
	}

	/**
	 * Has the browser login finished? {@code done} hears {token, name} when it has, {} while it is still waiting, and null
	 * when it is over without one (expired or refused) — any thread. A network failure counts as still waiting.
	 */
	void poll(String login, String secret, Consumer<String[]> done)
	{
		Request req = new Request.Builder().url(BASE + "/v1/auth/poll?login=" + login + "&secret=" + secret)
			.header("User-Agent", USER_AGENT).build();
		call(req, (code, o) ->
		{
			String token = str(o, "token");
			if (code == 200 && token != null && token.matches("[0-9a-f]{64}"))
			{
				String name = str(o, "name");
				done.accept(new String[]{token, name == null ? "" : name});
			}
			else
			{
				done.accept(code == 200 || code < 0 || code >= 500 ? new String[0] : null);
			}
		});
	}

	/**
	 * Flips ({@link #row} rows) for the user's page: {"v":1,"tag","who","flips":[...]}. {@code done} hears the HTTP status
	 * (200 = taken, 401 = this login ended on the server, -1 = no answer) — any thread.
	 */
	void upload(String token, String tag, String who, List<JsonArray> rows, IntConsumer done)
	{
		JsonObject b = new JsonObject();
		b.addProperty("v", 1);
		b.addProperty("tag", tag);
		b.addProperty("who", who);
		JsonArray a = new JsonArray();
		rows.forEach(a::add);
		b.add("flips", a);
		Request req = new Request.Builder().url(BASE + "/v1/me/flips").header("User-Agent", USER_AGENT)
			.header("Authorization", "Bearer " + token).post(RequestBody.create(JSON, gson.toJson(b))).build();
		call(req, (code, o) -> done.accept(code));
	}

	/** Ends this login on the server (fire and forget). */
	void logout(String token)
	{
		Request req = new Request.Builder().url(BASE + "/v1/auth/logout").header("User-Agent", USER_AGENT)
			.header("Authorization", "Bearer " + token).post(RequestBody.create(JSON, "{}")).build();
		call(req, (code, o) ->
		{
		});
	}

	/**
	 * One finished flip as the Worker takes it: [id, item, qty, buy each, sell each, profit after tax, bought at, sold at].
	 * The id is the sell offer's key and when what it sold was bought: the offer key alone (slot:item:price:quantity) comes
	 * back for a later flip of the same item at the same price, which must not replace the earlier one on the page.
	 */
	static JsonArray row(TradeLog.Flip f)
	{
		String id = (f.offer != null ? f.offer : "old:" + f.itemId + ":" + f.qty) + "@" + (f.boughtAt > 0 ? f.boughtAt : "a" + f.at);
		JsonArray r = new JsonArray();
		r.add(id.length() > 80 ? id.substring(0, 80) : id);
		r.add(f.itemId);
		r.add(f.qty);
		r.add(f.buyEach);
		r.add(f.sellEach);
		r.add(f.profit);
		r.add(f.boughtAt > 0 ? f.boughtAt : 0);
		r.add(f.at > 0 ? f.at : 0);
		return r;
	}

	private interface Answer
	{
		void on(int code, JsonObject body);
	}

	private void call(Request req, Answer answer)
	{
		http.newCall(req).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				answer.on(-1, null);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					ResponseBody b = r.body();
					JsonObject o = null;
					try
					{
						o = b == null ? null : gson.fromJson(b.string(), JsonObject.class);
					}
					catch (JsonParseException | IllegalStateException | IOException e)
					{
						// not JSON: the status alone answers
					}
					answer.on(r.code(), o);
				}
			}
		});
	}

	private static String str(JsonObject o, String key)
	{
		return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
	}
}
