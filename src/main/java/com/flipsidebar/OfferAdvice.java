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
	/** FlipBrain.paid's third field: what an under-water holding does — park, or sell at the market. */
	static final long PAID_PARK = 1;
	static final long PAID_CUT = 2;
	/** ...held FlipBrain.HOLD_MAX_MS under water: sell at the market (1.54.0). */
	static final long PAID_TIME = 3;
	/** ...the user's own Hold (the card's Hold button): never lowered under break-even (1.55.0). */
	static final long PAID_HELD = 4;
	static final String WAIT = "WAIT";      // "HOLD" until 1.42.1 — renamed so it is not confused with Holding (user)
	static final String IGNORED = "ignored";

	/** 0.4% in your favour: re-price up. */
	static volatile double RAISE_PCT = 0.004;
	/** 1.0% against you, a whole window without a fill: the trend broke. */
	static volatile double BAIL_PCT = 0.010;
	/**
	 * ⭐ A buy is never RAISED more than {@link #CHASE_PCT} over the price it was first placed at (FlipBrain.firstBids) —
	 * at least one gp step is always allowed, or an item under 100 gp could never move. User, 2026-11-01, of the day's
	 * losses: "im getting fustrated with us constantly loosing money by mske bad buying choices". The Rondache: RAISED
	 * +2.3% one second after placing (18,087,184 to 18,500,000), all 3 filled in 90 s, then it slid (-3.10m). MEASURED
	 * (tools/dear_hold_replay.py, the whole market, 100 h, items 1m+): a fill at the card's bid made +0.98% a flip, a
	 * fill reached only by a raise +0.33%; raises capped at 1% made +0.60% a fill. Until 1.60.28 the cap was for items
	 * 1m+ only. MEASURED again, release-bar session 2026-11-04 17:45 - 11-05 05:38 (journal + flips archive, every
	 * price): bought at the card's price 60 flips +1.22m, none lost; raised up to 1% 20 flips +284k, none lost; raised
	 * over 1% 18 flips -252k, 5 of the 6 losses — Red chinchompa 1,088 to 1,100 (-163k), Dragon chainbody 148,000 to
	 * 158,923 (-119k). NOT known: how many of those buys would have filled at all without the raise.
	 */
	static volatile double CHASE_PCT = 0.01;
	/**
	 * ⭐ A BUY THE MARKET LEFT is cancelled after this long with no fill (1.60.71): the market is past the chase cap
	 * ({@link #CHASE_PCT} over where the buy was placed), so it is not raised — and it is not coming back either. User,
	 * 2026-11-07 (session 2): "the amethyst arrows havent bought more in an hour and 48 minutes" — "a buy offer sitting
	 * for almost two hours is kinda wild". MEASURED: Amethyst arrow 11,000 @ 121 placed 14:29, 4,674 filled by 14:30,
	 * none after; the market 136-138 (+12%). Before, slowBuy held it: a PROVEN item's slow window is stretched for its
	 * size (slowWindow, ~2 h at the 30-minute window, so ~4 h), and its pace came from the first minute's 4,674 (so it
	 * looked fast). 30 minutes, proven or not, whatever the window setting; 0 = off. Live in rules.json ("leftBuyMin").
	 */
	static volatile long LEFT_BUY_MS = 30 * 60_000L;
	/** HoldOrSell's HOLD reasons by item (FlipBrain.judgeHolds): a sell of one is not lowered under break-even. */
	static volatile java.util.Map<Integer, String> holdWhy = java.util.Map.of();
	/** A buy with nothing bought this long after it was placed is cancelled (emptyBuy). */
	static volatile long EMPTY_BUY_MS = 3 * 60_000L;
	/** ...with this re-price window or a shorter one: the 5-minute window it was measured on. */
	static volatile long EMPTY_WINDOW_MS = 5 * 60_000L;
	private static final Map<String, Integer> RANK = Map.of(
		COLLECT, 0, CANCEL, 1, LOWER, 2, RAISE, 3, WAIT, 4, IGNORED, 6);

	final GameSnapshot.Offer offer;
	final String verdict;
	/** The price to change the offer to; 0 = none. */
	final long target;
	final String why;
	/**
	 * What the whole offer makes after tax — a sell at its own price against what was paid, a buy sold at today's
	 * insta-buy (FlipEngine.quoteBuy); {@code Long.MIN_VALUE} = not known. The Offers row shows it (user, 2026-11-01:
	 * "on offers can u squeeze expected proffit somewhere?"). Set by advise.
	 */
	long expected = Long.MIN_VALUE;
	/**
	 * A buy the market went past the chase cap on (the "not raised" WAIT): the price the cap is counted from (where the
	 * buy was placed); 0 = not. FlipBrain remembers it for {@link #stayPastCap}.
	 */
	long pastCap;

	private OfferAdvice(GameSnapshot.Offer offer, String verdict, long target, String why)
	{
		this.offer = offer;
		this.verdict = verdict;
		this.target = target;
		this.why = why;
	}

	/** {@code a} with {@code more} added to its why (FlipBrain: "or alch it"), its expected profit kept. */
	/** A RAISE on a buy that the inventory's coins cannot pay for (FlipBrain.raisesYouCanPay): WAIT, and why. */
	static OfferAdvice cannotPay(OfferAdvice a, long extra)
	{
		OfferAdvice c = new OfferAdvice(a.offer, WAIT, a.offer.price, String.format("raising to %,d needs %,d more coins "
			+ "than your inventory has - it waits at your price", a.target, extra));
		c.expected = a.expected;
		return c;
	}

	static OfferAdvice withMore(OfferAdvice a, String more)
	{
		OfferAdvice c = new OfferAdvice(a.offer, a.verdict, a.target, a.why + more);
		c.expected = a.expected;
		return c;
	}

	/** {@code a}'s offer CANCELLED to free its slot (FlipBrain.freeSlotForHeld), its expected profit kept. */
	static OfferAdvice freeSlot(OfferAdvice a, String why)
	{
		OfferAdvice c = new OfferAdvice(a.offer, CANCEL, 0, why);
		c.expected = a.expected;
		return c;
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
		return advise(offers, five, null, null, Map.of(), ignored, windowMs, nowMs);
	}

	/** With the wiki's /latest too: "the market now" is each side's fresh last trade (FlipEngine.quoteLive / quoteBuy). */
	static List<OfferAdvice> advise(List<GameSnapshot.Offer> offers, JsonObject five, JsonObject hour, JsonObject latest,
		Map<Integer, long[]> paid, Set<String> ignored, long windowMs, long nowMs)
	{
		return advise(offers, five, hour, latest, paid, Map.of(), ignored, windowMs, nowMs);
	}

	/** ...and {@code firstBid}: item -> the price its buy was first placed at (FlipBrain.firstBids; absent = this offer's). */
	static List<OfferAdvice> advise(List<GameSnapshot.Offer> offers, JsonObject five, JsonObject hour, JsonObject latest,
		Map<Integer, long[]> paid, Map<Integer, Long> firstBid, Set<String> ignored, long windowMs, long nowMs)
	{
		List<OfferAdvice> out = new ArrayList<>();
		for (GameSnapshot.Offer o : offers)
		{
			Long first = firstBid.get(o.itemId);
			OfferAdvice a = one(o, five, hour, latest, paid.get(o.itemId), first == null ? 0 : first, ignored, windowMs,
				nowMs);
			a.expected = a.ignored() ? Long.MIN_VALUE : expected(o, five, hour, latest, paid.get(o.itemId), nowMs);
			out.add(a);
		}
		out.sort(Comparator.comparingInt((OfferAdvice a) -> RANK.getOrDefault(a.verdict, 9))
			.thenComparingInt(a -> a.offer.slot));
		return out;
	}

	/** OfferAdvice.expected for one offer; {@code paid} = {paid each, ...} from Holding, or null. */
	static long expected(GameSnapshot.Offer o, JsonObject five, JsonObject hour, JsonObject latest, long[] paid, long nowMs)
	{
		if (!o.buy)
		{
			return paid == null || paid[0] <= 0 || o.price <= 0 ? Long.MIN_VALUE
				: (o.price - FlipEngine.tax(o.price, o.itemId) - paid[0]) * o.total;
		}
		JsonObject f = item(five, o.itemId);
		long ask = f == null ? 0 : FlipEngine.quoteBuy(item(hour, o.itemId), f, item(latest, o.itemId), nowMs / 1000)[1];
		return ask <= 0 ? Long.MIN_VALUE : (ask - FlipEngine.tax(ask, o.itemId) - o.price) * o.total;
	}

	private static JsonObject item(JsonObject data, int itemId)
	{
		JsonElement e = data == null ? null : data.get(String.valueOf(itemId));
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}

	private static OfferAdvice one(GameSnapshot.Offer o, JsonObject five, JsonObject hour, JsonObject latest, long[] paid,
		long first, Set<String> ignored, long windowMs, long nowMs)
	{
		// ⚠ an ignored offer is not flipping money (e.g. coins parked for someone else in a buy that
		// is never meant to fill) — never advise on it
		if (ignored.contains(o.key()))
		{
			return new OfferAdvice(o, IGNORED, 0, "you chose to ignore this offer");
		}
		if (o.cancelled)
		{
			return new OfferAdvice(o, COLLECT, 0, "cancelled - collect what is left");
		}
		if (o.complete || (o.total > 0 && o.filled >= o.total))
		{
			return new OfferAdvice(o, COLLECT, 0, "filled - collect it and free the slot");
		}
		// an expensive item of the "Expensive items (1m+)" setting: its buy waits for a seller, its sell follows the last
		// insta-buy down to what it cost for the first hour after the buy (dear) — then the rules below
		OfferAdvice dear = FlipEngine.dearTrade(o.price) ? dear(o, paid, item(latest, o.itemId), nowMs) : null;
		if (dear != null)
		{
			return dear;
		}
		JsonElement e = five == null ? null : five.get(String.valueOf(o.itemId));
		JsonObject f = e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
		JsonElement le = latest == null ? null : latest.get(String.valueOf(o.itemId));
		// the market now on this side of the book: its fresh last trade, else the 5-minute average
		// (FlipEngine.quoteLive) — a buy behind the top bid is seen in a minute, not after the next average.
		// What a buy would sell for is the LOWER of the two (FlipEngine.quoteBuy).
		JsonObject l = le != null && le.isJsonObject() ? le.getAsJsonObject() : null;
		JsonElement he = hour == null ? null : hour.get(String.valueOf(o.itemId));
		JsonObject h = he != null && he.isJsonObject() ? he.getAsJsonObject() : null;
		long[] live = f == null ? new long[]{0, 0} : FlipEngine.quoteLive(h, f, l, nowMs / 1000);
		long[] forBuy = f == null ? new long[]{0, 0} : FlipEngine.quoteBuy(h, f, l, nowMs / 1000);
		long market = o.buy ? live[0] : live[1];
		if (market <= 0 || o.price <= 0)
		{
			return new OfferAdvice(o, WAIT, o.price, "no trades in the last 5 minutes to compare with");
		}
		String part = o.filled > 0 ? String.format("%,d/%,d filled · ", o.filled, o.total) : "";
		// plain words only (user, 2026-10-31: "it should just say cancel offer and sell item. should
		// never read cutting the loss or anything like that"); nothing bought = nothing to sell
		// what it bought is SOLD, never kept (1.55.0; user, 2026-11-02: "The point of this isnt to keep anything")
		String cancel = part + (o.filled > 0 ? "Cancel offer and sell what it bought" : "Cancel offer");
		long sellNow = o.buy ? forBuy[1] : 0;
		long since = o.lastFill > 0 ? o.lastFill : o.placedAt;
		// unknown (open before tracking) counts as long enough
		boolean waited = since <= 0 || nowMs - since >= windowMs;
		// ⭐ A NEW BUY gets the re-price window before a small dip cancels it (user, 2026-10-31: "it just had me
		// set an offer then immedietly cancel it. it was a new offer"): the 5-minute averages wobble by a few
		// gp, and on a dear item the 2% tax leaves a thin margin, so "no margin now" flickered on right after
		// placing. Inside the window only a real fall (margin under -1% of the price) cancels it.
		boolean young = o.placedAt > 0 && nowMs - o.placedAt < windowMs;
		if (o.buy)
		{
			long margin = FlipEngine.marginNow(o.price, new long[]{market, sellNow}, o.itemId);
			if (margin != Long.MIN_VALUE && (young ? margin < -o.price * BAIL_PCT : margin <= 0))
			{
				OfferAdvice lower = lowerBuy(o, market, sellNow, part, "no profit left at your price");
				return lower != null ? lower : new OfferAdvice(o, CANCEL, 0, String.format("%s - it no longer makes a "
					+ "profit after tax at any price that fills (sells for %,d)", cancel, sellNow));
			}
		}
		double drift = (double) (market - o.price) / o.price;
		if (drift >= RAISE_PCT && o.buy && overChase(o, market, first))
		{
			// a buy is not chased past CHASE_PCT: it waits at its price, and a slow one is cancelled (slowBuy)
			OfferAdvice empty = emptyBuy(o, cancel, windowMs, nowMs);
			if (empty != null)
			{
				return empty;
			}
			// the market left it: no fill for LEFT_BUY_MS (never the proven stretch) = cancel, the slot flips again
			long quiet = since > 0 ? nowMs - since : 0;
			if (LEFT_BUY_MS > 0 && since > 0 && quiet >= LEFT_BUY_MS && o.filled < o.total)
			{
				return new OfferAdvice(o, CANCEL, 0, String.format("%s - the market moved %+.1f%% past it and nothing has bought "
					+ "for %s: it is not coming back to %,d, and the slot makes more on the next flip", cancel, drift * 100,
					FlipSidebarSlotOverlay.clock(quiet), o.price));
			}
			OfferAdvice slow = slowBuy(o, market, sellNow, part, cancel, first, slowWindow(o, windowMs), nowMs);
			if (slow != null)
			{
				return slow;
			}
			OfferAdvice left = new OfferAdvice(o, WAIT, o.price, String.format("%smarket %+.1f%% - not raised: "
				+ "a buy is never chased more than 1%% over %,d, where it was placed - wait for it, or cancel it",
				part, drift * 100, first > 0 ? first : o.price));
			left.pastCap = first > 0 ? first : o.price;
			return left;
		}
		if (drift >= RAISE_PCT && !o.buy && young && o.loweredFrom > 0 && market <= o.loweredFrom)
		{
			// ⭐ a sell just LOWERED is not raised straight back to the price it was not filling at: slowSell steps 0.5%
			// under a market at the ask, and that market is then +0.5% over the new ask — "trend confirming" (+0.4%)
			// undid it. Journal 11-01 → 11-03: 50 such raises on just-lowered sells, median 0 s after the re-place, all to
			// the old price or under it, 38 acted on (2026-11-03 18:11 Tome of Fire: 1,361,571 → 1,354,763 → 1,361,570
			// within 16 s). After the re-price window the drift rules judge it again.
			return new OfferAdvice(o, WAIT, o.price, String.format("%slowered from %,d - it did not fill there; not raised "
				+ "back inside the re-price window", part, o.loweredFrom));
		}
		if (drift >= RAISE_PCT)
		{
			// never raise a buy to a price that no longer pays — it turned red right after (user,
			// 2026-10-31: "dont have me adjust it to cancel it"): cancel straight away instead
			long raised = o.buy ? FlipEngine.marginNow(market, new long[]{market, sellNow}, o.itemId) : 1;
			if (raised != Long.MIN_VALUE && raised <= 0)
			{
				// a new offer is left alone for its window rather than cancelled straight after placing
				return young ? new OfferAdvice(o, WAIT, o.price, part + "new offer - give it the re-price window")
					: new OfferAdvice(o, CANCEL, 0, cancel + " - it only fills at a price with no profit left now");
			}
			return new OfferAdvice(o, RAISE, market, String.format("%smarket %+.1f%% - %s", part, drift * 100,
				o.buy ? "your bid is behind" : "trend confirming, ask more"));
		}
		// a sell under water inside its patience keeps its price and its place in the line (keepPlace) — before "slow"
		// or the drift rules re-list it at break-even
		OfferAdvice keep = o.buy ? null : keepPlace(o, market, paid, part, nowMs);
		if (keep != null)
		{
			return keep;
		}
		// a sell under water: parked in the inventory, or sold at the market — before "slow" lowers it to break-even
		// and keeps the slot (ParkCheck)
		OfferAdvice parked = o.buy || !waited ? null : parkOrCut(o, market, paid, part, nowMs);
		if (parked != null)
		{
			return parked;
		}
		OfferAdvice empty = emptyBuy(o, cancel, windowMs, nowMs);
		if (empty != null)
		{
			return empty;
		}
		long slowMs = slowWindow(o, windowMs);
		OfferAdvice slow = o.buy ? slowBuy(o, market, sellNow, part, cancel, first, slowMs, nowMs)
			: slowSell(o, market, paid, part, slowMs, nowMs);
		if (slow != null)
		{
			return slow;
		}
		if (drift <= -BAIL_PCT && waited)
		{
			if (o.buy)
			{
				OfferAdvice lower = lowerBuy(o, market, sellNow, part, String.format("market %+.1f%% under your bid",
					drift * 100));
				return lower != null ? lower : new OfferAdvice(o, CANCEL, 0, String.format("%s - the market fell %.1f%% "
					+ "and no lower price makes a profit after tax", cancel, -drift * 100));
			}
			// a sell is not lowered under break-even for the first hour after the buy (FlipEngine.SELL_PATIENCE_MS,
			// measured: half the plugin's losing sells came back within the hour); unknown = sell at the market.
			// From 1.41.0 (FlipBrain.underWater): an item whose price keeps coming back is
			// taken off the GE and PARKED in the inventory — the slot flips again — and one that rarely comes back is
			// sold at the market at once (parkOrCut, before this).
			long even = paid != null && paid[0] > 0 ? FlipEngine.breakEven(paid[0], o.itemId) : 0;
			boolean patient = even > 0 && paid[1] > 0
				&& nowMs - FlipEngine.patienceFrom(paid[1]) < FlipEngine.SELL_PATIENCE_MS;
			long target = patient ? Math.max(market, even) : market;
			if (target >= o.price)
			{
				return new OfferAdvice(o, WAIT, o.price, String.format("%sat break-even - waiting up to %d min for the price "
					+ "to come back (market %+.1f%%)", part, FlipEngine.SELL_PATIENCE_MS / 60_000, drift * 100));
			}
			return new OfferAdvice(o, LOWER, target, String.format("%smarket %+.1f%% and no fill for a window - %s", part,
				drift * 100, target > market ? "lower to break-even" : "sell at the market"));
		}
		if (drift <= -BAIL_PCT)
		{
			return new OfferAdvice(o, WAIT, o.price, String.format("%smarket %+.1f%% - give it the re-price window",
				part, drift * 100));
		}
		return new OfferAdvice(o, WAIT, o.price, String.format("%smarket %+.1f%% of your %s", part, drift * 100,
			o.buy ? "bid" : "ask"));
	}

	/**
	 * ⭐ A buy that no longer pays at its price is ADJUSTED, not taken down (user, 2026-11-02: "id rather it just have me
	 * adjust the offer then take it down and put it back up immedietly after taking it down" — a Dragon arrow buy at
	 * 2,430 was cancelled and the same item suggested again 13 min later at 2,425). LOWER — the GE's Modify — to the
	 * market's price when that still makes a profit after tax; null when it does not, and the buy is cancelled ("when
	 * something goes south ... you stop buying it", 2026-10-31).
	 */
	private static OfferAdvice lowerBuy(GameSnapshot.Offer o, long market, long sellNow, String part, String why)
	{
		if (market <= 0 || market >= o.price)
		{
			return null;
		}
		long each = FlipEngine.marginNow(market, new long[]{market, sellNow}, o.itemId);
		if (each == Long.MIN_VALUE || each <= 0)
		{
			return null;
		}
		return new OfferAdvice(o, LOWER, market, String.format("%s%s - lower your bid to %,d (still +%,d each after tax)",
			part, why, market, each));
	}

	/** A buy that pays to alch at its price (FlipBrain.alchBuys): left to fill, never re-priced or cancelled by the flip rules. */
	static OfferAdvice alchBuy(GameSnapshot.Offer o, String why)
	{
		return new OfferAdvice(o, WAIT, o.price, why);
	}

	/**
	 * ⭐ SELL ONLY CANCELS EVERY OPEN BUY (1.60.54; user, 2026-11-07: "Sell only mode should cancel any active buy offer too.
	 * This is an end of the session mode. It shouldnt let anything else be bought"): a buy still open — not finished, not
	 * cancelled, not one you chose to ignore — is told CANCEL (what it bought is then sold, as for any cancel). Sells keep
	 * their advice.
	 */
	static OfferAdvice sellOnly(OfferAdvice a)
	{
		GameSnapshot.Offer o = a.offer;
		if (!o.buy || o.cancelled || o.complete || (o.total > 0 && o.filled >= o.total) || a.ignored())
		{
			return a;
		}
		String part = o.filled > 0 ? String.format("%,d/%,d filled · ", o.filled, o.total) : "";
		OfferAdvice c = new OfferAdvice(o, CANCEL, 0, part + (o.filled > 0 ? "Cancel offer and sell what it bought"
			: "Cancel offer") + " - Sell only is on: nothing more is bought");
		c.expected = a.expected;
		return c;
	}

	/** Right after the start, before the latest trades are in: a verdict now could flip the moment they arrive. */
	static OfferAdvice waitingForPrices(OfferAdvice a)
	{
		return WAIT.equals(a.verdict) ? new OfferAdvice(a.offer, WAIT, a.offer.price, "getting prices - a moment") : a;
	}

	/**
	 * Would buying at {@code price} chase the item more than CHASE_PCT over where its buy was placed ({@code first}; 0 =
	 * this offer's price)? One gp over it is never a chase: 1% of an item under 100 gp is less than a gp.
	 */
	static boolean overChase(GameSnapshot.Offer o, long price, long first)
	{
		long base = first > 0 ? first : o.price;
		return o.buy && base > 0 && price > Math.max(base + 1, (long) Math.floor(base * (1 + CHASE_PCT)));
	}

	/**
	 * ⭐ A buy told CANCEL is not told RAISE after it (FlipBrain "south", within its hour): it stays CANCEL. Release-bar
	 * session 2026-11-04, Dragon chainbody: 19:43 CANCEL, 19:44 RAISE +6.8% to 158,132; 20:12 CANCEL, 20:14 RAISE, 20:16
	 * CANCEL — it was raised, filled, and sold at -119k.
	 */
	static OfferAdvice stayCancelled(OfferAdvice a, long toldAt, long nowMs)
	{
		GameSnapshot.Offer o = a.offer;
		String part = o.filled > 0 ? String.format("%,d/%,d filled · ", o.filled, o.total) : "";
		OfferAdvice c = new OfferAdvice(o, CANCEL, 0, String.format("%s%s - it was told to cancel %d min ago; the price "
			+ "jumping back up is not chased", part, o.filled > 0 ? "Cancel offer and sell what it bought" : "Cancel offer",
			Math.max(0, (nowMs - toldAt) / 60_000)));
		c.expected = a.expected;
		return c;
	}

	/**
	 * ⭐ PAST THE CHASE CAP, A BUY IS NOT RAISED FOR A RE-PRICE WINDOW (1.60.90). {@code seen} = {when its market was last
	 * past the cap (a {@link #pastCap} WAIT), the price the cap was counted from}, or null. A RAISE on the same buy (same
	 * cap base, {@code first}; 0 = this offer's price) less than {@code minMs} after that is a WAIT instead. Why: "the
	 * market now" is the last trade only while it is under 2 minutes old (FlipEngine.LATEST_FRESH_S), then the 5-minute
	 * average — which runs minutes BEHIND that trade. On an item trading less often than every 2 minutes the reading
	 * swaps between the two, and when the cap sits between them so does the advice. MEASURED in-game 2026-11-10
	 * 01:15-01:18 (journal): 11,000 Mahogany logs bid 60 (cap: 61), last insta-sell 62, 5-minute average 61 — RAISE
	 * "market +1.7% - your bid is behind" and WAIT "+3.3% - not raised" took turns 3 times, twice in one second at
	 * 01:18:44 as the 62 trade passed 2 minutes old and then a new 62 trade came in. After the window it is judged anew.
	 */
	static OfferAdvice stayPastCap(OfferAdvice a, long[] seen, long first, long nowMs, long minMs)
	{
		GameSnapshot.Offer o = a.offer;
		long base = first > 0 ? first : o.price;
		if (seen == null || !o.buy || !RAISE.equals(a.verdict) || seen[1] != base || nowMs - seen[0] >= minMs)
		{
			return a;
		}
		String part = o.filled > 0 ? String.format("%,d/%,d filled · ", o.filled, o.total) : "";
		OfferAdvice c = new OfferAdvice(o, WAIT, o.price, String.format("%snot raised: the market went past the 1%% cap over "
			+ "%,d (where it was placed) %s ago - wait for it, or cancel it", part, base,
			FlipSidebarSlotOverlay.clock(nowMs - seen[0])));
		c.expected = a.expected;
		return c;
	}

	/**
	 * ⭐ AN UNDER-WATER SELL KEEPS ITS PLACE (1.60.40): with the market under break-even, a sell whose buy price and time
	 * are on record keeps ITS price — no re-list to break-even, which would put it behind every offer already there (the
	 * GE fills the oldest offer at a price first) — until FlipEngine.SELL_PATIENCE_MS after the buy or this login
	 * (FlipEngine.patienceFrom); then the rules after this sell it at the market. Not for an item that rarely comes back
	 * (PAID_CUT), one held 8h+ (PAID_TIME) or the user's own Hold (parkOrCut says that). User, 2026-11-07, of Magic logs:
	 * "if we had had them in at earlier we could of beat the other and hit the recovery swing" — see
	 * FlipEngine.SELL_PATIENCE_MS for the measurement. Null = not one of these.
	 */
	private static OfferAdvice keepPlace(GameSnapshot.Offer o, long market, long[] paid, String part, long nowMs)
	{
		long how = paid != null && paid.length > 3 ? paid[2] : -1;
		if (paid == null || paid[0] <= 0 || paid[1] <= 0 || how == PAID_CUT || how == PAID_TIME || how == PAID_HELD)
		{
			return null;
		}
		long even = FlipEngine.breakEven(paid[0], o.itemId);
		long left = FlipEngine.SELL_PATIENCE_MS - (nowMs - FlipEngine.patienceFrom(paid[1]));
		if (market <= 0 || market >= even || left <= 0)
		{
			return null;
		}
		return new OfferAdvice(o, WAIT, o.price, String.format("%sunder what you paid - kept at %,d so it keeps its place in "
			+ "the line; the market (%,d) has %s to come back to %,d", part, o.price, market,
			FlipSidebarSlotOverlay.clock(left), even));
	}

	/**
	 * ⭐ A sell offer under break-even for a re-price window (FlipBrain.underWater) is SOLD, never kept (1.55.0): listed
	 * at break-even until FlipEngine.SELL_PATIENCE_MS after the buy (null here: the code after this), then LOWERED to the
	 * market. User, 2026-11-02, of "Cancel offer and keep the items" (1.44.0-1.54.0): "It shouldnt say keep the items.
	 * The point of this isnt to keep anything", "That's mis leading"; asked what Flipping Copilot does — its advice has
	 * only buy, sell, modify, abort and wait (Suggestion.java), no keep. The user's own Hold is never lowered. Null when
	 * above break-even, or with no price on record.
	 */
	private static OfferAdvice parkOrCut(GameSnapshot.Offer o, long market, long[] paid, String part, long nowMs)
	{
		long even = paid != null && paid[0] > 0 ? FlipEngine.breakEven(paid[0], o.itemId) : 0;
		long how = paid != null && paid.length > 3 ? paid[2] : -1;
		if (even <= 0 || market >= even || how < 0)      // an old {paid, last buy} entry: not judged
		{
			return null;
		}
		if (how == PAID_HELD)
		{
			return new OfferAdvice(o, WAIT, o.price, String.format("%syou hold this one - not lowered under what you paid "
				+ "(break-even %,d)", part, even));
		}
		// HOLD OR SELL (1.60.72): a HOLD verdict waits at break-even, whatever the patience (a SELL one goes on below)
		String hold = holdWhy.get(o.itemId);
		if (hold != null && o.price - FlipEngine.tax(o.price, o.itemId) >= paid[0])   // listed at no loss (even = +1 gp)
		{
			return new OfferAdvice(o, WAIT, o.price, part + hold);
		}
		if (how == PAID_PARK)
		{
			if (paid[1] > 0 && nowMs - FlipEngine.patienceFrom(paid[1]) < FlipEngine.SELL_PATIENCE_MS)
			{
				return null;          // at break-even for now: the code after this waits or lowers to break-even
			}
			return new OfferAdvice(o, LOWER, market, String.format("%sunder what you paid %d min+ after the buy (or your login) - sell at "
				+ "the market to free the slot and the coins", part, FlipEngine.SELL_PATIENCE_MS / 60_000));
		}
		if (how == PAID_TIME)
		{
			return new OfferAdvice(o, LOWER, market, String.format("%sheld %dh+ under what you paid - sell at the market to "
				+ "free the coins", part, FlipBrain.HOLD_MAX_MS / 3_600_000L));
		}
		return how == PAID_CUT ? new OfferAdvice(o, LOWER, market, String.format("%sback at break-even in only %.0f%% of the "
			+ "last day - sell at the market", part, paid[3] / 10.0)) : null;
	}

	/**
	 * ⭐ A SLOW SELL is called quits (user, 2026-10-31, of an Ancient essence sell 93,000 / 127,260 @ 14 open over
	 * 2 hours, still filling a little at a time: "when do we call it quits and let that slot produce income"). Open
	 * longer than two re-price windows, and at its own pace so far the rest would take longer than a window more:
	 * lower one step (0.5% of the price, at least 1 gp) — never under the market, never at a loss — to
	 * finish it and free the slot. Null when it is not slow, or cannot go lower without a loss.
	 */
	private static OfferAdvice slowSell(GameSnapshot.Offer o, long market, long[] paid, String part, long windowMs, long nowMs)
	{
		long open = o.placedAt > 0 ? nowMs - o.placedAt : 0;
		long left = o.total - o.filled;
		if (open < 2 * windowMs || left <= 0 || market > o.price)
		{
			return null;              // a market ABOVE the ask: the drift rules handle it (raise)
		}
		double perMs = o.filled > 0 && o.lastFill > o.placedAt ? (double) o.filled / (o.lastFill - o.placedAt) : 0;
		double etaMs = perMs > 0 ? left / perMs : Double.POSITIVE_INFINITY;
		if (etaMs <= windowMs)
		{
			return null;              // it will be done within a window at this pace
		}
		// ⭐ A SELL STILL FILLING FAST is not lowered: at its pace of the last 5 minutes it is done within two windows.
		// The GE is first come, first served at a price (user, 2026-11-05: "the older offers take priority over new
		// ones"); a lowered sell starts again behind those already there. MEASURED (journal 11-01..11-05, 203 sells this
		// rule lowered): of the 20 still filling at that pace, 15 then finished LATER than their own pace said
		// (median 11.5 vs 6.7 minutes), and the lowers gave up 92,515 gp. The pace since placing instead caught 16, 9
		// later. User: "ud have to tell me if it makes sense with data".
		if (o.recentFilled > 0 && left * (double) TradeLog.RECENT_MS / o.recentFilled <= 2 * windowMs)
		{
			return null;
		}
		// the floor is NO LOSS (what was paid back after tax, profit 0) — not breakEven's +1 each: to free a slot,
		// finishing at exactly what was paid is fine (Ancient essence paid 13: 13, where breakEven says 14)
		long even = paid != null && paid[0] > 0 ? FlipEngine.breakEven(paid[0] - 1, o.itemId) : 0;
		long step = Math.max(1, Math.round(o.price * 0.005));
		long target = Math.max(even, Math.min(o.price - step, market > 0 ? market : o.price - step));
		if (target >= o.price)
		{
			return null;              // already at break-even: lower only when the wait at break-even is up
		}
		String pace = perMs > 0 ? String.format("~%s more at this pace", FlipSidebarSlotOverlay.clock((long) etaMs)) : "no fills";
		return new OfferAdvice(o, LOWER, target, String.format("%sslow - open %s, %s: lower to %,d to finish it and free the slot%s",
			part, FlipSidebarSlotOverlay.clock(open), pace, target, target == even ? " (no profit, no loss on the rest)" : ""));
	}

	/**
	 * The window a SLOW offer is judged by: a proven item's (FlipEngine.proven) is FlipEngine.provenScale times longer, as
	 * its buy is that much bigger (FlipEngine.PROVEN_SHARE) — so it is not re-priced or cancelled for its size (1.60.30).
	 */
	static long slowWindow(GameSnapshot.Offer o, long windowMs)
	{
		if (FlipEngine.proven.contains(o.itemId))
		{
			for (AdjustWindow w : AdjustWindow.values())
			{
				if (w.minutes * 60_000L == windowMs)
				{
					return Math.round(windowMs * FlipEngine.provenScale(w));
				}
			}
		}
		return windowMs;
	}

	/**
	 * ⭐ A SLOW BUY frees its slot too (user, 2026-10-31: "time is money literally. if we wait for offers that arend
	 * doing aything for a long time were wasting a slot that could be making profit"). Open longer than two re-price
	 * windows and, at its pace so far (none = never), not done within one more window: raise one step (0.5%, at least
	 * 1 gp) when the raised bid still makes money after tax; else cancel — and sell what it bought. Null when it is
	 * not slow.
	 */
	/**
	 * ⭐ AN EMPTY BUY is cancelled {@link #EMPTY_BUY_MS} after it was placed (1.60.31; the 5-minute re-price window only —
	 * what was measured). User, 2026-11-05: "Ideas for increased gp per hour?" … "Do it". MEASURED (journal 11-01..11-05,
	 * 1,299 buys in 48.5h played, each priced by its real flips — tools/levers/levers.py): a buy fills within a median 1.1
	 * minutes if it fills at all (90% by 5.1); one with nothing after 3 minutes filled later only 29% of the time, and those
	 * 150 late fills made +108k in all, while the 518 empty buys held 5,279 slot-minutes — at 601 gp an occupied
	 * slot-minute made, +30k..+63k an hour. Null when it is not one (bought something, a longer window, placed unknown).
	 */
	/**
	 * ⭐ AN EXPENSIVE ITEM of the "Expensive items (1m+)" setting (FlipEngine.judgeDear: one at a time, a few trades an
	 * hour). A BUY waits for a seller up to FlipEngine.DEAR_BUY_WAIT_MS, then is cancelled — the 3-minute empty-buy
	 * rule and the re-price window would take it down before the next seller comes. 10 min since 1.60.36 (was 30: see
	 * FlipEngine.DEAR_BUY_WAIT_MS for the measurement). A SELL with nothing sold FlipEngine.DEAR_SELL_STEP_MS after it
	 * was listed or last filled is LOWERED to the last insta-buy ({@code latest}, within FlipEngine.DEAR_FRESH_S) — never
	 * under what it cost (profit 0, as slowSell) — until FlipEngine.SELL_PATIENCE_MS after the buy (1.60.37; it kept its
	 * price until then: see DEAR_SELL_STEP_MS for the measurement); from then the normal rules sell it at the market.
	 * Null = the normal rules: a buy placed before tracking or part-filled (not one of these), a sell whose buy time is
	 * not known or is an hour old.
	 */
	private static OfferAdvice dear(GameSnapshot.Offer o, long[] paid, JsonObject latest, long nowMs)
	{
		if (o.buy)
		{
			if (o.placedAt <= 0 || o.filled > 0)
			{
				return null;
			}
			long open = nowMs - o.placedAt;
			if (open < FlipEngine.DEAR_BUY_WAIT_MS)
			{
				return new OfferAdvice(o, WAIT, o.price, String.format("expensive item - waiting for a seller, up to %d min "
					+ "(%s so far)", FlipEngine.DEAR_BUY_WAIT_MS / 60_000, FlipSidebarSlotOverlay.clock(open)));
			}
			return new OfferAdvice(o, CANCEL, 0, String.format("Cancel offer - nothing bought in %s: an expensive item's "
				+ "buy waits %d min for a seller", FlipSidebarSlotOverlay.clock(open), FlipEngine.DEAR_BUY_WAIT_MS / 60_000));
		}
		if (paid == null || paid[1] <= 0 || nowMs - FlipEngine.patienceFrom(paid[1]) >= FlipEngine.SELL_PATIENCE_MS)
		{
			return null;
		}
		String part = o.filled > 0 ? String.format("%,d/%,d filled · ", o.filled, o.total) : "";
		long since = Math.max(o.placedAt, o.lastFill);
		long lastBuy = latest == null || nowMs / 1000 - Research.num(latest, "highTime") > FlipEngine.DEAR_FRESH_S ? 0
			: Research.num(latest, "high");
		// the floor is NO LOSS (what was paid back after tax, profit 0), as slowSell
		long even = paid[0] > 0 ? FlipEngine.breakEven(paid[0] - 1, o.itemId) : 0;
		if (since > 0 && nowMs - since >= FlipEngine.DEAR_SELL_STEP_MS && lastBuy > 0 && even > 0)
		{
			long target = Math.max(lastBuy, even);
			if (target < o.price)
			{
				return new OfferAdvice(o, LOWER, target, String.format("%sexpensive item - nothing sold in %s: lower to %s "
					+ "%,d", part, FlipSidebarSlotOverlay.clock(nowMs - since),
					target == even ? "what it cost (no profit, no loss)," : "the last insta-buy", target));
			}
		}
		return new OfferAdvice(o, WAIT, o.price, String.format("%sexpensive item - follows the last insta-buy every %d min, "
			+ "never under what it cost, until %d min after the buy or your login (%s left)", part, FlipEngine.DEAR_SELL_STEP_MS / 60_000,
			FlipEngine.SELL_PATIENCE_MS / 60_000, FlipSidebarSlotOverlay.clock(FlipEngine.SELL_PATIENCE_MS - (nowMs - FlipEngine.patienceFrom(paid[1])))));
	}

	private static OfferAdvice emptyBuy(GameSnapshot.Offer o, String cancel, long windowMs, long nowMs)
	{
		if (!o.buy || o.filled > 0 || o.placedAt <= 0 || windowMs > EMPTY_WINDOW_MS || nowMs - o.placedAt < EMPTY_BUY_MS)
		{
			return null;
		}
		return new OfferAdvice(o, CANCEL, 0, String.format("%s - nothing bought in %s: 7 in 10 buys still empty after 3 min "
			+ "never fill, and the slot makes more on the next flip", cancel, FlipSidebarSlotOverlay.clock(nowMs - o.placedAt)));
	}

	private static OfferAdvice slowBuy(GameSnapshot.Offer o, long market, long sellNow, String part, String cancel,
		long first, long windowMs, long nowMs)
	{
		long open = o.placedAt > 0 ? nowMs - o.placedAt : 0;
		long left = o.total - o.filled;
		if (open < 2 * windowMs || left <= 0 || market < o.price)
		{
			return null;              // a market BELOW the bid is a fall, not a queue: the drift rules handle it
		}
		double perMs = o.filled > 0 && o.lastFill > o.placedAt ? (double) o.filled / (o.lastFill - o.placedAt) : 0;
		double etaMs = perMs > 0 ? left / perMs : Double.POSITIVE_INFINITY;
		if (etaMs <= windowMs)
		{
			return null;
		}
		long step = Math.max(1, Math.round(o.price * 0.005));
		long raised = Math.max(o.price + step, market);
		long margin = FlipEngine.marginNow(raised, new long[]{raised, sellNow}, o.itemId);
		String pace = perMs > 0 ? String.format("~%s more at this pace", FlipSidebarSlotOverlay.clock((long) etaMs)) : "no fills";
		if (overChase(o, raised, first))
		{
			return new OfferAdvice(o, CANCEL, 0, String.format("%s - slow: open %s, %s, and a buy is never "
				+ "raised more than 1%% over %,d, where it was placed", cancel, FlipSidebarSlotOverlay.clock(open), pace,
				first > 0 ? first : o.price));
		}
		if (margin != Long.MIN_VALUE && margin > 0)
		{
			return new OfferAdvice(o, RAISE, raised, String.format("%sslow - open %s, %s: raise to %,d (still %+,d each after tax)",
				part, FlipSidebarSlotOverlay.clock(open), pace, raised, margin));
		}
		return new OfferAdvice(o, CANCEL, 0, String.format("%s - slow: open %s, %s, and raising would not pay", cancel,
			FlipSidebarSlotOverlay.clock(open), pace));
	}
}
