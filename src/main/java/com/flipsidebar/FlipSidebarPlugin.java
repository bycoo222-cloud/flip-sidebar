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
import com.google.gson.JsonParseException;
import com.google.inject.Provides;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.awt.Rectangle;
import java.nio.file.attribute.FileTime;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.FontID;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.VarClientIntChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Filepath;
import net.runelite.client.util.ImageUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Grand Exchange flipping inside RuneLite.
 *
 * <p>WHAT to flip comes from one of two places:</p>
 * <ul>
 *   <li>"Live prices" ON: the plugin's own brain (FlipBrain) asks the OSRS Wiki for prices, finds
 *       flips and shows them in its side panel (FlipSidebarPanel) — nothing else to run;</li>
 *   <li>OFF: the Flip Sidebar desktop program writes its card to
 *       {@code plugin-data/flip-sidebar/suggestion.json}, and the plugin makes no network calls.</li>
 * </ul>
 * <p>Either way the plugin draws the card (FlipSidebarOverlay), highlights the next thing to click
 * (FlipSidebarHighlightOverlay), puts the item in the GE search's "last searched" row so it is one
 * click, and remembers what your offers bought (TradeLog) so it can say what to sell.</p>
 *
 * <p>In the GE's quantity / price box, a line to click that puts the number in (as Flipping Copilot
 * does); optionally a key that does the same. Pressing Enter is always the user's.</p>
 */
