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
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The brain: scans the whole Grand Exchange and returns the flips worth doing.
 * A port of the Flip Sidebar's flip_advisor + flip_research + its scan, INCLUDING the calibration
 * on the user's 958 real Copilot flips (2026-10-31):
 * <ul>
 *   <li>quantity = a slice of the hour's trading (AdjustWindow.volumeShare) — big slices sat
 *       unfilled and lost money;</li>
 *   <li>buy-sell gap 1-15% (under 1% lost 36% of the time, over 15% lost 42%);</li>
 *   <li>liquid items only: 9m+ gp traded an hour (90% of the winning flips);</li>
 *   <li>margins under 10gp each only on items trading 50k+ units an hour (else 81% lost);</li>
 *   <li>bid AT the 5-minute average insta-sell, ask AT the 5-minute average insta-buy — where the
 *       user's Copilot flips actually traded (median 0.00% off both). ⚠ Never off a single recent
 *       trade: v1.11-1.16 bid at the HIGHER of the latest sale and the 5-minute average, and
 *       in-game 2026-10-31 that bought Aether catalyst at 269 (typical insta-sell 255-262, -182k)
 *       and Ranarr seed at 24,004 (typical 23,500, -84k) — one sale near the top set the price.</li>
 * </ul>
 * BLOCKING (network): run only on the plugin's engine thread.
 */
class FlipEngine
{
	private static final Logger log = LoggerFactory.getLogger(FlipEngine.class);

	static final Set<Integer> NEVER_FLIP = Set.of(13190);         // Old school bond: 10% to re-trade
	private static final Set<Integer> TAX_EXEMPT = Set.of(13190);
	static final double MAX_POSITION_FRAC = 0.25;
	static final double GAP_MIN_PCT = 1.0;
	static final double GAP_WARN_PCT = 8.0;
	static final double GAP_MAX_PCT = 15.0;
	static final long MIN_HOURLY_GP = 9_000_000L;
	static final long SMALL_MARGIN_MIN_UNITS_HR = 50_000L;
	private static final long MAPPING_MAX_AGE_MS = 24L * 3600 * 1000;

	/** Hears a scan as it goes — FlipBrain's status line and its early pick. */
	interface Progress
	{
		void checked(int done, int total);

		void found(Pick pick);
	}

	private final WikiPrices prices;
	private JsonArray mapping;
	private long mappingAt;
	/** Replaced whole, never changed in place: FlipBrain reads them from other threads. */
	private volatile Map<Integer, String> names = new HashMap<>();
	private volatile Map<Integer, Long> limits = new HashMap<>();

	@Inject
	FlipEngine(WikiPrices prices)
	{
		this.prices = prices;
	}

	static long tax(long sellPrice, int itemId)
	{
		if (sellPrice <= 50 || TAX_EXEMPT.contains(itemId))
		{
			return 0;
		}
		return Math.min((long) (sellPrice * 0.02), 5_000_000L);
	}

	String name(int itemId)
	{
		return names.get(itemId);
	}

	/** The GE buy limit, or 0 when unknown (before the first scan). */
	long limit(int itemId)
	{
		return limits.getOrDefault(itemId, 0L);
	}

	/** What is left of a buy limit after {@code used} this window; -1 when the limit is unknown. */
	static long remaining(long limit, long used)
	{
		return limit <= 0 ? -1 : Math.max(0, limit - used);
	}

	/**
	 * [bid, ask]: the 5-minute AVERAGE insta-sell and insta-buy; a side with no trades in those 5
	 * minutes falls back to the hour's average. Never a single trade. MEASURED 2026-10-31 on the
	 * user's 120 most recent finished Copilot flips: their buy price was a median 0.00% off the
	 * 5-minute average insta-sell (middle half -0.23% .. +0.11%) and -0.03% off the hour's; their
	 * sell price 0.00% off the 5-minute average insta-buy and +0.36% off the hour's
	 * (each flip matched to the wiki's 5-minute and hourly prices at the minute it traded).
	 */
	static long[] quote(JsonObject hour, JsonObject five)
	{
		long lo = five == null ? 0 : Research.num(five, "avgLowPrice");
		long hi = five == null ? 0 : Research.num(five, "avgHighPrice");
		if (lo <= 0 && hour != null)
		{
			lo = Research.num(hour, "avgLowPrice");
		}
		if (hi <= 0 && hour != null)
		{
			hi = Research.num(hour, "avgHighPrice");
		}
		return new long[]{lo, hi};
	}

	/**
	 * What one unit bought at {@code bid} makes if sold at today's ask ({@code quote[1]}) after the
	 * tax; {@code Long.MIN_VALUE} when there is no ask to judge by.
	 */
	static long marginNow(long bid, long[] quote, int itemId)
	{
		return quote[1] <= 0 ? Long.MIN_VALUE : quote[1] - tax(quote[1], itemId) - bid;
	}

