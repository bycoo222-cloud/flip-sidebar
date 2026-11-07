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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Offline: the plan — best profit a cast first, buy limits, an hour's buyable volume, cash, casts, free-to-play. */
public class AlchPlanTest
{
	private static final AlchPlan.Item BOLTS = new AlchPlan.Item(1, "Onyx bolts (e)", 9_570, 11_000, true);
	private static final AlchPlan.Item STAFF = new AlchPlan.Item(2, "Air battlestaff", 9_300, 18_000, true);
	private static final AlchPlan.Item BODY = new AlchPlan.Item(3, "Blue d'hide body", 5_616, 125, true);
	private static final AlchPlan.Item F2P = new AlchPlan.Item(4, "Rune 2h sword", 38_400, 70, false);
	private static final AlchPlan.Item LOSER = new AlchPlan.Item(5, "Yew longbow", 768, 18_000, true);

	private static Map<Integer, AlchPlan.Quote> quotes()
	{
		return Map.of(
			1, new AlchPlan.Quote(8_900, 8_850, 500_000, 500_000),     // +570 a cast (nature 100)
			2, new AlchPlan.Quote(8_700, 8_650, 50_000, 50_000),       // +500, 5,000 buyable in an hour
			3, new AlchPlan.Quote(4_900, 4_850, 10_000, 10_000),       // +616, limit 125
			4, new AlchPlan.Quote(37_900, 37_800, 5_000, 5_000),       // +400, limit 70
			5, new AlchPlan.Quote(800, 790, 100_000, 100_000));        // -132: never planned
	}

	private static AlchPlan.Settings settings()
	{
		AlchPlan.Settings s = new AlchPlan.Settings();
		s.castsPerHour = 1200;
		s.hours = 4;
		return s;
	}

	@Test
	public void bestProfitACastFirstWithinLimits()
	{
		AlchPlan.Plan p = AlchPlan.plan(List.of(BOLTS, STAFF, BODY, F2P, LOSER), quotes(), 100, settings());
		assertEquals("Blue d'hide body", p.rows.get(0).item.name);     // +616 a cast first
		assertEquals(125, p.rows.get(0).qty);                            // its buy limit
		assertEquals("Onyx bolts (e)", p.rows.get(1).item.name);       // +570
		assertEquals(4_800 - 125, p.rows.get(1).qty);                    // fills the 4 h of casts
		assertEquals(4_800, p.casts());
		assertTrue(p.rows.stream().noneMatch(r -> r.item.name.equals("Yew longbow")));
		assertEquals(616 * 125 + 570 * 4_675, p.profit());
		assertEquals(p.profit() * 1200 / 4_800, p.perHour(1200));
		assertTrue(p.more.stream().anyMatch(r -> r.item.name.equals("Air battlestaff")));   // pays, not needed
	}

	@Test
	public void volumeCashAndTheItemCapTheBuy()
	{
		AlchPlan.Settings s = settings();
		s.skip = Set.of(1, 3);
		Map<Integer, AlchPlan.Quote> thin = new java.util.HashMap<>(quotes());
		thin.put(2, new AlchPlan.Quote(8_700, 8_650, 5_000, 5_000));  // 5,000 trades an hour
		AlchPlan.Plan p = AlchPlan.plan(List.of(BOLTS, STAFF, BODY, F2P, LOSER), thin, 100, s);
		assertEquals("Air battlestaff", p.rows.get(0).item.name);
		assertEquals(2_000, p.rows.get(0).qty);                          // 10% of 4 h of trades, not its 18,000 limit
		assertEquals(70, p.rows.get(1).qty);                             // then the sword's limit
		s.skip = Set.of();
		s.maxItems = 1;                                                  // one GE slot: the item worth the most
		p = AlchPlan.plan(List.of(BOLTS, STAFF, BODY, F2P, LOSER), quotes(), 100, s);
		assertEquals(1, p.rows.size());
		assertEquals("Onyx bolts (e)", p.rows.get(0).item.name);
		assertEquals(4_800, p.rows.get(0).qty);
		s.maxItems = 8;
		s.cash = 1_000_000;
		p = AlchPlan.plan(List.of(BOLTS, STAFF, BODY, F2P, LOSER), quotes(), 100, s);
		assertTrue(p.cash() + p.casts() * 100 <= 1_000_000);
		assertEquals(125, p.rows.get(0).qty);                            // 125 bodies: 625k with the runes
		assertEquals((1_000_000 - 125 * 5_000) / 9_000, p.rows.get(1).qty);
	}

