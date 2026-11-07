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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * ⭐ A buy collected from the GE: did its items reach the inventory, or go straight to the bank (TradeLog.deposited)?
 * Client thread (FlipSidebarPlugin: offer events and trackBank).
 * <p>
 * The answer is "the inventory" when the item's inventory count went UP at any tick from the buy's first fill until
 * {@link #COLLECT_TICKS} after its slot emptied; never up = the bank. Until 1.60.31 it compared the inventory {@code
 * COLLECT_TICKS} after the slot emptied with the tick before it — wrong for a CANCELLED buy, which has its items AND
 * the refund coins to collect: items clicked first, the slot empties only at the coins' click, after the items were
 * already counted. MEASURED 2026-11-05 17:58–18:31 (journal + trade log): every one of the 5 buys wrongly marked
 * "collected to the bank" and held long-term (Sunfire splinters 8,694, Astral rune 9,454, Diamond bolt tips 1,415, Dragon
 * dart 173, Emerald necklace 1,198) was a cancelled buy with coins left to collect; no completed buy was marked. User:
 * "I have a ton of items but they aren't being show as flip items."
 */
final class CollectWatch
{
	/** Ticks after a slot empties to see the items arrive in the inventory. */
	static final int COLLECT_TICKS = 2;

	/** GE slot (0-7) -> {item, rose}: a buy with something bought; rose = 1 once its item's inventory count went up. */
	private final Map<Integer, long[]> slots = new HashMap<>();
	/** Buys just collected: {item, units, rose, tick the slot emptied}. */
	private final List<long[]> collects = new ArrayList<>();

	/**
	 * A slot's offer changed (before the trade log's update). {@code buyWithFills}: a buy that has bought something (BUYING,
	 * BOUGHT or CANCELLED_BUY with some sold); {@code empty}: the slot emptied, {@code bought} = {item, units} the trade
	 * log had in it (TradeLog.buyIn; null = none) — a collect, watched for {@link #COLLECT_TICKS} more ticks.
	 */
	void offer(int slot, int itemId, boolean buyWithFills, boolean empty, long[] bought, int tick)
	{
		if (empty)
		{
			long[] w = slots.remove(slot);
			if (bought != null && bought[1] > 0)
			{
				boolean rose = w != null && w[0] == bought[0] && w[1] > 0;
				collects.add(new long[]{bought[0], bought[1], rose ? 1 : 0, tick});
			}
			return;
		}
		if (buyWithFills)
		{
			long[] w = slots.get(slot);
			if (w == null || w[0] != itemId)
			{
				slots.put(slot, new long[]{itemId, 0});
			}
		}
		else
		{
			slots.remove(slot);       // a sell, or a buy with nothing bought yet
		}
	}

	/**
	 * One game tick: the inventory now and as the previous tick saw it (null = not known: rises are not seen). Returns
	 * the collects decided now as gone to the BANK, {item, units}.
	 */
	List<long[]> tick(Map<Integer, Integer> inv, Map<Integer, Integer> before, int tick)
	{
		if (before != null)
		{
			for (long[] w : slots.values())
			{
				w[1] |= rose(inv, before, w[0]) ? 1 : 0;
			}
			for (long[] c : collects)
			{
				c[2] |= rose(inv, before, c[0]) ? 1 : 0;
			}
		}
		List<long[]> bank = new ArrayList<>();
		for (Iterator<long[]> it = collects.iterator(); it.hasNext(); )
		{
			long[] c = it.next();
			if (tick - c[3] < COLLECT_TICKS)
			{
				continue;
			}
			it.remove();
			if (c[2] == 0)
			{
				bank.add(new long[]{c[0], c[1]});
			}
		}
		return bank;
	}

	private static boolean rose(Map<Integer, Integer> inv, Map<Integer, Integer> before, long itemId)
	{
		return inv.getOrDefault((int) itemId, 0) > before.getOrDefault((int) itemId, 0);
	}

	/** Logged out or hopped: nothing in flight is known any more. */
	void clear()
	{
		slots.clear();
		collects.clear();
	}
}
