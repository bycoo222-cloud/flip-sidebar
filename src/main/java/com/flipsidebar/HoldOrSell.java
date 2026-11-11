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
import java.util.List;

/**
 * ⭐ HOLD OR SELL (1.60.72) — for an item worth less now than what was paid: keep it listed at break-even, or sell at
 * the market and free the coins? User, 2026-11-07, holding 3 Twisted bucklers at -375k in session 2: "So do i dump it or
 * no..." — "this plug in should be handling this kind of thinking for me" — "build it now". The rule is the reasoning
 * worked out by hand for them that day, written down:
 * <ul>
 * <li>the item's WEEK (the wiki's hourly prices, 7 days): its usual price (the median hourly insta-buy) and how many of
 * the week's hours reached the break-even; its lowest hour is the STOP — under it, a dip has turned into a fall;</li>
 * <li>the cost of HOLDING: what the coins tied up would make flipping — the goal return
 * (FlipEngine.ROI_GOAL_PCT_HOUR, 0.33% an hour) — for each hour played; nothing while logged out;</li>
 * <li>HOLD when the price is still over the stop, the week's usual price is at or over the break-even, it reached the
 * break-even in at least {@link #MIN_WEEK_SHARE} of the week's hours, and selling now would lose more than an hour of
 * holding costs. Otherwise SELL — the reason in words.</li>
 * </ul>
 * The buckler that day: break-even 9,304,813, usual 9,479,617, 61% of the week's hours (17% of the last day), stop
 * 8,832,462, selling now -374,514, holding ~90k an hour played → HOLD. NOT measured as a gain: the user's rule.
 */
final class HoldOrSell
{
	/** at least this share of the week's hours reached the break-even, or it is SOLD */
	static volatile double MIN_WEEK_SHARE = 0.25;
	/** fewer hourly prices than this (about two days): not judged — the old rules apply */
	static final int MIN_HOURS = 48;

	static final class Verdict
	{
		final boolean hold;
		final long even;
		final long stop;
		final long typical;
		final double week;
		final double day;
		final long lossNow;
		final long costHour;
		/** one line: "Hold - sell at ... unless ..." or "Sell now - ..." with the reason */
		final String text;

		Verdict(boolean hold, long even, long stop, long typical, double week, double day, long lossNow, long costHour, String text)
		{
			this.hold = hold;
			this.even = even;
			this.stop = stop;
			this.typical = typical;
			this.week = week;
			this.day = day;
			this.lossNow = lossNow;
			this.costHour = costHour;
			this.text = text;
		}
	}

	/**
	 * ⭐ A VERDICT STANDS FOR A RE-PRICE WINDOW (1.60.89). The verdict to show now, given the one shown before
	 * ({@code prev}, decided at {@code prevSince}) and the one just worked out ({@code next}): a switch between Hold and
	 * Sell is only taken once the old decision has stood for {@code minMs} — except a Sell because the price fell under the
	 * week's lowest (a real fall), which is taken at once. Same decision: the new numbers. MEASURED in-game 2026-11-09
	 * 21:03-21:06 (journal): 5,358 Toadflax potion (unf) bought at 2,007 swapped between "Hold: sell at 2,048" and "held 8h+
	 * ... sell at the market" every 10-70 s, because "the loss is less than an hour of holding" flips between a market of
	 * 2,040 (loss 37.5k, an hour 35.5k: hold) and 2,041-2,042 (32k / 27k: sell). Null {@code next} (back at break-even, or
	 * no prices): null.
	 */
	static Verdict steady(Verdict prev, long prevSince, Verdict next, long market, long now, long minMs)
	{
		if (prev == null || next == null || prev.hold == next.hold)
		{
			return next;
		}
		if (!next.hold && market > 0 && market < next.stop)
		{
			return next;              // under the week's lowest: the dip has become a fall - say so at once
		}
		return now - prevSince >= minMs ? next : prev;
	}

