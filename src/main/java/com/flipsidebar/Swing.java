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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.IntToLongFunction;

/**
 * ⭐ SWING TRADES — the opt-in "Swing trades" setting, off by default. User, 2026-11-01: "thers 100m thats never touch.
 * thats like 2/3 of the cash un used", then "it should be toggleable ... but yea we should be utilizing as much as we
 * can", "i want it and i want to use it i want to maximize profit". Slots, not cash, cap the flips; an item held in the
 * inventory uses cash and no slot (Holding). A swing buys a liquid item near the BOTTOM of its last day's range, keeps it
 * in the inventory, and sells when it is back at the day's usual high.
 *
 * <p>MEASURED (tools/swing_replay.py, 100 hours of the whole market, entries over 76 hours, items under 100k with a usual
 * spread of 3% or less, fills a tenth of each block's volume): buy at or under the day's 10% insta-sell, sell at its 60%
 * insta-buy, up to 8 hours — 346 trades, 10% lost, median +2.45%, +10.5m against +7.1m for normal flips at the same
 * moments (~+45k an hour more for ~23m more cash held). By the gain expected at the buy: 1-2% 18% lost (mean +0.99%),
 * 2-3% 4%, 3-5% 10%, 5-8% 4%, 8%+ 12% (median +8.3% — big ones came back too). A buy at a NEW LOW for the day lost 30%
 * of the time against 10% inside the range (only 10 such swings — a small sample): not bought. Not yet measured on the
 * user's own trades: every swing card and buy is marked in the trade journal.</p>
 */
final class Swing
{
	/** Buy at or under this quantile of the day's 5-minute insta-sell averages. */
	static volatile double LOW_Q = 0.10;
	/** The day's usual high: this quantile of its 5-minute insta-buy averages — a swing needs MIN_PCT to it. */
	static volatile double HIGH_Q = 0.60;
	/**
	 * A swing SELLS at this much after tax, % of what was paid (FlipBrain.goal) — the user's choice, 2026-11-01 ("+1% or
	 * target"; the target is always at least this, so it is this). Replay (342 swings, 76h): at the target +10.28m, 11%
	 * lost, held 1.6h median · at +1% after tax +9.69m, 7% lost, 0.6h · at the first profit +2.41m, 2% lost.
	 */
	static volatile double EXIT_PCT = 1.0;
	/** The target must clear this much after tax, % of the bid. */
	static volatile double MIN_PCT = 1.0;
	/** The item's usual spread at most this, % — its profit must come from the swing, not the spread. */
	static volatile double MAX_SPREAD_PCT = 3.0;
	/** Traded both ways in at least this share of the blocks held. */
	static volatile double MIN_ACTIVE = 0.90;
	/** A swing waits this long for its target; then it is held like any other buy (FlipBrain.underWater). */
	static volatile long HOLD_MS = 8 * 3_600_000L;
	/** At least this many blocks of the day held (12 hours). */
	static final int MIN_BLOCKS = 144;
	/** A quarter of the slower side's hourly units at most (the replay's size). */
	static volatile double FLOW_SHARE = 0.25;

	private Swing()
	{
	}

	/** The least price that sells what cost {@code paid} each at EXIT_PCT after tax. */
	static long exitPrice(long paid, int itemId)
	{
		return FlipEngine.breakEven(paid + (long) Math.ceil(paid * EXIT_PCT / 100.0) - 1, itemId);
	}

