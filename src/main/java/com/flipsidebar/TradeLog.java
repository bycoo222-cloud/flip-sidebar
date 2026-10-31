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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What your GE offers bought and sold, worked out from how each slot changed — the way RuneLite's
 * own Grand Exchange plugin keeps its trade history. It is how the plugin knows "you bought 2,431
 * Manta rays at 960 and have not sold them" without the Flip Sidebar program — and, when they sell,
 * what that flip made or LOST (user, 2026-10-31: "i wanna see when a flip is a loss"). Saved per
 * account (FlipSidebarPlugin, RuneScape-profile config). Client thread only.
 *
 * <p>⚠ A sell offer's {@code spent} is the coins BEFORE the 2% tax — MEASURED 2026-10-31: 4,201
 * Ascension shards sold at 1,778 showed spent = 7,469,378 = 4,201 × 1,778 exactly. So the tax is
 * taken off here.</p>
 */
class TradeLog
{
	private static final int SLOTS = 8;
	/** Bought-and-unsold counts for this long after the last buy — the sidebar's 12 hours. */
	static final long HOLD_MS = 12L * 3600 * 1000;
	/** The GE buy-limit window: an item bought this recently is not offered again. */
	static final long LIMIT_MS = 4L * 3600 * 1000;

	/** A slot as last seen. */
	static class Slot
	{
		int itemId;
		boolean buy;
		long price;
		int total;
		int sold;
		long spent;
		/** When the offer was placed, epoch ms; 0 = unknown (it was there before it was tracked). */
		long placedAt;
		/** When it last filled, epoch ms; 0 = no fill seen yet. */
		long lastFill;
	}

	static class Bought
	{
		long qty;
		long cost;
		long lastBuy;
	}

	/** One finished (or finishing) flip: a sell matched against what was paid. */
	static class Flip
	{
		int itemId;
		long qty;
		long buyEach;
		long sellEach;
		/** After the 2% tax. */
		long profit;
		/** When it last filled, epoch ms; 0 = before the history was kept (filled earlier). */
		long at;
		/** The sell offer it came from: its fills grow ONE row, not a row per fill. */
		String offer;
	}

	/** The newest flips kept, per account. */
	static final int FLIPS_KEPT = 200;

	private Slot[] slots = new Slot[SLOTS];
	private Map<Integer, Bought> bought = new HashMap<>();
	/**
	 * Item -> {window start, units bought in it}: the GE buy limit. The 4-hour window starts at the
	 * FIRST item bought, is not extended by later buys, and then resets completely (OSRS wiki,
	 * "Grand Exchange/Buying limits", read 2026-10-31).
	 */
	private Map<Integer, long[]> limitUse = new HashMap<>();
	/** Newest last. */
	private List<Flip> flips = new ArrayList<>();
	/** {slot 0-7, profit} of each sell fill since the last {@link #takeDrops} — the slot flash. Not saved. */
	private transient List<long[]> drops;
	/** Null in a log saved before flips were kept: fix() fills in what is already selling. */
	private Integer version;

	/**
	 * One slot changed. Works out how many filled since it was last seen, and records them as
	 * bought (a buy) or takes them off what is held (a sell). Returns true when something changed
	 * and the log should be saved.
	 */
	boolean update(int slot, boolean empty, boolean buy, int itemId, long price, int total, int sold, long spent,
		long nowMs)
	{
		fix();
		if (slot < 0 || slot >= SLOTS)
		{
			return false;
		}
		Slot prev = slots[slot];
		if (empty)
		{
			slots[slot] = null;
			return prev != null;
		}
		int dq;
		long ds;
		if (prev != null && prev.itemId == itemId && prev.buy == buy && prev.price == price && prev.total == total)
		{
			dq = sold - prev.sold;
			ds = spent - prev.spent;
			if (dq == 0 && ds == 0)
			{
				return false;         // the login burst re-sends every slot unchanged
			}
		}
		else
		{
			dq = sold;                // a new offer in this slot
			ds = spent;
		}
		if (dq > 0 && buy)
		{
			Bought b = bought.computeIfAbsent(itemId, k -> new Bought());
			b.qty += dq;
			b.cost += ds;
			b.lastBuy = nowMs;
			long[] w = limitUse.get(itemId);
			if (w == null || nowMs - w[0] >= LIMIT_MS)
			{
				w = new long[]{nowMs, 0};
				limitUse.put(itemId, w);
			}
			w[1] += dq;
		}
		else if (dq > 0)
		{
			Bought b = bought.get(itemId);
			if (b != null)
			{
				long n = Math.min(dq, b.qty);
				record(slot, itemId, price, total, n, b.qty > 0 ? b.cost / b.qty : 0, ds / dq, nowMs);
				b.cost -= b.qty > 0 ? b.cost * n / b.qty : 0;
				b.qty -= n;
				if (b.qty <= 0)
				{
					bought.remove(itemId);
				}
			}
		}
		Slot s = new Slot();
		s.itemId = itemId;
		s.buy = buy;
		s.price = price;
		s.total = total;
		s.sold = sold;
		s.spent = spent;
		boolean same = prev != null && prev.itemId == itemId && prev.buy == buy && prev.price == price
			&& prev.total == total;
		// a new offer seen with nothing filled was just placed; one already part-filled when first
		// seen was there before it was tracked — its times are unknown, not "now"
		s.placedAt = same ? prev.placedAt : sold == 0 ? nowMs : 0;
		s.lastFill = !same ? 0 : dq > 0 ? nowMs : prev.lastFill;
		slots[slot] = s;
		bought.values().removeIf(b -> nowMs - b.lastBuy > HOLD_MS);
		limitUse.values().removeIf(w -> nowMs - w[0] >= LIMIT_MS);
		return true;
	}