	/** The lowest sell price that does not lose money after the 2% tax on what was paid. */
	static long breakEven(long paidEach, int itemId)
	{
		long p = paidEach + 1;
		while (p - tax(p, itemId) <= paidEach)
		{
			p++;
		}
		return p;
	}

	static long qtyFor(long budget, long price, long limit, long buyFlowHr, long sellFlowHr, double share)
	{
		if (price <= 0)
		{
			return 0;
		}
		long q = (long) (budget * MAX_POSITION_FRAC) / price;
		if (limit > 0)
		{
			q = Math.min(q, limit);
		}
		// a SLICE of what trades in an hour, sized to the slower side — a flip is only done
		// when it has been bought AND sold
		q = Math.min(q, Math.max(1, (long) (Math.min(buyFlowHr, sellFlowHr) * share)));
		return Math.max(0, q);
	}

	/** One researched item -> a Pick, or null if it fails the risk profile. */
	static Pick evaluate(int itemId, String name, long limit, long[] quote, long budget, RiskLevel risk,
		AdjustWindow window, Research r, long nowS)
	{
		Pick p = judge(itemId, name, limit, quote, budget, risk, window, r, nowS);
		return p == null || p.rejected != null ? null : p;
	}

	/**
	 * Like {@link #evaluate}, but an item that fails the risk profile is still priced — the same
	 * bid, ask and quantity — with {@code Pick.rejected} saying why. For an item the user picked
	 * themselves (user, 2026-10-31: "if the user manually wants to buy a specific item it still
	 * needs to input a good item quantity and price"). Null only when there is no price at all.
	 */
	static Pick judge(int itemId, String name, long limit, long[] quote, long budget, RiskLevel risk,
		AdjustWindow window, Research r, long nowS)
	{
		long buy = quote[0];
		long sell = quote[1];
		if (buy <= 0 || sell <= 0)
		{
			return null;
		}
		boolean known = r != null && r.ok;
		String rejected = null;
		if (NEVER_FLIP.contains(itemId))
		{
			rejected = "bonds are never a flip - 10% to make one tradeable again";
		}
		else if (!known)
		{
			rejected = r == null ? "no trade history" : r.why;
		}
		else if (r.contiguity < risk.minContiguity)
		{
			rejected = String.format("trades in only %.0f%% of 5-minute periods - too thin for %s risk",
				r.contiguity * 100, risk);
		}
		else if (r.sampled > 0 && (double) r.negativeBuckets / r.sampled > risk.maxNegativeFrac)
		{
			rejected = String.format("no margin after tax in %d of the last %d 5-minute periods",
				r.negativeBuckets, r.sampled);
		}
		// AT the averages, where the user's Copilot flips traded. (Until 1.16.7 one step inside the
		// book on top: on a 84 -> 87 Chaos rune that cost 2 of the 3gp margin.)
		long bid = buy;
		long ask = sell;
		long each = (ask - bid) - tax(ask, itemId);
		double gap = (ask - bid) * 100.0 / Math.max(bid, 1);
		if (rejected == null && each <= 0)
		{
			rejected = "no margin after the 2% tax right now";
		}
		else if (rejected == null && gap < GAP_MIN_PCT)
		{
			rejected = String.format("buy-sell gap %.1f%% is under %.0f%% - too thin", gap, GAP_MIN_PCT);
		}
		else if (rejected == null && gap > GAP_MAX_PCT)
		{
			rejected = String.format("buy-sell gap %.1f%% is over %.0f%% - 42%% of such flips lost", gap, GAP_MAX_PCT);
		}
		else if (rejected == null && each < risk.minMargin && (r.buyFlowHr + r.sellFlowHr) < SMALL_MARGIN_MIN_UNITS_HR)
		{
			rejected = String.format("%dgp each on an item trading under %,d an hour", each, SMALL_MARGIN_MIN_UNITS_HR);
		}
		// with no trade flow known, size on cash and the buy limit only
		long qty = known ? qtyFor(budget, bid, limit, r.buyFlowHr, r.sellFlowHr, window.volumeShare)
			: Math.min((long) (budget * MAX_POSITION_FRAC) / Math.max(bid, 1), limit > 0 ? limit : Long.MAX_VALUE);
		if (qty <= 0 && rejected == null)
		{
			rejected = "not enough cash for one";
		}
		double fill = known ? Math.max(r.fillMinutes(qty, true), r.fillMinutes(qty, false)) : -1;
		if (rejected == null && fill > window.minutes * risk.fillSlack * 2)
		{
			rejected = String.format("~%.0f min to fill - too slow for a %s re-price", fill, window);
		}

		Pick p = new Pick();
		p.itemId = itemId;
		p.name = name;
		p.bid = bid;
		p.ask = ask;
		p.qty = Math.max(0, qty);
		p.each = each;
		p.total = each * p.qty;
		p.fillMinutes = fill;
		p.asOf = nowS;
		p.research = r;
		p.limit = limit;
		p.rejected = rejected;
		if (gap > GAP_WARN_PCT)
		{
			p.warnings.add(String.format("gap %.1f%% is wide - 13%% of such flips lost money", gap));
		}
		if (known && r.trendKnown && Math.abs(r.trendPct) >= 2)
		{
			p.warnings.add(String.format("buy price %+.1f%% vs the previous 3h", r.trendPct));
		}
		else if (known && !r.trendKnown)
		{
			p.warnings.add("not enough history to judge the trend");
		}
		if (known && r.medianMargin > 0 && each > r.medianMargin * 2)
		{
			p.warnings.add(String.format("margin %d is %.1fx its 4h median (%d) - may close before you fill",
				each, (double) each / r.medianMargin, r.medianMargin));
		}
		if (known && r.marginMin <= 0)
		{
			p.warnings.add("margin dipped to " + r.marginMin + " at its worst in the last 4h");
		}
		return p;
	}

