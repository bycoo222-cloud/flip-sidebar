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

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import javax.swing.ButtonModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.AbstractBorder;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;

/**
 * ⭐ THE PANEL'S LOOK, drawn (user, 2026-11-04: themes "kinda basic looking" → "i want to get both of those done before
 * release"): rounded cards with a status stripe, headers with an accent line, themed buttons, and the top card —
 * session profit, gp an hour, a profit line — with each Look's texture (lava, glow, bevelled stone). Only drawing: the
 * panel's logic, data and advice are untouched.
 */
final class FlipUi
{
	private FlipUi()
	{
	}

	static void smooth(Graphics2D g)
	{
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
	}

	/** A card: rounded in the Look's colour (its background), bevelled stone in Old School. */
	static class Card extends JPanel
	{
		Card()
		{
			setOpaque(false);
		}

		Card(LayoutManager layout)
		{
			super(layout);
			setOpaque(false);
		}

		@Override
		protected void paintComponent(Graphics g0)
		{
			Graphics2D g = (Graphics2D) g0.create();
			smooth(g);
			paintCard(g, FlipTheme.now, getBackground(), 0, 0, getWidth(), getHeight());
			g.dispose();
		}
	}

	/** A card's shape and colour, in the Look. */
	static void paintCard(Graphics2D g, FlipTheme t, Color fill, int x, int y, int w, int h)
	{
		if (t.bevel)
		{
			g.setColor(fill);
			g.fillRect(x, y, w, h);
			g.setColor(t.bevelDark);
			g.drawRect(x, y, w - 1, h - 1);
			g.setColor(t.bevelLight);
			g.drawLine(x + 1, y + 1, x + w - 2, y + 1);
			g.drawLine(x + 1, y + 1, x + 1, y + h - 2);
			return;
		}
		RoundRectangle2D r = new RoundRectangle2D.Double(x, y, w - 1, h - 1, t.radius, t.radius);
		g.setColor(fill);
		g.fill(r);
		if (t.lava)
		{
			// embers under the stone: a glow along the lower edge
			g.setPaint(new GradientPaint(x, y + h - 7, new Color(0xFF, 0x50, 0x00, 0), x, y + h, new Color(0xFF, 0x60, 0x10, 80)));
			g.fill(new RoundRectangle2D.Double(x + 1, y + h - 8, w - 3, 7, t.radius, t.radius));
		}
		if (t.edge != null)
		{
			g.setColor(t.edge);
			g.draw(new RoundRectangle2D.Double(x + 0.5, y + 0.5, w - 2, h - 2, t.radius, t.radius));
		}
	}

	/** The coloured status bar at a card's left: green profit, red loss, amber when it needs you. */
	static final class Stripe extends AbstractBorder
	{
		private final Color color;
		private final Insets in;

		Stripe(Color color, int top, int left, int bottom, int right)
		{
			this.color = color;
			this.in = new Insets(top, left + 4, bottom, right);
		}

		@Override
		public Insets getBorderInsets(Component c)
		{
			return new Insets(in.top, in.left, in.bottom, in.right);
		}

		@Override
		public Insets getBorderInsets(Component c, Insets insets)
		{
			insets.set(in.top, in.left, in.bottom, in.right);
			return insets;
		}

		@Override
		public void paintBorder(Component c, Graphics g0, int x, int y, int w, int h)
		{
			if (color == null)
			{
				return;
			}
			Graphics2D g = (Graphics2D) g0.create();
			smooth(g);
			g.setColor(color);
			g.fill(new RoundRectangle2D.Double(x + 3, y + 5, 3, Math.max(4, h - 10), 3, 3));
			g.dispose();
		}
	}

	/** A card's padding with a status stripe ({@code color} null = no stripe). */
	static Border stripe(Color color, int top, int left, int bottom, int right)
	{
		return color == null ? new EmptyBorder(top, left + 4, bottom, right) : new Stripe(color, top, left, bottom, right);
	}

