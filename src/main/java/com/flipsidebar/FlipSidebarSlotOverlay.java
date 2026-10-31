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

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;

/**
 * On each GE slot: how long since the offer last filled — or since it was placed, before any fill —
 * orange once that is longer than the re-price window. Hover a slot for when it was placed and last
 * filled and, for a sell, what the rest makes after the tax. User, 2026-10-31: "theres normally a
 * timer that shows how long the offers been open ... from the last time someone bought one of the
 * item. it should also tell me expected profit when i hover my mouse over the active sell offer."
 * The times come from TradeLog, which sees every fill.
 *
 * <p>And each time a SELL fills — even partly — its slot flashes the profit just made, green, or the
 * loss in red, fading over a few seconds, as Flipping Copilot does (user, 2026-10-31: "every time
 * something sold partially there would be a green flash over the sale slot that tells u how much
 * profit or loss you just made").</p>
 */
class FlipSidebarSlotOverlay extends Overlay
{
	private static final int[] SLOTS = {
		InterfaceID.GeOffers.INDEX_0, InterfaceID.GeOffers.INDEX_1, InterfaceID.GeOffers.INDEX_2,
		InterfaceID.GeOffers.INDEX_3, InterfaceID.GeOffers.INDEX_4, InterfaceID.GeOffers.INDEX_5,
		InterfaceID.GeOffers.INDEX_6, InterfaceID.GeOffers.INDEX_7,
	};
	private static final Color TEXT = new Color(0xFFFFFF);
	private static final Color STALE = new Color(0xFF981F);
	private static final int INSET = 5;
	private static final int BASELINE = 14;
	/** How long the profit stays up after the LAST fill it shows. */
	private static final long FLASH_MS = 3_000;
	/** The text rises this far over RISE_MS, easing out, then stays. */
	private static final float FLASH_RISE = 16f;
	private static final long RISE_MS = 1_200;
	/** Full strength for this share of FLASH_MS, then it fades. */
	private static final double HOLD_PART = 0.45;
	/** The slot's colour pulse dies away over this. */
	private static final long PULSE_MS = 900;
	private static final Color GAIN = new Color(0x4FD16A);
	private static final Color LOSS = new Color(0xFF5A52);

	private final Client client;
	private final FlipSidebarPlugin plugin;
	private final FlipSidebarConfig config;
	private final TooltipManager tooltipManager;
	/** {slot 0-7, profit, started ms, last fill ms} — client thread only. */
	private final List<long[]> flashes = new ArrayList<>();

