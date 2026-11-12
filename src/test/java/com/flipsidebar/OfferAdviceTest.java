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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * OfferAdvice.stayPastCap (1.60.90): past the chase cap, a buy is not raised for a re-price window. The prices are the
 * in-game case of 2026-11-10 01:17-01:18 (journal): 11,000 Mahogany logs bid 60, placed 00:39:18, 8,185 bought by
 * 01:04:38; the 5-minute average insta-sell 61, insta-buy 67; the last insta-sell 62 at 01:16:36.
 */
public class OfferAdviceTest
{
	private static final int MAHOGANY = 6332;
	private static final long MIN = 60_000L, WINDOW = 5 * MIN;
	private static final long PLACED = 1_794_289_158_000L, LAST_FILL = 1_794_290_678_000L;
	private static final long T0117 = 1_794_291_443_000L, T0118 = 1_794_291_524_000L;
	private static final long[] SEEN = {T0117, 60};

	private static JsonObject obj(String json)
	{
		return new JsonParser().parse(json).getAsJsonObject();
	}

	/** The advice for the bid at {@code nowMs}, its first bid {@code first}. */
	private static OfferAdvice at(long nowMs, long first)
	{
		GameSnapshot.Offer bid = new GameSnapshot.Offer(4, true, MAHOGANY, "Mahogany logs", 60, 11_000, 8_185, false, false,
			PLACED, LAST_FILL);
		JsonObject five = obj("{\"6332\":{\"avgHighPrice\":67,\"highPriceVolume\":17152,\"avgLowPrice\":61,\"lowPriceVolume\":8568}}");
		JsonObject hour = obj("{\"6332\":{\"avgHighPrice\":60,\"avgLowPrice\":56}}");
		JsonObject latest = obj("{\"6332\":{\"high\":67,\"highTime\":1794291299,\"low\":62,\"lowTime\":1794291396}}");
		Set<Integer> was = FlipEngine.proven;
		FlipEngine.proven = Set.of(MAHOGANY);         // setup: a proven item, so the slow rule does not decide first
		try
		{
			return OfferAdvice.advise(List.of(bid), five, hour, latest, Map.of(), Map.of(MAHOGANY, first), Set.of(), WINDOW,
				nowMs).get(0);
		}
		finally
		{
			FlipEngine.proven = was;
		}
	}

	@Test
	public void theCaseSwitchedOnOneGp()
	{
		// what happened: at 01:17:23 the 62 trade is 47 s old - the market is 62, past the cap (61): WAIT
		OfferAdvice wait = at(T0117, 60);
		assertEquals(OfferAdvice.WAIT, wait.verdict);
		assertEquals(60, wait.pastCap);
		// at 01:18:44 it is 128 s old, so the reading falls back to the 5-minute average, 61: RAISE (the bug)
		OfferAdvice raise = at(T0118, 60);
		assertEquals(OfferAdvice.RAISE, raise.verdict);
		assertEquals(61, raise.target);
		assertEquals(0, raise.pastCap);
	}

	@Test
	public void aRaiseInsideTheWindowWaits()
	{
		OfferAdvice a = OfferAdvice.stayPastCap(at(T0118, 60), SEEN, 60, T0118, WINDOW);
		assertEquals(OfferAdvice.WAIT, a.verdict);
		assertEquals(60, a.target);
		assertTrue(a.why, a.why.startsWith("8,185/11,000 filled · not raised: the market went past the 1% cap over 60"));
		assertTrue(a.why, a.why.contains(" 1m ago"));
	}

	@Test
	public void afterTheWindowItIsJudgedAgain()
	{
		OfferAdvice raise = at(T0118, 60);
		assertSame(raise, OfferAdvice.stayPastCap(raise, SEEN, 60, T0117 + WINDOW, WINDOW));
	}

