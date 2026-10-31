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

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * The side panel: the flip to do now (with Placed / Skip), your coins, your open offers with
 * what to do about each, and the next best flips. Laid out like the Flip Sidebar program.
 *
 * <p>⚠ NOTHING IS REBUILT WHILE IT IS ON SCREEN. Every row exists from the start and only its
 * text changes, or it is shown / hidden. The sidebar's list used to be rebuilt as a scan ran and
 * the user found it "really fustrating to look at" — so this panel never does that.</p>
 *
 * <p>Swing thread only: {@link #show} is called through SwingUtilities.invokeLater.</p>
 */
class FlipSidebarPanel extends PluginPanel
{
	private static final Color GREEN = new Color(0x4FD16A);
	private static final Color ORANGE = new Color(0xFF981F);
	private static final Color YELLOW = new Color(0xFFD84A);
	private static final Color RED = new Color(0xFF5A52);
	private static final Color AMBER = new Color(0xFFB000);
	private static final Color DIM = new Color(0x9A9A9A);
	private static final Color WHITE = Color.WHITE;
	private static final Color CARD = ColorScheme.DARKER_GRAY_COLOR;
	private static final Color HOVER = ColorScheme.DARKER_GRAY_HOVER_COLOR;
	/** Text width for wrapping — narrow enough for the card, whose border and padding are widest. */
	private static final int WRAP_PX = 150;
	private static final int OFFER_ROWS = 8;
	private static final int NEXT_ROWS = 6;
	/** Every flip TradeLog keeps; the newest HISTORY_SHOWN until "Show all" is clicked. */
	private static final int HISTORY_ROWS = TradeLog.FLIPS_KEPT;
	private static final int HISTORY_SHOWN = 25;
	private static final long DAY_MS = 24L * 3600 * 1000;
	private static final int HELD_ROWS = 12;
	static final String LIVE_WARNING =
		"This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers";

	private final FlipBrain brain;
	private final ConfigManager configManager;
	private final FlipSidebarConfig config;
	private final ItemManager itemManager;
	private final FlipSidebarPlugin plugin;

	private final JLabel status = label("", DIM, small());
	private final JPanel offBox = box();
	private final JComboBox<RiskLevel> risk = new JComboBox<>(RiskLevel.values());
	private final JComboBox<AdjustWindow> window = new JComboBox<>(AdjustWindow.values());
	private boolean syncing;

	private final JPanel cardBox = box();
	private final JLabel cardIcon = new JLabel();
	private final JLabel cardAction = label("", WHITE, FontManager.getRunescapeBoldFont());
	private final JLabel cardName = label("", WHITE, FontManager.getRunescapeBoldFont());
	private final JLabel limit = value(WHITE);
	private final JLabel qty = value(YELLOW);
	private final JLabel price = value(YELLOW);
	private final JLabel sellAt = value(WHITE);
	private final JLabel profit = value(GREEN);
	private final JLabel fill = value(WHITE);
	/** Rows, not a grid: a hidden row must not leave a gap (a GridLayout keeps its cell). */
	private final JPanel sellRow = row("Then sell at", sellAt);
	/** The GE buy limit left for a buy — "9,000 / 18,000 · 2h05". */
	private final JPanel limitRow = row("Limit left", limit);
	private final JPanel fillRow = row("Fills in", fill);
	private final JPanel grid = new JPanel();
	private final JLabel cardSub = label("", DIM, small());
	private final JLabel cardNote = label("", ORANGE, small());
	private final JLabel cardHint = label("", DIM, small());
	private final JPanel buttons = new JPanel(new GridLayout(1, 2, 4, 0));
	private final JButton placedButton = new JButton("Placed");
	private final JButton skipButton = new JButton("Skip");

	private final JLabel cash = label("", WHITE, normal());
	private final JLabel cashSplit = label("", DIM, small());

	private final JLabel offersEmpty = label("", DIM, small());
	private final OfferRow[] offerRows = new OfferRow[OFFER_ROWS];
	/**
	 * Flips that finished, profit or LOSS — open; the next flips folded away until clicked. User,
	 * 2026-10-31: "id rather see a history then next flps open honestly. i wanna see when a flip is
	 * a loss."
	 */
	private final JLabel historySummary = label("", DIM, small());
	private final JLabel historyTotal = label("", DIM, small());
	private final JLabel historyEmpty = label("", DIM, small());
	private final HistoryRow[] historyRows = new HistoryRow[HISTORY_ROWS];
	/** "Show all 87 flips" / "Show fewer" — user, 2026-10-31: "history tab isnt very long". */
	private final JLabel historyMore = label("", ORANGE, small());
	private boolean historyAll;
	private final JLabel nextTitle = header("Next flips");
	private final JLabel nextEmpty = label("", DIM, small());
	private final NextRow[] nextRows = new NextRow[NEXT_ROWS];
	private boolean nextOpen;

	private final JButton rescanButton = new JButton("Scan now");
	/** No new buys while paused; sells and Wait / Collect still guide. */
	private final JButton pauseButton = new JButton("Pause");
	private final JLabel blockedLabel = label("", ORANGE, small());

	/** Bought and not sold, at today's price (Copilot's portfolio). */
	private final JLabel holdTotal = label("", DIM, small());
	private final HeldRow[] heldRows = new HeldRow[HELD_ROWS];
	/** Since the session began: profit, gp/hr, ROI, won / lost — Copilot's session stats. */
	private final JLabel sessionProfit = label("", WHITE, FontManager.getRunescapeBoldFont());
	private final JLabel sessionLine = label("", DIM, small());
	private final JLabel newSession = label("new session", ORANGE, small());
	private final JLabel exportLink = label("Export CSV", ORANGE, small());
	private final JLabel exportResult = label("", DIM, small());
	private final Timer clock;
	private FlipBrain.View shown;
	private int iconItem = -1;

	FlipSidebarPanel(FlipBrain brain, ConfigManager configManager, FlipSidebarConfig config, ItemManager itemManager,
		FlipSidebarPlugin plugin)
	{
		this.brain = brain;
		this.configManager = configManager;
		this.config = config;
		this.itemManager = itemManager;
		this.plugin = plugin;

		setLayout(new BorderLayout());
		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(content, BorderLayout.NORTH);

		JLabel title = label("Flip Sidebar", ORANGE, FontManager.getRunescapeBoldFont());
		put(content, title, 0);
		put(content, status, 2);

		// live prices are off: say what turning them on means, with RuneLite's own warning
		offBox.add(label(wrap("Live prices are off. Turn them on and this panel finds flips by itself - "
			+ "no other program needed. Prices come from the OSRS Wiki (prices.runescape.wiki)."), WHITE, small()));
		offBox.add(Box.createVerticalStrut(6));
		JButton turnOn = new JButton("Turn on live prices...");
		turnOn.setAlignmentX(Component.LEFT_ALIGNMENT);
		turnOn.addActionListener(e -> askLivePrices());
		offBox.add(turnOn);
		put(content, offBox, 8);

		// the two settings that change the picks most, here instead of only in the wrench menu
		JPanel settings = new JPanel(new GridLayout(2, 2, 4, 2));
		settings.setBackground(ColorScheme.DARK_GRAY_COLOR);
		settings.add(label("Risk", DIM, small()));
		settings.add(label("Re-price every", DIM, small()));
		settings.add(risk);
		settings.add(window);
		risk.setToolTipText("Low: only steady items. High: allows thinner, jumpier ones");
		window.setToolTipText("How often you come back to adjust offers - sets how much of an hour's trading one offer takes");
		risk.addActionListener(e -> setConfig("riskLevel", ((RiskLevel) risk.getSelectedItem()).name()));
		window.addActionListener(e -> setConfig("adjustWindow", ((AdjustWindow) window.getSelectedItem()).name()));
		put(content, settings, 8);

		// the card
		JPanel top = new JPanel(new BorderLayout(6, 0));
		top.setOpaque(false);
		cardIcon.setPreferredSize(new Dimension(36, 32));
		top.add(cardIcon, BorderLayout.WEST);
		JPanel names = new JPanel(new GridLayout(2, 1));
		names.setOpaque(false);
		names.add(cardAction);
		names.add(cardName);
		top.add(names, BorderLayout.CENTER);
		top.setAlignmentX(Component.LEFT_ALIGNMENT);
		cardBox.add(top);
		cardBox.add(Box.createVerticalStrut(6));
		grid.setLayout(new BoxLayout(grid, BoxLayout.Y_AXIS));
		grid.setOpaque(false);
		grid.setAlignmentX(Component.LEFT_ALIGNMENT);
		grid.add(limitRow);
		grid.add(row("Quantity", qty));
		grid.add(row("Price", price));
		grid.add(sellRow);
		grid.add(row("Profit", profit));
		grid.add(fillRow);
		cardBox.add(grid);
		cardBox.add(Box.createVerticalStrut(4));
		cardBox.add(cardSub);
		cardBox.add(cardNote);
		cardBox.add(cardHint);
		cardBox.add(Box.createVerticalStrut(6));
		buttons.setOpaque(false);
		buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
		placedButton.setToolTipText("You put this offer in - show the next thing to do");
		skipButton.setToolTipText("Not this one - show the next");
		placedButton.addActionListener(e -> brain.placed());
		skipButton.addActionListener(e -> brain.skip());
		buttons.add(placedButton);
		buttons.add(skipButton);
		cardBox.add(buttons);
		put(content, cardBox, 8);

		put(content, cash, 0);
		put(content, cashSplit, 10);

		put(content, header("Offers"), 2);
		put(content, offersEmpty, 2);
		for (int i = 0; i < OFFER_ROWS; i++)
		{
			offerRows[i] = new OfferRow();
			put(content, withGap(offerRows[i], 3), 0);
		}

		put(content, Box.createVerticalStrut(8), 0);
		put(content, header("Holding"), 2);
		put(content, holdTotal, 2);
		for (int i = 0; i < HELD_ROWS; i++)
		{
			heldRows[i] = new HeldRow();
			put(content, withGap(heldRows[i], 3), 0);
		}

		put(content, Box.createVerticalStrut(8), 0);
		put(content, header("Session"), 2);
		put(content, sessionProfit, 0);
		put(content, sessionLine, 0);
		link(newSession, "Start counting the session from now", plugin::newSession);
		put(content, newSession, 2);

		put(content, Box.createVerticalStrut(8), 0);
		put(content, header("History"), 2);
		put(content, historySummary, 0);
		put(content, historyTotal, 2);
		put(content, historyEmpty, 2);
		for (int i = 0; i < HISTORY_ROWS; i++)
		{
			historyRows[i] = new HistoryRow();
			put(content, withGap(historyRows[i], 3), 0);
		}
		historyMore.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		historyMore.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				historyAll = !historyAll;
				show(shown);
			}
		});
		put(content, historyMore, 2);
		link(exportLink, "Save every kept flip as a spreadsheet file (CSV)",
			() -> plugin.exportFlips(msg -> SwingUtilities.invokeLater(() -> exportResult.setText(wrap(msg)))));
		put(content, exportLink, 0);
		put(content, exportResult, 2);

		put(content, Box.createVerticalStrut(8), 0);
		nextTitle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		nextTitle.setToolTipText("Click to show or hide the next best flips");
		nextTitle.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				nextOpen = !nextOpen;
				show(shown);
			}
		});
		put(content, nextTitle, 2);
		put(content, nextEmpty, 2);
		for (int i = 0; i < NEXT_ROWS; i++)
		{
			nextRows[i] = new NextRow();
			put(content, withGap(nextRows[i], 3), 0);
		}

		rescanButton.setToolTipText("Look for flips again now instead of waiting for the next scan");
		rescanButton.addActionListener(e -> brain.rescan());
		pauseButton.setToolTipText("Stop suggesting new buys (sells and offers still guided) - press again to resume");
		pauseButton.addActionListener(e -> setConfig("paused", String.valueOf(!config.paused())));
		JPanel bottom = new JPanel(new GridLayout(1, 2, 4, 0));
		bottom.setOpaque(false);
		bottom.add(rescanButton);
		bottom.add(pauseButton);
		put(content, Box.createVerticalStrut(6), 0);
		put(content, bottom, 4);
		link(blockedLabel, "Let the plugin suggest these items again", () ->
			configManager.setConfiguration(FlipSidebarConfig.GROUP, "blockedItems", ""));
		put(content, blockedLabel, 0);

		// right-click the card: never suggest this item
		cardBox.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				cardMenu(e);
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				cardMenu(e);
			}
		});

		syncSettings();
		show(null);
		// the card's age line and the "scanned ... ago" text count up without a new view
		clock = new Timer(1000, e -> paintAges());
		clock.start();
	}

	/** Plugin shutDown: stop the age clock. */
	void stop()
	{
		clock.stop();
	}

	/** Risk / re-price changed in the wrench menu: match the boxes without writing back. */
	void syncSettings()
	{
		syncing = true;
		risk.setSelectedItem(config.riskLevel());
		window.setSelectedItem(config.adjustWindow());
		syncing = false;
	}

	void show(FlipBrain.View v)
	{
		shown = v;
		boolean live = v != null && v.live;
		status.setText(wrap(v == null ? "Starting..." : v.status));
		offBox.setVisible(!live);
		rescanButton.setVisible(live);
		rescanButton.setEnabled(live && !v.scanning && !v.paused);
		pauseButton.setVisible(live);
		pauseButton.setText(v != null && v.paused ? "Resume" : "Pause");
		blockedLabel.setVisible(v != null && v.blocked > 0);
		blockedLabel.setText(v == null ? "" : v.blocked + " item" + (v.blocked == 1 ? "" : "s") + " never suggested · clear");

		Suggestion s = live ? v.card : null;
		cardBox.setVisible(s != null);
		if (s != null)
		{
			paintCard(s, v);
		}

		if (v == null || !v.loggedIn)
		{
			cash.setText("Cash: -");
			cashSplit.setText("log in to see your coins");
		}
		else
		{
			long coins = v.inventoryCoins + Math.max(0, v.bankCoins);
			cash.setText("Cash: " + FlipBrain.gp(coins)
				+ (v.cashCap > 0 && v.cashCap < coins ? " · using " + FlipBrain.gp(v.cashCap) : ""));
			cashSplit.setText(wrap("inventory " + FlipBrain.gp(v.inventoryCoins) + " · bank "
				+ (v.bankCoins < 0 ? "not seen yet - open your bank once" : FlipBrain.gp(v.bankCoins))));
		}

		List<OfferAdvice> offers = v == null ? List.of() : v.offers;
		offersEmpty.setVisible(offers.isEmpty());
		offersEmpty.setText(v == null || !v.loggedIn ? "log in to see your offers" : "no open offers");
		for (int i = 0; i < OFFER_ROWS; i++)
		{
			offerRows[i].show(i < offers.size() ? offers.get(i) : null);
		}

		paintHolding(v);
		paintSession(v);
		List<GameSnapshot.Done> history = v == null ? List.of() : v.history;
		paintHistorySummary(history);
		historyEmpty.setVisible(history.isEmpty());
		historyEmpty.setText(v == null || !v.loggedIn ? "log in to see your flips"
			: "no finished flips yet - each sell shows here as it fills");
		int rows = historyAll ? history.size() : Math.min(HISTORY_SHOWN, history.size());
		for (int i = 0; i < HISTORY_ROWS; i++)
		{
			historyRows[i].show(i < rows && i < history.size() ? history.get(i) : null);
		}
		historyMore.setVisible(history.size() > HISTORY_SHOWN);
		historyMore.setText(historyAll ? "Show fewer" : "Show all " + history.size() + " flips");

		List<Pick> next = live ? v.next : List.of();
		nextTitle.setVisible(live);
		nextTitle.setText(nextOpen ? "Next flips - hide" : "Next flips (" + next.size() + ") - show");
		nextEmpty.setVisible(live && nextOpen && next.isEmpty());
		nextEmpty.setText(v != null && v.scanning ? "scanning..." : "nothing else worth doing right now");
		for (int i = 0; i < NEXT_ROWS; i++)
		{
			nextRows[i].show(nextOpen && i < next.size() ? next.get(i) : null);
		}
		paintAges();
		revalidate();
	}

	private void paintCard(Suggestion s, FlipBrain.View v)
	{
		boolean buy = "BUY".equals(s.action);
		boolean sell = "SELL".equals(s.action);
		boolean none = !buy && !sell;
		String label = buy ? "Buy" : sell ? "Sell" : "WAIT".equals(s.action) ? "Wait" : "COLLECT".equals(s.action) ? "Collect" : "";
		cardAction.setText(label);
		cardAction.setForeground(buy ? GREEN : ORANGE);
		cardAction.setVisible(!label.isEmpty());
		cardName.setText(wrap(none ? s.note : s.name));
		cardName.setForeground(none ? DIM : WHITE);
		if (none)
		{
			cardIcon.setIcon(null);
			iconItem = -1;
		}
		else if (s.itemId != iconItem && itemManager != null)     // null only in the off-screen preview
		{
			iconItem = s.itemId;
			itemManager.getImage(s.itemId).addTo(cardIcon);
		}
		cardIcon.setVisible(!none);
		grid.setVisible(!none);
		qty.setText(String.format("%,d", s.quantity));
		price.setText(String.format("%,d", s.price));
		sellAt.setText(String.format("%,d", s.sellPrice));
		sellRow.setVisible(buy);
		long[] lim = buy ? v.cardLimit : null;
		limitRow.setVisible(lim != null);
		if (lim != null)
		{
			limit.setText(String.format("%,d / %,d", lim[0], lim[1])
				+ (lim[2] > 0 ? " · " + FlipSidebarSlotOverlay.clock(lim[2] - System.currentTimeMillis()) : ""));
			limit.setForeground(lim[0] == 0 ? RED : WHITE);
		}
		profit.setText((s.profit >= 0 ? "+" : "") + FlipBrain.gp(s.profit));
		profit.setForeground(s.profit >= 0 ? GREEN : RED);
		fill.setText(s.fillMinutes < 0 ? "" : String.format("~%.0f min", Math.max(1, s.fillMinutes)));
		fillRow.setVisible(s.fillMinutes >= 0);
		cardSub.setText(wrap(v.cardSub));
		cardSub.setVisible(!v.cardSub.isEmpty());
		cardNote.setText(wrap(s.note == null || none ? "" : "⚠ " + s.note));
		cardNote.setVisible(!none && s.note != null && !s.note.isEmpty());
		buttons.setVisible(!none);
	}

	/** What is held: each item's value now, the total, and coins waiting in buy offers. */
	private void paintHolding(FlipBrain.View v)
	{
		List<FlipBrain.Held> held = v == null ? List.of() : v.held;
		long worth = 0;
		long profit = 0;
		for (FlipBrain.Held h : held)
		{
			if (h.market > 0)
			{
				worth += (h.market - FlipEngine.tax(h.market, h.itemId)) * h.qty;
				profit += h.profit;
			}
		}
		String buys = v != null && v.inBuys > 0 ? " · " + FlipBrain.gp(v.inBuys) + " in buy offers" : "";
		holdTotal.setText(wrap(held.isEmpty() ? "nothing bought and unsold" + buys
			: String.format("%d item%s · worth %s (%s%s)%s", held.size(), held.size() == 1 ? "" : "s",
				FlipBrain.gp(worth), profit >= 0 ? "+" : "", FlipBrain.gp(profit), buys)));
		for (int i = 0; i < HELD_ROWS; i++)
		{
			heldRows[i].show(i < held.size() ? held.get(i) : null);
		}
	}

	/** "+327k · 236k/hr" and "1h 23m · 9 flips · 9 won · 0 lost · ROI 2.4%", since the session began. */
	private void paintSession(FlipBrain.View v)
	{
		boolean on = v != null && v.loggedIn && v.sessionStart > 0;
		sessionProfit.setVisible(on);
		sessionLine.setVisible(on);
		newSession.setVisible(on);
		if (!on)
		{
			return;
		}
		long now = System.currentTimeMillis();
		long profit = 0;
		long cost = 0;
		int flips = 0;
		int won = 0;
		int lost = 0;
		for (GameSnapshot.Done d : v.history)
		{
			if (d.at >= v.sessionStart)
			{
				profit += d.profit;
				cost += d.buyEach * d.qty;
				flips++;
				won += d.profit > 0 ? 1 : 0;
				lost += d.profit < 0 ? 1 : 0;
			}
		}
		double hours = Math.max((now - v.sessionStart) / 3_600_000.0, 1 / 60.0);
		sessionProfit.setText(String.format("%s%s · %s/hr", profit >= 0 ? "+" : "-", FlipBrain.gp(Math.abs(profit)),
			FlipBrain.gp((long) (profit / hours))));
		sessionProfit.setForeground(profit >= 0 ? GREEN : RED);
		sessionLine.setText(wrap(String.format("%s · %d flip%s · %d won · %d lost%s",
			FlipSidebarSlotOverlay.clock(now - v.sessionStart), flips, flips == 1 ? "" : "s", won, lost,
			cost > 0 ? String.format(" · ROI %.1f%%", profit * 100.0 / cost) : "")));
	}

	/** Right-click the card: never suggest its item again. */
	private void cardMenu(MouseEvent e)
	{
		FlipBrain.View v = shown;
		Suggestion s = v == null ? null : v.card;
		if (!e.isPopupTrigger() || s == null || !"BUY".equals(s.action))
		{
			return;
		}
		JPopupMenu m = new JPopupMenu();
		JMenuItem never = new JMenuItem("Never suggest " + s.name);
		never.addActionListener(ev ->
		{
			block(s.itemId);
			brain.skip();
		});
		m.add(never);
		m.show(e.getComponent(), e.getX(), e.getY());
	}

	private void block(int itemId)
	{
		Set<Integer> ids = FlipSidebarConfig.parseBlocked(config.blockedItems());
		ids.add(itemId);
		StringBuilder csv = new StringBuilder();
		for (int id : ids)
		{
			csv.append(csv.length() == 0 ? "" : ",").append(id);
		}
		configManager.setConfiguration(FlipSidebarConfig.GROUP, "blockedItems", csv.toString());
	}

	/** A small orange text that acts as a link. */
	private static void link(JLabel l, String tip, Runnable onClick)
	{
		l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		l.setToolTipText(tip);
		l.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				onClick.run();
			}
		});
	}

	/** "Last 24h: +1.23m · 14 flips · 2 lost". */
	private void paintHistorySummary(List<GameSnapshot.Done> history)
	{
		long since = System.currentTimeMillis() - DAY_MS;
		long total = 0;
		int flips = 0;
		int lost = 0;
		for (GameSnapshot.Done d : history)
		{
			if (d.at == 0 || d.at >= since)
			{
				total += d.profit;
				flips++;
				lost += d.profit < 0 ? 1 : 0;
			}
		}
		historySummary.setVisible(flips > 0);
		historySummary.setText(String.format("Last 24h: %s%s · %d flip%s · %d lost", total >= 0 ? "+" : "",
			FlipBrain.gp(total), flips, flips == 1 ? "" : "s", lost));
		historySummary.setForeground(total >= 0 ? GREEN : RED);
		long all = 0;
		int allLost = 0;
		for (GameSnapshot.Done d : history)
		{
			all += d.profit;
			allLost += d.profit < 0 ? 1 : 0;
		}
		historyTotal.setVisible(history.size() > flips);
		historyTotal.setText(String.format("All kept: %s%s · %d flips · %d lost", all >= 0 ? "+" : "",
			FlipBrain.gp(all), history.size(), allLost));
	}

	/** The age texts that change every second. */
	private void paintAges()
	{
		for (HistoryRow r : historyRows)
		{
			r.paintAge();
		}
		paintSession(shown);
		for (OfferRow r : offerRows)
		{
			r.paintAge();
		}
		FlipBrain.View v = shown;
		if (v == null || !v.live)
		{
			return;
		}
		Suggestion s = v.card;
		if (s != null && "BUY".equals(s.action) && s.asOf > 0)
		{
			long age = System.currentTimeMillis() - s.asOf * 1000;
			boolean stale = age >= FlipBrain.STALE_MS;
			cardHint.setText(wrap(v.cardHint + " · " + (stale ? (age / 60_000) + "m old - prices may have moved"
				: (age / 1000) + "s ago")));
			cardHint.setForeground(stale ? ORANGE : DIM);
		}
		else if (s != null)
		{
			cardHint.setText(wrap(v.cardHint));
			cardHint.setForeground(DIM);
		}
		cardHint.setVisible(s != null && !v.cardHint.isEmpty());
		rescanButton.setText(v.scanning ? "Scanning..." : v.scannedAt <= 0 ? "Scan now"
			: "Scan now (last " + ago(System.currentTimeMillis() - v.scannedAt) + " ago)");
	}

	private void askLivePrices()
	{
		int answer = JOptionPane.showConfirmDialog(this,
			LIVE_WARNING + ".\n\nThe plugin asks prices.runescape.wiki (the OSRS Wiki's price service) for "
				+ "Grand Exchange prices.\nTurn on live prices?",
			"Flip Sidebar - live prices", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
		if (answer == JOptionPane.YES_OPTION)
		{
			setConfig("livePrices", "true");
		}
	}

	private void setConfig(String key, String value)
	{
		if (!syncing)
		{
			configManager.setConfiguration(FlipSidebarConfig.GROUP, key, value);
		}
	}

	private void toggleIgnored(String key)
	{
		Set<String> keys = FlipSidebarConfig.parseIgnored(config.ignoredOffers());
		if (!keys.remove(key))
		{
			keys.add(key);
		}
		configManager.setConfiguration(FlipSidebarConfig.GROUP, "ignoredOffers", String.join(",", keys));
	}

	// ── rows ────────────────────────────────────────────────────────────────────

	/** One open offer: name and what to do, the offer, why. Right-click to ignore it. */
	private final class OfferRow extends JPanel
	{
		private final JLabel name = label("", WHITE, FontManager.getRunescapeBoldFont());
		private final JLabel verdict = label("", DIM, FontManager.getRunescapeBoldFont());
		private final JLabel line = label("", WHITE, small());
		private final JLabel why = label("", DIM, small());
		private OfferAdvice advice;
		private String lineText = "";

		OfferRow()
		{
			setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
			setBackground(CARD);
			setBorder(new EmptyBorder(4, 6, 4, 6));
			JPanel head = new JPanel(new BorderLayout(4, 0));
			head.setOpaque(false);
			head.setAlignmentX(Component.LEFT_ALIGNMENT);
			head.add(name, BorderLayout.CENTER);
			head.add(verdict, BorderLayout.EAST);
			add(head);
			add(line);
			add(why);
			setToolTipText("Right-click to ignore an offer that is not your flipping money");
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					menu(e);
				}

				@Override
				public void mouseReleased(MouseEvent e)
				{
					menu(e);
				}
			});
		}

		private void menu(MouseEvent e)
		{
			OfferAdvice a = advice;
			if (!e.isPopupTrigger() || a == null)
			{
				return;
			}
			JPopupMenu m = new JPopupMenu();
			JMenuItem item = new JMenuItem(a.ignored() ? "Stop ignoring this offer" : "Ignore this offer (not my flipping money)");
			item.addActionListener(ev -> toggleIgnored(a.offer.key()));
			m.add(item);
			m.show(this, e.getX(), e.getY());
		}

		void show(OfferAdvice a)
		{
			advice = a;
			showRow(this, a != null);
			if (a == null)
			{
				return;
			}
			GameSnapshot.Offer o = a.offer;
			boolean ig = a.ignored();
			name.setText(o.name == null ? "Item " + o.itemId : o.name);
			name.setForeground(ig ? DIM : WHITE);
			verdict.setText(a.verdict);
			verdict.setForeground(ig ? DIM : OfferAdvice.COLLECT.equals(a.verdict) ? GREEN
				: OfferAdvice.CANCEL.equals(a.verdict) ? RED : a.adjust() ? AMBER : DIM);
			String text = String.format("%s %,d/%,d @ %,d", o.buy ? "Buy" : "Sell", o.filled, o.total, o.price);
			long[] lim = o.buy && shown != null ? shown.limits.get(o.itemId) : null;
			if (lim != null && !ig)
			{
				text += String.format(" · limit %,d left", lim[0]);
			}
			if (a.target > 0 && a.target != o.price && !ig)
			{
				text += String.format(" → %,d", a.target);
			}
			lineText = text;
			line.setForeground(ig ? DIM : WHITE);
			why.setText(wrap(a.why));
			paintAge();
		}

		/** The offer, plus how long since it last filled (or was placed) — counts up every second. */
		void paintAge()
		{
			OfferAdvice a = advice;
			if (a == null)
			{
				return;
			}
			GameSnapshot.Offer o = a.offer;
			long since = o.lastFill > 0 ? o.lastFill : o.placedAt;
			boolean active = !o.complete && !o.cancelled && !a.ignored();
			line.setText(wrap(active && since > 0 ? lineText + " · " + (o.lastFill > 0 ? "fill " : "open ")
				+ FlipSidebarSlotOverlay.clock(System.currentTimeMillis() - since) : lineText));
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}
	}

	/** One finished flip: what it made — or lost, in red. */
	private final class HistoryRow extends JPanel
	{
		private final JLabel name = label("", WHITE, FontManager.getRunescapeBoldFont());
		private final JLabel gain = label("", GREEN, FontManager.getRunescapeBoldFont());
		private final JLabel line = label("", DIM, small());
		private GameSnapshot.Done done;

		HistoryRow()
		{
			setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
			setBackground(CARD);
			JPanel head = new JPanel(new BorderLayout(4, 0));
			head.setOpaque(false);
			head.setAlignmentX(Component.LEFT_ALIGNMENT);
			head.add(name, BorderLayout.CENTER);
			head.add(gain, BorderLayout.EAST);
			add(head);
			add(line);
		}

		void show(GameSnapshot.Done d)
		{
			done = d;
			showRow(this, d != null);
			if (d == null)
			{
				return;
			}
			boolean loss = d.profit < 0;
			setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 2, 0, 0, loss ? RED : GREEN), new EmptyBorder(4, 6, 4, 6)));
			name.setText(d.name == null ? "Item " + d.itemId : d.name);
			gain.setText((loss ? "" : "+") + FlipBrain.gp(d.profit));
			gain.setForeground(loss ? RED : GREEN);
			paintAge();
		}

		void paintAge()
		{
			GameSnapshot.Done d = done;
			if (d == null)
			{
				return;
			}
			String when = d.at == 0 ? "earlier" : ago(System.currentTimeMillis() - d.at) + " ago";
			line.setText(String.format("%,d @ %,d → %,d · %s", d.qty, d.buyEach, d.sellEach, when));
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}
	}

	/** Bought and not sold: quantity, paid, now, and what selling all of it now makes. */
	private final class HeldRow extends JPanel
	{
		private final JLabel name = label("", WHITE, FontManager.getRunescapeBoldFont());
		private final JLabel gain = label("", GREEN, FontManager.getRunescapeBoldFont());
		private final JLabel line = label("", DIM, small());

		HeldRow()
		{
			setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
			setBackground(CARD);
			setBorder(new EmptyBorder(4, 6, 4, 6));
			JPanel head = new JPanel(new BorderLayout(4, 0));
			head.setOpaque(false);
			head.setAlignmentX(Component.LEFT_ALIGNMENT);
			head.add(name, BorderLayout.CENTER);
			head.add(gain, BorderLayout.EAST);
			add(head);
			add(line);
		}

		void show(FlipBrain.Held h)
		{
			showRow(this, h != null);
			if (h == null)
			{
				return;
			}
			name.setText(h.name == null ? "Item " + h.itemId : h.name);
			boolean known = h.market > 0;
			gain.setText(!known ? "" : (h.profit >= 0 ? "+" : "-") + FlipBrain.gp(Math.abs(h.profit)));
			gain.setForeground(h.profit >= 0 ? GREEN : RED);
			long[] lim = shown == null ? null : shown.limits.get(h.itemId);
			line.setText(wrap(String.format("%,d · paid %,d · now %s", h.qty, h.paidEach,
				known ? String.format("%,d", h.market) : "?")
				+ (lim == null ? "" : String.format(" · limit %,d left", lim[0]))));
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}
	}

	/** One of the next best flips. Click it to make it the card. */
	private final class NextRow extends JPanel
	{
		private final JLabel name = label("", WHITE, FontManager.getRunescapeBoldFont());
		private final JLabel gain = label("", GREEN, FontManager.getRunescapeBoldFont());
		private final JLabel line = label("", DIM, small());
		private Pick pick;

		NextRow()
		{
			setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
			setBackground(CARD);
			setBorder(new EmptyBorder(4, 6, 4, 6));
			JPanel head = new JPanel(new BorderLayout(4, 0));
			head.setOpaque(false);
			head.setAlignmentX(Component.LEFT_ALIGNMENT);
			head.add(name, BorderLayout.CENTER);
			head.add(gain, BorderLayout.EAST);
			add(head);
			add(line);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			setToolTipText("Click to do this one instead · right-click: never suggest it");
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					Pick p = pick;
					if (p != null && e.getButton() == MouseEvent.BUTTON1)
					{
						brain.doThis(p.itemId);
					}
				}

				@Override
				public void mousePressed(MouseEvent e)
				{
					menu(e);
				}

				@Override
				public void mouseReleased(MouseEvent e)
				{
					menu(e);
				}

				private void menu(MouseEvent e)
				{
					Pick p = pick;
					if (p == null || !e.isPopupTrigger())
					{
						return;
					}
					JPopupMenu m = new JPopupMenu();
					JMenuItem never = new JMenuItem("Never suggest " + p.name);
					never.addActionListener(ev -> block(p.itemId));
					m.add(never);
					m.show(NextRow.this, e.getX(), e.getY());
				}

				@Override
				public void mouseEntered(MouseEvent e)
				{
					setBackground(HOVER);
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					setBackground(CARD);
				}
			});
		}

		void show(Pick p)
		{
			pick = p;
			showRow(this, p != null);
			if (p == null)
			{
				return;
			}
			name.setText(p.name);
			gain.setText("+" + FlipBrain.gp(p.total));
			line.setText(wrap(String.format("%,d @ %,d → %,d · %s/hr", p.qty, p.bid, p.ask, FlipBrain.gp((long) p.perHour()))));
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}
	}

	// ── little helpers ──────────────────────────────────────────────────────────

	private static void put(JPanel content, Component c, int gapAfter)
	{
		if (c instanceof JComponent)
		{
			((JComponent) c).setAlignmentX(Component.LEFT_ALIGNMENT);
		}
		content.add(c);
		if (gapAfter > 0)
		{
			content.add(Box.createVerticalStrut(gapAfter));
		}
	}

	/**
	 * A row and the gap under it, shown and hidden TOGETHER ({@link #showRow}). A gap left behind by
	 * a hidden row is blank space — 175 hidden history rows left a 525px hole.
	 */
	private static JPanel withGap(JPanel row, int gap)
	{
		JPanel holder = new JPanel()
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		holder.setLayout(new BoxLayout(holder, BoxLayout.Y_AXIS));
		holder.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		holder.add(row);
		holder.add(Box.createVerticalStrut(gap));
		return holder;
	}

	/** Show or hide a row made by {@link #withGap}, gap included. */
	private static void showRow(JPanel row, boolean visible)
	{
		row.setVisible(visible);
		if (row.getParent() != null)
		{
			row.getParent().setVisible(visible);
		}
	}

	/** A padded dark box that stacks its children and never grows taller than they need. */
	private static JPanel box()
	{
		JPanel p = new JPanel()
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(CARD);
		p.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 2, 0, 0, ORANGE), new EmptyBorder(8, 8, 8, 8)));
		return p;
	}

	private static JLabel label(String text, Color colour, Font font)
	{
		JLabel l = new JLabel(text);
		l.setForeground(colour);
		l.setFont(font);
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	/** One "name ......... value" line of the card. */
	private static JPanel row(String name, JLabel value)
	{
		JPanel r = new JPanel(new BorderLayout())
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		r.setOpaque(false);
		r.setAlignmentX(Component.LEFT_ALIGNMENT);
		r.add(label(name, DIM, normal()), BorderLayout.WEST);
		r.add(value, BorderLayout.EAST);
		return r;
	}

	private static JLabel value(Color colour)
	{
		JLabel l = label("", colour, normal());
		l.setHorizontalAlignment(SwingConstants.RIGHT);
		return l;
	}

	private static JLabel header(String text)
	{
		return label(text, ORANGE, FontManager.getRunescapeBoldFont());
	}

	private static Font normal()
	{
		return FontManager.getRunescapeFont();
	}

	private static Font small()
	{
		return FontManager.getRunescapeSmallFont();
	}

	/** HTML so a long line wraps inside the panel instead of being cut off. */
	private static String wrap(String text)
	{
		String t = text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
		return "<html><div style='width:" + WRAP_PX + "px'>" + t + "</div></html>";
	}

	private static String ago(long ms)
	{
		long s = Math.max(0, ms / 1000);
		return s < 60 ? s + "s" : s < 3600 ? (s / 60) + "m" : s < 86400 ? (s / 3600) + "h" : (s / 86400) + "d";
	}
}
