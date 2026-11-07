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
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.Path2D;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.Box;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;

/**
 * The Statistics window — Copilot's: click an item's picture in the side panel for its PRICE GRAPH
 * (insta-buy / insta-sell, volume, the latest prices, the change, a crosshair readout); plus the
 * Portfolio (held stock at today's price, coins in offers), profit over time, which ITEMS make money
 * and which lose it, and every kept flip. User, 2026-10-31: "click the item icon ... it would bring
 * up the full-screen graph which is all those windows I sent u pictures of". Swing thread only.
 */
class FlipsWindow extends JDialog
{
	/** Fetches an item's price history (only with Live prices on); {@code done} may run on any thread. */
	interface Charts
	{
		void fetch(int itemId, String range, Consumer<PriceHistory> done);
	}

	private static final String[] RANGES = {"1d", "1w", "1m", "1y"};
	private static Color BUY_LINE;
	private static Color SELL_LINE;
	private static Color CARD_BUY;
	private static Color CARD_SELL;
	private static final int WAIT_MS = 20_000;
	/** Each range's step (PriceHistory.RANGES), in words, for the volume axis. */
	private static final Map<String, String> STEP_WORDS = Map.of(
		"1d", "per 5 minutes", "1w", "per hour", "1m", "per 6 hours", "1y", "per day");

	private static Color GREEN;
	private static Color RED;
	private static final Color GRID = new Color(255, 255, 255, 30);
	private static Color DIM;
	private static Color BACKGROUND;
	private static Color CARD;

	static
	{
		useTheme(FlipTheme.now);
	}

	/** The colours of {@code t} for the next window built (FlipTheme, the "Look" setting). */
	static void useTheme(FlipTheme t)
	{
		BUY_LINE = t.green;
		SELL_LINE = t.red;
		CARD_BUY = t.yellow;
		CARD_SELL = t.orange;
		GREEN = t.green;
		RED = t.red;
		DIM = t.dim;
		BACKGROUND = t.background;
		CARD = t.card;
	}
	private static final int ITEM_WIDTH = 190;

	private final ProfitGraph graph = new ProfitGraph();
	private final ItemsModel items = new ItemsModel();
	private final FlipsModel flips = new FlipsModel();
	private final PortfolioModel portfolio = new PortfolioModel();
	private final JLabel portfolioTotal = new JLabel();
	private final JLabel summary = new JLabel();
	private final JTabbedPane tabs = new JTabbedPane();
	private final JLabel learning = new JLabel();
	private final LimitsModel limits = new LimitsModel();
	private final PriceTab price;

	FlipsWindow(Window owner, Charts charts, Consumer<Consumer<String>> export)
	{
		this(owner, charts, export, c -> { }, c -> { }, null);
	}

	/** Every finished flip, all time (FlipSidebarPlugin.allFlips); null = the view's last 200 only. */
	private final java.util.function.Supplier<List<GameSnapshot.Done>> allFlips;

	/**
	 * ...with "Export data" / "Import data" (FlipSidebarPlugin.exportBrain / importBrain) beside Export CSV — where the user
	 * looked for them (2026-11-03, a screenshot of this window: "i dont see any export brain option but it should say
	 * export data export brain sounds weird. the import thing i cant find either. id expect it here").
	 */
	FlipsWindow(Window owner, Charts charts, Consumer<Consumer<String>> export, Consumer<java.awt.Component> exportData,
		Consumer<java.awt.Component> importData, java.util.function.Supplier<List<GameSnapshot.Done>> allFlips)
	{
		super(owner, "Flip Sidebar - Statistics", ModalityType.MODELESS);
		this.allFlips = allFlips;
		price = new PriceTab(charts);
		setDefaultCloseOperation(HIDE_ON_CLOSE);
		JPanel root = new JPanel(new BorderLayout(0, 6));
		root.setBorder(new EmptyBorder(8, 8, 8, 8));
		root.setBackground(BACKGROUND);
		summary.setFont(FlipTheme.now.bold());
		// Export CSV lives here, by the flips (user, 2026-10-31, of the link at the bottom of the panel's
		// History: "i have no idea how to do")
		JButton exportButton = new JButton("Export CSV");
		exportButton.setToolTipText("Save every kept flip as a spreadsheet file (CSV)");
		JLabel exportResult = new JLabel();
		exportResult.setFont(FlipTheme.now.small());
		exportResult.setForeground(DIM);
		exportButton.addActionListener(e -> export.accept(msg -> SwingUtilities.invokeLater(() -> exportResult.setText(msg))));
		JButton dataOut = new JButton("Export data");
		dataOut.setToolTipText("<html>Save what the plugin has learned on this account - its flips, the items it rests,<br>"
			+ "your holds and buy limits - and your settings, to one file. Load it on any PC with Import data.</html>");
		dataOut.addActionListener(e -> exportData.accept(this));
		JButton dataIn = new JButton("Import data");
		dataIn.setToolTipText("<html>Load a saved data file (yours, or a team file). It shows what is in it first and saves<br>"
			+ "what is here now before anything changes.</html>");
		dataIn.addActionListener(e -> importData.accept(this));
		JPanel buttons = new JPanel(new java.awt.GridLayout(1, 3, 6, 0));
		buttons.setOpaque(false);
		buttons.add(dataOut);
		buttons.add(dataIn);
		buttons.add(exportButton);
		JPanel exportBox = new JPanel(new BorderLayout(8, 0));
		exportBox.setOpaque(false);
		exportBox.add(exportResult, BorderLayout.CENTER);
		exportBox.add(buttons, BorderLayout.EAST);
		JPanel top = new JPanel(new BorderLayout(8, 0));
		top.setOpaque(false);
		top.add(summary, BorderLayout.WEST);
		top.add(exportBox, BorderLayout.EAST);
		top.add(buildItemSearch(), BorderLayout.CENTER);
		root.add(top, BorderLayout.NORTH);
		JPanel port = new JPanel(new BorderLayout(0, 6));
		port.setOpaque(false);
		portfolioTotal.setFont(FlipTheme.now.plain());
		port.add(portfolioTotal, BorderLayout.NORTH);
		port.add(new JScrollPane(table(portfolio, 5)), BorderLayout.CENTER);
		tabs.addTab("Price graph", price);
		tabs.addTab("Portfolio", port);
		tabs.addTab("Profit", graph.withRanges());
		tabs.addTab("Items", new JScrollPane(table(items, 4)));
		tabs.addTab("Flips", new JScrollPane(table(flips, 0)));
		// what the plugin learned from these flips (FlipLearner), in words
		learning.setVerticalAlignment(SwingConstants.TOP);
		learning.setFont(FlipTheme.now.plain());
		learning.setBorder(new EmptyBorder(8, 8, 8, 8));
		tabs.addTab("Learning", new JScrollPane(learning));
		// items whose GE buy limit gets used up (user, 2026-10-31: "track items we constantly hit buy limit on")
		tabs.addTab("Buy limits", new JScrollPane(table(limits, 1)));
		root.add(tabs, BorderLayout.CENTER);
		setContentPane(root);
		// big: most of the screen, and resizable to all of it
		Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
		setSize(new Dimension(Math.max(760, screen.width * 4 / 5), Math.max(520, screen.height * 4 / 5)));
		setLocationRelativeTo(owner);
	}