	@Test
	public void anotherBuyOrNothingSeenIsLeftAlone()
	{
		OfferAdvice raise = at(T0118, 60);
		assertSame("never past the cap", raise, OfferAdvice.stayPastCap(raise, null, 60, T0118, WINDOW));
		assertSame("a new buy (another cap base)", raise, OfferAdvice.stayPastCap(raise, new long[]{T0117, 58}, 60, T0118, WINDOW));
		OfferAdvice wait = at(T0117, 60);
		assertSame("only a RAISE is held", wait, OfferAdvice.stayPastCap(wait, SEEN, 60, T0118, WINDOW));
	}

	// OfferAdvice.stayRaised (1.60.96), the in-game case of 2026-11-11 19:35 (journal): 13,000 Bow string bid 151, placed
	// 19:35:03; 5-minute average insta-sell 151, insta-buy 160; the last insta-sell 152 at 19:33:13.
	private static final int BOW_STRING = 1777;
	private static final long BS_PLACED = 1_794_443_703_444L, BS_RAISE = 1_794_443_707_937L, BS_WAIT = 1_794_443_714_253L;

	private static OfferAdvice bowString(long price, long nowMs)
	{
		GameSnapshot.Offer bid = new GameSnapshot.Offer(6, true, BOW_STRING, "Bow string", price, 13_000, 0, false, false,
			BS_PLACED, 0);
		JsonObject five = obj("{\"1777\":{\"avgHighPrice\":160,\"highPriceVolume\":9858,\"avgLowPrice\":151,\"lowPriceVolume\":3000}}");
		JsonObject hour = obj("{\"1777\":{\"avgHighPrice\":160,\"avgLowPrice\":156}}");
		JsonObject latest = obj("{\"1777\":{\"high\":160,\"highTime\":1794443632,\"low\":152,\"lowTime\":1794443593}}");
		Set<Integer> was = FlipEngine.proven;
		FlipEngine.proven = Set.of(BOW_STRING);       // setup: proven, as it was that day
		try
		{
			return OfferAdvice.advise(List.of(bid), five, hour, latest, Map.of(), Map.of(), Set.of(), WINDOW, nowMs).get(0);
		}
		finally
		{
			FlipEngine.proven = was;
		}
	}

	@Test
	public void theRaiseCameAndWentOnOneTrade()
	{
		// what happened: the 152 trade 114 s old - RAISE to 152; 121 s old - the 5-minute average, 151: WAIT (the bug)
		OfferAdvice raise = bowString(151, BS_RAISE);
		assertEquals(OfferAdvice.RAISE, raise.verdict);
		assertEquals(152, raise.target);
		OfferAdvice wait = bowString(151, BS_WAIT);
		assertEquals(OfferAdvice.WAIT, wait.verdict);
		assertTrue(wait.why, wait.atBid);
	}

	@Test
	public void theRaiseStandsInsideTheWindow()
	{
		OfferAdvice a = OfferAdvice.stayRaised(bowString(151, BS_WAIT), new long[]{BS_RAISE, 151, 152}, BS_WAIT, WINDOW);
		assertEquals(OfferAdvice.RAISE, a.verdict);
		assertEquals(152, a.target);
		assertEquals("market +0.7% - your bid is behind (said 6s ago)", a.why);
	}

	@Test
	public void theRaiseIsNotKeptWhenItShouldNotBe()
	{
		long[] said = {BS_RAISE, 151, 152};
		OfferAdvice wait = bowString(151, BS_WAIT);
		assertSame("after the window", wait, OfferAdvice.stayRaised(wait, said, BS_RAISE + WINDOW, WINDOW));
		assertSame("never told to raise", wait, OfferAdvice.stayRaised(wait, null, BS_WAIT, WINDOW));
		OfferAdvice raisedTo = bowString(152, BS_WAIT);
		assertSame("re-priced: the offer is at 152 now", raisedTo, OfferAdvice.stayRaised(raisedTo, said, BS_WAIT, WINDOW));
		OfferAdvice pastCap = at(T0117, 60);
		assertSame("a past-the-cap WAIT is not a plain one", pastCap,
			OfferAdvice.stayRaised(pastCap, new long[]{T0117 - MIN, 60, 61}, T0117, WINDOW));
	}
}
