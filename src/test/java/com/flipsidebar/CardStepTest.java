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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * FlipBrain.waitStep / stepCard (1.60.92): all 8 slots in use and an offer to cancel or re-price = the card says that
 * step, not "Wait". The case: 2026-11-10 21:40:02 (journal), 8 offers, the Uncut diamond buy in slot 5 (454 of 8,260 at
 * 2,224) told CANCEL while the card said "Wait - All 8 GE slots are in use".
 */
public class CardStepTest
{
	private static final long NOW = 1_794_364_802_000L, WINDOW = 5 * 60_000L;

	private static Suggestion waitCard()
	{
		Suggestion s = new Suggestion();
		s.action = "WAIT";
		s.note = "All 8 GE slots are in use";
		return s;
	}

	/** A plain WAIT advice for an offer (no prices: "no trades in the last 5 minutes"). */
	private static OfferAdvice waiting(GameSnapshot.Offer o)
	{
		return OfferAdvice.advise(List.of(o), null, Set.of(), WINDOW, NOW).get(0);
	}

	private static GameSnapshot.Offer offer(int slot, boolean buy, int item, String name, long price, int total, int filled)
	{
		return new GameSnapshot.Offer(slot, buy, item, name, price, total, filled, false, false, NOW - 30 * 60_000L, NOW - 60_000L);
	}

	/** The 8 offers of the case: slot 5 = the Uncut diamond buy, told CANCEL; the rest wait. */
	private static List<OfferAdvice> theCase()
	{
		List<OfferAdvice> adv = new ArrayList<>();
		for (int slot = 1; slot <= 8; slot++)
		{
			adv.add(slot == 5
				? OfferAdvice.freeSlot(waiting(offer(5, true, 1617, "Uncut diamond", 2224, 8260, 454)),
					"454/8,260 filled · Cancel offer and sell what it bought - it no longer makes a profit after tax")
				: waiting(offer(slot, slot % 2 == 0, 1000 + slot, "Item " + slot, 100, 1000, 0)));
		}
		return adv;
	}

	/** A RAISE for slot 4: the Mahogany logs prices of OfferAdviceTest at 01:18:44 (market 61 over a bid of 60). */
	private static OfferAdvice raise()
	{
		GameSnapshot.Offer bid = new GameSnapshot.Offer(4, true, 6332, "Mahogany logs", 60, 11_000, 8_185, false, false,
			1_794_289_158_000L, 1_794_290_678_000L);
		JsonObject five = new JsonParser().parse("{\"6332\":{\"avgHighPrice\":67,\"highPriceVolume\":17152,\"avgLowPrice\":61,"
			+ "\"lowPriceVolume\":8568}}").getAsJsonObject();
		JsonObject hour = new JsonParser().parse("{\"6332\":{\"avgHighPrice\":60,\"avgLowPrice\":56}}").getAsJsonObject();
		JsonObject latest = new JsonParser().parse("{\"6332\":{\"high\":67,\"highTime\":1794291299,\"low\":62,"
			+ "\"lowTime\":1794291396}}").getAsJsonObject();
		Set<Integer> was = FlipEngine.proven;
		FlipEngine.proven = Set.of(6332);
		try
		{
			return OfferAdvice.advise(List.of(bid), five, hour, latest, Map.of(), Map.of(6332, 60L), Set.of(), WINDOW,
				1_794_291_524_000L).get(0);
		}
		finally
		{
			FlipEngine.proven = was;
		}
	}

	@Test
	public void theCaseSaysCancelNotWait()
	{
		OfferAdvice step = FlipBrain.waitStep(waitCard(), theCase());
		assertEquals(OfferAdvice.CANCEL, step.verdict);
		assertEquals(5, step.offer.slot);
		Suggestion card = FlipBrain.stepCard(step, NOW / 1000);
		assertEquals("CANCEL", card.action);
		assertEquals(1617, card.itemId);
		assertEquals("Uncut diamond buy, slot 5", card.note);
	}

	@Test
	public void cancelComesBeforeARaise()
	{
		OfferAdvice r = raise();
		assertEquals(OfferAdvice.RAISE, r.verdict);
		List<OfferAdvice> adv = theCase();
		adv.set(3, r);
		assertEquals(5, FlipBrain.waitStep(waitCard(), adv).offer.slot);
		adv.set(4, waiting(offer(5, true, 1617, "Uncut diamond", 2224, 8260, 454)));    // no cancel left: the raise
		OfferAdvice step = FlipBrain.waitStep(waitCard(), adv);
		assertSame(r, step);
		assertEquals("Mahogany logs buy, slot 4 - to 61", FlipBrain.stepCard(step, NOW / 1000).note);
	}

	@Test
	public void onlyTheAllSlotsWaitChanges()
	{
		Suggestion collect = new Suggestion();
		collect.action = "COLLECT";
		assertNull("Collect stays Collect", FlipBrain.waitStep(collect, theCase()));
		List<OfferAdvice> calm = theCase();
		calm.set(4, waiting(offer(5, true, 1617, "Uncut diamond", 2224, 8260, 454)));
		assertNull("nothing to change: it stays Wait", FlipBrain.waitStep(waitCard(), calm));
		assertNull("no card", FlipBrain.waitStep(null, theCase()));
	}
}
