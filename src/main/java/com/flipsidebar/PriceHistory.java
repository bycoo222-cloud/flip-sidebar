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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** An item's price history for the charts, built from the OSRS Wiki's prices ({@link #of}). */
class PriceHistory
{
	/** Range -> [wiki timestep, seconds shown]. */
	static final Map<String, Object[]> RANGES = Map.of(
		"1d", new Object[]{"5m", 86400L},
		"1w", new Object[]{"1h", 7 * 86400L},
		"1m", new Object[]{"6h", 30 * 86400L},
		"1y", new Object[]{"24h", 365 * 86400L});

	int itemId;
	/** "1d", "1w", "1m" or "1y". */
	String range;
	/**
	 * Each point is [epoch seconds, average insta-buy, average insta-sell, insta-buy volume,
	 * insta-sell volume] — a price of 0 = no trade then.
	 */
	long[][] points;
	Latest latest;
	/** When this was built, epoch seconds. */
	long written;

	/** From the wiki's /timeseries data and /latest record for the item. */
	static PriceHistory of(int itemId, String range, JsonArray series, JsonObject latest, long nowS)
	{
		long cut = nowS - (long) RANGES.get(range)[1];
		List<long[]> pts = new ArrayList<>();
		for (JsonElement e : series)
		{
			JsonObject p = e.getAsJsonObject();
			long ts = Research.num(p, "timestamp");
			if (ts >= cut)
			{
				pts.add(new long[]{ts, Research.num(p, "avgHighPrice"), Research.num(p, "avgLowPrice"),
					Research.num(p, "highPriceVolume"), Research.num(p, "lowPriceVolume")});
			}
		}
		PriceHistory h = new PriceHistory();
		h.itemId = itemId;
		h.range = range;
		h.points = pts.toArray(new long[0][]);
		h.latest = new Latest();
		if (latest != null)
		{
			h.latest.high = Research.num(latest, "high");
			h.latest.highTime = Research.num(latest, "highTime");
			h.latest.low = Research.num(latest, "low");
			h.latest.lowTime = Research.num(latest, "lowTime");
		}
		h.written = nowS;
		return h;
	}

	static class Latest
	{
		long high;
		long highTime;
		long low;
		long lowTime;
	}
}
