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
import java.util.Arrays;
import java.util.List;

/**
 * What an item's last ~30 hours of 5-minute trades say — stability, margin, trend and flow.
 * A port of the sidebar's flip_research.research_item; every field is measured, none guessed.
 */
class Research
{
	/** The last 48 five-minute buckets = 4 hours, also the GE buy-limit window. */
	private static final int WINDOW = 48;

	boolean ok;
	String why = "not researched";
	int buckets;
	double contiguity;
	long medianMargin;
	long marginMin;
	int negativeBuckets;
	int sampled;
	double trendPct;
	boolean trendKnown;
	/** Units instant-SOLD per hour — what your BUY offer eats. */
	long buyFlowHr;
	/** Units instant-BOUGHT per hour — what your SELL offer eats. */
	long sellFlowHr;

	/** Minutes for {@code qty} to fill at the recent pace; infinite when nothing is flowing. */
	double fillMinutes(long qty, boolean buySide)
	{
		long flow = buySide ? buyFlowHr : sellFlowHr;
		return flow <= 0 ? Double.POSITIVE_INFINITY : qty * 60.0 / flow;
	}

	static Research of(int itemId, JsonArray series)
	{
		Research r = new Research();
		List<long[]> pts = new ArrayList<>();          // [ts, high, low, highVol, lowVol]
		for (JsonElement e : series)
		{
			JsonObject p = e.getAsJsonObject();
			long hi = num(p, "avgHighPrice");
			long lo = num(p, "avgLowPrice");
			if (hi > 0 && lo > 0)
			{
				pts.add(new long[]{num(p, "timestamp"), hi, lo, num(p, "highPriceVolume"), num(p, "lowPriceVolume")});
			}
		}
		r.buckets = pts.size();
		if (r.buckets < 12)
		{
			r.why = "only " + r.buckets + " traded buckets in the whole series - barely trades";
			return r;
		}
		double spanHours = (pts.get(pts.size() - 1)[0] - pts.get(0)[0]) / 3600.0;
		r.contiguity = Math.min(1.0, r.buckets / Math.max(spanHours * 12.0, 1.0));

		List<long[]> win = pts.subList(Math.max(0, pts.size() - WINDOW), pts.size());
		r.sampled = win.size();
		long[] margins = new long[win.size()];
		for (int i = 0; i < win.size(); i++)
		{
			long[] p = win.get(i);
			margins[i] = p[1] - p[2] - FlipEngine.tax(p[1], itemId);
			if (margins[i] <= 0)
			{
				r.negativeBuckets++;
			}
		}
		long[] sorted = margins.clone();
		Arrays.sort(sorted);
		r.medianMargin = sorted.length % 2 == 1 ? sorted[sorted.length / 2]
			: (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2;
		r.marginMin = sorted[0];

		// trend needs BOTH halves populated — an empty older half once read as "+43375%"
		List<long[]> recent = pts.subList(Math.max(0, pts.size() - 12), pts.size());
		List<long[]> older = pts.subList(Math.max(0, pts.size() - 48), Math.max(0, pts.size() - 12));
		if (recent.size() >= 6 && older.size() >= 12)
		{
			double a = meanLow(recent);
			double b = meanLow(older);
			if (b > 0)
			{
				r.trendPct = (a - b) / b * 100.0;
				r.trendKnown = true;
			}
		}

		// ⚠ a RATE over the real time those buckets cover — the buckets only exist where a trade
		// happened, so "the last 12" can span hours on a thin item
		List<long[]> hour = recent;
		long spanS = hour.size() > 1 ? Math.max(1, hour.get(hour.size() - 1)[0] - hour.get(0)[0]) : 3600;
		double spanH = Math.max(spanS / 3600.0, 5 / 60.0);
		long hv = 0;
		long lv = 0;
		for (long[] p : hour)
		{
			hv += p[3];
			lv += p[4];
		}
		r.buyFlowHr = (long) (lv / spanH);
		r.sellFlowHr = (long) (hv / spanH);
		r.ok = true;
		r.why = "ok";
		return r;
	}

	private static double meanLow(List<long[]> pts)
	{
		double s = 0;
		for (long[] p : pts)
		{
			s += p[2];
		}
		return s / pts.size();
	}

	static long num(JsonObject o, String key)
	{
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? 0 : e.getAsLong();
	}
}