	/** A section header: its text, then a thin accent line to the panel's edge. */
	static final class Header extends JLabel
	{
		Header(String text, Color color, Font font)
		{
			super(text);
			setForeground(color);
			setFont(font);
			setBorder(new EmptyBorder(6, 0, 3, 0));
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}

		@Override
		protected void paintComponent(Graphics g0)
		{
			super.paintComponent(g0);
			String text = getText();
			if (text == null || text.startsWith("<html>"))
			{
				return;
			}
			Graphics2D g = (Graphics2D) g0.create();
			FontMetrics fm = getFontMetrics(getFont());
			Insets in = getInsets();
			int x = in.left + fm.stringWidth(text) + 8;
			int y = in.top + fm.getAscent() / 2 + 2;
			if (x < getWidth() - 6)
			{
				Color c = getForeground();
				g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 120));
				g.drawLine(x, y, getWidth() - 2, y);
			}
			g.dispose();
		}
	}

	/** A themed button: rounded (or the game's bevelled), lighter under the mouse. */
	static final class Button extends JButton
	{
		Button(String text)
		{
			super(text);
			FlipTheme t = FlipTheme.now;
			setContentAreaFilled(false);
			setFocusPainted(false);
			setBorderPainted(false);
			setOpaque(false);
			setRolloverEnabled(true);
			setForeground(t.bevel ? t.orange : t.text);
			setFont(t.family == null ? t.small() : t.small().deriveFont(Font.BOLD));
			setBorder(new EmptyBorder(4, 5, 4, 5));
		}

		@Override
		public void setMargin(Insets m)
		{
			setBorder(new EmptyBorder(Math.max(3, m.top + 1), Math.max(3, m.left + 1), Math.max(3, m.bottom + 1),
				Math.max(3, m.right + 1)));
		}

		@Override
		protected void paintComponent(Graphics g0)
		{
			FlipTheme t = FlipTheme.now;
			Graphics2D g = (Graphics2D) g0.create();
			smooth(g);
			ButtonModel m = getModel();
			int w = getWidth();
			int h = getHeight();
			if (t.bevel)
			{
				Color top = m.isPressed() ? new Color(0x3A, 0x30, 0x24) : m.isRollover() ? new Color(0x6A, 0x5A, 0x42)
					: new Color(0x5A, 0x4C, 0x38);
				g.setPaint(new GradientPaint(0, 0, top, 0, h, new Color(0x3A, 0x30, 0x24)));
				g.fillRect(0, 0, w, h);
				g.setColor(t.bevelLight.brighter());
				g.drawLine(0, 0, w - 1, 0);
				g.drawLine(0, 0, 0, h - 1);
				g.setColor(t.bevelDark);
				g.drawLine(0, h - 1, w - 1, h - 1);
				g.drawLine(w - 1, 0, w - 1, h - 1);
			}
			else
			{
				Color fill = !isEnabled() ? t.chip.darker() : m.isPressed() ? t.chip.darker() : m.isRollover() ? t.chip.brighter()
					: t.chip;
				g.setColor(fill);
				g.fill(new RoundRectangle2D.Double(0, 0, w - 1, h - 1, t.radius, t.radius));
				if (m.isRollover() && isEnabled())
				{
					Color o = t.orange;
					g.setColor(new Color(o.getRed(), o.getGreen(), o.getBlue(), 150));
					g.draw(new RoundRectangle2D.Double(0.5, 0.5, w - 2, h - 2, t.radius, t.radius));
				}
			}
			g.dispose();
			super.paintComponent(g0);
		}
	}

	/**
	 * ⭐ THE TOP CARD: the title, which mode (flipping / High alch), the session's profit and gp an hour, its line of
	 * flips, and the session's profit as a line — in the Look's texture. {@code corner} (the High alch button) sits at its
	 * top right.
	 */
	static final class SessionCard extends JPanel
	{
		private static final int HEIGHT = 148;   // 1.60.46: a 4th line (won · lost) — the 3rd was cut in a narrow sidebar
		private boolean on;
		private long profit;
		private long perHour;
		private String line = "";
		private long[] points = new long[0];
		private boolean alch;
		private ImageIcon alchIcon;
		// High alch mode (user, 2026-11-04: "switching to alch mode should show estimated profit and a graph for alching")
		private long alchMade;
		private long alchToCome;
		private long alchCasts;
		private long alchLeft;
		private String alchTime = "";
		private long[] alchPoints = new long[0];
		// Top graph = gp an hour (FlipSidebarPanel.rateLine): the session's gp an hour minute by minute, and the last 15
		// minutes' pace (Long.MIN_VALUE = not yet)
		private boolean rateGraph;
		private double cashPct = Double.NaN;
		private double cashGoal;
		private long[] rate = new long[0];
		private long[] pace = new long[0];

		SessionCard(JComponent corner)
		{
			super(new BorderLayout());
			setOpaque(false);
			JPanel top = new JPanel(new BorderLayout());
			top.setOpaque(false);
			top.setBorder(new EmptyBorder(6, 8, 0, 6));
			top.add(corner, BorderLayout.EAST);
			add(top, BorderLayout.NORTH);
			setPreferredSize(new Dimension(200, HEIGHT));
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, HEIGHT);
		}

		/** The session: whether there is one, its profit, gp an hour, its line, and the running profit after each flip. */
		void session(boolean on, long profit, long perHour, String line, long[] points)
		{
			this.on = on;
			this.profit = profit;
			this.perHour = perHour;
			this.line = line == null ? "" : line;
			this.points = points;
			repaint();
		}

		/** gp per hour as a % of the coins in flipping (NaN = not known), and the goal it is coloured against. */
		void cashRate(double pct, double goal)
		{
			this.cashPct = pct;
			this.cashGoal = goal;
			// the share explained on hover (1.60.77; user, 2026-11-07, of " · 0.42% of cash/hr": "this parts confusing to me")
			setToolTipText(Double.isFinite(pct) ? String.format("<html><b>%+.2f%% of your cash an hour</b>: your gp per hour as a "
				+ "share of the cash you flip with<br>(coins, coins in buy offers, and what is bought and not sold yet at what "
				+ "it cost).<br>The goal is %.2f%% - about 500k an hour on 150m - whatever your cash: green at or over it, "
				+ "orange close, red under.</html>", pct, goal) : null);
			repaint();
		}

		/** The Top graph setting: the line shows gp an hour ({@code on}) instead of the profit after each flip. */
		void rate(boolean on, long[] rate, long[] pace)
		{
			rateGraph = on;
			this.rate = rate;
			this.pace = pace;
			repaint();
		}

		/**
		 * High alch mode: what the casts made, what the items held and being bought still make, casts done and left, the
		 * time to cast them, and what the casts made over time (the line).
		 */
		void alchStats(long made, long toCome, long casts, long left, String time, long[] points)
		{
			alchMade = made;
			alchToCome = toCome;
			alchCasts = casts;
			alchLeft = left;
			alchTime = time == null ? "" : time;
			alchPoints = points;
			repaint();
		}

		/** The Equipment tab is open: the pill says EQUIPMENT (FlipSidebarPanel's mode tabs). */
		void gear(boolean on)
		{
			gearOn = on;
			repaint();
		}

		private boolean gearOn;

		void mode(boolean alch, ImageIcon icon)
		{
			this.alch = alch;
			this.alchIcon = icon;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g0)
		{
			FlipTheme t = FlipTheme.now;
			Graphics2D g = (Graphics2D) g0.create();
			smooth(g);
			int w = getWidth();
			int h = getHeight();
			Shape shape = new RoundRectangle2D.Double(0, 0, w - 1, h - 1, t.radius + 4, t.radius + 4);
			if (t.bevel)
			{
				paintCard(g, t, t.card, 0, 0, w, h);
			}
			else if (t.lava)
			{
				lava(g, shape, w, h);
			}
			else
			{
				g.setPaint(new GradientPaint(0, 0, t.heroTop, w, h, t.heroBottom));
				g.fill(shape);
			}
			if (t.motes != null)
			{
				motes(g, t.motes, 6, 6, w - 12, h - 12);
			}
			if (t.edge != null && !t.bevel)
			{
				g.setColor(t.edge);
				g.draw(shape);
			}
			// the title and the mode
			g.setFont(t.bold());
			text(g, t, "Flip Sidebar", 10, 22, t.orange);
			g.setFont(t.family == null ? t.small() : t.small().deriveFont(Font.BOLD));
			String mode = gearOn ? "EQUIPMENT" : alch ? "HIGH ALCH MODE" : "FLIPPING";
			FontMetrics fm = g.getFontMetrics();
			int iconW = !gearOn && alch && alchIcon != null ? alchIcon.getIconWidth() + 4 : 0;
			int pw = fm.stringWidth(mode) + 16 + iconW;
			g.setColor(gearOn ? new Color(0x26, 0x45, 0x6e) : alch ? t.alchBg : t.flipBg);
			if (t.bevel)
			{
				g.fillRect(10, 30, pw, 18);
			}
			else
			{
				g.fill(new RoundRectangle2D.Double(10, 30, pw, 18, 18, 18));
			}
			if (iconW > 0)
			{
				alchIcon.paintIcon(this, g, 16, 30 + (18 - alchIcon.getIconHeight()) / 2);
			}
			text(g, t, mode, 18 + iconW, 43, Color.WHITE);
			if (alch)
			{
				paintAlch(g, t, w);
				g.dispose();
				return;
			}
			if (!on)
			{
				g.setFont(t.small());
				text(g, t, "The session starts when you log in.", 10, 80, t.dim);
				g.dispose();
				return;
			}
			// the profit, big
			Font big = t.bold().deriveFont(t.family == null ? 30f : 26f);
			g.setFont(big);
			String p = (profit >= 0 ? "+" : "-") + FlipBrain.gp(Math.abs(profit));
			Color pc = profit >= 0 ? t.green : t.red;
			number(g, t, p, 10, 86, pc);
			int pwid = g.getFontMetrics().stringWidth(p);
			g.setFont(t.small());
			String rateText = FlipBrain.gp(perHour) + " gp per hour";
			text(g, t, rateText, 10, 102, t.text);
			if (Double.isFinite(cashPct))
			{
				// the share of the cash an hour, against the goal: green at it, amber over 60% of it, red under
				// a sign, not a dot in front: " · 0.42%" read as a minus in the small font (user, 2026-11-07); hover explains it
				String pctText = String.format("%+.2f%% of cash/hr", cashPct);
				int x = 10 + g.getFontMetrics().stringWidth(rateText) + 8;
				text(g, t, fit(g.getFontMetrics(), pctText, w - x - 10), x, 102,
					cashPct >= cashGoal ? t.green : cashPct >= cashGoal * 0.6 ? t.orange : t.red);
			}
			// the line under it: "ROI · time · flips", then "won · lost" (the panel splits them with a newline)
			String[] lines = line.split("\n", 2);
			Color lineColor = t.lava ? new Color(0xE8, 0xC8, 0xB0) : t.dim;
			text(g, t, fit(g.getFontMetrics(), lines[0], w - 20), 10, 118, lineColor);
			if (lines.length > 1)
			{
				text(g, t, fit(g.getFontMetrics(), lines[1], w - 20), 10, 134, lineColor);
			}
			// the session's profit as a line, right of the number
			int lx = Math.max(118, 10 + pwid + 16);
			int lw = w - lx - 10;
			int ly = 54;
			int lh = 36;   // ends above the gp per hour line (1.60.46)
			if (rateGraph)
			{
				paintRate(g, t, lx, ly, lw, lh, pc);
			}
			else if (points.length >= 2 && lw > 30)
			{
				long min = 0;
				long max = 0;
				for (long v : points)
				{
					min = Math.min(min, v);
					max = Math.max(max, v);
				}
				double span = Math.max(1, max - min);
				int zero = ly + lh - (int) Math.round(lh * (0 - min) / span);
				g.setColor(new Color(255, 255, 255, 40));
				g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{3f, 3f}, 0f));
				g.drawLine(lx, zero, lx + lw, zero);
				Path2D path = new Path2D.Double();
				for (int i = 0; i < points.length; i++)
				{
					double px = lx + (double) lw * i / (points.length - 1);
					double py = ly + lh - lh * (points[i] - min) / span;
					if (i == 0)
					{
						path.moveTo(px, py);
					}
					else
					{
						path.lineTo(px, py);
					}
				}
				g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g.setColor(t.glow != null ? t.glow : pc);
				g.draw(path);
				g.setStroke(new BasicStroke(1f));
				long last = points[points.length - 1];
				g.fillOval(lx + lw - 3, ly + lh - (int) Math.round(lh * (last - min) / span) - 3, 6, 6);
				g.setFont(t.small());
				String cap = points.length - 1 + " flips";
				// the caption ABOVE the line (1.60.46): under it, it ran into the gp per hour line
				text(g, t, cap, lx + lw - g.getFontMetrics().stringWidth(cap), ly - 3, new Color(255, 255, 255, 140));
			}
			g.dispose();
		}

		/**
		 * The Top graph "gp per hour" line in the box {@code lx, ly, lw, lh}: the session's gp per hour (thick, in the profit's
		 * colour) and the last 15 minutes' pace (thin, behind it) on one scale from 0 — when the thin line drops under the
		 * thick one, the hour number is falling; both flat = the session has levelled out.
		 */
		private void paintRate(Graphics2D g, FlipTheme t, int lx, int ly, int lw, int lh, Color pc)
		{
			if (lw <= 30)
			{
				return;
			}
			g.setFont(t.small());
			if (rate.length < 2)
			{
				// short enough for the card (2026-11-05: "gp an hour from 5 minutes in" ran off its right edge after a reset)
				FontMetrics pm = g.getFontMetrics();
				text(g, t, fit(pm, "gp/hr line", lw - 4), lx + 4, ly + 18, new Color(255, 255, 255, 120));
				text(g, t, fit(pm, "from 5 min in", lw - 4), lx + 4, ly + 32, new Color(255, 255, 255, 120));
				return;
			}
			long min = 0;
			long max = 1;
			for (int i = 0; i < rate.length; i++)
			{
				min = Math.min(min, rate[i]);
				max = Math.max(max, rate[i]);
				if (pace[i] != Long.MIN_VALUE)
				{
					min = Math.min(min, pace[i]);
					max = Math.max(max, pace[i]);
				}
			}
			double span = Math.max(1, max - min);
			if (min < 0)
			{
				int zero = ly + lh - (int) Math.round(lh * (0 - min) / span);
				g.setColor(new Color(255, 255, 255, 40));
				g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{3f, 3f}, 0f));
				g.drawLine(lx, zero, lx + lw, zero);
			}
			Path2D thin = new Path2D.Double();
			boolean started = false;
			Path2D thick = new Path2D.Double();
			for (int i = 0; i < rate.length; i++)
			{
				double px = lx + (double) lw * i / (rate.length - 1);
				double py = ly + lh - lh * (rate[i] - min) / span;
				if (i == 0)
				{
					thick.moveTo(px, py);
				}
				else
				{
					thick.lineTo(px, py);
				}
				if (pace[i] != Long.MIN_VALUE)
				{
					double qy = ly + lh - lh * (pace[i] - min) / span;
					if (started)
					{
						thin.lineTo(px, qy);
					}
					else
					{
						thin.moveTo(px, qy);
						started = true;
					}
				}
			}
			if (started)
			{
				g.setStroke(new BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g.setColor(new Color(255, 255, 255, 110));
				g.draw(thin);
			}
			g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.setColor(t.glow != null ? t.glow : pc);
			g.draw(thick);
			g.setStroke(new BasicStroke(1f));
			long last = rate[rate.length - 1];
			g.fillOval(lx + lw - 3, ly + lh - (int) Math.round(lh * (last - min) / span) - 3, 6, 6);
			String cap = started ? "gp/hr · thin: 15 min" : "gp per hour";
			FontMetrics fm = g.getFontMetrics();
			if (fm.stringWidth(cap) > lw)
			{
				cap = "gp/hr";
			}
			text(g, t, cap, lx + lw - fm.stringWidth(cap), ly - 3, new Color(255, 255, 255, 140));
		}

		/** The top card in High alch mode: the estimated profit, made / to come, casts, and the casts' profit as a line. */
		private void paintAlch(Graphics2D g, FlipTheme t, int w)
		{
			if (alchMade <= 0 && alchToCome <= 0 && alchLeft <= 0)
			{
				g.setFont(t.small());
				text(g, t, "Nothing to alch yet - the plan below", 10, 80, t.dim);
				text(g, t, "says what to buy for it.", 10, 94, t.dim);
				return;
			}
			Font big = t.bold().deriveFont(t.family == null ? 30f : 26f);
			g.setFont(big);
			String p = "+" + FlipBrain.gp(alchMade + alchToCome);
			number(g, t, p, 10, 86, t.green);
			int pwid = g.getFontMetrics().stringWidth(p);
			g.setFont(t.small());
			text(g, t, fit(g.getFontMetrics(), FlipBrain.gp(alchMade) + " made · " + FlipBrain.gp(alchToCome) + " to come", w - 20),
				10, 102, t.text);
			String casts = String.format("%,d cast · %,d left%s", alchCasts, alchLeft, alchLeft > 0 ? " · ~" + alchTime : "");
			text(g, t, fit(g.getFontMetrics(), casts, w - 20), 10, 118, t.lava ? new Color(0xE8, 0xC8, 0xB0) : t.dim);
			int lx = Math.max(118, 10 + pwid + 16);
			int lw = w - lx - 10;
			int ly = 54;
			int lh = 36;   // ends above the gp per hour line (1.60.46)
			if (lw <= 30)
			{
				return;
			}
			if (alchPoints.length < 2)
			{
				text(g, t, "the line starts", lx + 4, ly + 18, new Color(255, 255, 255, 120));
				text(g, t, "with your casts", lx + 4, ly + 32, new Color(255, 255, 255, 120));
				return;
			}
			long max = 1;
			for (long v : alchPoints)
			{
				max = Math.max(max, v);
			}
			Path2D path = new Path2D.Double();
			for (int i = 0; i < alchPoints.length; i++)
			{
				double px = lx + (double) lw * i / (alchPoints.length - 1);
				double py = ly + lh - (double) lh * alchPoints[i] / max;
				if (i == 0)
				{
					path.moveTo(px, py);
				}
				else
				{
					path.lineTo(px, py);
				}
			}
			g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.setColor(t.amber);
			g.draw(path);
			g.setStroke(new BasicStroke(1f));
			long last = alchPoints[alchPoints.length - 1];
			g.fillOval(lx + lw - 3, ly + lh - (int) Math.round((double) lh * last / max) - 3, 6, 6);
			String cap = "made alching";
			text(g, t, cap, lx + lw - g.getFontMetrics().stringWidth(cap), ly - 3, new Color(255, 255, 255, 140));
		}
	}

	// ---- drawing helpers ----

	/** Text with the game's black shadow in Old School. */
	static void text(Graphics2D g, FlipTheme t, String s, int x, int y, Color c)
	{
		if (t.shadow)
		{
			g.setColor(Color.BLACK);
			g.drawString(s, x + 1, y + 1);
		}
		g.setColor(c);
		g.drawString(s, x, y);
	}

	/** A big number, with a soft glow in the glowing Looks. */
	static void number(Graphics2D g, FlipTheme t, String s, int x, int y, Color c)
	{
		if (t.glow != null)
		{
			Composite was = g.getComposite();
			g.setColor(t.glow);
			for (int r = 2; r >= 1; r--)
			{
				g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.05f));
				for (int dx = -r; dx <= r; dx++)
				{
					for (int dy = -r; dy <= r; dy++)
					{
						if (dx * dx + dy * dy <= r * r)
						{
							g.drawString(s, x + dx, y + dy);
						}
					}
				}
			}
			g.setComposite(was);
		}
		text(g, t, s, x, y, c);
	}

	static String fit(FontMetrics fm, String s, int width)
	{
		if (s == null || fm.stringWidth(s) <= width)
		{
			return s == null ? "" : s;
		}
		int n = s.length();
		while (n > 0 && fm.stringWidth(s.substring(0, n) + "...") > width)
		{
			n--;
		}
		return s.substring(0, n).trim() + "...";
	}

	/** Dots of light (embers, motes) at fixed places so they do not flicker. */
	static void motes(Graphics2D g, Color c, int x, int y, int w, int h)
	{
		Composite was = g.getComposite();
		long seed = 11;
		for (int i = 0; i < 18; i++)
		{
			seed = seed * 6364136223846793005L + 1442695040888963407L;
			int px = x + (int) (((seed >>> 33) % 1000) * w / 1000);
			int py = y + (int) (((seed >>> 13) % 1000) * h / 1000);
			int r = 1 + (int) ((seed >>> 50) % 2);
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.25f + ((seed >>> 40) % 50) / 100f));
			g.setColor(c);
			g.fillOval(px, py, r * 2, r * 2);
		}
		g.setComposite(was);
	}

	/** Molten rock: basalt, glowing cracks in the corner, a lava pool with a wavy surface along the bottom (Infernal). */
	static void lava(Graphics2D g, Shape shape, int w, int h)
	{
		Shape clip = g.getClip();
		g.clip(shape);
		g.setPaint(new GradientPaint(0, 0, new Color(0x2A, 0x14, 0x0C), w, h, new Color(0x14, 0x09, 0x06)));
		g.fillRect(0, 0, w, h);
		int top = h - 9;                       // a shallow pool: the text above it stays readable
		Path2D pool = new Path2D.Double();
		pool.moveTo(0, h);
		pool.lineTo(0, top + 6);
		for (int x = 0; x <= w; x += 4)
		{
			pool.lineTo(x, top + 2 * Math.sin(x / 13.0) + 1 * Math.sin(x / 5.0 + 1));
		}
		pool.lineTo(w, h);
		pool.closePath();
		g.setPaint(new GradientPaint(0, top - 4, new Color(0xC8, 0x2A, 0x00), 0, h, new Color(0xFF, 0xB0, 0x20)));
		g.fill(pool);
		g.setPaint(new GradientPaint(0, top - 14, new Color(0xFF, 0x60, 0x00, 0), 0, top + 3, new Color(0xFF, 0x60, 0x00, 50)));
		g.fillRect(0, top - 14, w, 17);
		// in the top right, clear of the High alch button
		int[][] cracks = {{w - 96, 0, w - 88, 9, w - 93, 18}, {w - 58, 2, w - 63, 12, w - 56, 20}};
		for (int[] c : cracks)
		{
			Path2D p = new Path2D.Double();
			p.moveTo(c[0], c[1]);
			for (int i = 2; i + 1 < c.length; i += 2)
			{
				p.lineTo(c[i], c[i + 1]);
			}
			g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.setColor(new Color(0xFF, 0x50, 0x00, 40));
			g.draw(p);
			g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.setColor(new Color(0xE0, 0x6A, 0x20, 200));
			g.draw(p);
		}
		g.setStroke(new BasicStroke(1f));
		g.setClip(clip);
	}
}