	@Test
	public void fillTimeAssumesATenthOfWhatTrades()
	{
		AlchPlan.Plan p = AlchPlan.plan(List.of(BOLTS), quotes(), 100, settings());
		assertEquals(4_800, p.rows.get(0).qty);
		assertEquals(4_800 * 60.0 / (500_000 * 0.10), p.rows.get(0).minutes(), 1e-9);   // 5.76 min
		AlchPlan.Row unknown = new AlchPlan.Row(BOLTS, 8_900, 570, 100, 0);
		assertEquals(-1, unknown.minutes(), 1e-9);
	}

	@Test
	public void freeToPlayAndTheLeastProfit()
	{
		AlchPlan.Settings s = settings();
		s.freeToPlay = true;
		AlchPlan.Plan p = AlchPlan.plan(List.of(BOLTS, STAFF, BODY, F2P, LOSER), quotes(), 100, s);
		assertEquals(1, p.rows.size());
		assertEquals("Rune 2h sword", p.rows.get(0).item.name);
		s.freeToPlay = false;
		s.minProfit = 600;
		p = AlchPlan.plan(List.of(BOLTS, STAFF, BODY, F2P, LOSER), quotes(), 100, s);
		assertEquals(1, p.rows.size());                                  // only the +616 bodies
		assertEquals(0, AlchPlan.plan(List.of(BOLTS), quotes(), 0, settings()).rows.size());   // no nature price: nothing
	}

	@Test
	public void quotesTakeAFreshLastTradeElseTheAverages()
	{
		Gson g = new Gson();
		JsonObject hour = g.fromJson("{\"1\":{\"avgHighPrice\":9000,\"avgLowPrice\":8800,\"highPriceVolume\":400,"
			+ "\"lowPriceVolume\":300},\"2\":{\"avgHighPrice\":8700,\"avgLowPrice\":8600,\"highPriceVolume\":50,"
			+ "\"lowPriceVolume\":60}}", JsonObject.class);
		JsonObject five = g.fromJson("{\"1\":{\"avgHighPrice\":8950,\"avgLowPrice\":8850}}", JsonObject.class);
		JsonObject latest = g.fromJson("{\"1\":{\"high\":8920,\"highTime\":1000},\"2\":{\"high\":8000,\"highTime\":100}}",
			JsonObject.class);
		Map<Integer, AlchPlan.Quote> q = AlchPlan.quotes(latest, five, hour, 1_100);
		assertEquals(8_920, q.get(1).now);                               // fresh last trade
		assertEquals(8_851, q.get(1).offer);                             // the 5-minute insta-sell + 1
		assertEquals(400, q.get(1).buysHr);
		assertEquals(8_700, q.get(2).now);                               // stale last trade: the hour's average
		assertEquals(8_601, q.get(2).offer);
		JsonArray mapping = g.fromJson("[{\"id\":1,\"name\":\"Onyx bolts (e)\",\"highalch\":9570,\"limit\":11000,"
			+ "\"members\":true},{\"id\":9,\"name\":\"Coins\"}]", JsonArray.class);
		List<AlchPlan.Item> items = AlchPlan.items(mapping);
		assertEquals(1, items.size());                                   // no high alch value: not an item to alch
		assertEquals(11_000, items.get(0).limit);
	}
}
