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
import java.util.List;

/** One flip the engine found: what to bid, what to ask, how many, and what could go wrong. */
class Pick
{
	/** The least time a flip holds its GE slot, minutes (perHour). */
	static final double SLOT_MIN_MINUTES = 5.0;

	int itemId;
	String name;
	/** Price to BUY at (one step above the insta-sell, so the offer is at the front). */
	long bid;
	/** Price to SELL at afterwards (one step below the insta-buy). */
	long ask;
	long qty;
	/** Profit each, after the GE tax. */
	long each;
	long total;
	/**
	 * ⭐ DISPLAY ONLY (1.58.0): the whole offer's profit if it sells at the last hour's average insta-buy (Research.hourAsk)
	 * where that is under {@link #ask} — not used to rank or pick. MEASURED 2026-11-02 (calib.py, ~/.cache/claude-gs/rules:
	 * 240 real flips matched to their buy card, 11-01 10:39 to 11-02 06:33): the cards promised +14.6m and the flips made +0.73m — the gap was 64% selling under the card's ask (the
	 * market reached it within an hour of the buy only 65% of the time) and 36% paying over its bid. Under 100k this
	 * estimate came true 74% (the card's own 42%), and it ranks the flips the same (top half +2.42m against +2.49m).
	 * Long.MIN_VALUE = not known.
	 */
	long likely = Long.MIN_VALUE;
	double fillMinutes;
	/** Ranking weight (rank): 1 = none; a dip or a good hour of its daily cycle raises it (FlipEngine). */
	double boost = 1;
	/** Why {@link #boost} is what it is, short ("3.4% under its day's usual price · near its usual daily low"); "" = none. */
	String boostWhy = "";
	final List<String> warnings = new ArrayList<>();
	/** When it was priced, epoch seconds. */
	long asOf;
	/** What it was judged on, kept so FlipBrain can re-price it on newer trades. */
	Research research;
	long limit;
	/** Why it is not a recommended flip (FlipEngine.judge); null when it is one. */
	String rejected;
	/** A swing buy (Swing.find): {@link #ask} is the day's usual high it is held for, in the inventory, not a slot. */
	boolean swing;
	/** A deep swing (Swing.findDeep): held up to FlipEngine.DEEP_HOLD_MS, not Swing.HOLD_MS. */
	boolean deep;
	/**
	 * An EXPENSIVE ITEM priced on its latest trades (FlipEngine.judgeDear, the "Expensive items (1m+)" setting): one at a
	 * time, re-priced on the latest trades (FlipBrain.fresh), its buy waits for a seller (OfferAdvice.dear).
	 */
	boolean dear;

	/**
	 * Profit per hour of the GE slot it holds — the scan's ranking. A flip holds its slot for at least
	 * {@link #SLOT_MIN_MINUTES}, however fast its fill estimate. MEASURED: the plugin's real flips took a median 14.6 min
	 * from the last buy fill to the sale (7 of 30 under 5); Copilot's, 29 min for items under 1k against 10 min at 1m+.
	 * With 0.5 (until 1.37.1) a rune order capped by its GE limit ranked as if its slot came back each minute: in-game
	 * 2026-11-01, 8 slots held 24.7m of 1.5-7m rune orders with 150m to flip ("were only using 34 m").
	 * {@code portfolio_replay.py}'s winning rule (+3.66m against +2.68m by profit per flip, 30m window) has this floor.
	 */
	double perHour()
	{
		// a swing holds its slot for the buy and, later, the sell — not while it waits in the inventory
		double slot = Math.max(fillMinutes, SLOT_MIN_MINUTES) + (swing ? SLOT_MIN_MINUTES : 0);
		return total / slot * 60.0;
	}

	/**
	 * What the cards are ORDERED by: {@link #perHour} × {@link #boost} (1.60.44). The card still shows perHour — the boost
	 * is how much more often such a buy has paid (FlipEngine.DIP_BOOST_PER_PCT, cycles), not more gp on this flip.
	 */
	double rank()
	{
		return perHour() * boost;
	}

	double roi()
	{
		return total * 100.0 / Math.max(1, qty * bid);
	}

	long committed()
	{
		return qty * bid;
	}

	/**
	 * A copy for {@code q} items — the coins on hand now buy fewer than it was sized for (FlipBrain.affordable). The
	 * profit, the Likely line and the fill time scale with the quantity; the prices and the profit each do not.
	 */
	Pick sized(long q)
	{
		Pick c = new Pick();
		c.itemId = itemId;
		c.name = name;
		c.bid = bid;
		c.ask = ask;
		c.each = each;
		c.qty = q;
		c.total = qty <= 0 ? 0 : total * q / qty;
		c.likely = likely == Long.MIN_VALUE || qty <= 0 ? likely : likely * q / qty;
		c.fillMinutes = qty <= 0 ? fillMinutes : fillMinutes * q / qty;
		c.warnings.addAll(warnings);
		c.asOf = asOf;
		c.research = research;
		c.limit = limit;
		c.rejected = rejected;
		c.swing = swing;
		c.dear = dear;
		c.boost = boost;
		c.boostWhy = boostWhy;
		c.deep = deep;
		return c;
	}

	String firstWarning()
	{
		return warnings.isEmpty() ? "" : warnings.get(0);
	}
}
