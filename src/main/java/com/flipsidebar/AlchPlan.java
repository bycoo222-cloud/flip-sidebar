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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.gameval.ItemID;

/**
 * ⭐ HIGH ALCH MODE (1.59.0; merged from the Alch Finder plugin — user, 2026-11-02: "I want the two plug ins merged
 * together bc they will conflict with eachother"). THE PLAN: which items to buy, how many and at what price, to fill
 * the next hours of High Level Alchemy at the best
 * profit — not a list of profitable items (other plugins have that), a plan that fits the buy limits and what really
 * trades. Profit a cast = high alch value - what the item cost - a nature rune (no GE tax: the item is alched, not
 * sold). AT MOST {@link Settings#maxItems} ITEMS — one GE slot each (live prices 2026-11-02 06:58 spread the best plan
 * over 37 items, 1-6 of most). The items worth the most over the whole plan are picked (profit a cast x what can be
 * bought), then the casts go to the best profit a cast first, each item up to its buy limit, what one player can buy
 * over the plan's hours without moving the price ({@link #VOLUME_SHARE} of the trades), and the cash.
 *
 * <p>MEASURED (whole-market replay, 5 days of 5-minute prices, 2026-10-28 to 11-02, alch_replay.py): an alcher planning
 * every 4 h this way made +636k / +669k gp an hour of casting buying at the insta-buy, +657k / +693k by offers at the
 * insta-sell (the two halves of the data), ~530-580 a cast; every 4 h plan had 4 h of casts. ASSUMED: 1,200 casts an
 * hour, a fire staff (no fire runes), fills at 10% of the volume — the replay bought in the plan's first hour; buying
 * on through the plan's hours (you cast while the offers fill) is ASSUMED.</p>
 */
final class AlchPlan
{
	static final int NATURE_RUNE = ItemID.NATURERUNE;
	/** Of an hour's trades of an item, the share one player's buying takes — the replay's fill model. */
	static final double VOLUME_SHARE = 0.10;
	/** A last trade older than this is not "now" (seconds). */
	static final long FRESH_S = 600;

	private AlchPlan()
	{
	}

	/** An alchable item from the wiki's mapping. */
	static final class Item
	{
		final int id;
		final String name;
		final long highAlch;
		/** GE buy limit (4 h); 0 = not known. */
		final long limit;
		final boolean members;

		Item(int id, String name, long highAlch, long limit, boolean members)
		{
			this.id = id;
			this.name = name;
			this.highAlch = highAlch;
			this.limit = limit;
			this.members = members;
		}
	}

	/** One item's prices now. */
	static final class Quote
	{
		/** What buying at once costs (insta-buy): the fresh last trade, else the 5-minute, else the hour's average. */
		final long now;
		/** An offer at the insta-sell price (+1 gp). */
		final long offer;
		/** Insta-buys an hour (what buying at once takes from). */
		final long buysHr;
		/** Insta-sells an hour (what an offer fills from). */
		final long sellsHr;

		Quote(long now, long offer, long buysHr, long sellsHr)
		{
			this.now = now;
			this.offer = offer;
			this.buysHr = buysHr;
			this.sellsHr = sellsHr;
		}
	}

	/** The plan's settings (FlipSidebarConfig, High alch mode). */
	static final class Settings
	{
		int castsPerHour = 1200;
		int hours = 4;
		boolean offer;
		long minProfit = 50;
		/** 0 = no limit. */
		long cash;
		boolean freeToPlay;
		/** One GE slot each: at most this many items. */
		int maxItems = 8;
		/** Item -> {units already bought in its open buy-limit window, when it resets} (TradeLog.limitUse). */
		Map<Integer, long[]> used = Map.of();
		/** Item -> units still to fill in its open buy offers (any open buy offer: a key, even at 0): their coins are in
		 * the GE already, not in {@link #cash}. */
		Map<Integer, Long> waiting = Map.of();
		/** A row must make at least this in all (the "Minimum profit per flip" setting); 0 = any. */
		long minTotal;
		Set<Integer> skip = Set.of();
	}