@PluginDescriptor(
	name = "Flip Sidebar",
	internalName = "flip-sidebar",
	description = "Finds Grand Exchange flips and guides each offer: side panel, highlights, price chart",
	tags = {"flipping", "grand exchange", "ge", "merching", "money"}
)
public class FlipSidebarPlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(FlipSidebarPlugin.class);
	private static final String SUGGESTION_FILE = "suggestion.json";
	/** The chart: the plugin ASKS the sidebar for an item's prices; it never fetches them itself. */
	private static final String CHART_REQUEST_FILE = "chart_request.json";
	private static final String CHART_FILE = "chart.json";
	/** With live prices, the open item's chart is fetched again this often. */
	private static final long CHART_REFRESH_MS = 60_000;
	/** Saved per account (RuneScape profile): what the offers bought, and the bank's coins. */
	private static final String TRADE_LOG_KEY = "tradeLog";
	private static final String BANK_COINS_KEY = "bankCoins";
	private static final String SESSION_KEY = "sessionStart";
	private static final String FLIPS_CSV = "flips.csv";
	private static final int PLATINUM_VALUE = 1000;
	private static final String MODIFY = "Modify";
	/** The set-up screen opens a tick or two after "Modify offer"; gone after this, it was not. */
	private static final int MODIFY_OPEN_TICKS = 5;
	private static final int[] GE_SLOTS = {
		InterfaceID.GeOffers.INDEX_0, InterfaceID.GeOffers.INDEX_1, InterfaceID.GeOffers.INDEX_2,
		InterfaceID.GeOffers.INDEX_3, InterfaceID.GeOffers.INDEX_4, InterfaceID.GeOffers.INDEX_5,
		InterfaceID.GeOffers.INDEX_6, InterfaceID.GeOffers.INDEX_7,
	};

	/** An offer being re-placed after "Modify offer" (see onMenuOptionClicked). */
	static final class Modify
	{
		/** 1-8. */
		final int slot;
		final int itemId;
		final String name;
		final boolean buy;
		final long oldPrice;
		/** The price the advice wanted (RAISE or LOWER); 0 = no advice, the user's own choice. */
		final long target;
		/** What was left to fill — what the re-placed offer should be for. */
		final int quantity;
		final int clickedTick;
		boolean opened;

		Modify(int slot, int itemId, String name, boolean buy, long oldPrice, long target, int quantity, int clickedTick)
		{
			this.slot = slot;
			this.itemId = itemId;
			this.name = name;
			this.buy = buy;
			this.oldPrice = oldPrice;
			this.target = target;
			this.quantity = quantity;
			this.clickedTick = clickedTick;
		}
	}
	/** The set-up screen's Confirm button — its being on screen means set-up is open. */
	private static final int SETUP_CONFIRM = 58;

	/** The chatbox mode while the GE item search is open. */
	private static final int MESLAYER_MODE_GE_SEARCH = 14;
	/**
	 * The game's own script that selects an item from the GE search results, with the
	 * arguments the game itself attaches to a result row (mouse click / Enter key).
	 */
	private static final int SCRIPT_GE_SEARCH_SELECT = 754;
	private static final int SEARCH_SELECT_OP_ARG = 84;
	private static final int SEARCH_SELECT_KEY_ARG = -2147483640;
	/** Children of the search results: the "last searched" row is the first four. */
	private static final int ROW = 0;
	private static final int ROW_LABEL = 1;
	private static final int ROW_NAME = 2;
	private static final int ROW_ICON = 3;
	private static final String ROW_TEXT = "Flip Sidebar:";
	/** Row geometry and look — the same as the game's own "last searched" row. */
	private static final int ROW_X = 114;
	private static final int ROW_WIDTH = 256;
	private static final int ROW_HEIGHT = 32;
	private static final int LABEL_WIDTH = 95;
	private static final int ICON_X = 214;
	private static final int ICON_WIDTH = 36;
	private static final int NAME_X = 254;
	private static final int NAME_WIDTH = 116;
	private static final int ROW_FONT = 495;
	private static final int ICON_ROTATION_X = 550;
	private static final int ICON_ZOOM = 1031;
	/** Widget opacity: 255 is fully clear; the row darkens a little under the mouse. */
	private static final int OPACITY_CLEAR = 255;
	private static final int OPACITY_HOVER = 200;

	private static final String TITLE_BUY_QTY = "How many do you wish to buy?";
	private static final String TITLE_SELL_QTY = "How many do you wish to sell?";
	private static final String TITLE_PRICE = "Set a price for each item:";
	/** The click-to-fill line in the GE's number box: where, and its colours (dark on the light box). */
	private static final int FILL_X = 10;
	private static final int FILL_Y = 40;
	private static final int FILL_HEIGHT = 20;
	private static final int FILL_TITLE_SHIFT = 7;
	private static final int FILL_TEXT = 0x8A3A00;
	private static final int FILL_TEXT_HOVER = 0xFF7A00;
	private static final int PROMPT_NONE = 0;
	private static final int PROMPT_QUANTITY = 1;
	private static final int PROMPT_PRICE = 2;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ItemManager itemManager;

	@Inject
	private KeyManager keyManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private FlipSidebarOverlay overlay;

	@Inject
	private FlipSidebarHighlightOverlay highlightOverlay;

	@Inject
	private FlipSidebarChartOverlay chartOverlay;

	@Inject
	private FlipSidebarSlotOverlay slotOverlay;

	@Inject
	private FlipSidebarItemOverlay itemOverlay;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private FlipSidebarConfig config;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private Gson gson;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ConfigManager configManager;

	@Inject
	private FlipBrain brain;

	private FlipSidebarPanel panel;
	private NavigationButton navButton;

	/** The Flip Sidebar program's card, read from suggestion.json (live prices off). */
	private volatile Suggestion suggestion;
	/** This account's trade log and bank coins — loaded per RuneScape profile. Client thread. */
	private TradeLog tradeLog;
	private String tradeLogProfile;
	private long bankCoins = -1;
	/** This account's session start (epoch ms), saved; the panel's "new session" resets it. */
	private long sessionStart;
	private boolean snapshotDirty;
	/** See {@link Modify}. */
	private Modify modifying;
	/** The user's own pick open in the set-up screen, and its price once fetched (FlipBrain.priceItem). */
	private volatile int pricedItem = -1;
	private volatile boolean pricedSell;
	private volatile Suggestion manual;
	private ScheduledFuture<?> poller;
	private FileTime lastRead;

	private volatile PriceHistory history;
	private FileTime lastChartRead;
	private volatile String chartRange = "1d";
	private int requestedItem = -1;
	private String requestedRange;
	private long requestedAt;

	/** Which GE number prompt is open (read on the client thread, used by the key listener). */
	private volatile int prompt = PROMPT_NONE;
	private boolean offersDirty;
	private boolean inventoryDirty;
	private final Map<Integer, String> names = new HashMap<>();

	private final KeyListener fillKeyListener = new KeyListener()
	{
		private boolean swallowTyped;

		@Override
		public void keyTyped(KeyEvent e)
		{
			if (swallowTyped)
			{
				e.consume();      // the key that filled the box must not ALSO type a letter
				swallowTyped = false;
			}
		}

		@Override
		public void keyPressed(KeyEvent e)
		{
			if (!config.fillKeyEnabled() || prompt == PROMPT_NONE || !config.fillKey().matches(e))
			{
				return;
			}
			e.consume();
			swallowTyped = true;
			final int kind = prompt;
			clientThread.invoke(() -> fillPrompt(kind));
		}

		@Override
		public void keyReleased(KeyEvent e)
		{
		}
	};

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		overlayManager.add(highlightOverlay);
		overlayManager.add(chartOverlay);
		overlayManager.add(slotOverlay);
		overlayManager.add(itemOverlay);
		keyManager.registerKeyListener(fillKeyListener);
		mouseManager.registerMouseListener(chartOverlay.mouse());
		// file reads stay OFF the client thread; the sidebar rewrites the file when its card changes
		poller = executor.scheduleWithFixedDelay(this::readSuggestion, 0, 1, TimeUnit.SECONDS);
		offersDirty = true;
		inventoryDirty = true;
		snapshotDirty = true;

		FlipSidebarPanel p = new FlipSidebarPanel(brain, configManager, config, itemManager, this);
		panel = p;
		navButton = NavigationButton.builder()
			.tooltip("Flip Sidebar")
			.icon(ImageUtil.loadImageResource(getClass(), "panel_icon.png"))
			.priority(7)
			.panel(p)
			.build();
		clientToolbar.addNavigation(navButton);
		brain.setIgnored(FlipSidebarConfig.parseIgnored(config.ignoredOffers()));
		brain.start(v -> SwingUtilities.invokeLater(() -> p.show(v)));
		log.debug("Flip Sidebar started");
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		overlayManager.remove(highlightOverlay);
		overlayManager.remove(chartOverlay);
		overlayManager.remove(slotOverlay);
		overlayManager.remove(itemOverlay);
		keyManager.unregisterKeyListener(fillKeyListener);
		mouseManager.unregisterMouseListener(chartOverlay.mouse());
		if (poller != null)
		{
			poller.cancel(true);
			poller = null;
		}
		brain.stop();
		clientToolbar.removeNavigation(navButton);
		navButton = null;
		panel.stop();
		panel = null;
		tradeLog = null;
		tradeLogProfile = null;
		bankCoins = -1;
		sessionStart = 0;
		modifying = null;
		pricedItem = -1;
		manual = null;
		suggestion = null;
		lastRead = null;
		history = null;
		lastChartRead = null;
		requestedItem = -1;
		prompt = PROMPT_NONE;
		names.clear();
		log.debug("Flip Sidebar stopped");
	}

	/** The card to show: the plugin's own with live prices on, else the Flip Sidebar program's. */
	Suggestion getSuggestion()
	{
		return config.livePrices() ? brain.card() : suggestion;
	}

	/** GE slots (1-8) to cancel, from the offer advice; empty with live prices off. */
	Set<Integer> cancelSlots()
	{
		FlipBrain.View v = brain.view();
		return v == null ? Set.of() : v.cancelSlots;
	}

	/** GE slots (1-8) to re-price (RAISE or LOWER), from the offer advice; empty with live prices off. */
	Set<Integer> adjustSlots()
	{
		FlipBrain.View v = brain.view();
		return v == null ? Set.of() : v.adjustSlots;
	}

	/**
	 * The offer being re-placed through the game's "Modify offer", while its set-up screen is open;
	 * null otherwise. Client thread only.
	 */
	Modify modifying()
	{
		Modify m = modifying;
		return m != null && setupItem() == m.itemId ? m : null;
	}

	/** {placed, last fill} epoch ms of the offer in GE slot {@code slot} (0-7); null if unknown. Client thread. */
	long[] slotTimes(int slot)
	{
		TradeLog t = tradeLog();
		return t == null ? null : t.times(slot);
	}

	/** The panel's "new session": session stats count from now. Any thread. */
	void newSession()
	{
		clientThread.invoke(() ->
		{
			if (tradeLog() == null)
			{
				return;
			}
			sessionStart = System.currentTimeMillis();
			configManager.setRSProfileConfiguration(FlipSidebarConfig.GROUP, SESSION_KEY, sessionStart);
			snapshotDirty = true;
		});
	}

	/**
	 * Every kept flip to {@code plugin-data/flip-sidebar/<character>/flips.csv} (time, item, quantity,
	 * buy, sell, profit after tax). {@code done} hears where, or why not. Any thread.
	 */
	void exportFlips(Consumer<String> done)
	{
		clientThread.invoke(() ->
		{
			TradeLog t = tradeLog();
			if (t == null)
			{
				done.accept("log in first");
				return;
			}
			StringBuilder csv = new StringBuilder("time,item,quantity,buy each,sell each,profit after tax\n");
			SimpleDateFormat when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
			List<TradeLog.Flip> flips = new ArrayList<>(t.flips());
			Collections.reverse(flips);                      // oldest first in a spreadsheet
			for (TradeLog.Flip f : flips)
			{
				String name = itemName(f.itemId);
				csv.append(f.at > 0 ? when.format(new Date(f.at)) : "").append(',')
					.append('"').append(name == null ? String.valueOf(f.itemId) : name.replace("\"", "\"\"")).append('"')
					.append(',').append(f.qty).append(',').append(f.buyEach).append(',').append(f.sellEach)
					.append(',').append(f.profit).append('\n');
			}
			String player = playerName();
			write(player, FLIPS_CSV, csv.toString());
			done.accept("Saved " + flips.size() + " flips to plugin-data/flip-sidebar/" + player + "/" + FLIPS_CSV);
		});
	}

	/** {left, limit, resets at (0 = none open)} of an item's GE buy limit; null when unknown. */
	long[] limitNow(int itemId)
	{
		return config.livePrices() ? brain.limitNow(itemId) : null;
	}

	/** {used, resets at, limit} of an item's open 4-hour buy-limit window; null when none. */
	long[] limitInfo(int itemId)
	{
		return brain.limitInfo(itemId);
	}

	/** Today's 5-minute average insta-buy for an item; 0 when unknown (live prices off). */
	long market(int itemId)
	{
		return quote(itemId)[1];
	}

	/** Today's 5-minute average {insta-sell, insta-buy} (the hour's if none); zeros with live prices off. */
	long[] quote(int itemId)
	{
		return config.livePrices() ? brain.quote(itemId) : new long[]{0, 0};
	}

	/** The unnoted item for a noted one (and itself otherwise). Client thread. */
	int canonical(int itemId)
	{
		return itemManager.canonicalize(itemId);
	}

	/** What was paid each for an item still held, or 0 when not on record. Client thread. */
	long paidEach(int itemId)
	{
		TradeLog t = tradeLog();
		return t == null ? 0 : t.paidEach(itemId);
	}

	/** Where the flip box was last drawn, so the chart can stay off it; null if not drawn. */
	Rectangle boxBounds()
	{
		Rectangle b = overlay.getBounds();
		return getSuggestion() == null || b == null || b.isEmpty() ? null : b;
	}

	/** True while the Grand Exchange offers window is on screen. Client thread only. */
	boolean isGeOpen()
	{
		Widget ge = client.getWidget(InterfaceID.GeOffers.UNIVERSE);
		return ge != null && !ge.isHidden();
	}

	/** The item in the offer set-up screen, or -1 when set-up is not open. Client thread only. */
	int setupItem()
	{
		Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
		Widget confirm = setup == null ? null : setup.getChild(SETUP_CONFIRM);
		if (!isGeOpen() || confirm == null || confirm.isHidden())
		{
			return -1;
		}
		int item = client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
		return item > 0 ? item : -1;
	}

	/** Where the GE window is on screen, or null. Client thread only. */
	Rectangle geBounds()
	{
		Widget ge = client.getWidget(InterfaceID.GeOffers.UNIVERSE);
		return ge == null || ge.isHidden() ? null : ge.getBounds();
	}

	PriceHistory getHistory()
	{
		return history;
	}

	String getChartRange()
	{
		return chartRange;
	}

	void setChartRange(String range)
	{
		chartRange = range;
	}

	/** True when prices were asked for a while ago and none have come back. */
	boolean historyWaitedLong()
	{
		return requestedAt > 0 && System.currentTimeMillis() - requestedAt > 6000;
	}

	/** An item's name, cached. Client thread only. */
	String itemName(int itemId)
	{
		if (itemId <= 0)
		{
			return null;
		}
		return names.computeIfAbsent(itemId, id -> itemManager.getItemComposition(id).getName());
	}

	// ── reading the card ────────────────────────────────────────────────────────

	private void readSuggestion()
	{
		if (config.livePrices())
		{
			return;                   // the plugin's own brain makes the card and the chart
		}
		readChart();
		try
		{
			Filepath file = getPluginDirectory().joinSegment(SUGGESTION_FILE);
			if (!file.exists())
			{
				suggestion = null;
				lastRead = null;
				return;
			}
			FileTime modified = file.getLastModifiedTime();
			if (modified.equals(lastRead))
			{
				return;
			}
			try (Reader reader = file.openBufferedReader())
			{
				suggestion = gson.fromJson(reader, Suggestion.class);
			}
			lastRead = modified;
		}
		catch (IOException | JsonParseException | IllegalArgumentException e)
		{
			// a half-written file is retried next second; the sidebar writes atomically anyway
			log.debug("Flip Sidebar: could not read {}", SUGGESTION_FILE, e);
		}
	}

	private void readChart()
	{
		try
		{
			Filepath file = getPluginDirectory().joinSegment(CHART_FILE);
			if (!file.exists())
			{
				return;
			}
			FileTime modified = file.getLastModifiedTime();
			if (modified.equals(lastChartRead))
			{
				return;
			}
			try (Reader reader = file.openBufferedReader())
			{
				history = gson.fromJson(reader, PriceHistory.class);
			}
			lastChartRead = modified;
		}
		catch (IOException | JsonParseException | IllegalArgumentException e)
		{
			log.debug("Flip Sidebar: could not read {}", CHART_FILE, e);
		}
	}

	/**
	 * The open item's prices for the chart: fetched by the plugin with live prices on (again every
	 * minute), else asked of the sidebar program whenever the item or the range changes.
	 */
	private void requestChart()
	{
		int item = config.priceChart() ? setupItem() : -1;
		String range = chartRange;
		if (item <= 0)
		{
			return;
		}
		boolean same = item == requestedItem && range.equals(requestedRange);
		if (config.livePrices())
		{
			if (same && System.currentTimeMillis() - requestedAt < CHART_REFRESH_MS)
			{
				return;
			}
			requestedItem = item;
			requestedRange = range;
			requestedAt = System.currentTimeMillis();
			brain.chart(item, range, h -> history = h);
			return;
		}
		if (same)
		{
			return;
		}
		requestedItem = item;
		requestedRange = range;
		requestedAt = System.currentTimeMillis();
		Map<String, Object> req = new LinkedHashMap<>();
		req.put("itemId", item);
		req.put("range", range);
		req.put("asked", Instant.now().getEpochSecond());
		write(null, CHART_REQUEST_FILE, gson.toJson(req));
	}

	// ── the GE item search: the card's item as the one-click "last searched" row ──

	@Subscribe
	public void onVarClientIntChanged(VarClientIntChanged event)
	{
		if (event.getIndex() != VarClientID.MESLAYERMODE)
		{
			return;
		}
		// run after the game has finished building the chatbox for the new mode
		clientThread.invokeLater(() ->
		{
			updatePrompt();
			if (config.quickPick() && client.getVarcIntValue(VarClientID.MESLAYERMODE) == MESLAYER_MODE_GE_SEARCH)
			{
				fillSearchRow();
			}
			if (config.clickToFill() && prompt != PROMPT_NONE)
			{
				addFillLine();
			}
		});
	}

	private void fillSearchRow()
	{
		Suggestion s = getSuggestion();
		Widget results = client.getWidget(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
		if (s == null || !"BUY".equals(s.action) || results == null)
		{
			return;
		}
		// Ask the GAME whether it drew its own "last searched" row: it does only when an item was
		// searched before AND 'Show last searched' is ticked. Counting the results' children is
		// wrong — the "Start typing…" text and the checkbox live in the same list, so it is never
		// empty. (That is exactly why v0.2.1 built nothing on 2026-10-31.)
		boolean gameRow = client.getVarpValue(VarPlayerID.GE_LAST_SEARCHED) != -1
			&& client.getVarbitValue(VarbitID.DISABLE_LAST_SEARCHED) == 0;
		Widget label = results.getChild(ROW_LABEL);
		boolean ours = label != null && ROW_TEXT.equals(label.getText());
		if (!gameRow && !ours)
		{
			buildSearchRow(results, s);
			return;
		}
		// Otherwise REUSE the row that is there — the game's, or the one this plugin built.
		Widget row = results.getChild(ROW);
		Widget name = results.getChild(ROW_NAME);
		Widget icon = results.getChild(ROW_ICON);
		if (row == null || label == null || name == null || icon == null)
		{
			return;
		}
		row.setHasListener(true);
		row.setOnOpListener(SCRIPT_GE_SEARCH_SELECT, s.itemId, SEARCH_SELECT_OP_ARG);
		row.setOnKeyListener(SCRIPT_GE_SEARCH_SELECT, s.itemId, SEARCH_SELECT_KEY_ARG);
		row.setName("<col=ff9040>" + s.name + "</col>");
		row.setAction(0, "Select");
		row.revalidate();
		label.setText(ROW_TEXT);
		label.revalidate();
		name.setText(s.name);
		name.revalidate();
		icon.setItemId(s.itemId);
		icon.revalidate();
	}

	private void buildSearchRow(Widget results, Suggestion s)
	{
		Widget row = results.createChild(ROW, WidgetType.RECTANGLE);
		row.setFilled(true);
		row.setTextColor(0xFFFFFF);
		row.setOpacity(OPACITY_CLEAR);
		row.setOriginalX(ROW_X);
		row.setOriginalY(0);
		row.setOriginalWidth(ROW_WIDTH);
		row.setOriginalHeight(ROW_HEIGHT);
		row.setHasListener(true);
		row.setOnOpListener(SCRIPT_GE_SEARCH_SELECT, s.itemId, SEARCH_SELECT_OP_ARG);
		row.setOnKeyListener(SCRIPT_GE_SEARCH_SELECT, s.itemId, SEARCH_SELECT_KEY_ARG);
		row.setName("<col=ff9040>" + s.name + "</col>");
		row.setAction(0, "Select");
		row.setOnMouseOverListener((JavaScriptCallback) ev -> row.setOpacity(OPACITY_HOVER));
		row.setOnMouseLeaveListener((JavaScriptCallback) ev -> row.setOpacity(OPACITY_CLEAR));
		row.revalidate();

		Widget label = results.createChild(ROW_LABEL, WidgetType.TEXT);
		label.setText(ROW_TEXT);
		label.setFontId(ROW_FONT);
		label.setOriginalX(ROW_X);
		label.setOriginalY(0);
		label.setOriginalWidth(LABEL_WIDTH);
		label.setOriginalHeight(ROW_HEIGHT);
		label.setYTextAlignment(1);
		label.revalidate();

		Widget name = results.createChild(ROW_NAME, WidgetType.TEXT);
		name.setText(s.name);
		name.setFontId(ROW_FONT);
		name.setOriginalX(NAME_X);
		name.setOriginalY(0);
		name.setOriginalWidth(NAME_WIDTH);
		name.setOriginalHeight(ROW_HEIGHT);
		name.setYTextAlignment(1);
		name.revalidate();

		Widget icon = results.createChild(ROW_ICON, WidgetType.GRAPHIC);
		icon.setItemId(s.itemId);
		icon.setItemQuantity(1);
		icon.setItemQuantityMode(0);
		icon.setRotationX(ICON_ROTATION_X);
		icon.setModelZoom(ICON_ZOOM);
		icon.setBorderType(1);
		icon.setOriginalX(ICON_X);
		icon.setOriginalY(0);
		icon.setOriginalWidth(ICON_WIDTH);
		icon.setOriginalHeight(ROW_HEIGHT);
		icon.revalidate();
	}

	/** The one-click row, if the plugin filled it in — for the highlight. Client thread only. */
	Widget filledSearchRow()
	{
		Widget results = client.getWidget(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
		if (results == null)
		{
			return null;
		}
		Widget label = results.getChild(ROW_LABEL);
		return label != null && ROW_TEXT.equals(label.getText()) ? results.getChild(ROW) : null;
	}

	// ── the optional fill key ───────────────────────────────────────────────────

	private void updatePrompt()
	{
		Widget title = client.getWidget(InterfaceID.Chatbox.MES_TEXT);
		String t = title == null || title.isHidden() ? null : title.getText();
		if (TITLE_BUY_QTY.equals(t) || TITLE_SELL_QTY.equals(t))
		{
			prompt = PROMPT_QUANTITY;
		}
		else if (TITLE_PRICE.equals(t))
		{
			prompt = PROMPT_PRICE;
		}
		else
		{
			prompt = PROMPT_NONE;
		}
	}

	/**
	 * The number for the open GE quantity / price box: what was left and the advice's price while
	 * an offer is re-placed after "Modify offer"; else the card's, for the card's item and kind of
	 * offer. 0 = nothing to offer.
	 */
	private long promptValue(int kind)
	{
		int item = client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
		boolean sellOffer = client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE) == 1;
		Modify m = modifying();
		if (m != null && m.itemId == item && m.buy != sellOffer)
		{
			return kind == PROMPT_QUANTITY ? m.quantity : m.target;
		}
		Suggestion s = offerSuggestion();
		return s == null || s.discouraged ? 0 : kind == PROMPT_QUANTITY ? s.quantity : s.price;
	}

	/** True when the open set-up screen is the card's item and kind of offer. Client thread. */
	private boolean isCardOffer(int item, boolean sellOffer)
	{
		Suggestion s = getSuggestion();
		return s != null && ("BUY".equals(s.action) || "SELL".equals(s.action)) && s.itemId == item
			&& sellOffer == "SELL".equals(s.action);
	}

	/**
	 * What the open set-up screen should be filled with: the card, for the card's item and kind of
	 * offer; else the price worked out for the user's own pick — null while it is being fetched, or
	 * with live prices off. Client thread only.
	 */
	Suggestion offerSuggestion()
	{
		int item = client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
		boolean sellOffer = client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE) == 1;
		if (item <= 0)
		{
			return null;
		}
		if (isCardOffer(item, sellOffer))
		{
			return getSuggestion();
		}
		Suggestion m = manual;
		return m != null && m.itemId == item && "SELL".equals(m.action) == sellOffer ? m : null;
	}

	/**
	 * The user opened an offer for something that is not the card: price THAT item — once per
	 * opening, then left alone while the screen is open (nothing changes while it is typed in).
	 */
	private void priceOwnPick(int setup)
	{
		boolean sellOffer = client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE) == 1;
		if (setup <= 0)
		{
			pricedItem = -1;
			manual = null;
			return;
		}
		if (!config.livePrices() || modifying() != null || isCardOffer(setup, sellOffer)
			|| (setup == pricedItem && sellOffer == pricedSell))
		{
			return;
		}
		pricedItem = setup;
		pricedSell = sellOffer;
		manual = null;
		int have = 0;
		ItemContainer inv = client.getItemContainer(InventoryID.INV);
		for (Item it : inv == null ? new Item[0] : inv.getItems())
		{
			if (it.getId() > 0 && itemManager.canonicalize(it.getId()) == setup)
			{
				have += it.getQuantity();
			}
		}
		long basis = 0;
		TradeLog t = tradeLog();
		for (long[] h : t == null ? List.<long[]>of() : t.holdings(System.currentTimeMillis()))
		{
			if (h[0] == setup)
			{
				basis = h[2];
			}
		}
		// (Selling what is still being bought is the player's call — no warning: user, 2026-10-31,
		// "if i did it thats fine ... too many warning might be a little much for most players".)
		brain.priceItem(setup, itemName(setup), !sellOffer, have, basis, q ->
		{
			if (pricedItem == q.itemId && pricedSell == "SELL".equals(q.action))
			{
				manual = q;
			}
		});
	}

	/** Put the card's / pick's number in the box. Enter is still the user's to press. */
	private void fillPrompt(int kind)
	{
		fillValue(promptValue(kind));
	}

	/** Put {@code value} in the open GE number box. Enter is still the user's to press. Client thread. */
	private void fillValue(long value)
	{
		Widget input = client.getWidget(InterfaceID.Chatbox.MES_TEXT2);
		if (input == null || value <= 0)
		{
			return;
		}
		input.setText(value + "*");
		client.setVarcStrValue(VarClientID.MESLAYERINPUT, String.valueOf(value));
	}

	/**
	 * A line in the GE's quantity / price box — click it and the number goes in; pressing Enter is
	 * still the user's. Flipping Copilot, on the Plugin Hub, does the same ("set to ... gp", its
	 * OfferEditor). User, 2026-10-31: *"how do we get a clickable box that puts in the number like co
	 * pilot."* Same place as Copilot's line, the box's title nudged down to make room.
	 */
	private void addFillLine()
	{
		final int kind = prompt;
		Widget layer = client.getWidget(InterfaceID.Chatbox.MES_LAYER);
		if (layer == null)
		{
			return;
		}
		boolean added = false;
		long value = promptValue(kind);
		if (value > 0)
		{
			added = fillLine(layer, FILL_Y, "Flip Sidebar: click to fill " + String.format("%,d", value)
				+ (kind == PROMPT_PRICE ? " gp" : ""), value, () -> fillPrompt(kind));
		}
		// ⭐ ANY buy offer's quantity box: the GE buy limit left, one click to fill it in. User, 2026-10-31:
		// "it should be just like when ... u tell me how many to buy and i click it. there should be the
		// same thing above it for buy limit. clickable that sets it to the ge buy limit."
		boolean buyOffer = client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE) != 1;
		long[] lim = kind == PROMPT_QUANTITY && buyOffer ? limitNow(client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH)) : null;
		if (lim != null)
		{
			int y = value > 0 ? FILL_Y - FILL_HEIGHT : FILL_Y;
			added |= lim[0] > 0
				? fillLine(layer, y, "Flip Sidebar: buy limit left " + String.format("%,d", lim[0]) + " - click to fill",
					lim[0], () -> fillValue(lim[0]))
				: fillLine(layer, y, "Flip Sidebar: buy limit reached - resets in "
					+ FlipSidebarSlotOverlay.clock(lim[2] - System.currentTimeMillis()), 0, null);
		}
		Widget title = client.getWidget(InterfaceID.Chatbox.MES_TEXT);
		if (added && title != null)
		{
			title.setOriginalY(title.getOriginalY() + FILL_TITLE_SHIFT);
			title.revalidate();
		}
	}

	/** One line in the GE number box at {@code y}: clickable to put {@code value} in, or plain text when {@code fill} is null. */
	private boolean fillLine(Widget layer, int y, String text, long value, Runnable fill)
	{
		Widget line = layer.createChild(-1, WidgetType.TEXT);
		line.setText(text);
		line.setTextColor(FILL_TEXT);
		line.setFontId(FontID.BOLD_12);
		line.setXTextAlignment(WidgetTextAlignment.LEFT);
		line.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
		line.setOriginalX(FILL_X);
		line.setOriginalY(y);
		line.setOriginalHeight(FILL_HEIGHT);
		line.setWidthMode(WidgetSizeMode.MINUS);
		line.setOriginalWidth(FILL_X);
		if (fill != null)
		{
			line.setHasListener(true);
			line.setAction(0, "Fill");
			line.setName("<col=ff9040>" + String.format("%,d", value) + "</col>");
			line.setOnOpListener((JavaScriptCallback) ev -> fill.run());
			line.setOnMouseOverListener((JavaScriptCallback) ev -> line.setTextColor(FILL_TEXT_HOVER));
			line.setOnMouseLeaveListener((JavaScriptCallback) ev -> line.setTextColor(FILL_TEXT));
		}
		line.revalidate();
		return true;
	}

	// ── your offers and coins, written for the sidebar ──────────────────────────

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		offersDirty = true;
		snapshotDirty = true;
		GrandExchangeOffer o = event.getOffer();
		GrandExchangeOfferState st = o.getState();
		if (st == GrandExchangeOfferState.EMPTY && client.getGameState() != GameState.LOGGED_IN)
		{
			return;                   // the client empties every slot while logging in — not a collect
		}
		TradeLog t = tradeLog();
		if (t != null && t.update(event.getSlot(), st == GrandExchangeOfferState.EMPTY, isBuy(st), o.getItemId(),
			o.getPrice(), o.getTotalQuantity(), o.getQuantitySold(), o.getSpent(), System.currentTimeMillis()))
		{
			saveTradeLog(t);
			flashDrops(t);
		}
	}

	/** Each sell fill just seen flashes its slot with the profit (FlipSidebarSlotOverlay). */
	private void flashDrops(TradeLog t)
	{
		for (long[] d : t.takeDrops())
		{
			slotOverlay.flash((int) d[0], d[1]);
		}
	}

	private void saveTradeLog(TradeLog t)
	{
		configManager.setRSProfileConfiguration(FlipSidebarConfig.GROUP, TRADE_LOG_KEY, gson.toJson(t));
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.INV)
		{
			inventoryDirty = true;
			snapshotDirty = true;
		}
		else if (event.getContainerId() == InventoryID.BANK)
		{
			long coins = coins(event.getItemContainer());
			if (tradeLog() != null && coins != bankCoins)
			{
				bankCoins = coins;
				configManager.setRSProfileConfiguration(FlipSidebarConfig.GROUP, BANK_COINS_KEY, coins);
				snapshotDirty = true;
			}
			if (config.exportState())
			{
				writeItems("bank.json", "bank", event.getItemContainer());
			}
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			brain.update(null);
		}
		snapshotDirty = true;
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		// another account: its own trade log and bank coins, and nothing on the card carries over
		tradeLogProfile = null;
		snapshotDirty = true;
		brain.accountChanged();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!FlipSidebarConfig.GROUP.equals(event.getGroup()) || event.getKey() == null)
		{
			return;
		}
		switch (event.getKey())
		{
			case "ignoredOffers":
				brain.setIgnored(FlipSidebarConfig.parseIgnored(config.ignoredOffers()));
				break;
			case "livePrices":
			case "riskLevel":
			case "adjustWindow":
			case "minProfit":
			case "maxCash":
			case "paused":
			case "blockedItems":
				brain.settingsChanged();
				SwingUtilities.invokeLater(() ->
				{
					if (panel != null)
					{
						panel.syncSettings();
					}
				});
				clientThread.invoke(() -> requestedItem = -1);      // the chart's source may have changed
				break;
			default:
				break;
		}
	}

	/**
	 * ⚠ The game's "Modify offer" CANCELS the offer, collects what it bought, and opens the set-up
	 * screen to place it again — only Confirm puts it back. In-game 2026-10-31 the plugin knew
	 * nothing of this: it kept highlighting the CARD's item search on that screen, and the user lost
	 * the offer (*"i went to adjust it and my offer dissapeared"*). So the click is caught here, while
	 * the offer is still in its slot: what it was, how much is left, and the price the advice wants.
	 */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		String option = event.getMenuOption();
		// "Modify offer" on a slot; the button inside an open offer may word it differently
		if (option == null || !option.startsWith(MODIFY) || !isGeOpen())
		{
			return;
		}
		int slot = -1;
		for (int i = 0; i < GE_SLOTS.length; i++)
		{
			if (event.getParam1() == GE_SLOTS[i])        // a widget option: param1 is the widget
			{
				slot = i + 1;
			}
		}
		if (slot < 0)
		{
			slot = client.getVarbitValue(VarbitID.GE_SELECTEDSLOT);    // the button inside an open offer
		}
		GrandExchangeOffer[] all = client.getGrandExchangeOffers();
		GrandExchangeOffer o = all == null || slot < 1 || slot > all.length ? null : all[slot - 1];
		if (o == null || o.getState() == GrandExchangeOfferState.EMPTY)
		{
			return;
		}
		long target = 0;
		FlipBrain.View v = brain.view();
		for (OfferAdvice a : v == null ? List.<OfferAdvice>of() : v.offers)
		{
			if (a.offer.slot == slot && a.adjust())
			{
				target = a.target;
			}
		}
		modifying = new Modify(slot, o.getItemId(), itemName(o.getItemId()), isBuy(o.getState()), o.getPrice(),
			target, o.getTotalQuantity() - o.getQuantitySold(), client.getTickCount());
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		requestChart();
		int setup = setupItem();
		brain.setSettingUp(setup);
		priceOwnPick(setup);
		Modify m = modifying;
		if (m != null)
		{
			if (setup == m.itemId)
			{
				m.opened = true;
			}
			else if (m.opened || client.getTickCount() - m.clickedTick > MODIFY_OPEN_TICKS)
			{
				modifying = null;     // confirmed, backed out of, or it never opened
			}
		}
		if (snapshotDirty && client.getGameState() == GameState.LOGGED_IN && client.getLocalPlayer() != null)
		{
			snapshotDirty = false;
			brain.update(snapshot());
		}
		// batched to at most one write per tick — the login burst fires all 8 slots at once
		if (!config.exportState() || client.getLocalPlayer() == null)
		{
			return;
		}
		if (offersDirty)
		{
			offersDirty = false;
			writeOffers();
		}
		if (inventoryDirty)
		{
			inventoryDirty = false;
			writeItems("inventory.json", "inventory", client.getItemContainer(InventoryID.INV));
		}
	}

	private void writeOffers()
	{
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		List<Map<String, Object>> slots = new ArrayList<>();
		for (int i = 0; offers != null && i < offers.length; i++)
		{
			GrandExchangeOffer o = offers[i];
			GrandExchangeOfferState st = o == null ? GrandExchangeOfferState.EMPTY : o.getState();
			Map<String, Object> slot = new LinkedHashMap<>();
			slot.put("slot", i + 1);
			slot.put("state", st.name());
			if (st != GrandExchangeOfferState.EMPTY)
			{
				slot.put("type", isBuy(st) ? "BUY" : "SELL");
				slot.put("item_id", o.getItemId());
				slot.put("item_name", itemName(o.getItemId()));
				slot.put("price", o.getPrice());
				slot.put("quantity_filled", o.getQuantitySold());
				slot.put("quantity_total", o.getTotalQuantity());
				slot.put("spent", o.getSpent());
				slot.put("complete", st == GrandExchangeOfferState.BOUGHT || st == GrandExchangeOfferState.SOLD);
				slot.put("cancelled", st == GrandExchangeOfferState.CANCELLED_BUY
					|| st == GrandExchangeOfferState.CANCELLED_SELL);
			}
			slots.add(slot);
		}
		Map<String, Object> doc = new LinkedHashMap<>();
		doc.put("exported_at", Instant.now().toString());
		doc.put("player_name", playerName());
		doc.put("source", "Flip Sidebar plugin");
		doc.put("slots", slots);
		write(playerName(), "ge_offers.json", gson.toJson(doc));
	}

	private void writeItems(String file, String kind, ItemContainer container)
	{
		if (container == null)
		{
			return;
		}
		List<Map<String, Object>> items = new ArrayList<>();
		Item[] all = container.getItems();
		for (int i = 0; i < all.length; i++)
		{
			if (all[i].getId() <= 0 || all[i].getQuantity() <= 0)
			{
				continue;
			}
			Map<String, Object> it = new LinkedHashMap<>();
			it.put("slot", i);
			it.put("id", all[i].getId());
			it.put("quantity", all[i].getQuantity());
			it.put("name", itemName(all[i].getId()));
			items.add(it);
		}
		Map<String, Object> doc = new LinkedHashMap<>();
		doc.put("exported_at", Instant.now().toString());
		doc.put("kind", kind);
		doc.put("items", items);
		write(playerName(), file, gson.toJson(doc));
	}

	// ── the brain's view of the game ────────────────────────────────────────────

	/** This account's trade log, (re)loaded when the RuneScape profile changes; null before login. */
	private TradeLog tradeLog()
	{
		String profile = configManager.getRSProfileKey();
		if (profile == null)
		{
			return null;
		}
		if (!profile.equals(tradeLogProfile))
		{
			TradeLog t = null;
			String json = configManager.getRSProfileConfiguration(FlipSidebarConfig.GROUP, TRADE_LOG_KEY);
			try
			{
				t = json == null ? null : gson.fromJson(json, TradeLog.class);
			}
			catch (JsonParseException e)
			{
				log.debug("Flip Sidebar: saved trade log unreadable - starting a new one", e);
			}
			tradeLog = t != null ? t : new TradeLog();
			String bank = configManager.getRSProfileConfiguration(FlipSidebarConfig.GROUP, BANK_COINS_KEY);
			try
			{
				bankCoins = bank == null ? -1 : Long.parseLong(bank);
			}
			catch (NumberFormatException e)
			{
				bankCoins = -1;
			}
			String session = configManager.getRSProfileConfiguration(FlipSidebarConfig.GROUP, SESSION_KEY);
			try
			{
				sessionStart = session == null ? 0 : Long.parseLong(session);
			}
			catch (NumberFormatException e)
			{
				sessionStart = 0;
			}
			if (sessionStart <= 0)
			{
				sessionStart = System.currentTimeMillis();
				configManager.setRSProfileConfiguration(FlipSidebarConfig.GROUP, SESSION_KEY, sessionStart);
			}
			tradeLogProfile = profile;
		}
		return tradeLog;
	}

	/** Coins, the inventory, the 8 offers and what is held — for FlipBrain. Client thread. */
	private GameSnapshot snapshot()
	{
		long now = System.currentTimeMillis();
		TradeLog t = tradeLog();
		ItemContainer inv = client.getItemContainer(InventoryID.INV);
		Map<Integer, Integer> counts = new HashMap<>();
		if (inv != null)
		{
			for (Item it : inv.getItems())
			{
				if (it.getId() > 0 && it.getQuantity() > 0)
				{
					// noted and unnoted together: the GE sells either
					counts.merge(itemManager.canonicalize(it.getId()), it.getQuantity(), Integer::sum);
				}
			}
		}
		List<GameSnapshot.Offer> offers = new ArrayList<>();
		GrandExchangeOffer[] all = client.getGrandExchangeOffers();
		boolean logChanged = false;
		for (int i = 0; all != null && i < all.length; i++)
		{
			GrandExchangeOffer o = all[i];
			GrandExchangeOfferState st = o == null ? GrandExchangeOfferState.EMPTY : o.getState();
			if (st == GrandExchangeOfferState.EMPTY)
			{
				continue;
			}
			// ⚠ catch fills whose event came before the account was known (logging in after a buy
			// finished offline): re-checking an unchanged slot changes nothing, and an EMPTY slot is
			// never cleared here — only its event does that — so nothing can be counted twice
			if (t != null && t.update(i, false, isBuy(st), o.getItemId(), o.getPrice(), o.getTotalQuantity(),
				o.getQuantitySold(), o.getSpent(), now))
			{
				logChanged = true;
			}
			long[] times = t == null ? null : t.times(i);
			offers.add(new GameSnapshot.Offer(i + 1, isBuy(st), o.getItemId(), itemName(o.getItemId()), o.getPrice(),
				o.getTotalQuantity(), o.getQuantitySold(),
				st == GrandExchangeOfferState.BOUGHT || st == GrandExchangeOfferState.SOLD,
				st == GrandExchangeOfferState.CANCELLED_BUY || st == GrandExchangeOfferState.CANCELLED_SELL,
				times == null ? 0 : times[0], times == null ? 0 : times[1]));
		}
		if (logChanged)
		{
			saveTradeLog(t);
			flashDrops(t);
		}
		List<GameSnapshot.Holding> holdings = new ArrayList<>();
		List<GameSnapshot.Done> history = new ArrayList<>();
		Map<Integer, long[]> limits = Map.of();
		if (t != null)
		{
			for (long[] h : t.holdings(now))
			{
				holdings.add(new GameSnapshot.Holding((int) h[0], itemName((int) h[0]), h[1], h[2], h[3]));
			}
			limits = t.limitUse(now);
			for (TradeLog.Flip f : t.flips())
			{
				history.add(new GameSnapshot.Done(f.itemId, itemName(f.itemId), f.qty, f.buyEach, f.sellEach,
					f.profit, f.at));
			}
		}
		return new GameSnapshot(coins(inv), bankCoins, counts, offers, holdings, limits, history, sessionStart);
	}

	private static boolean isBuy(GrandExchangeOfferState st)
	{
		return st == GrandExchangeOfferState.BUYING || st == GrandExchangeOfferState.BOUGHT
			|| st == GrandExchangeOfferState.CANCELLED_BUY;
	}

	/** Coins plus platinum tokens (1,000 each) in a container. */
	private static long coins(ItemContainer c)
	{
		long n = 0;
		if (c != null)
		{
			for (Item it : c.getItems())
			{
				if (it.getId() == ItemID.COINS)
				{
					n += it.getQuantity();
				}
				else if (it.getId() == ItemID.PLATINUM)
				{
					n += (long) it.getQuantity() * PLATINUM_VALUE;
				}
			}
		}
		return n;
	}

	private String playerName()
	{
		Player p = client.getLocalPlayer();
		return p == null || p.getName() == null ? "unknown" : p.getName();
	}

	/**
	 * Write OFF the client thread, atomically (tmp + move), under plugin-data/flip-sidebar/ —
	 * in the character's own folder when {@code player} is given.
	 */
	private void write(String player, String file, String json)
	{
		executor.execute(() ->
		{
			try
			{
				Filepath dir = player == null ? getPluginDirectory() : getPluginDirectory().joinSegment(player);
				dir.createDirectories();
				Filepath out = dir.joinSegment(file);
				Filepath tmp = dir.joinSegment(file + ".tmp");
				tmp.write(json.getBytes(StandardCharsets.UTF_8));
				try
				{
					tmp.moveTo(out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				}
				catch (IOException atomicFailed)
				{
					tmp.moveTo(out, StandardCopyOption.REPLACE_EXISTING);
				}
			}
			catch (IOException | IllegalArgumentException e)
			{
				log.debug("Flip Sidebar: could not write {}", file, e);
			}
		});
	}

	@Provides
	FlipSidebarConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(FlipSidebarConfig.class);
	}
}