	/**
	 * Scan the GE: cheap filters over every item, then research every candidate that could make
	 * {@code minProfit} (the most promising first, at most {@code maxResearch}) and judge each.
	 * Returns only the picks at or above {@code minProfit}, best gp an hour first — the sidebar's
	 * "best" list. {@code progress} hears each item checked and each pick found.
	 */
	List<Pick> scan(long budget, RiskLevel risk, AdjustWindow window, long minProfit, Set<Integer> busy,
		Map<Integer, Long> limitUsed, int maxResearch, Progress progress) throws IOException
	{
		long nowMs = System.currentTimeMillis();
		if (mapping == null || nowMs - mappingAt > MAPPING_MAX_AGE_MS)
		{
			mapping = prices.mapping();
			mappingAt = nowMs;
			Map<Integer, String> n = new HashMap<>();
			Map<Integer, Long> l = new HashMap<>();
			for (JsonElement e : mapping)
			{
				JsonObject m = e.getAsJsonObject();
				int id = (int) Research.num(m, "id");
				n.put(id, m.has("name") ? m.get("name").getAsString() : String.valueOf(id));
				l.put(id, Research.num(m, "limit"));
			}
			names = n;
			limits = l;
		}
		JsonObject h1 = prices.data("/1h");
		JsonObject h5 = prices.data("/5m");
		long nowS = nowMs / 1000;
		long cap = (long) (budget * MAX_POSITION_FRAC);

		List<Object[]> cand = new ArrayList<>();       // {score, id, quote, limit left}
		for (Map.Entry<Integer, String> e : names.entrySet())
		{
			int id = e.getKey();
			String key = String.valueOf(id);
			if (busy.contains(id) || NEVER_FLIP.contains(id) || !h1.has(key))
			{
				continue;
			}
			JsonObject v = h1.getAsJsonObject(key);
			long[] q = quote(v, h5.has(key) ? h5.getAsJsonObject(key) : null);
			long b = q[0];
			long s = q[1];
			long bv = Research.num(v, "lowPriceVolume");
			long sv = Research.num(v, "highPriceVolume");
			if (b <= 0 || s <= 0 || Math.min(bv, sv) < 120 || b > cap || (bv + sv) * b < MIN_HOURLY_GP)
			{
				continue;
			}
			long pe = (s - b) - tax(s, id);
			if (pe <= 0)
			{
				continue;
			}
			// sized to what is LEFT of the 4-hour buy limit; a used-up limit waits for its reset
			long left = remaining(limits.getOrDefault(id, 0L), limitUsed.getOrDefault(id, 0L));
			if (left == 0)
			{
				continue;
			}
			long lim = Math.max(0, left);
			long qq = Math.min(cap / b, Math.min(lim > 0 ? lim : Long.MAX_VALUE, Math.min(bv, sv)));
			if (qq <= 0 || pe * qq < minProfit)
			{
				continue;
			}
			cand.add(new Object[]{(double) (pe * qq) / Math.max(qq * b, 1), id, q, lim});
		}
		cand.sort((x, y) -> Double.compare((double) y[0], (double) x[0]));

		List<Pick> picks = new ArrayList<>();
		int n = Math.min(maxResearch, cand.size());
		for (int i = 0; i < n; i++)
		{
			if (Thread.currentThread().isInterrupted())
			{
				break;                // the plugin was turned off mid-scan
			}
			progress.checked(i, n);
			int id = (int) cand.get(i)[1];
			try
			{
				Research r = Research.of(id, prices.timeseries(id, "5m"));
				Pick p = evaluate(id, names.get(id), (long) cand.get(i)[3], (long[]) cand.get(i)[2],
					budget, risk, window, r, nowS);
				if (p != null && p.total >= minProfit)
				{
					picks.add(p);
					progress.found(p);
				}
			}
			catch (IOException | RuntimeException ex)
			{
				log.debug("Flip Sidebar: research failed for {}", id, ex);
			}
		}
		progress.checked(n, n);
		picks.sort(Comparator.comparingDouble(Pick::perHour).reversed());
		return Collections.unmodifiableList(picks);
	}
}