	/**
	 * An item's day from a MarketMemory series ({avg insta-buy, avg insta-sell, insta-buy volume, insta-sell volume} per
	 * block, 0 = none): {buy at or under, sell at, units an hour on the slower side over the last hour, the day's lowest
	 * insta-sell}; null when the item does not qualify (too few blocks, not liquid, a wide usual spread).
	 */
	static long[] range(int[] s, int held)
	{
		int n = s.length / 4;
		List<long[]> both = new ArrayList<>();
		for (int i = 0; i < n; i++)
		{
			if (s[i * 4] > 0 && s[i * 4 + 1] > 0)
			{
				both.add(new long[]{s[i * 4], s[i * 4 + 1]});
			}
		}
		if (held < MIN_BLOCKS || both.size() < MIN_ACTIVE * held)
		{
			return null;
		}
		double[] spread = new double[both.size()];
		long[] lows = new long[both.size()];
		long[] highs = new long[both.size()];
		for (int i = 0; i < both.size(); i++)
		{
			long[] b = both.get(i);
			spread[i] = (b[0] - b[1]) * 100.0 / b[1];
			highs[i] = b[0];
			lows[i] = b[1];
		}
		Arrays.sort(spread);
		Arrays.sort(lows);
		Arrays.sort(highs);
		if (spread[spread.length / 2] > MAX_SPREAD_PCT)
		{
			return null;
		}
		long hv = 0;
		long lv = 0;
		for (int i = Math.max(0, n - 12); i < n; i++)
		{
			hv += s[i * 4 + 2];
			lv += s[i * 4 + 3];
		}
		return new long[]{lows[(int) (LOW_Q * (lows.length - 1))], highs[(int) (HIGH_Q * (highs.length - 1))], Math.min(hv, lv),
			lows[0]};
	}

	/**
	 * The swing buys now, best first: items whose bid now ({@code bidNow}, 0 = no price) is at or under the day's low,
	 * selling at the day's usual high clears MIN_PCT after tax. {@code skip}: items not to suggest (in an offer, held,
	 * blocked, resting, members' items for a free-to-play account). Quantity: the GE limit left ({@code limitLeft}, -1 =
	 * unknown), a quarter of the slower side's hourly units, and {@code cash}.
	 */
	static List<Pick> find(Iterable<Integer> ids, IntFunction<int[]> series, int held, IntToLongFunction bidNow,
		IntToLongFunction limitLeft, IntFunction<String> name, Set<Integer> skip, long cash, long minProfit, long nowS)
	{
		List<Pick> out = new ArrayList<>();
		for (int id : ids)
		{
			if (skip.contains(id) || FlipEngine.NEVER_FLIP.contains(id))
			{
				continue;
			}
			long bid = bidNow.applyAsLong(id);
			if (bid <= 0 || bid >= FlipEngine.LEARNED_UNDER)
			{
				continue;
			}
			long[] r = range(series.apply(id), held);
			if (r == null || bid > r[0] || bid < r[3])
			{
				continue;             // not low in its day — or under the whole day: falling, not swinging
			}
			long target = r[1];
			if ((target - FlipEngine.tax(target, id) - bid) * 100.0 / bid < MIN_PCT)
			{
				continue;
			}
			long exit = Math.min(target, exitPrice(bid, id));         // sold at +1% after tax (EXIT_PCT)
			long each = exit - FlipEngine.tax(exit, id) - bid;
			long left = limitLeft.applyAsLong(id);
			long qty = Math.min(cash / bid, Math.max(1, (long) (r[2] * FLOW_SHARE)));
			qty = left >= 0 ? Math.min(qty, left) : qty;
			if (qty <= 0 || each * qty < minProfit)
			{
				continue;
			}
			Pick p = new Pick();
			p.itemId = id;
			p.name = name.apply(id);
			p.bid = bid;
			p.ask = exit;
			p.qty = qty;
			p.each = each;
			p.total = each * qty;
			p.fillMinutes = r[2] > 0 ? qty * 60.0 / r[2] : -1;
			p.asOf = nowS;
			p.limit = left;
			p.swing = true;
			out.add(p);
		}
		out.sort(Comparator.comparingDouble(Pick::perHour).reversed());
		return out;
	}