	@Inject
	FlipSidebarSlotOverlay(Client client, FlipSidebarPlugin plugin, FlipSidebarConfig config, TooltipManager tooltipManager)
	{
		super(plugin);
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.tooltipManager = tooltipManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	/**
	 * A sell in GE slot {@code slot} (0-7) just made {@code profit}. Fills close together add up —
	 * the number changes, the animation carries on (restarting it made the text jump back down:
	 * user, 2026-10-31, "its really choppy"). Client thread.
	 */
	void flash(int slot, long profit)
	{
		long now = System.currentTimeMillis();
		for (long[] f : flashes)
		{
			if (f[0] == slot && now - f[3] < FLASH_MS)
			{
				f[1] += profit;
				f[3] = now;
				return;
			}
		}
		flashes.add(new long[]{slot, profit, now, now});
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		long now = System.currentTimeMillis();
		flashes.removeIf(f -> now - f[3] > FLASH_MS);
		if (!plugin.isGeOpen())
		{
			return null;
		}
		if (config.profitFlash())
		{
			drawFlashes(g, now);
		}
		if (!config.slotTimers())
		{
			return null;
		}
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		Point mouse = client.getMouseCanvasPosition();
		long window = config.adjustWindow().minutes * 60_000L;
		g.setFont(FontManager.getRunescapeSmallFont());
		FontMetrics fm = g.getFontMetrics();
		for (int i = 0; offers != null && i < Math.min(offers.length, SLOTS.length); i++)
		{
			GrandExchangeOffer o = offers[i];
			GrandExchangeOfferState st = o == null ? GrandExchangeOfferState.EMPTY : o.getState();
			Widget w = st == GrandExchangeOfferState.EMPTY ? null : client.getWidget(SLOTS[i]);
			Rectangle r = w == null || w.isHidden() ? null : w.getBounds();
			if (r == null)
			{
				continue;
			}
			boolean active = st == GrandExchangeOfferState.BUYING || st == GrandExchangeOfferState.SELLING;
			long[] t = plugin.slotTimes(i);
			long since = t == null ? 0 : t[1] > 0 ? t[1] : t[0];
			if (active && since > 0)
			{
				String text = clock(now - since);
				int x = r.x + r.width - INSET - fm.stringWidth(text);
				int y = r.y + BASELINE;
				g.setColor(Color.BLACK);
				g.drawString(text, x + 1, y + 1);
				g.setColor(now - since > window ? STALE : TEXT);
				g.drawString(text, x, y);
			}
			if (mouse != null && r.contains(mouse.getX(), mouse.getY()))
			{
				tooltipManager.add(new Tooltip(String.join("</br>", tooltip(o, st, t, now))));
			}
		}
		return null;
	}

	/**
	 * The "+12,345" (green) or "-3,210" (red) over each slot that just sold: a colour pulse on the
	 * slot, the number rising with an ease-out, held, then faded — drawn at fractional positions,
	 * so it glides instead of stepping a whole pixel at a time.
	 */
	private void drawFlashes(Graphics2D g, long now)
	{
		Object aa = g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
		Object fm0 = g.getRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
		g.setFont(FontManager.getRunescapeBoldFont());
		for (long[] f : flashes)
		{
			Widget w = f[0] >= 0 && f[0] < SLOTS.length ? client.getWidget(SLOTS[(int) f[0]]) : null;
			Rectangle r = w == null || w.isHidden() ? null : w.getBounds();
			if (r != null)
			{
				paintFlash(g, r, f, now);
			}
		}
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, aa);
		g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, fm0);
	}

	/** One flash {slot, profit, started, last fill} over the slot's bounds {@code r}, at time {@code now}. */
	static void paintFlash(Graphics2D g, Rectangle r, long[] f, long now)
	{
		FontMetrics fm = g.getFontMetrics();
		Color c = f[1] >= 0 ? GAIN : LOSS;
		// the slot pulses at each fill, dying away quickly
		double pulse = Math.max(0, 1.0 - (double) (now - f[3]) / PULSE_MS);
		if (pulse > 0)
		{
			g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) (80 * pulse * pulse)));
			g.fill(r);
		}
		// rise: ease-out cubic from the first fill; fade: held, then smooth, from the last fill
		double tr = Math.min(1.0, (double) (now - f[2]) / RISE_MS);
		float rise = (float) (FLASH_RISE * (1 - Math.pow(1 - tr, 3)));
		double tf = (double) (now - f[3]) / FLASH_MS;
		double alpha = tf <= HOLD_PART ? 1.0 : Math.max(0, 1.0 - (tf - HOLD_PART) / (1 - HOLD_PART));
		alpha = alpha * alpha * (3 - 2 * alpha);       // smoothstep
		String text = (f[1] >= 0 ? "+" : "-") + String.format("%,d", Math.abs(f[1]));
		float x = r.x + (r.width - fm.stringWidth(text)) / 2f;
		float y = r.y + r.height / 2f + fm.getAscent() / 2f - rise;
		int a = (int) Math.round(255 * alpha);
		g.setColor(new Color(0, 0, 0, a));
		g.drawString(text, x + 1, y + 1);
		g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), a));
		g.drawString(text, x, y);
	}

	/**
	 * The slot's hover, in the inventory hover's words (user, 2026-10-31, showing it: "i want this
	 * but when you hover over the sell offer box"): what was paid, break-even, the offer's price
	 * against the market, and what the rest makes after tax; then the buy limit and the times.
	 */
	private List<String> tooltip(GrandExchangeOffer o, GrandExchangeOfferState st, long[] t, long now)
	{
		boolean buy = st == GrandExchangeOfferState.BUYING || st == GrandExchangeOfferState.BOUGHT
			|| st == GrandExchangeOfferState.CANCELLED_BUY;
		int item = o.getItemId();
		long price = o.getPrice();
		int left = o.getTotalQuantity() - o.getQuantitySold();
		long[] market = plugin.quote(item);
		List<String> lines = new ArrayList<>();
		lines.add("Flip Sidebar: " + plugin.itemName(item));
		if (buy)
		{
			lines.add(market[0] > 0 ? String.format("Your bid %,d · now %,d", price, market[0])
				: String.format("Your bid %,d", price));
			long[] lim = plugin.limitInfo(item);
			if (lim != null)
			{
				lines.add(lim[2] > 0
					? String.format("Buy limit: %,d of %,d used · resets in %s", lim[0], lim[2], clock(lim[1] - now))
					: String.format("Bought %,d this window · resets in %s", lim[0], clock(lim[1] - now)));
			}
		}
		else
		{
			long paid = plugin.paidEach(item);
			if (paid > 0)
			{
				lines.add(String.format("Paid %,d each · break-even %,d", paid, FlipEngine.breakEven(paid, item)));
			}
			lines.add(market[1] > 0 ? String.format("Your ask %,d · now %,d", price, market[1])
				: String.format("Your ask %,d", price));
			if (paid <= 0)
			{
				lines.add("Paid: not on record - profit unknown");
			}
			else if (left > 0)
			{
				long rest = (price - FlipEngine.tax(price, item) - paid) * left;
				lines.add(String.format("→ <col=%s>%s%s</col> if the other %,d sell, after tax",
					rest >= 0 ? "4fd16a" : "ff5a52", rest >= 0 ? "+" : "-", FlipBrain.gp(Math.abs(rest)), left));
			}
		}
		String placed = t == null || t[0] <= 0 ? "placed before tracking" : "placed " + clock(now - t[0]) + " ago";
		String fill = t == null || t[1] <= 0 ? "no fill yet" : "last fill " + clock(now - t[1]) + " ago";
		lines.add(Character.toUpperCase(placed.charAt(0)) + placed.substring(1) + " · " + fill);
		return lines;
	}

	/** 45s, 12m, 1h05. */
	static String clock(long ms)
	{
		long s = Math.max(0, ms / 1000);
		if (s < 60)
		{
			return s + "s";
		}
		if (s < 3600)
		{
			return (s / 60) + "m";
		}
		return String.format("%dh%02d", s / 3600, (s % 3600) / 60);
	}
}