	/** One item in the plan. */
	static final class Row
	{
		final Item item;
		/** Paid each. */
		final long price;
		/** Profit a cast: high alch - price - a nature rune. */
		final long each;
		final long qty;
		/** What trades an hour on the buying side (insta-buys to buy now, insta-sells for an offer). */
		final long volHr;

		Row(Item item, long price, long each, long qty, long volHr)
		{
			this.item = item;
			this.price = price;
			this.each = each;
			this.qty = qty;
			this.volHr = volHr;
		}

		/**
		 * About how long the buy takes to fill, minutes, if it gets {@link #VOLUME_SHARE} of what trades (the replay's
		 * fill model — ASSUMED for one player; user, 2026-11-02: "The wait times for buying on alch items is crazy so
		 * maybe an estimated buy time"). -1 = not known.
		 */
		double minutes()
		{
			return volHr <= 0 ? -1 : qty * 60.0 / (volHr * VOLUME_SHARE);
		}

		long total()
		{
			return each * qty;
		}
	}

	static final class Plan
	{
		final List<Row> rows;
		/** Profitable too, but not needed (or no room) for this plan — worth the most over a whole plan first. */
		final List<Row> more;
		final long nature;
		final long castsWanted;

		Plan(List<Row> rows, List<Row> more, long nature, long castsWanted)
		{
			this.rows = rows;
			this.more = more;
			this.nature = nature;
			this.castsWanted = castsWanted;
		}

		long casts()
		{
			return rows.stream().mapToLong(r -> r.qty).sum();
		}

		long profit()
		{
			return rows.stream().mapToLong(Row::total).sum();
		}

		/** Coins for the items (the nature runes come on top: {@link #casts} of them at {@link #nature}). */
		long cash()
		{
			return rows.stream().mapToLong(r -> r.price * r.qty).sum();
		}

		/** Profit an hour of casting at {@code castsPerHour}. */
		long perHour(int castsPerHour)
		{
			long c = casts();
			return c <= 0 ? 0 : profit() * castsPerHour / c;
		}
	}

	/** The plan: best profit a cast first, each item up to its limit / an hour's buyable volume / the cash. */
	static Plan plan(List<Item> items, Map<Integer, Quote> quotes, long nature, Settings s)
	{
		long want = (long) s.castsPerHour * s.hours;
		List<Row> all = new ArrayList<>();
		for (Item it : items)
		{
			Quote q = quotes.get(it.id);
			if (q == null || it.id == NATURE_RUNE || s.skip.contains(it.id) || (s.freeToPlay && it.members))
			{
				continue;
			}
			long price = s.offer ? q.offer : q.now;
			long vol = s.offer ? q.sellsHr : q.buysHr;
			long each = it.highAlch - price - nature;
			if (price <= 0 || nature <= 0 || each < s.minProfit)
			{
				continue;
			}
			long buyable = (long) (vol * VOLUME_SHARE * s.hours);
			long[] u = s.used.get(it.id);
			long left = it.limit > 0 ? Math.max(0, it.limit - (u == null ? 0 : u[0])) : Long.MAX_VALUE;
			long qty = Math.min(left, buyable);
			if (qty > 0)
			{
				all.add(new Row(it, price, each, qty, vol));
			}
		}
		// the items worth the most over the whole plan get the GE slots
		all.sort(Comparator.comparingLong((Row r) -> -r.each * Math.min(r.qty, want)).thenComparingLong(r -> -r.each));
		int k = Math.max(1, Math.min(s.maxItems, all.size()));
		List<Row> picked = new ArrayList<>(all.subList(0, Math.min(k, all.size())));
		List<Row> more = new ArrayList<>(all.subList(Math.min(k, all.size()), all.size()));
		// ...and the casts go to the best profit a cast first
		picked.sort(Comparator.comparingLong((Row r) -> -r.each).thenComparingLong(r -> -r.qty));
		List<Row> rows = new ArrayList<>();
		long left = want;
		long cash = s.cash > 0 ? s.cash : Long.MAX_VALUE;
		for (Row r : picked)
		{
			// what an open buy offer of it still waits on is bought already: casts and nature runes, but not the coins on
			// hand a second time — counted twice, the plan's first buy left no coins for the next item (code, 2026-11-03).
			// And no more of it while that offer is open (the card offers it again once the offer is done): the coins go
			// to the next item
			Long open = s.waiting.get(r.item.id);
			long waiting = open == null ? 0 : Math.min(Math.min(r.qty, left), open);
			long free = Math.max(0, cash - waiting * nature);
			long extra = open != null ? 0
				: Math.max(0, Math.min(r.qty, Math.min(left, free / Math.max(1, r.price + nature))));
			long q = waiting + extra;
			// never a row worth less than the minimum — 2026-11-02 19:13-19:14 (journal) the card said 2, 10 and 5 Dragon
			// javelin tips at +369 a cast (+738 to +3,690 in all), each right after a big buy was placed
			if (q > 0 && (open != null || q * r.each >= s.minTotal))
			{
				rows.add(new Row(r.item, r.price, r.each, q, r.volHr));
				left -= q;
				cash = Math.max(0, cash - waiting * nature - extra * (r.price + nature));
			}
			else
			{
				more.add(r);
			}
		}
		// worth the most over a whole plan first — the 1-of-a-kind oddities (thin trading) last
		more.sort(Comparator.comparingLong((Row r) -> -r.each * Math.min(r.qty, want)));
		return new Plan(Collections.unmodifiableList(rows), Collections.unmodifiableList(more), nature, want);
	}