	/**
	 * ⭐ DEEP SWINGS — a multi-day dip (1.60.44; user, 2026-11-07: "Maybe we buy it when its low knowing its coming back" …
	 * "I want all of it"): an item at least {@link FlipEngine#DEEP_DIP_PCT}% under its 30-day level (FlipEngine.avg30,
	 * profiles.json) — kept in the inventory and sold at +1% after tax (EXIT_PCT), waiting up to FlipEngine.DEEP_HOLD_MS.
	 * MEASURED (NOTES.md "BIG BRAIN FINDINGS": every item's daily insta-sell for a year, items 100 gp+ trading 5m+ a day;
	 * bought X% under the 30-day median, sold at the first day within 7 whose insta-buy average was 1%+ over break-even,
	 * else day 7; vs the same items on random days): 10%+ under — +5.41% vs +4.23% (1st half of the year), +6.20% vs +4.22%
	 * (2nd), lost 12-14% vs 18-19%; 20%+ under — edge +2.7 / +4.4 points, lost 10-13% vs 17%. ⚠ Daily averages (fills at
	 * them assumed); the market fell ~10% the last month (holding costs). Sized to {@link #DEEP_FLOW_SHARE} of an hour's
	 * trading and the cash for one buy. Not 1m+ (bid < LEARNED_UNDER as the day swings), not an item being dumped.
	 */
	static List<Pick> findDeep(Iterable<Integer> ids, IntFunction<int[]> series, int held, IntToLongFunction bidNow,
		IntToLongFunction limitLeft, IntFunction<String> name, Set<Integer> skip, java.util.function.IntPredicate dumping,
		long cash, long minProfit, long nowS)
	{
		List<Pick> out = new ArrayList<>();
		java.util.Map<Integer, Long> level = FlipEngine.avg30;
		for (int id : ids)
		{
			Long lv = level.get(id);
			if (lv == null || skip.contains(id) || FlipEngine.NEVER_FLIP.contains(id) || dumping.test(id))
			{
				continue;
			}
			long bid = bidNow.applyAsLong(id);
			if (bid < 100 || bid >= FlipEngine.LEARNED_UNDER || bid > lv * (1 - FlipEngine.DEEP_DIP_PCT / 100))
			{
				continue;
			}
			int[] a = series.apply(id);
			long units = 0;
			for (int i = 0; i + 3 < a.length; i += 4)
			{
				units += a[i + 2] + a[i + 3];
			}
			long perHour = held > 0 ? units * 12 / held : 0;
			long exit = exitPrice(bid, id);
			long each = exit - FlipEngine.tax(exit, id) - bid;
			long left = limitLeft.applyAsLong(id);
			long qty = Math.min(cash / bid, Math.max(1, (long) (perHour * DEEP_FLOW_SHARE)));
			qty = left >= 0 ? Math.min(qty, left) : qty;
			if (perHour <= 0 || qty <= 0 || each * qty < minProfit)
			{
				continue;
			}
			Pick p = new Pick();
			p.itemId = id;
			p.name = name.apply(id);
			p.bid = bid;
			p.ask = exit;
			p.qty = qty;
			p.each = each;
			p.total = each * qty;
			p.fillMinutes = qty * 60.0 / perHour;
			p.asOf = nowS;
			p.limit = left;
			p.swing = true;
			p.deep = true;
			p.boostWhy = String.format("%.0f%% under its 30-day level (%,d)", 100 - bid * 100.0 / lv, lv);
			p.warnings.add(String.format("%.0f%% under its 30-day level (%,d) - a multi-day dip: kept up to %d days for +1%% "
				+ "after tax", 100 - bid * 100.0 / lv, lv, FlipEngine.DEEP_HOLD_MS / 86_400_000L));
			out.add(p);
		}
		out.sort(Comparator.comparingDouble(Pick::perHour).reversed());
		return out;
	}

	/** A deep swing buys at most this share of an hour's trading (both ways) of the item. */
	static volatile double DEEP_FLOW_SHARE = 0.10;
}
