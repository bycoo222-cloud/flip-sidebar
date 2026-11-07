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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * A price chart on the chat box while an item is open in the offer set-up screen:
 * insta-buy (green) and insta-sell (red) over 1 day / week / month / year, the latest of each
 * with its age, and dashed lines for your offer and the card's buy / sell prices.
 * The numbers come from live prices (see PriceHistory); this class only draws.
 */
class FlipSidebarChartOverlay extends Overlay
{
	static final String[] RANGES = {"1d", "1w", "1m", "1y"};
	/** Each range's step (PriceHistory.RANGES), for the volume legend. */
	private static final Map<String, String> VOLUME_STEP = Map.of("1d", "per 5 min", "1w", "per hour",
		"1m", "per 6 hours", "1y", "per day");

	/** Size beside the GE window; on the chat box the chart takes the chat box's size. */
	private static final int HEIGHT = 190;
	private static final int WIDTH = 380;
	/** Smaller than this and the chart is unreadable: use the spot beside the GE instead. */
	private static final int MIN_WIDTH = 250;
	private static final int MIN_HEIGHT = 100;
	/** The chat box's message area in the game's standard layout, above its row of tabs. */
	private static final int CHAT_AREA_HEIGHT = 142;
	private static final int GAP = 6;
	private static final int PAD = 6;
	private static final int AXIS = 44;
	private static final Color BG = new Color(28, 28, 28, 230);
	private static final Color FRAME = new Color(90, 90, 90);
	private static final Color GRID = new Color(255, 255, 255, 25);
	private static final Color TEXT = new Color(220, 220, 220);
	private static final Color DIM = new Color(150, 150, 150);
	private static final Color HIGH = new Color(0x4FD16A);
	private static final Color LOW = new Color(0xFF5A52);
	private static final Color OFFER = new Color(0x3EC6FF);
	private static final Color CARD_BUY = new Color(0xFFD84A);
	private static final Color CARD_SELL = new Color(0xFF981F);
	private static final Color BUTTON = new Color(60, 60, 60);
	private static final Color BUTTON_ON = new Color(0xFF981F);
	private static final Stroke LINE = new BasicStroke(1.5f);
	private static final Stroke DASH = new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
		10f, new float[]{5f, 4f}, 0f);

	private final Client client;
	private final FlipSidebarPlugin plugin;
	private final FlipSidebarConfig config;
	/** Where the range buttons were last drawn — read by the mouse listener (another thread). */
	private volatile Rectangle[] buttons = new Rectangle[0];
	/** Where the Price / Volume buttons were last drawn (same). */
	private volatile Rectangle[] modes = new Rectangle[0];
	/** The chat box's message area the last time it was open — the chart's spot when it is closed. */
	private Rectangle lastChat;

	private final MouseAdapter mouse = new MouseAdapter()
	{
		@Override
		public MouseEvent mousePressed(MouseEvent e)
		{
			Rectangle[] b = buttons;
			for (int i = 0; i < b.length; i++)
			{
				if (b[i] != null && b[i].contains(e.getPoint()))
				{
					plugin.setChartRange(RANGES[i]);
					e.consume();      // a click on the chart's button must not also click the game
					return e;
				}
			}
			Rectangle[] m = modes;
			for (int i = 0; i < m.length; i++)
			{
				if (m[i] != null && m[i].contains(e.getPoint()))
				{
					plugin.setChartVolume(i == 1);
					e.consume();
					return e;
				}
			}
			return e;
		}
	};

	@Inject
	FlipSidebarChartOverlay(Client client, FlipSidebarPlugin plugin, FlipSidebarConfig config)
	{
		super(plugin);
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	MouseAdapter mouse()
	{
		return mouse;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		// prices come only from Live prices: with it off there is no chart at all
		int item = config.priceChart() && config.livePrices() ? plugin.setupItem() : -1;
		Rectangle ge = item > 0 ? plugin.geBounds() : null;
		Rectangle area = ge == null ? null : place(ge);
		if (area == null)
		{
			buttons = new Rectangle[0];
			modes = new Rectangle[0];
			return null;
		}
		int x = area.x;
		int w = area.width;
		int y = area.y;
		int height = area.height;

		g.setColor(BG);
		g.fillRect(x, y, w, height);
		g.setColor(FRAME);
		g.drawRect(x, y, w, height);
		Font small = FontManager.getRunescapeSmallFont();
		g.setFont(small);
		FontMetrics fm = g.getFontMetrics();

		// range buttons, top-left
		String range = plugin.getChartRange();
		Rectangle[] b = new Rectangle[RANGES.length];
		int bx = x + PAD;
		for (int i = 0; i < RANGES.length; i++)
		{
			b[i] = new Rectangle(bx, y + PAD, 24, 14);
			g.setColor(RANGES[i].equals(range) ? BUTTON_ON : BUTTON);
			g.fill(b[i]);
			g.setColor(RANGES[i].equals(range) ? Color.BLACK : TEXT);
			g.drawString(RANGES[i], bx + (24 - fm.stringWidth(RANGES[i])) / 2, y + PAD + 11);
			bx += 27;
		}
		buttons = b;
		// Price / Volume (user, 2026-10-31, of this chart: "i want it here too")
		boolean volume = plugin.isChartVolume();
		String[] modeNames = {"Price", "Volume"};
		Rectangle[] m = new Rectangle[2];
		bx += 8;
		for (int i = 0; i < 2; i++)
		{
			boolean on = (i == 1) == volume;
			int bw = fm.stringWidth(modeNames[i]) + 10;
			m[i] = new Rectangle(bx, y + PAD, bw, 14);
			g.setColor(on ? BUTTON_ON : BUTTON);
			g.fill(m[i]);
			g.setColor(on ? Color.BLACK : TEXT);
			g.drawString(modeNames[i], bx + 5, y + PAD + 11);
			bx += bw + 3;
		}
		modes = m;

		PriceHistory h = plugin.getHistory();
		if (h == null || h.itemId != item || !range.equals(h.range) || h.points == null || h.points.length < 2)
		{
			g.setColor(DIM);
			g.drawString(plugin.historyWaitedLong() ? "No prices yet - the OSRS Wiki has not answered" : "Loading prices...",
				bx + 8, y + PAD + 11);
			return null;
		}

		if (volume)
		{
			drawVolume(g, h, x, y, w, height, fm);
			return null;
		}

		// latest insta-buy / insta-sell with their age, top-right
		long now = System.currentTimeMillis() / 1000;
		if (h.latest != null)
		{
			String hi = "Insta-buy " + fmt(h.latest.high) + " | " + ago(now - h.latest.highTime);
			String lo = "Insta-sell " + fmt(h.latest.low) + " | " + ago(now - h.latest.lowTime);
			g.setColor(HIGH);
			g.drawString(hi, x + w - PAD - fm.stringWidth(hi), y + PAD + 9);
			g.setColor(LOW);
			g.drawString(lo, x + w - PAD - fm.stringWidth(lo), y + PAD + 20);
		}

		// scale: the history, plus your offer and the card's prices when they are near it
		long min = Long.MAX_VALUE;
		long max = Long.MIN_VALUE;
		for (long[] p : h.points)
		{
			for (int k = 1; k <= 2; k++)
			{
				if (p.length > k && p[k] > 0)
				{
					min = Math.min(min, p[k]);
					max = Math.max(max, p[k]);
				}
			}
		}
		if (min == Long.MAX_VALUE)
		{
			g.setColor(DIM);
			g.drawString("No trades in this range", x + PAD, y + 40);
			return null;
		}
		long offer = client.getVarpLongValue(VarPlayerID.GE_NEWOFFER_PRICE);
		Suggestion s = plugin.offerSuggestion();         // the card's, or the user's own pick's
		boolean cardItem = s != null && s.itemId == item;
		long[] extras = {offer, cardItem ? s.price : 0, cardItem ? s.sellPrice : 0};
		for (long v : extras)
		{
			if (v > 0 && v >= min / 2 && v <= max * 2)
			{
				min = Math.min(min, v);
				max = Math.max(max, v);
			}
		}
		if (max == min)
		{
			max = min + 1;
		}
		long step = niceStep((max - min) / 4.0);
		min = Math.max(0, (min / step) * step);
		max = ((max + step - 1) / step) * step;
		if (max <= min)
		{
			max = min + step;
		}

		int cx = x + AXIS;
		int cy = y + PAD + 26;
		int cw = w - AXIS - PAD;
		int ch = height - (cy - y) - 16;
		long t0 = h.points[0][0];
		long t1 = h.points[h.points.length - 1][0];
		if (t1 <= t0)
		{
			t1 = t0 + 1;
		}

		// grid + price labels, one per round step
		for (long v = min; v <= max; v += step)
		{
			int gy = cy + ch - (int) (ch * (v - min) / (max - min));
			g.setColor(GRID);
			g.drawLine(cx, gy, cx + cw, gy);
			g.setColor(DIM);
			String lab = fmt(v);
			g.drawString(lab, cx - 4 - fm.stringWidth(lab), gy + 4);
		}

		g.setStroke(LINE);
		drawSeries(g, h.points, 1, HIGH, cx, cy, cw, ch, t0, t1, min, max);
		drawSeries(g, h.points, 2, LOW, cx, cy, cw, ch, t0, t1, min, max);

		g.setStroke(DASH);
		dashed(g, offer, OFFER, "your offer", false, cx, cy, cw, ch, min, max, fm);
		if (cardItem)
		{
			// the sell line says what the flip makes each after tax (user, 2026-10-31: "in the graphs with
			// buy and sell it should be saying expected profit")
			String each = s.quantity > 0 && s.profit != 0 ? String.format(" (%+,d each)", s.profit / s.quantity) : "";
			boolean sell = "SELL".equals(s.action);
			dashed(g, s.price, CARD_BUY, sell ? "sell at" + each : "buy at", true, cx, cy, cw, ch, min, max, fm);
			if (!sell)
			{
				dashed(g, s.sellPrice, CARD_SELL, "sell at" + each, true, cx, cy, cw, ch, min, max, fm);
			}
		}
		g.setStroke(new BasicStroke(1f));

		// time labels: start and end of the range
		SimpleDateFormat f = new SimpleDateFormat("1d".equals(h.range) ? "HH:mm" : "MMM d");
		g.setColor(DIM);
		g.drawString(f.format(new Date(t0 * 1000)), cx, cy + ch + 12);
		String end = f.format(new Date(t1 * 1000));
		g.drawString(end, cx + cw - fm.stringWidth(end), cy + ch + 12);
		return null;
	}

	/**
	 * Where the chart goes — the user's design, 2026-10-31: *"The graph should just be on the chat
	 * box when theres nothing to type and you have an item up. When u click something that makes
	 * it so u have to type like quanity or price then it disappears and comes back after."*
	 * So: ON the chat box's message area while no prompt is open, and nothing at all while one is
	 * (the GE's quantity and price boxes, the item search). With the chat box CLOSED, still there:
	 * *"if the chat box is closed id want it to still display in that location"* — just above the
	 * chat tabs at its last open size (or its standard size if not opened since login). Beside the GE window only if
	 * neither can be found — and then never over the flip box (in-game it hid the 1w / 1m buttons).
	 */
	private Rectangle place(Rectangle ge)
	{
		Widget prompt = client.getWidget(InterfaceID.Chatbox.MES_LAYER);
		if (prompt != null && !prompt.isHidden())
		{
			return null;              // something is being typed: out of the way until it closes
		}
		int canvasW = client.getCanvasWidth();
		int canvasH = client.getCanvasHeight();
		Widget chat = client.getWidget(InterfaceID.Chatbox.CHATAREA);
		Rectangle cb = chat == null || chat.isHidden() ? null : chat.getBounds();
		if (fits(cb, canvasW, canvasH))
		{
			lastChat = new Rectangle(cb);
			return cb;
		}
		// the chat box is closed: where it opens — just above its tabs, which stay on screen, at its
		// last open size (so a resized window still puts it in the right place)
		Widget tabs = client.getWidget(InterfaceID.Chatbox.CONTROLS);
		Rectangle tb = tabs == null || tabs.isHidden() ? null : tabs.getBounds();
		if (tb != null)
		{
			int h = lastChat != null ? lastChat.height : CHAT_AREA_HEIGHT;
			int w = lastChat != null ? lastChat.width : tb.width;
			Rectangle above = new Rectangle(tb.x, tb.y - h, w, h);
			if (fits(above, canvasW, canvasH))
			{
				return above;
			}
		}
		if (fits(lastChat, canvasW, canvasH))
		{
			return lastChat;
		}
		Rectangle box = plugin.boxBounds();
		List<Rectangle> spots = new ArrayList<>();
		spots.add(new Rectangle(ge.x + ge.width + GAP, ge.y, WIDTH, HEIGHT));
		if (box != null)
		{
			spots.add(new Rectangle(ge.x + ge.width + GAP, box.y + box.height + GAP, WIDTH, HEIGHT));
		}
		spots.add(new Rectangle(ge.x - GAP - WIDTH, ge.y, WIDTH, HEIGHT));
		spots.add(new Rectangle(ge.x, ge.y + ge.height + GAP, ge.width, HEIGHT));
		Rectangle fallback = null;
		for (Rectangle r : spots)
		{
			if (r.x < 0 || r.y < 0 || r.x + r.width > canvasW || r.y + r.height > canvasH)
			{
				continue;
			}
			if (box == null || !r.intersects(box))
			{
				return r;
			}
			if (fallback == null)
			{
				fallback = r;
			}
		}
		return fallback;
	}

	/** Big enough to read, and on the canvas. */
	private static boolean fits(Rectangle r, int canvasW, int canvasH)
	{
		return r != null && r.width >= MIN_WIDTH && r.height >= MIN_HEIGHT && r.x >= 0 && r.y >= 0
			&& r.x + r.width <= canvasW && r.y + r.height <= canvasH;
	}

	private static void drawSeries(Graphics2D g, long[][] pts, int k, Color c, int cx, int cy, int cw, int ch,
		long t0, long t1, long min, long max)
	{
		Path2D.Double path = new Path2D.Double();
		boolean pen = false;
		for (long[] p : pts)
		{
			if (p.length <= k || p[k] <= 0)
			{
				continue;           // no trade in that bucket: bridge the gap
			}
			double px = cx + (double) cw * (p[0] - t0) / (t1 - t0);
			double py = cy + ch - (double) ch * (p[k] - min) / (max - min);
			if (pen)
			{
				path.lineTo(px, py);
			}
			else
			{
				path.moveTo(px, py);
				pen = true;
			}
		}
		g.setColor(c);
		g.draw(path);
	}

	private static void dashed(Graphics2D g, long v, Color c, String label, boolean right, int cx, int cy,
		int cw, int ch, long min, long max, FontMetrics fm)
	{
		if (v <= 0 || v < min || v > max)
		{
			return;
		}
		int py = cy + ch - (int) (ch * (v - min) / (max - min));
		g.setColor(c);
		g.drawLine(cx, py, cx + cw, py);
		g.drawString(label, right ? cx + cw - 3 - fm.stringWidth(label) : cx + 3, py - 2);
	}

	/** Volume view: a bar per step, insta-sold (red) under insta-bought (green), a count axis. */
	private static void drawVolume(Graphics2D g, PriceHistory h, int x, int y, int w, int height, FontMetrics fm)
	{
		long most = 0;
		for (long[] p : h.points)
		{
			most = Math.max(most, at(p, 3) + at(p, 4));
		}
		String per = VOLUME_STEP.getOrDefault(h.range, "");
		g.setColor(HIGH);
		String bought = "Insta-bought " + per;
		g.drawString(bought, x + w - PAD - fm.stringWidth(bought), y + PAD + 9);
		g.setColor(LOW);
		String sold = "Insta-sold " + per;
		g.drawString(sold, x + w - PAD - fm.stringWidth(sold), y + PAD + 20);
		if (most <= 0)
		{
			g.setColor(DIM);
			g.drawString("No trades in this range", x + PAD, y + 40);
			return;
		}
		long step = niceStep(most / 4.0);
		long max = ((most + step - 1) / step) * step;
		int cx = x + AXIS;
		int cy = y + PAD + 26;
		int cw = w - AXIS - PAD;
		int ch = height - (cy - y) - 16;
		long t0 = h.points[0][0];
		long t1 = Math.max(t0 + 1, h.points[h.points.length - 1][0]);
		for (long v = 0; v <= max; v += step)
		{
			int gy = cy + ch - (int) (ch * v / max);
			g.setColor(GRID);
			g.drawLine(cx, gy, cx + cw, gy);
			g.setColor(DIM);
			String lab = v >= 10_000 ? (v / 1000) + "k" : fmt(v);      // "80,000" was cut off at the left edge
			g.drawString(lab, cx - 4 - fm.stringWidth(lab), gy + 4);
		}
		double slot = (double) cw / h.points.length;
		int bw = Math.max(1, (int) slot - (slot >= 3 ? 1 : 0));
		for (long[] p : h.points)
		{
			int px = cx + (int) ((double) cw * (p[0] - t0) / (t1 - t0)) - bw / 2;
			int soldH = (int) (ch * at(p, 4) / max);
			int boughtH = (int) (ch * (at(p, 3) + at(p, 4)) / max) - soldH;
			g.setColor(LOW);
			g.fillRect(px, cy + ch - soldH, bw, soldH);
			g.setColor(HIGH);
			g.fillRect(px, cy + ch - soldH - boughtH, bw, boughtH);
		}
		SimpleDateFormat f = new SimpleDateFormat("1d".equals(h.range) ? "HH:mm" : "MMM d");
		g.setColor(DIM);
		g.drawString(f.format(new Date(t0 * 1000)), cx, cy + ch + 12);
		String end = f.format(new Date(t1 * 1000));
		g.drawString(end, cx + cw - fm.stringWidth(end), cy + ch + 12);
	}

	/** A point's field {@code k}, 0 when it has none (volumes are fields 3 and 4). */
	private static long at(long[] p, int k)
	{
		return p.length > k ? p[k] : 0;
	}

	/** A round step (1, 2 or 5 × a power of ten) close to {@code raw}. */
	private static long niceStep(double raw)
	{
		if (raw <= 1)
		{
			return 1;
		}
		double mag = Math.pow(10, Math.floor(Math.log10(raw)));
		double r = raw / mag;
		double nice = r <= 1 ? 1 : r <= 2 ? 2 : r <= 5 ? 5 : 10;
		return Math.max(1, (long) (nice * mag));
	}

	static String fmt(long v)
	{
		if (v >= 10_000_000)
		{
			return String.format("%.1fm", v / 1_000_000.0);
		}
		if (v >= 100_000)
		{
			return String.format("%dk", v / 1000);
		}
		return String.format("%,d", v);
	}

	private static String ago(long s)
	{
		if (s < 0 || s > 10L * 365 * 86400)
		{
			return "?";
		}
		return s < 60 ? s + "s ago" : s < 3600 ? (s / 60) + "m ago" : (s / 3600) + "h ago";
	}
}
