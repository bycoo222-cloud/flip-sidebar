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
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The whole Grand Exchange's last day: every item's 5-minute average insta-buy / insta-sell and volumes, one
 * wiki block (all items, one request) per 5 minutes — for MarketReplay (user, 2026-10-31: "it should be pulling
 * data from the entire market constantly and be learning that way"). A ring of {@link #CAP} blocks; saved to
 * the plugin's folder so a restart does not fetch the day again. Not thread-safe: FlipBrain's thread only.
 */
final class MarketMemory
{
	/** 24 hours of 5-minute blocks. */
	static final int CAP = 288;
	static final long STEP_S = 300;
	private static final int VERSION = 1;
	/** Kept only for items that traded in at least this share of the blocks held (none under it is ever a pick). */
	private static final double KEEP_SHARE = 0.50;

	/** Block start, epoch seconds, of each ring slot; 0 = empty. */
	private final long[] slotTs = new long[CAP];
	/** Item -> per slot {avg insta-buy, avg insta-sell, insta-buy volume, insta-sell volume}; 0 = no trade. */
	private final Map<Integer, int[]> items = new HashMap<>();
	private long newest;

	long newest()
	{
		return newest;
	}

	int items()
	{
		return items.size();
	}

	/** Blocks held of the last day before {@link #newest}. */
	int blocks()
	{
		int n = 0;
		for (int i = 0; i < CAP; i++)
		{
			n += has(i) ? 1 : 0;
		}
		return n;
	}

	/** One wiki /5m answer ({timestamp, data}). False when it was held already. */
	boolean add(JsonObject block)
	{
		long ts = Research.num(block, "timestamp");
		JsonElement d = block.get("data");
		if (ts <= 0 || d == null || !d.isJsonObject() || ts <= newest - (CAP - 1) * STEP_S)
		{
			return false;
		}
		int slot = slot(ts);
		if (slotTs[slot] == ts)
		{
			return false;
		}
		for (int[] a : items.values())
		{
			Arrays.fill(a, slot * 4, slot * 4 + 4, 0);
		}
		slotTs[slot] = ts;
		for (Map.Entry<String, JsonElement> e : d.getAsJsonObject().entrySet())
		{
			if (!e.getValue().isJsonObject())
			{
				continue;
			}
			JsonObject o = e.getValue().getAsJsonObject();
			long hi = Research.num(o, "avgHighPrice");
			long lo = Research.num(o, "avgLowPrice");
			if ((hi <= 0 && lo <= 0) || hi > Integer.MAX_VALUE || lo > Integer.MAX_VALUE)
			{
				continue;
			}
			int id;
			try
			{
				id = Integer.parseInt(e.getKey());
			}
			catch (NumberFormatException x)
			{
				continue;
			}
			int[] a = items.computeIfAbsent(id, k -> new int[CAP * 4]);
			a[slot * 4] = (int) hi;
			a[slot * 4 + 1] = (int) lo;
			a[slot * 4 + 2] = (int) Math.min(Integer.MAX_VALUE, Research.num(o, "highPriceVolume"));
			a[slot * 4 + 3] = (int) Math.min(Integer.MAX_VALUE, Research.num(o, "lowPriceVolume"));
		}
		newest = Math.max(newest, ts);
		return true;
	}

	/** Block starts of the last day (up to {@code newestTs}) not held yet, newest first. */
	List<Long> missing(long newestTs)
	{
		List<Long> out = new ArrayList<>();
		for (int k = 0; k < CAP; k++)
		{
			long ts = newestTs - k * STEP_S;
			if (slotTs[slot(ts)] != ts)
			{
				out.add(ts);
			}
		}
		return out;
	}

	/** Drop items that traded in under half the blocks held — never a pick, and most of the memory. */
	void prune()
	{
		int held = blocks();
		if (held < 72)
		{
			return;                   // too early to judge an item by
		}
		for (Iterator<int[]> it = items.values().iterator(); it.hasNext(); )
		{
			int[] a = it.next();
			int traded = 0;
			for (int i = 0; i < CAP; i++)
			{
				traded += has(i) && a[slotOf(i) * 4] > 0 && a[slotOf(i) * 4 + 1] > 0 ? 1 : 0;
			}
			if (traded < held * KEEP_SHARE)
			{
				it.remove();
			}
		}
	}

	/** Every held item's id. */
	Iterable<Integer> ids()
	{
		return items.keySet();
	}

	/**
	 * Item {@code id}'s blocks oldest first, as {avg insta-buy, avg insta-sell, insta-buy volume, insta-sell volume}
	 * per index 0..CAP-1 ending at {@link #newest}; a block not held, or with no trade, reads 0.
	 */
	int[] series(int id)
	{
		int[] a = items.get(id);
		int[] out = new int[CAP * 4];
		if (a == null)
		{
			return out;
		}
		for (int i = 0; i < CAP; i++)
		{
			if (has(i))
			{
				System.arraycopy(a, slotOf(i) * 4, out, i * 4, 4);
			}
		}
		return out;
	}

	/** True when item {@code id} is kept (it traded in enough of the blocks held; prune drops the rest). */
	boolean holds(int id)
	{
		return items.containsKey(id);
	}

	/**
	 * Item {@code id}'s held blocks as the wiki's /timeseries?timestep=5m "data" array — {timestamp, avgHighPrice,
	 * avgLowPrice, highPriceVolume, lowPriceVolume}, oldest first, 0 for no trade — so Research reads it the same way
	 * (FlipEngine.series5m: no request to the wiki). Empty when the item is not held.
	 */
	JsonArray asSeries(int id)
	{
		JsonArray out = new JsonArray();
		int[] a = items.get(id);
		if (a == null)
		{
			return out;
		}
		for (int i = 0; i < CAP; i++)
		{
			if (!has(i))
			{
				continue;
			}
			int k = slotOf(i) * 4;
			JsonObject o = new JsonObject();
			o.addProperty("timestamp", newest - (CAP - 1 - i) * STEP_S);
			o.addProperty("avgHighPrice", a[k]);
			o.addProperty("avgLowPrice", a[k + 1]);
			o.addProperty("highPriceVolume", a[k + 2]);
			o.addProperty("lowPriceVolume", a[k + 3]);
			out.add(o);
		}
		return out;
	}

	/** Index i (0 = oldest .. CAP-1 = newest) holds its block. */
	private boolean has(int i)
	{
		long ts = newest - (CAP - 1 - i) * STEP_S;
		return newest > 0 && slotTs[slot(ts)] == ts;
	}

	private int slotOf(int i)
	{
		return slot(newest - (CAP - 1 - i) * STEP_S);
	}

	private static int slot(long ts)
	{
		return (int) Math.floorMod(ts / STEP_S, (long) CAP);
	}

	// ── saved in the plugin's folder (FlipBrain) ─────────────────────────────────

	void save(OutputStream raw) throws IOException
	{
		try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(raw)))
		{
			out.writeInt(VERSION);
			out.writeInt(CAP);
			out.writeLong(newest);
			for (long t : slotTs)
			{
				out.writeLong(t);
			}
			out.writeInt(items.size());
			for (Map.Entry<Integer, int[]> e : items.entrySet())
			{
				out.writeInt(e.getKey());
				for (int v : e.getValue())
				{
					out.writeInt(v);
				}
			}
		}
	}

	/** A saved memory, or an empty one when it is missing, old or unreadable. */
	static MarketMemory load(InputStream raw)
	{
		MarketMemory m = new MarketMemory();
		try (DataInputStream in = new DataInputStream(new GZIPInputStream(raw)))
		{
			if (in.readInt() != VERSION || in.readInt() != CAP)
			{
				return new MarketMemory();
			}
			m.newest = in.readLong();
			for (int i = 0; i < CAP; i++)
			{
				m.slotTs[i] = in.readLong();
			}
			int n = in.readInt();
			for (int k = 0; k < n; k++)
			{
				int id = in.readInt();
				int[] a = new int[CAP * 4];
				for (int i = 0; i < a.length; i++)
				{
					a[i] = in.readInt();
				}
				m.items.put(id, a);
			}
			return m;
		}
		catch (IOException e)
		{
			return new MarketMemory();
		}
	}
}