	/** The alchable items of the wiki's /mapping (with a high alch value). */
	static List<Item> items(JsonArray mapping)
	{
		List<Item> out = new ArrayList<>();
		for (JsonElement e : mapping)
		{
			JsonObject m = e.getAsJsonObject();
			long ha = num(m, "highalch");
			if (ha > 0)
			{
				out.add(new Item((int) num(m, "id"), m.has("name") ? m.get("name").getAsString() : "Item " + num(m, "id"),
					ha, num(m, "limit"), m.has("members") && m.get("members").getAsBoolean()));
			}
		}
		return out;
	}

	/** Item id -> its prices now, from the wiki's /latest, /5m and /1h "data" objects (any may be null). */
	static Map<Integer, Quote> quotes(JsonObject latest, JsonObject five, JsonObject hour, long nowS)
	{
		Map<Integer, Quote> out = new HashMap<>();
		if (hour == null)
		{
			return out;
		}
		for (Map.Entry<String, JsonElement> e : hour.entrySet())
		{
			int id;
			try
			{
				id = Integer.parseInt(e.getKey());
			}
			catch (NumberFormatException ex)
			{
				continue;
			}
			JsonObject h = e.getValue().getAsJsonObject();
			JsonObject f = obj(five, e.getKey());
			JsonObject l = obj(latest, e.getKey());
			long high = l != null && num(l, "high") > 0 && nowS - num(l, "highTime") <= FRESH_S ? num(l, "high")
				: f != null && num(f, "avgHighPrice") > 0 ? num(f, "avgHighPrice") : num(h, "avgHighPrice");
			long low = f != null && num(f, "avgLowPrice") > 0 ? num(f, "avgLowPrice") : num(h, "avgLowPrice");
			out.put(id, new Quote(high, low > 0 ? low + 1 : 0, num(h, "highPriceVolume"), num(h, "lowPriceVolume")));
		}
		return out;
	}

	private static JsonObject obj(JsonObject data, String key)
	{
		JsonElement e = data == null ? null : data.get(key);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}

	static long num(JsonObject o, String k)
	{
		JsonElement e = o.get(k);
		// v2 averages may have two decimals (1.60.47): rounded, never cut (getAsLong cut 213.83 to 213)
		return e == null || e.isJsonNull() ? 0 : Math.round(e.getAsDouble());
	}
}
