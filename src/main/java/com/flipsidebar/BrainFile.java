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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.util.Map;
import java.util.TreeMap;

/**
 * ⭐ EXPORT / IMPORT of what the plugin has learned (user, 2026-11-03: "there needs to be a way to export all the brains
 * learned market data and load it. I want to be able to save what ive learned and move it with me"). One JSON file:
 * the account's learning (FlipLearner: what its flips taught — activity floor, problem items resting, buy-limit hits),
 * its trade log (finished flips, what is held and paid, buy-limit windows, banked holds) and the plugin's settings.
 * Not in it: the market's last day (MarketMemory) — filled from the wiki in ~8 minutes, and stale in a file.
 */
final class BrainFile
{
	static final String FORMAT = "flip-sidebar-brain";
	static final int VERSION = 1;
	/**
	 * A TEAM brain (TeamBrain merges players' exports): the learning only, its flips in FlipLearner's team; importing it
	 * ADDS to an account's learning instead of replacing it.
	 */
	static final String KIND_TEAM = "team";

	private BrainFile()
	{
	}

	/** The file's text. {@code player} names the character it came from (for the import's warning); may be null. */
	static String write(Gson gson, String learningJson, String tradeLogJson, Map<String, String> settings, String player,
		long nowMs)
	{
		return write(gson, learningJson, tradeLogJson, settings, player, nowMs, null);
	}

	/** ...with every finished flip of the account, all time (the plugin's flip archive), oldest first; null = none. */
	static String write(Gson gson, String learningJson, String tradeLogJson, Map<String, String> settings, String player,
		long nowMs, java.util.List<TradeLog.Flip> allFlips)
	{
		JsonObject o = new JsonObject();
		o.addProperty("format", FORMAT);
		o.addProperty("version", VERSION);
		o.addProperty("exportedAt", nowMs);
		if (player != null)
		{
			o.addProperty("player", player);
		}
		// RuneLite's Gson (2.8.5) has no JsonParser.parseString: through the injected Gson
		o.add("learning", learningJson == null ? new JsonObject() : gson.fromJson(learningJson, JsonElement.class));
		o.add("tradeLog", tradeLogJson == null ? new JsonObject() : gson.fromJson(tradeLogJson, JsonElement.class));
		JsonObject s = new JsonObject();
		for (Map.Entry<String, String> e : new TreeMap<>(settings).entrySet())
		{
			s.addProperty(e.getKey(), e.getValue());
		}
		o.add("settings", s);
		if (allFlips != null)
		{
			o.add("flips", gson.toJsonTree(allFlips));
		}
		return gson.newBuilder().setPrettyPrinting().create().toJson(o);
	}

	/** A team brain's text: the merged learning and who it came from. */
	static String writeTeam(Gson gson, String learningJson, java.util.Collection<String> players, long nowMs)
	{
		JsonObject o = new JsonObject();
		o.addProperty("format", FORMAT);
		o.addProperty("version", VERSION);
		o.addProperty("kind", KIND_TEAM);
		o.addProperty("exportedAt", nowMs);
		com.google.gson.JsonArray who = new com.google.gson.JsonArray();
		for (String p : players)
		{
			who.add(p);
		}
		o.add("players", who);
		o.add("learning", gson.fromJson(learningJson, JsonElement.class));
		return gson.newBuilder().setPrettyPrinting().create().toJson(o);
	}

	/** A file read back: its parts, or {@link #error} saying why it cannot be used. */
	static final class Read
	{
		String error;
		/** A team brain: importing it adds to the learning (FlipLearner.withTeam). */
		boolean team;
		int players;
		FlipLearner learning;
		String learningJson;
		TradeLog tradeLog;
		Map<String, String> settings = new TreeMap<>();
		/** Every finished flip, all time (the flip archive); null when the file has none. */
		java.util.List<TradeLog.Flip> flips;
		String player;
		long exportedAt;
	}

	static Read read(Gson gson, String text)
	{
		Read r = new Read();
		try
		{
			JsonElement root = gson.fromJson(text, JsonElement.class);
			JsonObject o = root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
			if (o == null || !o.has("format") || !FORMAT.equals(o.get("format").getAsString()))
			{
				r.error = "This is not a Flip Sidebar data file.";
				return r;
			}
			if (o.has("version") && o.get("version").getAsInt() > VERSION)
			{
				r.error = "This file is from a newer Flip Sidebar - update the plugin first.";
				return r;
			}
			r.exportedAt = o.has("exportedAt") ? o.get("exportedAt").getAsLong() : 0;
			r.team = o.has("kind") && KIND_TEAM.equals(o.get("kind").getAsString());
			r.players = o.has("players") && o.get("players").isJsonArray() ? o.get("players").getAsJsonArray().size() : 0;
			r.player = o.has("player") ? o.get("player").getAsString() : null;
			JsonElement l = o.get("learning");
			r.learningJson = l == null || !l.isJsonObject() ? null : gson.toJson(l);
			r.learning = r.learningJson == null ? null : gson.fromJson(r.learningJson, FlipLearner.class);
			JsonElement t = o.get("tradeLog");
			r.tradeLog = t == null || !t.isJsonObject() ? null : gson.fromJson(t, TradeLog.class);
			if (r.team && (r.learning == null || r.learning.teamSize() == 0))
			{
				r.error = "The team file has no flips in it.";
				return r;
			}
			if (r.learning == null && r.tradeLog == null)
			{
				r.error = "The file has nothing learned in it.";
				return r;
			}
			JsonElement all = o.get("flips");
			if (all != null && all.isJsonArray())
			{
				r.flips = new java.util.ArrayList<>();
				for (JsonElement e : all.getAsJsonArray())
				{
					TradeLog.Flip f = gson.fromJson(e, TradeLog.Flip.class);
					if (f != null)
					{
						r.flips.add(f);
					}
				}
			}
			JsonElement s = o.get("settings");
			if (s != null && s.isJsonObject())
			{
				for (Map.Entry<String, JsonElement> e : s.getAsJsonObject().entrySet())
				{
					if (e.getValue().isJsonPrimitive())
					{
						r.settings.put(e.getKey(), e.getValue().getAsString());
					}
				}
			}
		}
		catch (JsonParseException | IllegalStateException | UnsupportedOperationException | NumberFormatException e)
		{
			r.error = "The file could not be read (" + e.getClass().getSimpleName() + ").";
		}
		return r;
	}

	/** "400 flips measured · 13 items resting · 200 finished flips · 1 long-term hold · 27 settings". */
	static String summary(FlipLearner l, TradeLog t, int settings)
	{
		FlipLearner f = l == null ? null : l.fixed();
		int holds = t == null ? 0 : t.held().size();
		int team = f == null ? 0 : f.teamSize();
		return String.format("%,d flips measured%s · %,d items resting · %,d finished flips · %,d long-term hold%s · %,d settings",
			f == null ? 0 : f.measured(), team > 0 ? String.format(" + %,d team flips", team) : "",
			f == null ? 0 : f.restingCount(), t == null ? 0 : t.flips().size(), holds, holds == 1 ? "" : "s", settings);
	}
}
