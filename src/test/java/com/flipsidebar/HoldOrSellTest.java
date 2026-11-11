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
import com.google.gson.JsonObject;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * HoldOrSell.steady (1.60.89): a Hold / Sell switch stands for a re-price window. The numbers are the in-game case of
 * 2026-11-09 21:03-21:06 (journal): 5,358 Toadflax potion (unf) bought at 2,007, its week "usual price 2,152, at your
 * price in 81% of the week's hours", lowest 1,886.
 */
public class HoldOrSellTest
{
	private static final int TOADFLAX = 3002;
	private static final long QTY = 5358, PAID = 2007, MIN = 60_000L;

	/** A week of hourly prices like the case's: 136 hours at 2,152, 31 at 2,000, one at its lowest 1,886. */
	private static JsonArray week()
	{
		JsonArray a = new JsonArray();
		for (int i = 0; i < 168; i++)
		{
			JsonObject o = new JsonObject();
			o.addProperty("avgHighPrice", i == 0 ? 1886 : i <= 31 ? 2000 : 2152);
			a.add(o);
		}
		return a;
	}

	private static HoldOrSell.Verdict at(long market)
	{
		return HoldOrSell.decide(week(), PAID, QTY, market, TOADFLAX, 0.18, 0.33);
	}

	@Test
	public void theCaseFlipsOnOneGp()
	{
		// what happened: the verdict itself swaps between a market of 2,040 and 2,042 (the bug steady() stops)
		assertTrue(at(2040).hold);
		assertFalse(at(2042).hold);
	}

	@Test
	public void aSwitchWaitsForTheWindow()
	{
		long t0 = 1_000_000_000L, window = 5 * MIN;
		HoldOrSell.Verdict hold = at(2040), sell = at(2042);
		assertSame("30 s later the wiggle does not swap it", hold, HoldOrSell.steady(hold, t0, sell, 2042, t0 + 30_000, window));
		assertSame("nor back", sell, HoldOrSell.steady(sell, t0, hold, 2040, t0 + 30_000, window));
		assertSame("after the window it does", sell, HoldOrSell.steady(hold, t0, sell, 2042, t0 + window, window));
	}

	@Test
	public void aRealFallIsSaidAtOnce()
	{
		long t0 = 1_000_000_000L, window = 5 * MIN;
		HoldOrSell.Verdict hold = at(2040), fall = at(1880);
		assertFalse(fall.hold);
		assertSame("under the week's lowest: Sell at once", fall, HoldOrSell.steady(hold, t0, fall, 1880, t0 + 10_000, window));
	}

	@Test
	public void sameDecisionOrNoneTakesTheNew()
	{
		long t0 = 1_000_000_000L, window = 5 * MIN;
		HoldOrSell.Verdict a = at(2040), b = at(2039);
		assertSame(b, HoldOrSell.steady(a, t0, b, 2039, t0 + 1_000, window));
		assertSame(a, HoldOrSell.steady(null, 0, a, 2040, t0, window));
		assertNull(HoldOrSell.steady(a, t0, null, 2050, t0 + 1_000, window));
	}

	@Test
	public void aRecentHigherTradeIsNotAFall()
	{
		long window = 5 * MIN;
		// the shards case (10:14:50): the reading 714, the last insta-buy 729 two minutes ago (139 s) - judged on 729
		assertTrue(HoldOrSell.fallPrice(714, 729, 139_000, window) == 729);
		assertTrue("older than the window: the reading", HoldOrSell.fallPrice(714, 729, window, window) == 714);
		assertTrue("a lower trade changes nothing", HoldOrSell.fallPrice(714, 700, 10_000, window) == 714);
		assertTrue("no trade known", HoldOrSell.fallPrice(714, 0, -1, window) == 714);
		// with the Toadflax week (lowest 1,886): a reading of 1,880 while someone paid 2,040 a minute ago stays Hold
		long t0 = 1_000_000_000L;
		HoldOrSell.Verdict hold = at(2040), fall = at(1880);
		assertSame(hold, HoldOrSell.steady(hold, t0, fall, HoldOrSell.fallPrice(1880, 2040, 60_000, window), t0 + 30_000, window));
		assertSame("no recent higher trade: said at once", fall,
			HoldOrSell.steady(hold, t0, fall, HoldOrSell.fallPrice(1880, 0, -1, window), t0 + 30_000, window));
	}
}
