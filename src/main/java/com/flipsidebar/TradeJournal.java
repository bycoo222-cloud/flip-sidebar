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
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.runelite.client.util.Filepath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The TRADE JOURNAL — measurement before any more rule changes. User, 2026-11-01, of the plan "instrumentation first:
 * a journal of every suggestion, offer, fill and sale with what was predicted and the market at that moment; then fit
 * fills and markouts on real data; adopt a rule only when it wins on days it was not tuned on": "id rather push it back
 * and have it be right ... i say we should go with it".
 *
 * <p>One JSON object per line, {@code {"t": epoch ms, "e": event, ...}}, in plugin-data/flip-sidebar/&lt;character&gt;/
 * journal/&lt;yyyy-MM-dd&gt;.jsonl (local date). Events: {@code session} (version and settings), {@code offer} (every GE
 * slot change, raw — our own fills), {@code card} (each new card: what was predicted and from what), {@code advice}
 * (a slot's verdict changed), {@code instant} (a fill no slot showed), {@code hold} / {@code release} / {@code letgo}.
 * Local only, never sent anywhere; appended off the client thread; files past {@link #KEEP_DAYS} are deleted.</p>
 */
final class TradeJournal
{
	private static final Logger log = LoggerFactory.getLogger(TradeJournal.class);
	/** Days of journal kept. */
	static final int KEEP_DAYS = 60;

	private final Executor executor;
	private final Gson gson;
	private volatile Filepath dir;
	private volatile boolean on = true;

	TradeJournal(Executor executor, Gson gson)
	{
		this.executor = executor;
		this.gson = gson;
	}

	void setOn(boolean on)
	{
		this.on = on;
	}

	/** The character's journal folder (null = logged out: nothing is written). True when it changed. */
	boolean setDir(Filepath d)
	{
		Filepath old = dir;
		dir = d;
		if (d != null && !d.equals(old))
		{
			executor.execute(() -> prune(d));
			return true;
		}
		return false;
	}

	/** Append one event; {@code f} is consumed (its "t" and "e" are set here). Any thread. */
	void log(String event, JsonObject f)
	{
		Filepath d = dir;
		if (!on || d == null)
		{
			return;
		}
		long now = System.currentTimeMillis();
		f.addProperty("t", now);
		f.addProperty("e", event);
		String line = gson.toJson(f) + "\n";
		String file = new SimpleDateFormat("yyyy-MM-dd").format(new Date(now)) + ".jsonl";
		executor.execute(() ->
		{
			try
			{
				d.createDirectories();
				d.joinSegment(file).write(line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE,
					StandardOpenOption.APPEND);
			}
			catch (IOException | IllegalArgumentException e)
			{
				log.debug("Flip Sidebar: journal write failed", e);
			}
		});
	}

	/** Delete journal days past KEEP_DAYS (the file names sort by date). */
	private static void prune(Filepath d)
	{
		if (!d.isDirectory())
		{
			return;
		}
		try (Stream<Filepath> s = d.walk(1))
		{
			List<Filepath> days = s.filter(p -> p.getFileName().endsWith(".jsonl")).sorted().collect(Collectors.toList());
			for (Filepath p : new ArrayList<>(days.subList(0, Math.max(0, days.size() - KEEP_DAYS))))
			{
				p.deleteIfExists();
			}
		}
		catch (IOException | IllegalArgumentException e)
		{
			log.debug("Flip Sidebar: journal prune failed", e);
		}
	}
}