	/**
	 * ⭐ THE PRICE A "REAL FALL" IS JUDGED ON (1.60.93): the market reading, or the last insta-buy ({@code lastBuy},
	 * {@code lastBuyAgeMs} old) when that trade is higher and newer than {@code windowMs}. For {@link #steady}'s exception:
	 * a reading under the week's lowest is not a fall while someone paid more than that inside the window. MEASURED
	 * in-game 2026-11-10 10:08-10:19 (journal), 13,164 Ascension shards (break-even 745, the week's lowest 716): the
	 * reading swapped between the last insta-buy (729; "the market now" while that trade is under 2 minutes old,
	 * FlipEngine.LATEST_FRESH_S) and the 5-minute average (714) — the same swap as OfferAdvice.stayPastCap's — so the
	 * verdict went Hold at 10:14:30, "sell at the market" at 10:14:50 (714 &lt; 716: "a fall, at once"), Hold at 10:19:47.
	 * An unknown trade ({@code lastBuy} 0 or a negative age) = the market reading.
	 */
	static long fallPrice(long market, long lastBuy, long lastBuyAgeMs, long windowMs)
	{
		return lastBuy > market && lastBuyAgeMs >= 0 && lastBuyAgeMs < windowMs ? lastBuy : market;
	}

	/**
	 * The verdict for {@code qty} of an item bought at {@code basis} each, worth {@code market} each now (the sell side),
	 * from its hourly prices ({@code hourly}: the wiki's /timeseries 1h "data", oldest first) and the share of the last
	 * day it reached the break-even ({@code dayShare}, -1 = from the hourly prices). Null when not under water or the
	 * prices are too few to judge.
	 */
	static Verdict decide(JsonArray hourly, long basis, long qty, long market, int itemId, double dayShare, double ratePctHour)
	{
		long even = FlipEngine.breakEven(basis, itemId);
		if (hourly == null || basis <= 0 || qty <= 0 || market <= 0 || market >= even)
		{
			return null;
		}
		List<Long> highs = new ArrayList<>();
		for (JsonElement e : hourly)
		{
			JsonObject o = e.getAsJsonObject();
			if (o.has("avgHighPrice") && !o.get("avgHighPrice").isJsonNull())
			{
				highs.add(Math.round(o.get("avgHighPrice").getAsDouble()));
			}
		}
		if (highs.size() < MIN_HOURS)
		{
			return null;
		}
		int reached = 0;
		for (long h : highs)
		{
			reached += h >= even ? 1 : 0;
		}
		double week = (double) reached / highs.size();
		if (dayShare < 0)
		{
			List<Long> last = highs.subList(Math.max(0, highs.size() - 24), highs.size());
			int r = 0;
			for (long h : last)
			{
				r += h >= even ? 1 : 0;
			}
			dayShare = (double) r / last.size();
		}
		List<Long> sorted = new ArrayList<>(highs);
		Collections.sort(sorted);
		long typical = sorted.get(sorted.size() / 2);
		long stop = sorted.get(0);
		long lossNow = qty * (market - FlipEngine.tax(market, itemId) - basis);
		long costHour = Math.round(qty * basis * ratePctHour / 100.0);
		String facts = String.format("usual price this week %s, at your price in %.0f%% of the week's hours (%.0f%% of the last day); "
			+ "selling now %+,d, holding ~%s an hour you play", FlipBrain.gp(typical), week * 100, dayShare * 100, lossNow, FlipBrain.gp(costHour));
		String why = market < stop ? String.format("it is under its lowest this week (%s) - the dip has become a fall", FlipBrain.gp(stop))
			: typical < even ? String.format("its usual price this week (%s) is under your break-even", FlipBrain.gp(typical))
			: week < MIN_WEEK_SHARE ? String.format("it reached your price in only %.0f%% of the week's hours", week * 100)
			: -lossNow <= costHour ? String.format("the loss (%,d) is less than an hour of holding", lossNow)
			: null;
		if (why != null)
		{
			return new Verdict(false, even, stop, typical, week, dayShare, lossNow, costHour,
				"Sell now: " + why + " (" + facts + ")");
		}
		return new Verdict(true, even, stop, typical, week, dayShare, lossNow, costHour,
			String.format("Hold: sell at %,d (break-even) unless it trades under %,d (%s)", even, stop, facts));
	}
}
