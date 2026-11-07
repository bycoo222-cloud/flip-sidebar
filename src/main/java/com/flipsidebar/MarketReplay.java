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

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntToLongFunction;

/**
 * Replays the plugin's flip rules on EVERY item over the whole market's last day (MarketMemory) and scores each
 * item — the Java form of tools/market_backtest.py. Every 15 minutes of the day and every item: the pick (bid =
 * the block's average insta-sell, ask = its average insta-buy, margin after tax, quantity = the smaller of the GE
 * limit, a quarter of the cash, and a slice of the slower side's hourly volume, profit >= 10k), the buy (filled in
 * the next half hour by blocks whose insta-sell is at or under the bid, taking {@link #PART} of their volume), and
 * the sell (at max(market, break-even) for the patience, then the market).
 *
 * <p>⚠ An approximation from averages — far kinder than real flips (the first run: 4% lost against 27% real); used
 * only where it agreed with the real flips. MEASURED 2026-10-31 (tools/replay_vs_real.py): items it rated +5k a
 * flip or better — 69 real flips, 21% lost, +1,015k; weak or losing in it — 17 real flips, 41% lost, -263k (that
 * check replayed the same day as the flips; in the plugin it judges from the PAST day only).</p>
 */
final class MarketReplay
{
	/** Our share of a block's volume at our price: one buyer of many. */
	static final double PART = 0.10;
	/** Blocks a buy may take to fill (30 minutes); the rest is dropped. */
	static final int BUY_WAIT = 6;
	/** Decide every 3 blocks (15 minutes). */
	static final int STEP = 3;
	/** Blocks of history a decision looks back on (6 hours). */
	static final int HIST = 72;
	static final long MIN_PROFIT = 10_000;
	/** Under this margin each only on items trading 50k+ an hour (RiskLevel.minMargin, High). */
	static final long MARGIN_MIN = 20;
	static final long SMALL_MARGIN_FLOW = 50_000;
	/** Blocks that must be held before a replay counts (the first day fills in over ~25 minutes). */
	static final int MIN_BLOCKS = 260;

	/** One item's replayed flips over the day. */
	static final class Item
	{
		int flips;
		int lost;
		long profit;
		long minutes;

		long each()
		{
			return flips == 0 ? 0 : profit / flips;
		}
	}

	static final class Result
	{
		final Map<Integer, Item> items = new HashMap<>();
		int flips;
		int lost;
		long profit;
	}

	/**
	 * @param limit   an item's GE buy limit (0 = unknown)
	 * @param budget  the coins planned with
	 * @param share   the slice of the slower side's hourly volume one offer may take (AdjustWindow.volumeShare)
	 * @param patienceMin minutes a sell waits at break-even (FlipEngine.SELL_PATIENCE_MS)
	 */
	static Result run(MarketMemory memory, IntToLongFunction limit, long budget, double share, long patienceMin)
	{
		Result out = new Result();
		int n = MarketMemory.CAP;
		for (int id : memory.ids())
		{
			if (FlipEngine.NEVER_FLIP.contains(id))
			{
				continue;
			}
			int[] s = memory.series(id);
			long lim = limit.applyAsLong(id);
			for (int t = HIST; t < n - BUY_WAIT - 1; t += STEP)
			{
				long ask = s[t * 4];
				long bid = s[t * 4 + 1];
				if (ask <= 0 || bid <= 0)
				{
					continue;
				}
				long each = ask - FlipEngine.tax(ask, id) - bid;
				if (each <= 0)
				{
					continue;
				}
				long flow = flow(s, t);
				if (flow < 0 || (each < MARGIN_MIN && flow < SMALL_MARGIN_FLOW))
				{
					continue;
				}
				long qty = Math.min(lim > 0 ? lim : Long.MAX_VALUE,
					Math.min((long) (budget * FlipEngine.MAX_POSITION_FRAC) / bid, (long) (flow * share)));
				if (qty <= 0 || each * qty < MIN_PROFIT)
				{
					continue;
				}
				long[] r = simulate(s, t, id, bid, qty, patienceMin, n);
				if (r == null)
				{
					continue;
				}
				Item it = out.items.computeIfAbsent(id, k -> new Item());
				it.flips++;
				it.lost += r[0] < 0 ? 1 : 0;
				it.profit += r[0];
				it.minutes += r[1];
				out.flips++;
				out.lost += r[0] < 0 ? 1 : 0;
				out.profit += r[0];
			}
		}
		return out;
	}

	/**
	 * Units an hour on the slower side over the last 12 blocks; -1 when there are fewer than 12 traded blocks
	 * with both sides in the last {@link #HIST} (too thin to replay — the same minimum Research has).
	 */
	private static long flow(int[] s, int t)
	{
		int both = 0;
		for (int k = Math.max(0, t - HIST); k <= t; k++)
		{
			both += s[k * 4] > 0 && s[k * 4 + 1] > 0 ? 1 : 0;
		}
		if (both < 12)
		{
			return -1;
		}
		long hv = 0;
		long lv = 0;
		for (int k = t - 11; k <= t; k++)
		{
			hv += s[k * 4 + 2];
			lv += s[k * 4 + 3];
		}
		return Math.min(hv, lv);
	}

	/** {profit, minutes from the pick to the sale}, or null: nothing bought, or still held at the end of the day. */
	private static long[] simulate(int[] s, int t, int id, long bid, long qty, long patienceMin, int n)
	{
		long bought = 0;
		int k = t + 1;
		while (k < Math.min(n, t + 1 + BUY_WAIT) && bought < qty)
		{
			long lo = s[k * 4 + 1];
			if (lo > 0 && lo <= bid)
			{
				bought += Math.min(qty - bought, (long) (s[k * 4 + 3] * PART));
			}
			k++;
		}
		if (bought <= 0)
		{
			return null;
		}
		long even = FlipEngine.breakEven(bid, id);
		int start = k;
		for (; k < n; k++)
		{
			long hi = s[k * 4];
			if (hi <= 0)
			{
				continue;
			}
			long ask = (k - start) * 5L < patienceMin ? Math.max(hi, even) : hi;
			if (hi >= ask && s[k * 4 + 2] * PART >= bought * 0.25)
			{
				return new long[]{(ask - FlipEngine.tax(ask, id) - bid) * bought, (k - t) * 5L};
			}
		}
		return null;
	}
}