	/** [item id, quantity, average paid each, last buy fill (epoch ms)] of everything bought in the last 12h and not sold. */
	List<long[]> holdings(long nowMs)
	{
		fix();
		List<long[]> out = new ArrayList<>();
		for (Map.Entry<Integer, Bought> e : bought.entrySet())
		{
			Bought b = e.getValue();
			if (b.qty > 0 && nowMs - b.lastBuy <= HOLD_MS)
			{
				out.add(new long[]{e.getKey(), b.qty, b.cost / b.qty, b.lastBuy});
			}
		}
		return out;
	}

	/** Item -> {units bought in its open buy-limit window, when that window resets (epoch ms)}. */
	Map<Integer, long[]> limitUse(long nowMs)
	{
		fix();
		Map<Integer, long[]> out = new HashMap<>();
		for (Map.Entry<Integer, long[]> e : limitUse.entrySet())
		{
			long[] w = e.getValue();
			if (nowMs - w[0] < LIMIT_MS)
			{
				out.put(e.getKey(), new long[]{w[1], w[0] + LIMIT_MS});
			}
		}
		return out;
	}

	/** {placed, last fill} of the offer in {@code slot} (0-7), epoch ms (0 = unknown); null if none. */
	long[] times(int slot)
	{
		fix();
		Slot s = slot >= 0 && slot < SLOTS ? slots[slot] : null;
		return s == null ? null : new long[]{s.placedAt, s.lastFill};
	}

	/** What was paid each for {@code itemId} still held, or 0 when not known. */
	long paidEach(int itemId)
	{
		fix();
		Bought b = bought.get(itemId);
		return b == null || b.qty <= 0 ? 0 : b.cost / b.qty;
	}

	/** The sell fills seen since the last call, {slot 0-7, profit after tax} — then forgotten. */
	List<long[]> takeDrops()
	{
		List<long[]> out = drops == null ? List.of() : drops;
		drops = null;
		return out;
	}

	/** Flips, newest first. */
	List<Flip> flips()
	{
		fix();
		List<Flip> out = new ArrayList<>(flips);
		Collections.reverse(out);
		return out;
	}

	/** {@code n} sold at {@code sellEach} (before tax) against {@code buyEach} paid — added to its offer's row. */
	private void record(int slot, int itemId, long price, int total, long n, long buyEach, long sellEach, long nowMs)
	{
		if (n <= 0)
		{
			return;
		}
		String key = slot + ":" + itemId + ":" + price + ":" + total;
		long profit = (sellEach - FlipEngine.tax(sellEach, itemId) - buyEach) * n;
		if (nowMs > 0)                // a live fill, not one filled in from an older save
		{
			if (drops == null)
			{
				drops = new ArrayList<>();
			}
			drops.add(new long[]{slot, profit});
		}
		for (int i = flips.size() - 1; i >= Math.max(0, flips.size() - 16); i--)
		{
			if (key.equals(flips.get(i).offer))
			{
				Flip f = flips.remove(i);
				f.sellEach = (f.sellEach * f.qty + sellEach * n) / (f.qty + n);
				f.qty += n;
				f.profit += profit;
				f.at = nowMs;
				flips.add(f);
				return;
			}
		}
		Flip f = new Flip();
		f.itemId = itemId;
		f.qty = n;
		f.buyEach = buyEach;
		f.sellEach = sellEach;
		f.profit = profit;
		f.at = nowMs;
		f.offer = key;
		flips.add(f);
		while (flips.size() > FLIPS_KEPT)
		{
			flips.remove(0);
		}
	}

	/** A log loaded from an old or hand-edited save can miss fields. */
	private void fix()
	{
		if (slots == null || slots.length != SLOTS)
		{
			slots = new Slot[SLOTS];
		}
		if (bought == null)
		{
			bought = new HashMap<>();
		}
		if (limitUse == null)
		{
			limitUse = new HashMap<>();
		}
		if (flips == null)
		{
			flips = new ArrayList<>();
		}
		if (version == null)
		{
			// saved before flips were kept: the sells already filling are flips too, against what
			// is still held (selling does not change the average paid)
			version = 1;
			for (int i = 0; i < SLOTS; i++)
			{
				Slot sl = slots[i];
				Bought b = sl == null || sl.buy || sl.sold <= 0 ? null : bought.get(sl.itemId);
				if (b != null && b.qty > 0)
				{
					record(i, sl.itemId, sl.price, sl.total, sl.sold, b.cost / b.qty, sl.spent / sl.sold, 0);
				}
			}
		}
	}
}