	/**
	 * Open on an item's price graph. {@code buyAt} / {@code sellAt} (0 = none) are drawn as dashed lines
	 * named {@code buyName} / {@code sellName} — "buy at" for the card, "you bought at" for a past flip.
	 */
	/**
	 * ⭐ ANY ITEM'S GRAPH (1.60.55; user, 2026-11-07: "In statistics I should be able to search any item to get a graph. I
	 * have an active sell off I just want to see its graph but theres no easy way to do that"): type 2+ letters, pick from
	 * the list (Enter = the first) and the Price graph tab shows it. FlipBrain.searchItems / itemName, set by the panel.
	 */
	private final javax.swing.JTextField itemSearch = new javax.swing.JTextField(16);
	private final javax.swing.JPopupMenu itemHits = new javax.swing.JPopupMenu();
	private java.util.function.BiFunction<String, Integer, List<Integer>> searcher;
	private java.util.function.IntFunction<String> namer;

	void setItemSearch(java.util.function.BiFunction<String, Integer, List<Integer>> search, java.util.function.IntFunction<String> name)
	{
		searcher = search;
		namer = name;
	}

	private JPanel buildItemSearch()
	{
		JPanel p = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
		p.setOpaque(false);
		JLabel l = new JLabel("Graph of any item:");
		l.setForeground(DIM);
		l.setFont(FlipTheme.now.small());
		itemSearch.setToolTipText("Type 2 or more letters of an item's name, then pick it - its price graph opens");
		itemHits.setFocusable(false);
		itemSearch.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
		{
			@Override
			public void insertUpdate(javax.swing.event.DocumentEvent e)
			{
				SwingUtilities.invokeLater(FlipsWindow.this::searchTyped);
			}

			@Override
			public void removeUpdate(javax.swing.event.DocumentEvent e)
			{
				SwingUtilities.invokeLater(FlipsWindow.this::searchTyped);
			}

			@Override
			public void changedUpdate(javax.swing.event.DocumentEvent e)
			{
				SwingUtilities.invokeLater(FlipsWindow.this::searchTyped);
			}
		});
		itemSearch.addActionListener(e ->
		{
			List<Integer> ids = searcher == null ? List.of() : searcher.apply(itemSearch.getText(), 1);
			if (!ids.isEmpty())
			{
				pickItem(ids.get(0));
			}
		});
		p.add(l);
		p.add(itemSearch);
		return p;
	}

	private void searchTyped()
	{
		itemHits.setVisible(false);
		itemHits.removeAll();
		String q = itemSearch.getText();
		if (searcher == null || q == null || q.trim().length() < 2)
		{
			return;
		}
		for (int id : searcher.apply(q, 8))
		{
			javax.swing.JMenuItem mi = new javax.swing.JMenuItem(namer == null ? "Item " + id : namer.apply(id));
			mi.addActionListener(e -> pickItem(id));
			itemHits.add(mi);
		}
		if (itemHits.getComponentCount() > 0 && itemSearch.isShowing())
		{
			itemHits.show(itemSearch, 0, itemSearch.getHeight());
			itemSearch.requestFocusInWindow();
		}
	}

	private void pickItem(int id)
	{
		itemHits.setVisible(false);
		showItem(id, namer == null ? "Item " + id : namer.apply(id), 0, null, 0, null, null);
	}

	void showItem(int itemId, String name, long buyAt, String buyName, long sellAt, String sellName, Outcome outcome)
	{
		tabs.setSelectedIndex(0);
		price.load(itemId, name, new Mark(buyAt, buyName), new Mark(sellAt, sellName), outcome);
	}

	/**
	 * What the buy and the sell on the graph make — "Expected profit: +80 each after tax · +281k for
	 * 3,512" under the item's name (user, 2026-10-31: "in the graphs with buy and sell it should be saying
	 * expected profit"). {@code qty} 0 = each only.
	 */
	static final class Outcome
	{
		final String label;
		final long each;
		final long total;
		final long qty;

		Outcome(String label, long each, long total, long qty)
		{
			this.label = label;
			this.each = each;
			this.total = total;
			this.qty = qty;
		}

		String text()
		{
			return String.format("%s: %s%,d each after tax", label, each > 0 ? "+" : "", each)
				+ (qty > 0 ? String.format(" · %s%s for %,d", total > 0 ? "+" : total < 0 ? "-" : "",
				FlipBrain.gp(Math.abs(total)), qty) : "");
		}

		Color colour()
		{
			long v = qty > 0 ? total : each;
			return v > 0 ? GREEN : v < 0 ? RED : DIM;
		}
	}

	/** A dashed price line on the graph: 0 = none. */
	private static final class Mark
	{
		final long at;
		final String name;

		Mark(long at, String name)
		{
			this.at = at;
			this.name = name;
		}
	}

	/** Items whose buy limit was used up (FlipBrain.limitTable). */
	void showLimits(List<Object[]> rows)
	{
		limits.show(rows);
	}

	/** What the plugin has learned from the flips (FlipLearner), one line each. */
	void showLearning(List<String> lines)
	{
		StringBuilder t = new StringBuilder("<html><div style='width:700px'><b>What the plugin has learned from your flips</b><br>"
			+ "It re-learns as flips finish. Everything stays on this computer.<ul>");
		for (String l : lines)
		{
			t.append("<li style='margin-bottom:6px'>").append(l.replace("&", "&amp;").replace("<", "&lt;")).append("</li>");
		}
		learning.setText(t.append(lines.isEmpty() ? "<li>Nothing yet - log in and finish a few flips.</li>" : "")
			.append("</ul></div></html>").toString());
	}

	/** Opened from a button, not an item: stay on the tab last used, or Profit when no item was picked yet. */
	void showDefault()
	{
		if (tabs.getSelectedIndex() == 0 && !price.hasItem())
		{
			tabs.setSelectedIndex(2);
		}
	}

	/** New data from the panel. */
	void show(FlipBrain.View v)
	{
		// all time when the archive is there (the trade log's history is the last 200 flips)
		List<GameSnapshot.Done> h = allFlips != null ? allFlips.get() : v == null ? List.of() : v.history;
		portfolio.show(v == null ? List.of() : v.held);
		long worth = 0;
		long pl = 0;
		for (FlipBrain.Held x : v == null ? List.<FlipBrain.Held>of() : v.held)
		{
			if (x.market > 0)
			{
				worth += (x.market - FlipEngine.tax(x.market, x.itemId)) * x.qty;
				pl += x.profit;
			}
		}
		portfolioTotal.setText(String.format("Holding worth %s after tax (%s%s) · %s in buy offers", FlipBrain.gp(worth),
			pl >= 0 ? "+" : "-", FlipBrain.gp(Math.abs(pl)), FlipBrain.gp(v == null ? 0 : v.inBuys)));
		portfolioTotal.setForeground(pl >= 0 ? GREEN : RED);
		long total = 0;
		int lost = 0;
		for (GameSnapshot.Done d : h)
		{
			total += d.profit;
			lost += d.profit < 0 ? 1 : 0;
		}
		summary.setText(String.format("%d flips · %d won · %d lost · %s%s total", h.size(), h.size() - lost, lost,
			total >= 0 ? "+" : "-", FlipBrain.gp(Math.abs(total))));
		summary.setForeground(total >= 0 ? GREEN : RED);
		graph.show(h);
		items.show(h);
		flips.show(h);
	}

	private static JTable table(AbstractTableModel model, int sortColumn)
	{
		JTable t = new JTable(model);
		t.setFillsViewportHeight(true);
		t.setRowHeight(20);
		TableRowSorter<AbstractTableModel> sorter = new TableRowSorter<>(model);
		sorter.setSortKeys(List.of(new RowSorter.SortKey(sortColumn, SortOrder.DESCENDING)));
		t.setRowSorter(sorter);
		t.setDefaultRenderer(Long.class, new NumberRenderer());
		t.setDefaultRenderer(Integer.class, new NumberRenderer());
		for (int c = 0; c < model.getColumnCount(); c++)
		{
			if (model.getColumnClass(c) == String.class)
			{
				t.getColumnModel().getColumn(c).setPreferredWidth(ITEM_WIDTH);     // item names, not squeezed
			}
		}
		return t;
	}

	/** Numbers with commas; a "profit" column green or red. */
	private static final class NumberRenderer extends DefaultTableCellRenderer
	{
		NumberRenderer()
		{
			setHorizontalAlignment(SwingConstants.RIGHT);
		}

		@Override
		public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus,
			int row, int column)
		{
			Component c = super.getTableCellRendererComponent(table, value, selected, focus, row, column);
			String name = table.getColumnName(column);
			boolean money = name.contains("rofit") || name.startsWith("Best") || name.startsWith("Worst") || name.startsWith("Avg");
			if (value instanceof Number)
			{
				long v = ((Number) value).longValue();
				setText(money && v > 0 ? String.format("+%,d", v) : String.format("%,d", v));
				if (!selected)
				{
					c.setForeground(!money ? table.getForeground() : v > 0 ? GREEN : v < 0 ? RED : DIM);
				}
			}
			return c;
		}
	}

	/** One row per item: how it has done. */
	private static final class ItemsModel extends AbstractTableModel
	{
		private static final String[] COLS = {"Item", "Flips", "Won", "Lost", "Profit", "Avg / flip", "Best", "Worst"};
		private final List<Object[]> rows = new ArrayList<>();

		void show(List<GameSnapshot.Done> history)
		{
			Map<Integer, Object[]> by = new LinkedHashMap<>();
			for (GameSnapshot.Done d : history)
			{
				Object[] r = by.computeIfAbsent(d.itemId, k -> new Object[]{
					d.name == null ? "Item " + d.itemId : d.name, 0, 0, 0, 0L, 0L, Long.MIN_VALUE, Long.MAX_VALUE});
				r[1] = (int) r[1] + 1;
				r[2] = (int) r[2] + (d.profit > 0 ? 1 : 0);
				r[3] = (int) r[3] + (d.profit < 0 ? 1 : 0);
				r[4] = (long) r[4] + d.profit;
				r[6] = Math.max((long) r[6], d.profit);
				r[7] = Math.min((long) r[7], d.profit);
			}
			rows.clear();
			for (Object[] r : by.values())
			{
				r[5] = (long) r[4] / (int) r[1];
				rows.add(r);
			}
			fireTableDataChanged();
		}

		@Override
		public int getRowCount()
		{
			return rows.size();
		}

		@Override
		public int getColumnCount()
		{
			return COLS.length;
		}

		@Override
		public String getColumnName(int c)
		{
			return COLS[c];
		}

		@Override
		public Class<?> getColumnClass(int c)
		{
			return c == 0 ? String.class : c <= 3 ? Integer.class : Long.class;
		}

		@Override
		public Object getValueAt(int r, int c)
		{
			return rows.get(r)[c];
		}
	}

	/** Every kept flip. */
	private static final class FlipsModel extends AbstractTableModel
	{
		private static final String[] COLS = {"When", "Item", "Quantity", "Buy each", "Sell each", "Profit"};
		private final SimpleDateFormat when = new SimpleDateFormat("MMM d HH:mm");
		private final List<GameSnapshot.Done> rows = new ArrayList<>();

		void show(List<GameSnapshot.Done> history)
		{
			rows.clear();
			rows.addAll(history);
			fireTableDataChanged();
		}

		@Override
		public int getRowCount()
		{
			return rows.size();
		}

		@Override
		public int getColumnCount()
		{
			return COLS.length;
		}

		@Override
		public String getColumnName(int c)
		{
			return COLS[c];
		}

		@Override
		public Class<?> getColumnClass(int c)
		{
			return c == 1 ? String.class : c == 0 ? WhenCell.class : Long.class;
		}

		@Override
		public Object getValueAt(int r, int c)
		{
			GameSnapshot.Done d = rows.get(r);
			switch (c)
			{
				case 0:
					return new WhenCell(d.at, d.at > 0 ? when.format(new Date(d.at)) : "earlier");
				case 1:
					return d.name == null ? "Item " + d.itemId : d.name;
				case 2:
					return d.qty;
				case 3:
					return d.buyEach;
				case 4:
					return d.sellEach;
				default:
					return d.profit;
			}
		}
	}

	/** A time that sorts by the moment, shows as text. */
	private static final class WhenCell implements Comparable<WhenCell>
	{
		final long at;
		final String text;

		WhenCell(long at, String text)
		{
			this.at = at;
			this.text = text;
		}

		@Override
		public int compareTo(WhenCell o)
		{
			return Long.compare(at, o.at);
		}

		@Override
		public String toString()
		{
			return text;
		}
	}

	/** Items whose GE buy limit was used up: how often, when, when it resets, what their flips made. */
	private static final class LimitsModel extends AbstractTableModel
	{
		private static final String[] COLS = {"Item", "Maxed (24h)", "Maxed (30 days)", "Last maxed", "Limit resets in",
			"GE limit", "Profit from its flips"};
		private final List<Object[]> rows = new ArrayList<>();

		void show(List<Object[]> in)
		{
			rows.clear();
			rows.addAll(in);
			fireTableDataChanged();
		}

		@Override
		public int getRowCount()
		{
			return rows.size();
		}

		@Override
		public int getColumnCount()
		{
			return COLS.length;
		}

		@Override
		public String getColumnName(int c)
		{
			return COLS[c];
		}

		@Override
		public Class<?> getColumnClass(int c)
		{
			return c == 0 || c == 3 || c == 4 ? String.class : Long.class;
		}

		@Override
		public Object getValueAt(int r, int c)
		{
			Object[] row = rows.get(r);
			switch (c)
			{
				case 3:
					long at = (long) row[3];
					return at <= 0 ? "-" : new SimpleDateFormat("MMM d HH:mm").format(new Date(at));
				case 4:
					long reset = (long) row[4];
					return reset <= 0 ? "now - buy again" : FlipSidebarSlotOverlay.clock(reset - System.currentTimeMillis());
				default:
					return row[c];
			}
		}
	}

	/** Held stock at today's price. */
	private static final class PortfolioModel extends AbstractTableModel
	{
		private static final String[] COLS = {"Item", "Quantity", "Paid each", "Now", "Worth (after tax)", "Profit"};
		private final List<FlipBrain.Held> rows = new ArrayList<>();

		void show(List<FlipBrain.Held> held)
		{
			rows.clear();
			rows.addAll(held);
			fireTableDataChanged();
		}

		@Override
		public int getRowCount()
		{
			return rows.size();
		}

		@Override
		public int getColumnCount()
		{
			return COLS.length;
		}

		@Override
		public String getColumnName(int c)
		{
			return COLS[c];
		}

		@Override
		public Class<?> getColumnClass(int c)
		{
			return c == 0 ? String.class : Long.class;
		}

		@Override
		public Object getValueAt(int r, int c)
		{
			FlipBrain.Held h = rows.get(r);
			switch (c)
			{
				case 0:
					return h.name == null ? "Item " + h.itemId : h.name;
				case 1:
					return h.qty;
				case 2:
					return h.paidEach;
				case 3:
					return h.market <= 0 ? null : h.market;              // no trades to price it on: blank
				case 4:
					return h.market <= 0 ? null : (h.market - FlipEngine.tax(h.market, h.itemId)) * h.qty;
				default:
					return h.market <= 0 ? null : h.profit;
			}
		}
	}

	/** An item's price graph with its range buttons and the latest numbers. */
	private static final class PriceTab extends JPanel
	{
		private final Charts charts;
		private final JLabel title = new JLabel("Click an item's picture in the side panel");
		private final JLabel stats = new JLabel(" ");
		private final JLabel outcome = new JLabel();
		private final PriceGraph graph = new PriceGraph();
		private final JToggleButton[] buttons = new JToggleButton[RANGES.length];
		private int itemId = -1;
		private String range = "1d";
		private Timer wait;
		private Mark buy = new Mark(0, "");
		private Mark sell = new Mark(0, "");

		PriceTab(Charts charts)
		{
			super(new BorderLayout(0, 6));
			this.charts = charts;
			setOpaque(false);
			title.setFont(FlipTheme.now.bold());
			stats.setFont(FlipTheme.now.plain());
			stats.setForeground(DIM);
			JPanel head = new JPanel(new BorderLayout(8, 2));
			head.setOpaque(false);
			JPanel left = new JPanel(new BorderLayout());
			left.setOpaque(false);
			outcome.setFont(FlipTheme.now.bold());
			left.add(title, BorderLayout.NORTH);
			left.add(outcome, BorderLayout.CENTER);
			left.add(stats, BorderLayout.SOUTH);
			head.add(left, BorderLayout.CENTER);
			JPanel rb = new JPanel(new GridLayout(1, RANGES.length, 4, 0));
			rb.setOpaque(false);
			ButtonGroup group = new ButtonGroup();
			for (int i = 0; i < RANGES.length; i++)
			{
				String r = RANGES[i];
				buttons[i] = new JToggleButton(r, r.equals(range));
				buttons[i].addActionListener(e ->
				{
					range = r;
					fetch();
				});
				group.add(buttons[i]);
				rb.add(buttons[i]);
			}
			// Price | Volume (user, 2026-10-31: "a tick that you can pick at the top that switches between
			// price ... to volume")
			JPanel modes = new JPanel(new GridLayout(1, 2, 4, 0));
			modes.setOpaque(false);
			ButtonGroup modeGroup = new ButtonGroup();
			for (boolean vol : new boolean[]{false, true})
			{
				JToggleButton b = new JToggleButton(vol ? "Volume" : "Price", !vol);
				b.setToolTipText(vol ? "How many were insta-bought and insta-sold in each step" : "Insta-buy and insta-sell prices");
				b.addActionListener(e -> graph.setVolume(vol));
				modeGroup.add(b);
				modes.add(b);
			}
			JPanel east = new JPanel(new BorderLayout(16, 0));
			east.setOpaque(false);
			east.add(modes, BorderLayout.WEST);
			east.add(rb, BorderLayout.EAST);
			head.add(east, BorderLayout.EAST);
			add(head, BorderLayout.NORTH);
			add(graph, BorderLayout.CENTER);
		}

		boolean hasItem()
		{
			return itemId > 0;
		}

		void load(int itemId, String name, Mark buy, Mark sell, Outcome result)
		{
			outcome.setText(result == null ? "" : result.text());
			outcome.setForeground(result == null ? DIM : result.colour());
			outcome.setVisible(result != null);
			this.itemId = itemId;
			this.buy = buy;
			this.sell = sell;
			title.setText(name == null ? "Item " + itemId : name);
			fetch();
		}

		private void fetch()
		{
			if (itemId <= 0)
			{
				return;
			}
			final int id = itemId;
			final String r = range;
			graph.set(null, null, null, "Loading prices...");
			stats.setText(" ");
			// a failed fetch never answers: say so instead of "Loading" for ever
			if (wait != null)
			{
				wait.stop();
			}
			wait = new Timer(WAIT_MS, e -> graph.set(null, null, null, "No answer from the prices site - pick a range to try again"));
			wait.setRepeats(false);
			wait.start();
			final Timer mine = wait;
			charts.fetch(id, r, h -> SwingUtilities.invokeLater(() ->
			{
				if (id != itemId || !r.equals(range))
				{
					return;           // another item or range was picked meanwhile
				}
				mine.stop();
				if (h == null)
				{
					graph.set(null, null, null, "Turn on Live prices in the plugin settings to see price graphs");
					return;
				}
				graph.set(h, buy, sell, null);
				stats.setText(statsText(h));
			}));
		}

		/** "Insta-buy 1,234 (2m ago) · insta-sell 1,200 (5m ago) · 1d +2.3% · 12,345 traded a day". */
		private String statsText(PriceHistory h)
		{
			long now = System.currentTimeMillis() / 1000;
			StringBuilder t = new StringBuilder();
			if (h.latest != null && h.latest.high > 0)
			{
				t.append(String.format("Insta-buy %,d (%s ago)", h.latest.high,
					FlipSidebarSlotOverlay.clock((now - h.latest.highTime) * 1000)));
			}
			if (h.latest != null && h.latest.low > 0)
			{
				t.append(String.format(" · insta-sell %,d (%s ago)", h.latest.low,
					FlipSidebarSlotOverlay.clock((now - h.latest.lowTime) * 1000)));
			}
			long first = 0;
			long last = 0;
			long volume = 0;
			for (long[] p : h.points)
			{
				if (p[1] > 0)
				{
					first = first == 0 ? p[1] : first;
					last = p[1];
				}
				volume += p.length > 4 ? p[3] + p[4] : 0;
			}
			if (first > 0)
			{
				t.append(String.format(" · over %s: %+.1f%%", range, (last - first) * 100.0 / first));
			}
			if (h.points.length > 1 && volume > 0)
			{
				double days = Math.max((h.points[h.points.length - 1][0] - h.points[0][0]) / 86400.0, 1 / 24.0);
				t.append(String.format(" · %,d traded a day", (long) (volume / Math.max(days, 1.0))));
			}
			return t.toString();
		}
	}

	/**
	 * PRICE: insta-buy (green) and insta-sell (red) lines with the buy / sell marks; VOLUME: bars of
	 * how many were insta-bought (green, on top) and insta-sold (red) in each step. Both: a crosshair
	 * readout under the mouse.
	 */
	private static final class PriceGraph extends JPanel
	{
		private PriceHistory h;
		private Mark buy;
		private Mark sell;
		private String message = "Click an item's picture in the side panel";
		private int hoverX = -1;
		private boolean volume;

		PriceGraph()
		{
			setBackground(CARD);
			addMouseMotionListener(new MouseMotionAdapter()
			{
				@Override
				public void mouseMoved(MouseEvent e)
				{
					hoverX = e.getX();
					repaint();
				}
			});
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseExited(MouseEvent e)
				{
					hoverX = -1;
					repaint();
				}
			});
		}

		void setVolume(boolean volume)
		{
			this.volume = volume;
			repaint();
		}

		void set(PriceHistory h, Mark buy, Mark sell, String message)
		{
			this.h = h;
			this.buy = buy;
			this.sell = sell;
			this.message = message;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g0)
		{
			super.paintComponent(g0);
			Graphics2D g = (Graphics2D) g0;
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setFont(FlipTheme.now.small());
			FontMetrics fm = g.getFontMetrics();
			int left = 74;
			int right = getWidth() - 20;
			int top = 20;
			int bottom = getHeight() - 36;
			if (h == null || h.points == null || h.points.length < 2 || right <= left || bottom <= top)
			{
				say(g, message == null ? "No trades in this range" : message, left, top);
				return;
			}
			long[][] pts = h.points;
			long t0 = pts[0][0];
			long t1 = Math.max(t0 + 1, pts[pts.length - 1][0]);
			if (!(volume ? paintVolume(g, fm, pts, t0, t1, left, right, top, bottom)
				: paintPrice(g, fm, pts, t0, t1, left, right, top, bottom)))
			{
				say(g, "No trades in this range", left, top);
				return;
			}
			// time labels: five along the bottom
			SimpleDateFormat f = new SimpleDateFormat("1d".equals(h.range) ? "HH:mm" : "1w".equals(h.range) ? "EEE HH:mm" : "MMM d");
			for (int k = 0; k <= 4; k++)
			{
				long tk = t0 + (t1 - t0) * k / 4;
				String lab = f.format(new Date(tk * 1000));
				int tx = x(tk, t0, t1, left, right);
				g.setColor(GRID);
				g.drawLine(tx, bottom, tx, bottom + 4);
				g.setColor(DIM);
				g.drawString(lab, tx - (k == 0 ? 0 : k == 4 ? fm.stringWidth(lab) : fm.stringWidth(lab) / 2), bottom + 18);
			}
			// crosshair readout at the nearest point
			if (hoverX >= left && hoverX <= right)
			{
				long[] near = pts[0];
				for (long[] p : pts)
				{
					if (Math.abs(x(p[0], t0, t1, left, right) - hoverX) < Math.abs(x(near[0], t0, t1, left, right) - hoverX))
					{
						near = p;
					}
				}
				int cx = x(near[0], t0, t1, left, right);
				g.setColor(new Color(255, 255, 255, 80));
				g.drawLine(cx, top, cx, bottom);
				String when = new SimpleDateFormat("MMM d HH:mm").format(new Date(near[0] * 1000));
				String[] lines = volume
					? new String[]{when, String.format("insta-bought %,d", at(near, 3)), String.format("insta-sold %,d", at(near, 4)),
						String.format("total %,d", at(near, 3) + at(near, 4))}
					: new String[]{when, near[1] > 0 ? String.format("insta-buy %,d", near[1]) : "insta-buy -",
						near[2] > 0 ? String.format("insta-sell %,d", near[2]) : "insta-sell -"};
				Color[] cs = {Color.WHITE, BUY_LINE, SELL_LINE, DIM};
				int bw = 0;
				for (String l : lines)
				{
					bw = Math.max(bw, fm.stringWidth(l));
				}
				int bx = cx + 10 + bw + 12 > right ? cx - bw - 22 : cx + 10;
				g.setColor(new Color(20, 20, 20, 220));
				g.fillRect(bx, top + 6, bw + 12, lines.length * 14 + 8);
				for (int i = 0; i < lines.length; i++)
				{
					g.setColor(cs[i]);
					g.drawString(lines[i], bx + 6, top + 20 + i * 14);
				}
			}
		}

		private static void say(Graphics2D g, String text, int left, int top)
		{
			g.setFont(FlipTheme.now.plain());
			g.setColor(DIM);
			g.drawString(text, left, top + 20);
		}

		/** The two price lines, the price grid and the buy / sell marks; false when nothing traded. */
		private boolean paintPrice(Graphics2D g, FontMetrics fm, long[][] pts, long t0, long t1, int left, int right,
			int top, int bottom)
		{
			long min = Long.MAX_VALUE;
			long max = Long.MIN_VALUE;
			for (long[] p : pts)
			{
				for (int k = 1; k <= 2; k++)
				{
					if (p[k] > 0)
					{
						min = Math.min(min, p[k]);
						max = Math.max(max, p[k]);
					}
				}
			}
			if (min == Long.MAX_VALUE)
			{
				return false;
			}
			for (long v : new long[]{buy.at, sell.at})
			{
				if (v > 0 && v >= min / 2 && v <= max * 2)
				{
					min = Math.min(min, v);
					max = Math.max(max, v);
				}
			}
			long pad = Math.max(1, (max - min) / 20);
			min = Math.max(0, min - pad);
			max = max + pad;
			for (int k = 0; k <= 5; k++)
			{
				long v = min + (max - min) * k / 5;
				int y = y(v, min, max, top, bottom);
				g.setColor(GRID);
				g.drawLine(left, y, right, y);
				g.setColor(DIM);
				String lab = String.format("%,d", v);
				g.drawString(lab, left - 6 - fm.stringWidth(lab), y + 4);
			}
			g.setStroke(new BasicStroke(1.6f));
			series(g, pts, 1, BUY_LINE, t0, t1, left, right, min, max, top, bottom);
			series(g, pts, 2, SELL_LINE, t0, t1, left, right, min, max, top, bottom);
			g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{6f, 4f}, 0f));
			dashed(g, buy, CARD_BUY, left, right, min, max, top, bottom, fm);
			dashed(g, sell, CARD_SELL, left, right, min, max, top, bottom, fm);
			g.setStroke(new BasicStroke(1f));
			g.setColor(BUY_LINE);
			g.drawString("insta-buy", right - 150, top - 6);
			g.setColor(SELL_LINE);
			g.drawString("insta-sell", right - 80, top - 6);
			return true;
		}

		/** A bar per step: insta-sold (red) under insta-bought (green); false when nothing traded. */
		private boolean paintVolume(Graphics2D g, FontMetrics fm, long[][] pts, long t0, long t1, int left, int right,
			int top, int bottom)
		{
			long max = 0;
			for (long[] p : pts)
			{
				max = Math.max(max, at(p, 3) + at(p, 4));
			}
			if (max <= 0)
			{
				return false;
			}
			max += Math.max(1, max / 20);
			for (int k = 0; k <= 5; k++)
			{
				long v = max * k / 5;
				int y = y(v, 0, max, top, bottom);
				g.setColor(GRID);
				g.drawLine(left, y, right, y);
				g.setColor(DIM);
				String lab = String.format("%,d", v);
				g.drawString(lab, left - 6 - fm.stringWidth(lab), y + 4);
			}
			int bw = (int) Math.max(1, (right - left) / (double) pts.length - 1);
			for (long[] p : pts)
			{
				int x = x(p[0], t0, t1, left, right) - bw / 2;
				int sold = bottom - y(at(p, 4), 0, max, top, bottom);
				int bought = bottom - y(at(p, 3) + at(p, 4), 0, max, top, bottom) - sold;
				g.setColor(SELL_LINE);
				g.fillRect(x, bottom - sold, bw, sold);
				g.setColor(BUY_LINE);
				g.fillRect(x, bottom - sold - bought, bw, bought);
			}
			String step = STEP_WORDS.getOrDefault(h.range, "");
			g.setColor(DIM);
			g.drawString("items traded " + step, left, top - 6);
			g.setColor(BUY_LINE);
			g.drawString("insta-bought", right - 160, top - 6);
			g.setColor(SELL_LINE);
			g.drawString("insta-sold", right - 80, top - 6);
			return true;
		}

		/** A point's field {@code k}, 0 when it has none (volumes are fields 3 and 4). */
		private static long at(long[] p, int k)
		{
			return p.length > k ? p[k] : 0;
		}

		private static void series(Graphics2D g, long[][] pts, int k, Color c, long t0, long t1, int left, int right,
			long min, long max, int top, int bottom)
		{
			Path2D.Double path = new Path2D.Double();
			boolean pen = false;
			for (long[] p : pts)
			{
				if (p[k] <= 0)
				{
					continue;
				}
				double x = x(p[0], t0, t1, left, right);
				double y = y(p[k], min, max, top, bottom);
				if (pen)
				{
					path.lineTo(x, y);
				}
				else
				{
					path.moveTo(x, y);
					pen = true;
				}
			}
			g.setColor(c);
			g.draw(path);
			if (pts.length <= 120)
			{
				for (long[] p : pts)
				{
					if (p[k] > 0)
					{
						g.fillOval(x(p[0], t0, t1, left, right) - 2, y(p[k], min, max, top, bottom) - 2, 4, 4);
					}
				}
			}
		}

		private static void dashed(Graphics2D g, Mark m, Color c, int left, int right, long min, long max,
			int top, int bottom, FontMetrics fm)
		{
			if (m.at <= 0 || m.at < min || m.at > max)
			{
				return;
			}
			String label = String.format("%s %,d", m.name, m.at);
			int y = y(m.at, min, max, top, bottom);
			g.setColor(c);
			g.drawLine(left, y, right, y);
			g.drawString(label, right - fm.stringWidth(label), y - 3);
		}

		private static int x(long t, long t0, long t1, int left, int right)
		{
			return left + (int) ((right - left) * (double) (t - t0) / (t1 - t0));
		}

		private static int y(long v, long min, long max, int top, int bottom)
		{
			return bottom - (int) ((bottom - top) * (double) (v - min) / (max - min));
		}
	}

	/** Profit adding up over time: the line, a dot per flip (green won / red lost), the zero line. */
	private static final class ProfitGraph extends JPanel
	{
		/** A pause between two flips at least this long is marked on the graph. */
		private static final long BREAK_MS = 60 * 60_000L;
		private List<GameSnapshot.Done> points = List.of();
		private List<GameSnapshot.Done> all = List.of();
		/** Day, week, month, year, all (0): only the flips that finished in it. */
		private long rangeMs;
		private int hoverX = -1;
		private final JLabel rangeTotal = new JLabel();

		/** The graph under its range buttons (user, 2026-11-03: "i should have day week month year all time"). */
		JPanel withRanges()
		{
			JPanel bar = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
			bar.setOpaque(false);
			javax.swing.ButtonGroup group = new javax.swing.ButtonGroup();
			String[] names = {"Day", "Week", "Month", "Year", "All time"};
			long[] spans = {86_400_000L, 7 * 86_400_000L, 30 * 86_400_000L, 365 * 86_400_000L, 0};
			for (int i = 0; i < names.length; i++)
			{
				javax.swing.JToggleButton b = new javax.swing.JToggleButton(names[i], spans[i] == 0);
				long span = spans[i];
				b.setFocusable(false);
				b.addActionListener(e -> setRange(span));
				group.add(b);
				bar.add(b);
			}
			rangeTotal.setFont(FlipTheme.now.small());
			bar.add(Box.createHorizontalStrut(12));
			bar.add(rangeTotal);
			JPanel p = new JPanel(new BorderLayout(0, 6));
			p.setOpaque(false);
			p.add(bar, BorderLayout.NORTH);
			p.add(this, BorderLayout.CENTER);
			return p;
		}

		void setRange(long ms)
		{
			rangeMs = ms;
			filter();
		}

		private void filter()
		{
			long from = rangeMs <= 0 ? Long.MIN_VALUE : System.currentTimeMillis() - rangeMs;
			List<GameSnapshot.Done> p = new ArrayList<>();
			long sum = 0;
			int lost = 0;
			for (GameSnapshot.Done d : all)
			{
				if (d.at >= from)
				{
					p.add(d);
					sum += d.profit;
					lost += d.profit < 0 ? 1 : 0;
				}
			}
			points = p;
			rangeTotal.setText(String.format("%,d flips · %d lost · %s%s", p.size(), lost, sum >= 0 ? "+" : "-",
				FlipBrain.gp(Math.abs(sum))));
			rangeTotal.setForeground(sum >= 0 ? GREEN : RED);
			repaint();
		}

		ProfitGraph()
		{
			setBackground(CARD);
			addMouseMotionListener(new MouseMotionAdapter()
			{
				@Override
				public void mouseMoved(MouseEvent e)
				{
					hoverX = e.getX();
					repaint();
				}
			});
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseExited(MouseEvent e)
				{
					hoverX = -1;
					repaint();
				}
			});
		}

		void show(List<GameSnapshot.Done> history)
		{
			List<GameSnapshot.Done> p = new ArrayList<>(history);
			p.sort(Comparator.comparingLong(d -> d.at));
			all = p;
			filter();
		}

		@Override
		protected void paintComponent(Graphics g0)
		{
			super.paintComponent(g0);
			Graphics2D g = (Graphics2D) g0;
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setFont(FlipTheme.now.small());
			FontMetrics fm = g.getFontMetrics();
			int left = 70;
			int right = getWidth() - 16;
			int top = 16;
			int bottom = getHeight() - 28;
			if (points.isEmpty() || right <= left || bottom <= top)
			{
				g.setColor(DIM);
				g.drawString("No finished flips yet - each sell shows here as it fills", left, top + 20);
				return;
			}
			long[] cum = new long[points.size()];
			long run = 0;
			long min = 0;
			long max = 0;
			for (int i = 0; i < cum.length; i++)
			{
				run += points.get(i).profit;
				cum[i] = run;
				min = Math.min(min, run);
				max = Math.max(max, run);
			}
			if (max == min)
			{
				max = min + 1;
			}
			// horizontal grid with gp labels, the zero line brighter
			for (int k = 0; k <= 4; k++)
			{
				long v = min + (max - min) * k / 4;
				int y = yOf(v, min, max, top, bottom);
				g.setColor(GRID);
				g.drawLine(left, y, right, y);
				g.setColor(DIM);
				String lab = (v >= 0 ? "+" : "-") + FlipBrain.gp(Math.abs(v));
				g.drawString(lab, left - 6 - fm.stringWidth(lab), y + 4);
			}
			int zy = yOf(0, min, max, top, bottom);
			g.setColor(new Color(255, 255, 255, 90));
			g.drawLine(left, zy, right, zy);
			// x by flip order (times bunch up, and a night has no flips) — with the time under it (user, 2026-11-01:
			// "i wish there was time on this graph"), every break of an hour or more marked, and a hover per flip
			timeAxis(g, fm, left, right, top, bottom);
			Path2D.Double line = new Path2D.Double();
			for (int i = 0; i < cum.length; i++)
			{
				double x = xOf(i, cum.length, left, right);
				double y = yOf(cum[i], min, max, top, bottom);
				if (i == 0)
				{
					line.moveTo(x, y);
				}
				else
				{
					line.lineTo(x, y);
				}
			}
			g.setStroke(new BasicStroke(2f));
			g.setColor(run >= 0 ? GREEN : RED);
			g.draw(line);
			for (int i = 0; i < cum.length; i++)
			{
				double x = xOf(i, cum.length, left, right);
				int y = yOf(cum[i], min, max, top, bottom);
				g.setColor(points.get(i).profit >= 0 ? GREEN : RED);
				g.fillOval((int) x - 3, y - 3, 6, 6);
			}
			hover(g, fm, cum, left, right, top, bottom, min, max);
		}

		/** Times under the graph (the date where it changes), and a dashed line with its length at each break. */
		private void timeAxis(Graphics2D g, FontMetrics fm, int left, int right, int top, int bottom)
		{
			int n = points.size();
			SimpleDateFormat day = new SimpleDateFormat("MMM d HH:mm");
			SimpleDateFormat hm = new SimpleDateFormat("HH:mm");
			SimpleDateFormat date = new SimpleDateFormat("yyyyMMdd");
			int ticks = Math.max(2, Math.min(n, (right - left) / 110));
			int lastEnd = Integer.MIN_VALUE;
			String lastDate = null;
			for (int k = 0; k < ticks && n > 0; k++)
			{
				int i = n == 1 ? 0 : (int) Math.round((double) k * (n - 1) / (ticks - 1));
				long at = points.get(i).at;
				int x = (int) xOf(i, n, left, right);
				String d = at > 0 ? date.format(new Date(at)) : "";
				String lab = at <= 0 ? "earlier" : d.equals(lastDate) ? hm.format(new Date(at)) : day.format(new Date(at));
				int w = fm.stringWidth(lab);
				int lx = Math.max(left, Math.min(right - w, x - w / 2));
				if (lx <= lastEnd + 8)
				{
					continue;             // would overlap the label before
				}
				g.setColor(GRID);
				g.drawLine(x, bottom, x, bottom + 4);
				g.setColor(DIM);
				g.drawString(lab, lx, bottom + 18);
				lastEnd = lx + w;
				lastDate = at > 0 ? d : lastDate;
			}
			Stroke solid = g.getStroke();
			g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{4f, 4f}, 0f));
			int labelEnd = Integer.MIN_VALUE;
			for (int i = 1; i < n; i++)
			{
				long a = points.get(i - 1).at;
				long b = points.get(i).at;
				if (a <= 0 || b - a < BREAK_MS)
				{
					continue;
				}
				int x = (int) ((xOf(i - 1, n, left, right) + xOf(i, n, left, right)) / 2);
				g.setColor(new Color(255, 255, 255, 60));
				g.drawLine(x, top, x, bottom);
				String lab = FlipSidebarSlotOverlay.clock(b - a) + " break";
				int w = fm.stringWidth(lab);
				int lx = Math.min(right - w, x + 4);
				if (lx > labelEnd + 6)
				{
					g.setColor(DIM);
					g.drawString(lab, lx, top + 10);
					labelEnd = lx + w;
				}
			}
			g.setStroke(solid);
		}

		/** The flip under the mouse: when, what, its profit and the running total. */
		private void hover(Graphics2D g, FontMetrics fm, long[] cum, int left, int right, int top, int bottom, long min, long max)
		{
			int n = points.size();
			if (hoverX < left - 6 || hoverX > right + 6 || n == 0)
			{
				return;
			}
			int i = n == 1 ? 0 : (int) Math.round((double) (hoverX - left) * (n - 1) / Math.max(1, right - left));
			i = Math.max(0, Math.min(n - 1, i));
			GameSnapshot.Done d = points.get(i);
			int cx = (int) xOf(i, n, left, right);
			g.setColor(new Color(255, 255, 255, 80));
			g.drawLine(cx, top, cx, bottom);
			int cy = yOf(cum[i], min, max, top, bottom);
			g.setColor(Color.WHITE);
			g.drawOval(cx - 5, cy - 5, 10, 10);
			String[] lines = {
				d.at > 0 ? new SimpleDateFormat("EEE MMM d HH:mm").format(new Date(d.at)) : "before times were kept",
				(d.name == null ? "?" : d.name) + String.format(" · %,d", d.qty),
				(d.profit >= 0 ? "+" : "") + String.format("%,d", d.profit),
				"total " + (cum[i] >= 0 ? "+" : "-") + FlipBrain.gp(Math.abs(cum[i]))};
			Color[] cs = {Color.WHITE, Color.WHITE, d.profit >= 0 ? GREEN : RED, DIM};
			int bw = 0;
			for (String l : lines)
			{
				bw = Math.max(bw, fm.stringWidth(l));
			}
			int bx = cx + 10 + bw + 12 > right ? cx - bw - 22 : cx + 10;
			g.setColor(new Color(20, 20, 20, 220));
			g.fillRect(bx, top + 6, bw + 12, lines.length * 14 + 8);
			for (int k = 0; k < lines.length; k++)
			{
				g.setColor(cs[k]);
				g.drawString(lines[k], bx + 6, top + 20 + k * 14);
			}
		}

		private static double xOf(int i, int n, int left, int right)
		{
			return left + (right - left) * (n == 1 ? 0.5 : (double) i / (n - 1));
		}

		private static int yOf(long v, long min, long max, int top, int bottom)
		{
			return bottom - (int) ((bottom - top) * (double) (v - min) / (max - min));
		}
	}
}
