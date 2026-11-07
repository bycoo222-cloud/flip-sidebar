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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ⭐ THE PLAYER'S GE OFFERS for Data sync (1.60.42): each offer's life — item, buy or sell, price, quantity, filled, gp,
 * its state, when it was placed, first filled and last changed, and the market when it was placed — sent with the
 * random team tag (never a name) to the Worker's POST /v1/offers (TeamSync.uploadOffers). User, 2026-11-07: "Can we do
 * the storing users trades? Like right away until user count becomes an issue" — the data Flipping Copilot learns from
 * (which bids fill, how fast, which get cancelled); the server's big brain is meant to use it. Only with Data sync and
 * Live prices on. Kept in memory: what was not sent when RuneLite closes is lost (at most one upload's worth).
 * <p>Any thread (synchronized): {@link #seen} from the client thread, {@link #take} / {@link #sent} from the upload.</p>
 */
final class OfferShare
{
	/** Offers kept waiting to be sent, at most (the oldest go first past it). */
	static final int MAX_WAITING = 2_000;

	private static final class Rec
	{
		String key;
		int item;
		boolean buy;
		long price;
		long total;
		long filled;
		long spent;
		String state;
		/** When it was placed, epoch ms; 0 = before this client saw it (the login burst). */
		long placed;
		long first;
		long last;
		long[] mk = new long[6];
		boolean closed;
	}

	private final Map<Integer, Rec> bySlot = new HashMap<>();
	private final LinkedHashMap<String, Rec> waiting = new LinkedHashMap<>();

	/**
	 * One GE offer event. {@code known} = placed while this client watched (not the login burst, when the client sends
	 * every slot again). {@code mk} = FlipBrain.marketNow (lo, hi, l5, h5, lv5, hv5 used; null = none).
	 */
	synchronized void seen(int slot, String state, int item, long price, long total, long sold, long spent, boolean buy,
		boolean known, long now, JsonObject mk)
	{
		if (item <= 0 || "EMPTY".equals(state))
		{
			bySlot.remove(slot);
			return;
		}
		Rec r = bySlot.get(slot);
		boolean active = "BUYING".equals(state) || "SELLING".equals(state);
		if (r == null || r.item != item || r.price != price || r.total != total || r.buy != buy || (r.closed && active))
		{
			r = new Rec();
			r.item = item;
			r.buy = buy;
			r.price = price;
			r.total = total;
			r.placed = known && sold == 0 ? now : 0;
			r.key = (r.placed > 0 ? r.placed : now) + ":" + slot + ":" + item + ":" + price + ":" + total;
			if (mk != null && r.placed > 0)
			{
				String[] k = {"lo", "hi", "l5", "h5", "lv5", "hv5"};
				for (int i = 0; i < k.length; i++)
				{
					r.mk[i] = mk.has(k[i]) && !mk.get(k[i]).isJsonNull() ? mk.get(k[i]).getAsLong() : 0;
				}
			}
			bySlot.put(slot, r);
		}
		if (r.closed)
		{
			return;                   // the client repeats a finished offer: nothing new
		}
		boolean change = !state.equals(r.state) || sold != r.filled;
		if (sold > r.filled && r.first == 0)
		{
			r.first = now;
		}
		r.filled = Math.max(r.filled, sold);
		r.spent = Math.max(r.spent, spent);
		r.state = state;
		r.last = now;
		r.closed = !active;
		if (change)
		{
			waiting.remove(r.key);
			waiting.put(r.key, r);
			while (waiting.size() > MAX_WAITING)
			{
				String oldest = waiting.keySet().iterator().next();
				waiting.remove(oldest);
			}
		}
	}

	/**
	 * Up to {@code max} changed offers as upload rows: [key, item, buy (0/1), price, total, filled, spent, state, placed,
	 * first, last, lo, hi, l5, h5, lv5, hv5]. They stay waiting until {@link #sent}.
	 */
	synchronized List<JsonArray> take(int max)
	{
		List<JsonArray> out = new ArrayList<>();
		for (Rec r : waiting.values())
		{
			if (out.size() >= max)
			{
				break;
			}
			JsonArray a = new JsonArray();
			a.add(r.key);
			a.add(r.item);
			a.add(r.buy ? 1 : 0);
			a.add(r.price);
			a.add(r.total);
			a.add(r.filled);
			a.add(r.spent);
			a.add(r.state);
			a.add(r.placed);
			a.add(r.first);
			a.add(r.last);
			for (long v : r.mk)
			{
				a.add(v);
			}
			out.add(a);
		}
		return out;
	}

	/** The rows of {@link #take} reached the server: those not changed since are no longer waiting. */
	synchronized void sent(List<JsonArray> rows)
	{
		for (JsonArray a : rows)
		{
			Rec r = waiting.get(a.get(0).getAsString());
			if (r != null && r.filled == a.get(5).getAsLong() && r.state.equals(a.get(7).getAsString()))
			{
				waiting.remove(r.key);
			}
		}
	}

	synchronized int waitingCount()
	{
		return waiting.size();
	}
}
