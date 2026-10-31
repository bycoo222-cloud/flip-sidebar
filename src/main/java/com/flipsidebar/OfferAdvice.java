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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What to do about each open offer — collect, raise, lower, hold or cancel. The rule is the
 * user's own stop-loss:
 * <blockquote>"if i have to adjust the offer down we cancel the offer and sell the item bc the
 * price is actively falling."</blockquote>
 * <ul>
 *   <li>A BUY whose flip no longer pays — today's sell price after tax is not above the bid — is
 *       CANCELLED at once: stop buying, sell what was bought (FlipBrain "cut the loss"). User,
 *       2026-10-31: "when something goes south it gets a cancel buy ... you stop buying it you sell
 *       what you bough and you try to minimize loss". ⚠ Waiting for "no fill for a window" was wrong
 *       for buys: in a fall, sellers dump INTO the bid, so it keeps filling and never went red.</li>
 *   <li>Market moved your way: RAISE (buy or sell) — at once.</li>
 *   <li>Market moved against you: only once the offer has gone a whole re-price window with no
 *       fill — a buy is CANCELLED (do not chase it down), a sell is LOWERED to the market.
 *       ⚠ In-game 2026-10-31 a 1% dip a few minutes in said "cancel and dump" on Ascension
 *       shards; sold at 1,741, they were back at 1,779 within the hour. A blip is not "actively
 *       falling".</li>
 *   <li>A sell is never cancelled — it is adjusted with the GE's Modify button (user: "when its a
 *       sell offer it should never ba a cancel offer just and adjust offer").</li>
 * </ul>
 * "The market" is the 5-minute AVERAGE, never one trade (FlipEngine.quote's lesson).
 */
final class OfferAdvice
{
	static final String COLLECT = "COLLECT";
	static final String CANCEL = "CANCEL";
	static final String RAISE = "RAISE";
	static final String LOWER = "LOWER";
	static final String HOLD = "HOLD";
	static final String IGNORED = "ignored";

	/** 0.4% in your favour: re-price up. */
	private static final double RAISE_PCT = 0.004;
	/** 1.0% against you, a whole window without a fill: the trend broke. */
	private static final double BAIL_PCT = 0.010;
	private static final Map<String, Integer> RANK = Map.of(
		COLLECT, 0, CANCEL, 1, LOWER, 2, RAISE, 3, HOLD, 4, IGNORED, 6);

	final GameSnapshot.Offer offer;
	final String verdict;
	/** The price to change the offer to; 0 = none. */
	final long target;
	final String why;

	private OfferAdvice(GameSnapshot.Offer offer, String verdict, long target, String why)
	{
		this.offer = offer;
		this.verdict = verdict;
		this.target = target;
		this.why = why;
	}

	boolean ignored()
	{
		return IGNORED.equals(verdict);
	}

	/** RAISE or LOWER: done with the GE's Modify button. */
	boolean adjust()
	{
		return RAISE.equals(verdict) || LOWER.equals(verdict);
	}

	/**
	 * Most urgent first. {@code five} is the wiki's /5m data (null with live prices off);
	 * {@code windowMs} the re-price window an adverse move must outlast.
	 */
	static List<OfferAdvice> advise(List<GameSnapshot.Offer> offers, JsonObject five, Set<String> ignored,
		long windowMs, long nowMs)
	{
		List<OfferAdvice> out = new ArrayList<>();
		for (GameSnapshot.Offer o : offers)
		{
			out.add(one(o, five, ignored, windowMs, nowMs));
		}
		out.sort(Comparator.comparingInt((OfferAdvice a) -> RANK.getOrDefault(a.verdict, 9))
			.thenComparingInt(a -> a.offer.slot));
		return out;
	}

	private static OfferAdvice one(GameSnapshot.Offer o, JsonObject five, Set<String> ignored, long windowMs,
		long nowMs)
	{
		// ⚠ an ignored offer is not flipping money (e.g. coins parked for someone else in a buy that
		// is never meant to fill) — never advise on it
		if (ignored.contains(o.key()))
		{
			return new OfferAdvice(o, IGNORED, 0, "not your money");
		}
		if (o.cancelled)
		{
			return new OfferAdvice(o, COLLECT, 0, "cancelled - collect what is left");
		}
		if (o.complete || (o.total > 0 && o.filled >= o.total))
		{
			return new OfferAdvice(o, COLLECT, 0, "filled - collect it and free the slot");
		}
		JsonElement e = five == null ? null : five.get(String.valueOf(o.itemId));
		JsonObject f = e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
		// what the card would offer now: the 5-minute average on this side of the book
		long market = f == null ? 0 : Research.num(f, o.buy ? "avgLowPrice" : "avgHighPrice");
		if (market <= 0 || o.price <= 0)
		{
			return new OfferAdvice(o, HOLD, o.price, "no trades in the last 5 minutes to compare with");
		}
		String part = o.filled > 0 ? String.format("%,d/%,d filled · ", o.filled, o.total) : "";
		if (o.buy)
		{
			long sellNow = Research.num(f, "avgHighPrice");
			long margin = FlipEngine.marginNow(o.price, new long[]{market, sellNow}, o.itemId);
			if (margin != Long.MIN_VALUE && margin <= 0)
			{
				return new OfferAdvice(o, CANCEL, 0, String.format("%sgone south - it sells for %,d now, %+,d each after tax: "
					+ "stop buying, sell what you have", part, sellNow, margin));
			}
		}
		long since = o.lastFill > 0 ? o.lastFill : o.placedAt;
		// unknown (open before tracking) counts as long enough
		boolean waited = since <= 0 || nowMs - since >= windowMs;
		double drift = (double) (market - o.price) / o.price;
		if (drift >= RAISE_PCT)
		{
			return new OfferAdvice(o, RAISE, market, String.format("%smarket %+.1f%% - %s", part, drift * 100,
				o.buy ? "your bid is behind" : "trend confirming, ask more"));
		}
		if (drift <= -BAIL_PCT && waited)
		{
			return o.buy
				? new OfferAdvice(o, CANCEL, 0, String.format("%smarket %+.1f%% and no fill for a window - do not chase it down",
					part, drift * 100))
				: new OfferAdvice(o, LOWER, market, String.format("%smarket %+.1f%% and no fill for a window - falling, sell at the market",
					part, drift * 100));
		}
		if (drift <= -BAIL_PCT)
		{
			return new OfferAdvice(o, HOLD, o.price, String.format("%smarket %+.1f%% - give it the re-price window",
				part, drift * 100));
		}
		return new OfferAdvice(o, HOLD, o.price, String.format("%smarket %+.1f%% of your %s", part, drift * 100,
			o.buy ? "bid" : "ask"));
	}
}
