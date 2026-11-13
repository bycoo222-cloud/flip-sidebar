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
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.inject.Provides;
import java.awt.Component;
import java.awt.event.KeyEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.awt.Rectangle;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.EnumSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.FontID;
import net.runelite.api.GameState;
import net.runelite.api.WorldType;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuEntryAdded;
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
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.config.RuneLiteConfig;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.banktags.BankTagsPlugin;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Filepath;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Grand Exchange flipping inside RuneLite.
 *
 * <p>With "Live prices" on, the plugin's brain (FlipBrain) asks the OSRS Wiki for prices, finds flips
 * and shows them in its side panel (FlipSidebarPanel). Off (the default), it makes no network calls
 * and finds nothing.</p>
 * <p>The plugin draws the card (FlipSidebarOverlay), highlights the next thing to click
 * (FlipSidebarHighlightOverlay), puts the item in the GE search's "last searched" row so it is one
 * click, and remembers what your offers bought (TradeLog) so it can say what to sell.</p>
 *
 * <p>In the GE's quantity / price box, a line to click that puts the number in (as Flipping Copilot
 * does); optionally a key that does the same. Pressing Enter is always the user's.</p>
 */
@PluginDescriptor(
	name = "Flip Sidebar",
	internalName = "flip-sidebar",
	description = "Finds Grand Exchange flips, plans High Alchemy buys and guides each offer: side panel, highlights",
	tags = {"flipping", "grand exchange", "ge", "merching", "money", "alch", "high alch"}
)
// Bank Tags (part of RuneLite) shares its TagManager only with plugins that name it here: the "holding" bank tab
@PluginDependency(BankTagsPlugin.class)
public class FlipSidebarPlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(FlipSidebarPlugin.class);
	/** With live prices, the open item's chart is fetched again this often. */
	private static final long CHART_REFRESH_MS = 60_000;
	/** Saved per account (RuneScape profile): what the offers bought, and the bank's coins. */
	private static final String TRADE_LOG_KEY = "tradeLog";
	/**
	 * The trade log's own copy, plugin-data/flip-sidebar/tradelog/&lt;RS profile&gt;.json, written at once on every change —
	 * RuneLite writes its settings to disk only now and then, so a client that is killed (the dev icon's restart, a crash)
	 * loses the last minutes. MEASURED 2026-11-01: a relaunch at 12:12 loaded the save of ~12:05 — a Pharaoh's sceptre
	 * bought at 12:11:44 (7,616,412) and collected had no record, so it was neither held nor sold and was suggested as a
	 * buy again (user: "buying items i already have in my inventory", "that arent showing as bough to be sold"); a
	 * Dragon harpoon bought 10:26 the same way at the 10:34 relaunch. Read in the background at start (tradeLogFiles).
	 */
	private static final String TRADE_LOG_DIR = "tradelog";
	/** RS profile -> its trade log file's JSON, read off the client thread at start. */
	private final Map<String, String> tradeLogFiles = new java.util.concurrent.ConcurrentHashMap<>();
	private static final String BANK_COINS_KEY = "bankCoins";
	private static final String SESSION_KEY = "sessionStart";
	/** Saved per account: what FlipLearner learned from its flips. */
	private static final String LEARNING_KEY = "learning";
	/** Where an import saves what was there before it (plugin-data/flip-sidebar/backups). */
	private static final String BACKUP_DIR = "backups";
	/** This account's random team-sync tag (TeamSync). */
	private static final String TEAM_TAG_KEY = "teamTag";
	private static final String BRAIN_TITLE = "Flip Sidebar - data";
	private static final String FLIPS_CSV = "flips.csv";
	private static final int PLATINUM_VALUE = 1000;
	private static final String MODIFY = "Modify";
	/** A slot's "Abort offer" (its right-click option, the game's text). */
	private static final String ABORT = "Abort";
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
		/**
		 * What was left to fill — what the re-placed offer should be for. Not final: the click only STARTS the
		 * cancel, and more can fill before it lands (onGrandExchangeOfferChanged keeps it true).
		 */
		int quantity;
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
		/** A GE slot event (slot 0-7): when it is this offer's, what is left is what the slot now says. */
		void settle(int slot0, int item, int total, int sold)
		{
			if (slot0 + 1 == slot && item == itemId)
			{
				quantity = total - sold;
			}
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
	/** How long after a GE number box closes the set-up highlights wait for the game to show the number (settling). */
	private static final int SETTLE_TICKS = 2;
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
	private Notifier notifier;

	@Inject
	private okhttp3.OkHttpClient okHttpClient;

	@Inject
	private SpriteManager spriteManager;

	@Inject
	private KeyManager keyManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private FlipSidebarOverlay overlay;

	@Inject
	private FlipSidebarHighlightOverlay highlightOverlay;

	@Inject
	private FlipSidebarClerkOverlay clerkOverlay;

	@Inject
	private FlipSidebarChartOverlay chartOverlay;

	@Inject
	private FlipSidebarSlotOverlay slotOverlay;

	@Inject
	private FlipSidebarItemOverlay itemOverlay;

	/** High alch mode: the High Level Alchemy spell and the item to alch, outlined (drawing only). */
	@Inject
	private FlipSidebarAlchOverlay alchOverlay;

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

	@Inject
	private HoldingBankTab holdingTab;

	@Inject
	private TeamSync teamSync;

	@Inject
	private Account account;
	/** Bank Tags' on/off in RuneLite's plugin list (group "runelite", key = its class name in lower case). */
	private static final String BANK_TAGS_ON_KEY = "banktagsplugin";

	private FlipSidebarPanel panel;
	/** The world logged into has its own Grand Exchange (mainEconomy): no guidance, no records, no Data sync. */
	private volatile boolean otherEconomy;
	/** The brain's newest view, for a panel built after it (a new Look). */
	private volatile FlipBrain.View lastView;
	private NavigationButton navButton;

	/** This account's trade log and bank coins — loaded per RuneScape profile. Client thread. */
	private TradeLog tradeLog;
	private String tradeLogProfile;
	private long bankCoins = -1;
	/** This account's session start (epoch ms), saved; the panel's "new session" resets it. */
	private long sessionStart;
	private boolean snapshotDirty;
	/** The inventory / the bank has loaded since this login (Holding is checked against them). Client thread. */
	private boolean invSeen;
	private boolean bankSeen;
	/**
	 * The inventory and the bank (null = the bank was not open) as the last tick saw them — to see items DEPOSITED or
	 * WITHDRAWN (trackBank). Client thread.
	 */
	private Map<Integer, Integer> tickInv;
	private Map<Integer, Integer> tickBank;
	/** Buys collected: did the items reach the inventory, or go straight to the bank? (CollectWatch, trackBank) */
	private final CollectWatch collectWatch = new CollectWatch();
	/**
	 * Every item in the bank (unnoted id -> how many) as it was the last time it was open this login; empty before.
	 * Kept here, not read back from the game: test 4, 2026-11-01 — a long-term hold banked and the bank closed was
	 * gone from Holding (ASSUMED cause: the bank's contents cannot be read once it is closed; kept either way).
	 */
	private Map<Integer, Integer> bankCounts = Map.of();
	/** See {@link Modify}. */
	private Modify modifying;
	/** The user's own pick open in the set-up screen, and its price once fetched (FlipBrain.priceItem). */
	private volatile int pricedItem = -1;
	private volatile boolean pricedSell;
	private volatile Suggestion manual;
	/** The local trade journal (TradeJournal); null while the plugin is off. */
	private TradeJournal journal;
	/** The tick the game last reached LOGGED_IN — the journal marks the login burst of offer events. */
	private int loginTick;
	/** True until the first LOGGED_IN after the login screen (FlipEngine.sessionStart); the plugin starts logged out. */
	private boolean loggedOut = true;
	/** Ticks after LOGGED_IN before the inventory / bank count as seen (trimHoldings): 10 = 6 seconds. */
	private static final int LOGIN_SETTLE_TICKS = 10;
	/** Journal file format: bump when its fields change meaning. */
	static final int JOURNAL_FORMAT = 1;
	/** The offer being set up, watched for a fill the game never shows in a slot (watchInstant). Client thread. */
	private Watch watch;
	/** Ticks after the set-up screen closes before an unseen fill is judged (the bow left 1 tick after Confirm). */
	private static final int INSTANT_TICKS = 3;

	/** An offer screen's item, its kind, and the inventory just before it closed. */
	private static final class Watch
	{
		final int item;
		final boolean sell;
		long have;
		long coins;
		int closedTick = -1;
		/** A slot showed this item, or a slot was collected, after it closed: the slots tell the story. */
		boolean seen;

		Watch(int item, boolean sell)
		{
			this.item = item;
			this.sell = sell;
		}
	}

	private volatile PriceHistory history;
	private volatile String chartRange = "1d";
	private volatile boolean chartVolume;
	private int requestedItem = -1;
	private String requestedRange;
	private long requestedAt;

	/**
	 * ⭐ EVERY finished flip of this account, all time — plugin-data/flip-sidebar/flips/<profile>.jsonl, one JSON line per
	 * row made or grown (the last line of a row wins). The trade log keeps the last 200, about a day or two of flipping;
	 * the Statistics window needs them all (user, 2026-11-03: "the profit graph needs to be adustable. i can only see the
	 * last day or two. i should have day week month year all time"). Keyed TradeLog.archiveKey.
	 */
	private final Map<String, TradeLog.Flip> archive = Collections.synchronizedMap(new LinkedHashMap<>());
	private volatile String archiveProfile;
	private static final String FLIP_ARCHIVE_DIR = "flips";
	/** The team's flips' own files (plugin-data/flip-sidebar/team/<profile>.json): TeamSync and team brains. */
	private static final String TEAM_DIR = "team";
	/** The team flips' version last written to their file (FlipLearner.teamVersion). Client thread. */
	private int teamSavedVersion = -1;
	/** Team sync every 30 minutes while the setting is on. */
	private ScheduledFuture<?> teamTask;
	/** Sends the player's GE offers every 10 minutes with Data sync (offerTick, OfferShare). */
	private ScheduledFuture<?> offerTask;
	private final OfferShare offerShare = new OfferShare();
	/** Logged in: every finished flip to the account's page every 2 minutes (accountTick). */
	private ScheduledFuture<?> accountTask;
	/** A browser login being waited for: asks the Worker every 3 seconds (accountLogin). */
	private ScheduledFuture<?> loginPoll;
	/**
	 * What the panel's account row says while not plainly logged in or out: "waiting" (the browser login is open),
	 * "expired", "failed", "sync" (Data sync is off), "ended" (the server ended the login); null = nothing to say.
	 */
	private volatile String accountNote;
	/** Flips already on the account's page: "tag|flip id" -> "qty:profit:sold at" when sent. Executor / OkHttp threads. */
	private final Map<String, String> accountSent = new java.util.concurrent.ConcurrentHashMap<>();
	/** Re-reads rules.json every minute (rulesTick, FlipEngine.applyRules). */
	private ScheduledFuture<?> rulesTask;
	/** rules.json's last-modified time when it was last read; -1 = never. */
	private long rulesAt = -1;
	static final String RULES_FILE = "rules.json";
	/** The big brain's per-item facts (FlipEngine.applyProfiles), written by ~/flip-finder/bigbrain/build_profiles.py. */
	static final String PROFILES_FILE = "profiles.json";
	/** profiles.json's last-modified time when last read; -1 = never. */
	private long profilesAt = -1;
	/**
	 * A one-shot list of items to ADD to the Equipment watch, dropped in the plugin folder (gear_add.json: [id, ...] or
	 * ["name", ...]) — read within a minute (rulesTick), merged into the "gearWatch" setting, then deleted. User,
	 * 2026-11-07: "i am not good at this stuff. i need you to get this all set up for me" — so items can be added for them.
	 */
	static final String GEAR_ADD_FILE = "gear_add.json";
	/** Flip ids this run has uploaded (TeamSync): not sent again. */
	private final Set<String> teamSent = Collections.synchronizedSet(new HashSet<>());
	/** Bumped each time the learner is (re)loaded: a save queued by an older one is dropped (an import). Client thread. */
	private int learnGen;
	/** Which GE number prompt is open (read on the client thread, used by the key listener). */
	private volatile int prompt = PROMPT_NONE;
	/** The click-to-fill lines in the open GE number box, and whether its title was moved down for them. Client thread. */
	private final List<Widget> fillLines = new ArrayList<>();
	/** The Y the box's title was moved to for the fill lines (shiftTitle); MIN_VALUE = not moved. Client thread. */
	private int titleShiftedTo = Integer.MIN_VALUE;
	/** When a GE number box last closed (game tick), and the set-up screen's price and quantity then (settling). */
	private int boxClosedTick = -1;
	private long boxClosedPrice;
	private int boxClosedQuantity;
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
			if (!config.fillKeyEnabled() || guidanceOff() || prompt == PROMPT_NONE || !config.fillKey().matches(e))
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
		// 1.60.74: "Spend on alching" went from whole millions (0 = no limit) to thousands (0 = nothing) — the user's
		// setting carried over once
		if (!"true".equals(configManager.getConfiguration(FlipSidebarConfig.GROUP, "alchSpendMoved")))
		{
			int m = config.alchSpendMillions();
			configManager.setConfiguration(FlipSidebarConfig.GROUP, "alchSpendK",
				m <= 0 ? FlipSidebarConfig.ALCH_NO_LIMIT_K : Math.min(FlipSidebarConfig.ALCH_NO_LIMIT_K, m * 1_000));
			configManager.setConfiguration(FlipSidebarConfig.GROUP, "alchSpendMoved", "true");
		}
		overlayManager.add(overlay);
		overlayManager.add(highlightOverlay);
		overlayManager.add(clerkOverlay);
		overlayManager.add(chartOverlay);
		overlayManager.add(slotOverlay);
		overlayManager.add(itemOverlay);
		overlayManager.add(alchOverlay);
		keyManager.registerKeyListener(fillKeyListener);
		mouseManager.registerMouseListener(chartOverlay.mouse());
		snapshotDirty = true;

		FlipTheme.now = FlipTheme.of(config.look());
		FlipSidebarPanel.useTheme(FlipTheme.now);
		buildPanel();
		brain.setIgnored(FlipSidebarConfig.parseIgnored(config.ignoredOffers()));
		try
		{
			brain.setMarketFile(getPluginDirectory().joinSegment("market.bin.gz"));      // the whole market's last day
		}
		catch (IOException e)
		{
			log.debug("Flip Sidebar: no plugin folder - the market memory is not saved", e);
		}
		executor.execute(this::readTradeLogFiles);
		journal = new TradeJournal(executor, gson);
		journal.setOn(config.tradeJournal());
		brain.setJournal(journal);
		brain.setSwingPlaced(this::swingPlaced);
		brain.setGearEvents(this::gearEvent);
		brain.start(v ->
		{
			lastView = v;
			SwingUtilities.invokeLater(() ->
			{
				FlipSidebarPanel now = panel;               // the panel of the moment (a new Look builds a new one)
				if (now != null)
				{
					now.show(v);
				}
			});
		});
		holdingTab.sync(config.holdingBankTab());
		teamTask = executor.scheduleWithFixedDelay(this::teamTick, 2, 30, TimeUnit.MINUTES);
		rulesTask = executor.scheduleWithFixedDelay(this::rulesTick, 0, 60, TimeUnit.SECONDS);
		offerTask = executor.scheduleWithFixedDelay(this::offerTick, 5, 10, TimeUnit.MINUTES);
		accountTask = executor.scheduleWithFixedDelay(this::accountTick, 1, 2, TimeUnit.MINUTES);
		log.debug("Flip Sidebar started");
	}

	/**
	 * World types that share the main game's Grand Exchange. A WHITELIST: any other type — Leagues, Deadman, fresh-start,
	 * beta, tournament, the hardcore worlds (HARDCORE_MAIN), and any mode added later — is its own economy until known
	 * otherwise. It fails closed: no guidance on an unknown world rather than the main game's prices there.
	 */
	static final Set<WorldType> MAIN_ECONOMY = EnumSet.of(WorldType.MEMBERS, WorldType.PVP, WorldType.BOUNTY,
		WorldType.SKILL_TOTAL, WorldType.HIGH_RISK, WorldType.LEGACY_ONLY, WorldType.EOC_ONLY);

	/** The main game's economy: RuneLite's standard profile on a world of only main-economy types. */
	static boolean mainEconomy(Set<WorldType> types, RuneScapeProfileType profile)
	{
		return profile == RuneScapeProfileType.STANDARD && (types == null || MAIN_ECONOMY.containsAll(types));
	}

	/** Whether this world is another economy, checked now (client thread); tells the brain when it changes. */
	private boolean otherEconomyNow()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return otherEconomy;
		}
		boolean other = !mainEconomy(client.getWorldType(), RuneScapeProfileType.getCurrent(client));
		if (other != otherEconomy)
		{
			otherEconomy = other;
			brain.otherEconomy = other;
			log.debug("Flip Sidebar: world {} {}", client.getWorld(), other ? "has its own economy - no guidance" : "is the main economy");
			snapshotDirty = true;
		}
		return other;
	}

	/** No guidance (cards, highlights, fill lines, the fill key): paused, or a world with its own economy. */
	boolean guidanceOff()
	{
		return config.paused() || otherEconomy;
	}

	/** The side panel and its toolbar button, in the current Look (Swing thread, or startUp). */
	private void buildPanel()
	{
		FlipSidebarPanel p = new FlipSidebarPanel(brain, configManager, config, itemManager, this);
		p.useGameIcons(spriteManager);     // the game's High Level Alchemy icon on the mode button and banner
		panel = p;
		navButton = NavigationButton.builder()
			.tooltip("Flip Sidebar")
			.icon(ImageUtil.loadImageResource(getClass(), "panel_icon.png"))
			.priority(7)
			.panel(p)
			.build();
		clientToolbar.addNavigation(navButton);
	}

	/**
	 * ⭐ A new Look (FlipTheme): the panel is built again in its colours and fonts, filled with the newest view, and
	 * opened again if it was open. Nothing else changes — the brain, the offers and the advice carry on. Swing thread.
	 */
	private void newLook()
	{
		if (panel == null || navButton == null)
		{
			return;
		}
		boolean open = panel.isShowing();
		FlipTheme.now = FlipTheme.of(config.look());
		FlipSidebarPanel.useTheme(FlipTheme.now);
		clientToolbar.removeNavigation(navButton);
		panel.stop();
		buildPanel();
		FlipBrain.View v = lastView;
		if (v != null)
		{
			panel.show(v);
		}
		if (open)
		{
			clientToolbar.openPanel(navButton);
		}
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		overlayManager.remove(highlightOverlay);
		overlayManager.remove(clerkOverlay);
		overlayManager.remove(chartOverlay);
		overlayManager.remove(slotOverlay);
		overlayManager.remove(itemOverlay);
		overlayManager.remove(alchOverlay);
		keyManager.unregisterKeyListener(fillKeyListener);
		mouseManager.unregisterMouseListener(chartOverlay.mouse());
		if (teamTask != null)
		{
			teamTask.cancel(false);
			teamTask = null;
		}
		if (rulesTask != null)
		{
			rulesTask.cancel(false);
			rulesTask = null;
		}
		if (offerTask != null)
		{
			offerTask.cancel(false);
			offerTask = null;
		}
		if (accountTask != null)
		{
			accountTask.cancel(false);
			accountTask = null;
		}
		stopLoginPoll();
		brain.stop();
		brain.setJournal(null);
		journal = null;
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
		history = null;
		invSeen = false;
		bankSeen = false;
		bankCounts = Map.of();
		holdingTab.stop();
		requestedItem = -1;
		prompt = PROMPT_NONE;
		names.clear();
		log.debug("Flip Sidebar stopped");
	}

	/** The card to show; none with live prices off. */
	/** High alch mode: the inventory items worth alching, the plan's first (FlipBrain.alchTargets). Any thread. */
	List<Integer> alchTargets()
	{
		return brain.alchTargets();
	}

	Suggestion getSuggestion()
	{
		return config.livePrices() ? brain.card() : null;
	}

	/** GE slots (1-8) to cancel, from the offer advice; empty with live prices off. */
	Set<Integer> cancelSlots()
	{
		FlipBrain.View v = brain.view();
		return v == null ? Set.of() : v.cancelSlots;
	}

	/**
	 * The ONE offer to act on before the card's next offer: the first slot to COLLECT (finished or cancelled), else the
	 * first to CANCEL, else the first to RAISE / LOWER; null when none. The highlight and the box both follow it (one
	 * step at a time, user 2026-11-01; collect first — "it should highlight the coins and the item if there is one after
	 * you cancel an offer", else the next cancel was lit and the cancelled one was left in its slot).
	 */
	OfferAdvice nextOfferStep()
	{
		return nextOfferStep(brain.view());
	}

	/** nextOfferStep for one view of the brain (the panel's, which it is drawing). */
	static OfferAdvice nextOfferStep(FlipBrain.View v)
	{
		if (v == null)
		{
			return null;
		}
		OfferAdvice next = null;
		int nextRank = 9;
		for (OfferAdvice a : v.offers)
		{
			// High alch mode guides alching: a flip offer's cancel / re-price is not a step there, only collecting (coins
			// and slots) — 2026-11-03 20:13 (journal) alch mode with a 1m budget led to RAISE a Lightbearer buy (6 x
			// 2,981,513) and a Burning claws buy (user: "its asking be to buy something way over my 1m as well as do the
			// flip steps instead of the alch ones"). The Offers list still shows their advice.
			if (v.alchMode && !OfferAdvice.COLLECT.equals(a.verdict))
			{
				continue;
			}
			int rank = OfferAdvice.COLLECT.equals(a.verdict) ? 0 : v.cancelSlots.contains(a.offer.slot) ? 1
				: v.adjustSlots.contains(a.offer.slot) ? 2 : 9;
			if (rank < nextRank || (rank == nextRank && rank < 9 && a.offer.slot < next.offer.slot))
			{
				next = a;
				nextRank = rank;
			}
		}
		return next;
	}

	/**
	 * A step's "First: ..." text: "CANCEL Soul rune (slot 3)" — a collect names the Collect button, which the highlight
	 * lights for it (it takes every finished offer at once).
	 */
	static String stepText(OfferAdvice step)
	{
		return OfferAdvice.COLLECT.equals(step.verdict) ? "COLLECT - the Collect button (top right)"
			: step.verdict + " " + (step.offer.name == null ? "?" : step.offer.name) + " (slot " + step.offer.slot + ")";
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

	/** The chat-box chart shows volume bars instead of the price lines (its Price / Volume buttons). */
	boolean isChartVolume()
	{
		return chartVolume;
	}

	void setChartVolume(boolean volume)
	{
		chartVolume = volume;
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

	/** The open item's prices for the chart, fetched with live prices on — again every minute. */
	private void requestChart()
	{
		int item = config.priceChart() && config.livePrices() ? setupItem() : -1;
		String range = chartRange;
		if (item <= 0)
		{
			return;
		}
		if (item == requestedItem && range.equals(requestedRange)
			&& System.currentTimeMillis() - requestedAt < CHART_REFRESH_MS)
		{
			return;
		}
		requestedItem = item;
		requestedRange = range;
		requestedAt = System.currentTimeMillis();
		brain.chart(item, range, h -> history = h);
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
			int was = prompt;
			updatePrompt();
			if (was != PROMPT_NONE && prompt == PROMPT_NONE)
			{
				boxClosed();
			}
			if (guidanceOff())
			{
				return;               // paused: no one-click search item, no click-to-fill lines
			}
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

	/**
	 * A GE quantity or price box is open (read from its title every game tick and on each chat-mode change, so it cannot
	 * stay set after the box closes). The highlights wait while it is (FlipSidebarHighlightOverlay.drawFill). Client thread.
	 */
	boolean numberBoxOpen()
	{
		return prompt != PROMPT_NONE;
	}

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

	/** A GE number box closed: its lines went with it, and the set-up highlights wait for the number (settling). */
	private void boxClosed()
	{
		fillLines.clear();
		boxClosedTick = client.getTickCount();
		boxClosedPrice = client.getVarpLongValue(VarPlayerID.GE_NEWOFFER_PRICE);
		boxClosedQuantity = client.getVarbitValue(VarbitID.GE_NEWOFFER_QUANTITY);
	}

	/**
	 * Just after a GE number box closed, until the set-up screen shows a new price or quantity (at most SETTLE_TICKS):
	 * the highlights read the set-up screen's own value, which the game sends after the box has closed, so for that
	 * moment the old value lights the same "..." button again. 2026-11-03 17:26 (Archers ring, RuneLite's client log +
	 * the journal): the price was entered at 17:26:24, a second price box was open at 17:26:25, Confirm was clicked at
	 * 17:26:26 with it still open, and the game left that box on screen with no offer behind it. Client thread.
	 */
	boolean settling()
	{
		return settling(boxClosedTick, client.getTickCount(), boxClosedPrice,
			client.getVarpLongValue(VarPlayerID.GE_NEWOFFER_PRICE), boxClosedQuantity,
			client.getVarbitValue(VarbitID.GE_NEWOFFER_QUANTITY));
	}

	static boolean settling(int closedTick, int now, long priceThen, long priceNow, int quantityThen, int quantityNow)
	{
		int since = now - closedTick;     // negative after a login reset the tick count: not settling
		return closedTick >= 0 && since >= 0 && since <= SETTLE_TICKS && priceNow == priceThen && quantityNow == quantityThen;
	}

	/**
	 * The set-up screen closed under a GE number box that stayed open (the game leaves it after Confirm, 2026-11-03
	 * 17:26): its click-to-fill number is for an offer already placed, so the lines go. Client thread.
	 */
	private void hideFillLines()
	{
		for (Widget line : fillLines)
		{
			line.setHidden(true);
		}
		fillLines.clear();
		shiftTitle(false);
	}

	/**
	 * The box's title moved down FILL_TITLE_SHIFT for the fill lines ({@code on}) or back — SET from where it was before
	 * our move, never added again: a second "..." click while the box is open rebuilds it without resetting the title,
	 * and the old "Y + shift" moved it twice, onto the number line (the user's crops, 2026-11-04: three offers, the prompt
	 * ~10 px low; client.log 06:07:28 → 06:07:30 a price box re-opened while open, its input reset, no close between).
	 * Client thread.
	 */
	private void shiftTitle(boolean on)
	{
		Widget title = client.getWidget(InterfaceID.Chatbox.MES_TEXT);
		if (title == null)
		{
			return;
		}
		int now = title.getOriginalY();
		int want = titleY(now, titleShiftedTo, on);
		if (want != now)
		{
			title.setOriginalY(want);
			title.revalidate();
		}
		titleShiftedTo = on ? want : Integer.MIN_VALUE;
	}

	/** Where the title goes: {@code shiftedTo} = where we moved it last (MIN_VALUE = not moved). */
	static int titleY(int now, int shiftedTo, boolean on)
	{
		int base = now == shiftedTo ? now - FILL_TITLE_SHIFT : now;          // still where we put it: from before that
		return on ? base + FILL_TITLE_SHIFT : base;
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
			return kind == PROMPT_QUANTITY ? setupQuantity(m.quantity, sellOffer, item) : m.target;
		}
		Suggestion s = offerSuggestion();
		return s == null || s.discouraged ? 0 : kind == PROMPT_QUANTITY ? setupQuantity(s.quantity, sellOffer, item) : s.price;
	}

	/**
	 * The quantity the set-up screen should show: for a SELL never more than the inventory holds (noted ones too) —
	 * the game does not take a sell for more than is held, so a bigger number is never met and the Quantity highlight
	 * never clears (2026-11-02 22:31 → 01:35, a Gold bar sell: 5,908 asked with 5,583 held, filled and entered 1,738
	 * times, the quantity never matched).
	 * While nothing is counted yet (the collected items not in the inventory yet) the number is left as it is. The fill
	 * key and the highlight both use this. Client thread.
	 */
	long setupQuantity(long want, boolean sell, int itemId)
	{
		return capToHeld(want, sell, heldCount(itemId));
	}

	/** How many of {@code itemId} the inventory holds, noted ones too. Client thread. */
	long heldCount(int itemId)
	{
		return count(client.getItemContainer(InventoryID.INV), itemId);
	}

	static long capToHeld(long want, boolean sell, long have)
	{
		return !sell || want <= 0 || have <= 0 ? want : Math.min(want, have);
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
		if (setup <= 0 || !config.livePrices() || guidanceOff() || modifying() != null || isCardOffer(setup, sellOffer))
		{
			pricedItem = -1;      // nothing open, or the card's own offer: no "Your pick"
			ownPick(null);
			return;
		}
		if (setup == pricedItem && sellOffer == pricedSell)
		{
			return;
		}
		pricedItem = setup;
		pricedSell = sellOffer;
		ownPick(null);
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
		for (long[] h : t == null ? List.<long[]>of() : t.kept())
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
				ownPick(q);
			}
		});
	}

	/**
	 * The own pick — the floating box reads {@code manual}; the side panel gets it too, as some turn the box off
	 * (user, 2026-11-01). Only on a change: this runs every game tick. Any thread.
	 */
	private void ownPick(Suggestion s)
	{
		if (s == manual)
		{
			return;
		}
		manual = s;
		FlipSidebarPanel p = panel;
		long[] lim = s != null && "BUY".equals(s.action) ? limitNow(s.itemId) : null;
		if (p != null)
		{
			SwingUtilities.invokeLater(() -> p.showOwnPick(s, lim));
		}
	}

	/** Put the card's / pick's number in the box. Enter is still the user's to press. */
	private void fillPrompt(int kind)
	{
		if (setupItem() < 0)
		{
			return;                   // a box left open after its offer was placed (hideFillLines): nothing to fill
		}
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
		// a box rebuilt while open may keep the lines made for it before: gone, so they never stack under the new ones
		for (Widget old : fillLines)
		{
			old.setHidden(true);
		}
		fillLines.clear();
		if (layer == null)
		{
			return;
		}
		boolean added = false;
		long value = promptValue(kind);
		// your own pick's warning goes where the number is offered (user, 2026-10-31: "it should show in the
		// chat box under quantity and price where the offer normally would be")
		Suggestion own = modifying() == null ? offerSuggestion() : null;
		boolean ownPick = own != null && own.manual;
		String tag = ownPick && own.chatNote != null && !own.chatNote.isEmpty() ? " · " + own.chatNote : "";
		if (value > 0)
		{
			added = fillLine(layer, FILL_Y, "Flip Sidebar: click to fill " + String.format("%,d", value)
				+ (kind == PROMPT_PRICE ? " gp" : "") + tag, value, () -> fillPrompt(kind));
		}
		else if (ownPick && own.discouraged && own.note != null && !own.note.startsWith("Buy limit reached")
			&& !own.note.startsWith("Your offer in slot"))     // both said by the buy limit line below
		{
			added = fillLine(layer, FILL_Y, "Flip Sidebar: " + own.note, 0, null);     // can't be priced: why
		}
		// ⭐ ANY buy offer's quantity box: the GE buy limit left, one click to fill it in. User, 2026-10-31:
		// "it should be just like when ... u tell me how many to buy and i click it. there should be the
		// same thing above it for buy limit. clickable that sets it to the ge buy limit."
		boolean buyOffer = client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE) != 1;
		long[] lim = kind == PROMPT_QUANTITY && buyOffer ? limitNow(client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH)) : null;
		if (lim != null)
		{
			int y = added ? FILL_Y - FILL_HEIGHT : FILL_Y;
			// what its other open buy offers still wait on counts too (FlipBrain.waitingBuys); not while
			// modifying, where the offer being changed is one of them
			long[] wait = modifying() == null ? brain.waitingBuysNow(client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH))
				: new long[2];
			long room = lim[0] - wait[0];
			added |= room > 0
				? fillLine(layer, y, "Flip Sidebar: buy limit left " + String.format("%,d", room)
					+ (wait[0] > 0 ? " after slot " + wait[1] : "") + " - click to fill", room, () -> fillValue(room))
				: wait[0] > 0
				? fillLine(layer, y, "Flip Sidebar: your offer in slot " + wait[1] + " already asks for the rest of the buy limit", 0, null)
				: fillLine(layer, y, "Flip Sidebar: buy limit reached - resets in "
					+ FlipSidebarSlotOverlay.clock(lim[2] - System.currentTimeMillis()), 0, null);
		}
		shiftTitle(added);
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
		fillLines.add(line);
		return true;
	}

	// ── your offers and coins ───────────────────────────────────────────────────

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		if (otherEconomyNow())
		{
			return;                   // another economy's offers: never in this account's trade log or learning
		}
		snapshotDirty = true;
		GrandExchangeOffer o = event.getOffer();
		GrandExchangeOfferState st = o.getState();
		if (st == GrandExchangeOfferState.EMPTY && client.getGameState() != GameState.LOGGED_IN)
		{
			return;                   // the client empties every slot while logging in — not a collect
		}
		Modify m = modifying;
		// "Modify offer" only STARTS the cancel: what was left is final when the slot says so. 2026-11-02 22:31 a Gold bar
		// sell of 10,000 had 4,092 sold at the click and 4,417 when the cancel landed a second later; the click's 5,908
		// was kept and asked for with 5,583 held (setupQuantity)
		if (m != null)
		{
			m.settle(event.getSlot(), o.getItemId(), o.getTotalQuantity(), o.getQuantitySold());
		}
		Watch w = watch;
		// the offer is in a slot after all (also when its event beats the tick that sees the screen close — else a
		// quick collect of a normal instant buy would count twice), or a collect moved coins and items
		if (w != null && (o.getItemId() == w.item || (w.closedTick >= 0 && st == GrandExchangeOfferState.EMPTY)))
		{
			w.seen = true;
		}
		TradeJournal j = journal;
		if (j != null)
		{
			JsonObject e = new JsonObject();
			e.addProperty("slot", event.getSlot() + 1);
			e.addProperty("state", st.name());
			e.addProperty("item", o.getItemId());
			e.addProperty("price", o.getPrice());
			e.addProperty("total", o.getTotalQuantity());
			e.addProperty("sold", o.getQuantitySold());
			e.addProperty("spent", o.getSpent());
			e.addProperty("gs", client.getGameState().name());
			e.addProperty("sinceLogin", client.getTickCount() - loginTick);
			if (o.getItemId() > 0)
			{
				e.add("mk", brain.marketNow(o.getItemId()));
			}
			j.log("offer", e);
		}
		if (config.dataSync() && config.livePrices() && !otherEconomy)
		{
			// Data sync: the offer's life for the server (OfferShare); placed while watched = not the login burst
			boolean known = client.getTickCount() - loginTick > LOGIN_SETTLE_TICKS;
			offerShare.seen(event.getSlot() + 1, st.name(), o.getItemId(), o.getPrice(), o.getTotalQuantity(),
				o.getQuantitySold(), o.getSpent(), isBuy(st), known, System.currentTimeMillis(),
				o.getItemId() > 0 ? brain.marketNow(o.getItemId()) : null);
		}
		TradeLog t = tradeLog();
		// a buy collected (its slot emptied): into the inventory, or straight to the bank? (CollectWatch, trackBank)
		boolean emptied = st == GrandExchangeOfferState.EMPTY;
		long[] wasBuy = t == null || !emptied ? null : t.buyIn(event.getSlot());
		boolean settled = client.getTickCount() - loginTick > LOGIN_SETTLE_TICKS && tickInv != null;
		collectWatch.offer(event.getSlot(), o.getItemId(), isBuy(st) && o.getQuantitySold() > 0, emptied,
			settled ? wasBuy : null, client.getTickCount());
		boolean changed = t != null && t.update(event.getSlot(), st == GrandExchangeOfferState.EMPTY, isBuy(st),
			o.getItemId(), o.getPrice(), o.getTotalQuantity(), o.getQuantitySold(), o.getSpent(), System.currentTimeMillis());
		// a High alch mode buy that pays to alch is for ALCHING: never offered for sale (TradeLog.forAlch)
		if (t != null && isBuy(st) && o.getQuantitySold() > 0 && config.alchMode() && !guidanceOff()
			&& brain.paysToAlch(o.getItemId(), o.getPrice()) && t.setForAlch(o.getItemId(), true))
		{
			changed = true;
			snapshotDirty = true;
		}
		if (changed)
		{
			saveTradeLog(t);
			flashDrops(t);
		}
	}

	/** "Save for alching" (a Holding row, the sell card) or its undo (a To alch row). Swing thread. */
	void saveForAlch(int itemId, boolean on)
	{
		clientThread.invoke(() ->
		{
			TradeLog t = tradeLog();
			if (t != null && t.setForAlch(itemId, on))
			{
				saveTradeLog(t);
				snapshotDirty = true;
				journalItem(on ? "forAlch" : "notForAlch", itemId, x -> { });
			}
		});
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
		archiveFresh(t);
		t.savedAt = System.currentTimeMillis();
		String json = gson.toJson(t);
		configManager.setRSProfileConfiguration(FlipSidebarConfig.GROUP, TRADE_LOG_KEY, json);
		String profile = tradeLogProfile;
		if (profile != null)
		{
			tradeLogFiles.put(profile, json);
			write(TRADE_LOG_DIR, profile + ".json", json);       // at once, off the client thread, atomically
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		recordContainer(event.getContainerId(), event.getItemContainer());
		// "seen" only after the login has settled (LOGIN_SETTLE_TICKS): the login burst can report a container before
		// everything is loaded (trimHoldings)
		boolean settled = client.getGameState() == GameState.LOGGED_IN && client.getTickCount() - loginTick > LOGIN_SETTLE_TICKS;
		if (event.getContainerId() == InventoryID.INV)
		{
			invSeen |= settled;
			snapshotDirty = true;
		}
		else if (event.getContainerId() == InventoryID.BANK)
		{
			bankSeen |= settled;
			// an empty view never replaces the last real one (a bank holding nothing at all is not a flipper's)
			Map<Integer, Integer> now = itemCounts(event.getItemContainer());
			bankCounts = now.isEmpty() ? bankCounts : now;
			snapshotDirty = true;
			long coins = coins(event.getItemContainer());
			if (tradeLog() != null && coins != bankCoins)
			{
				bankCoins = coins;
				configManager.setRSProfileConfiguration(FlipSidebarConfig.GROUP, BANK_COINS_KEY, coins);
				snapshotDirty = true;
			}
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			brain.update(null);
			if (journal != null)
			{
				journal.setDir(null);
			}
		}
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			loginTick = client.getTickCount();
			if (loggedOut)
			{
				// a real login (not a loading screen or a world hop): an under-water sell's patience restarts here
				FlipEngine.sessionStart = System.currentTimeMillis();
				loggedOut = false;
			}
		}
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			loggedOut = true;
		}
		if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
		{
			invSeen = false;          // what the player has is known again only once these load
			bankSeen = false;
			bankCounts = Map.of();
			tickInv = null;
			tickBank = null;
			collectWatch.clear();
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
		if (RuneLiteConfig.GROUP_NAME.equals(event.getGroup()) && BANK_TAGS_ON_KEY.equals(event.getKey()))
		{
			holdingTab.sync(config.holdingBankTab());      // Bank Tags turned on or off
			return;
		}
		if (FlipSidebarConfig.GROUP.equals(event.getGroup()) && "look".equals(event.getKey()))
		{
			SwingUtilities.invokeLater(this::newLook);
			return;
		}
		if (FlipSidebarConfig.GROUP.equals(event.getGroup()) && "dataSync".equals(event.getKey()) && config.dataSync())
		{
			executor.execute(this::teamTick);              // turned on: sync now, not in half an hour
		}
		if (!FlipSidebarConfig.GROUP.equals(event.getGroup()) || event.getKey() == null)
		{
			return;
		}
		switch (event.getKey())
		{
			case "ignoredOffers":
				brain.setIgnored(FlipSidebarConfig.parseIgnored(config.ignoredOffers()));
				break;
			case "tradeJournal":
				if (journal != null)
				{
					journal.setOn(config.tradeJournal());
				}
				break;
			case "holdingBankTab":
				holdingTab.sync(config.holdingBankTab());
				break;
			case "livePrices":
			case "riskLevel":
			case "adjustWindow":
			case "minProfit":
			case "maxCashMillions":
			case "freeToPlay":
			case "sellOnly":
			case "swingTrades":
			case "holdLimit":
			case "paused":
			case "blockedItems":
			case "alchMode":
			case "alchHours":
			case "alchCastsPerHour":
			case "alchBuy":
			case "alchMinProfit":
			case "alchMaxItems":
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
	 * "Left-click cancel / re-price" (user, 2026-11-06, of another flipping plugin: "when u click on a cancel trade it seems
	 * to auto cancel the trade" → "yes add it"): while the highlight lights a slot red (cancel) or amber (re-price), every
	 * OTHER option of that slot is deprioritized, so its left-click is "Abort offer" / "Modify offer". Nothing is added to
	 * the menu and nothing is clicked: one click is still the one game action the player picks, the same as choosing it
	 * from the right-click menu, and every other slot keeps its own left-click. It follows the highlight exactly
	 * (FlipSidebarHighlightOverlay.drawOfferActions): off with highlighting off or paused, while an offer is being
	 * re-placed, and when the next step is a collect. Client thread.
	 */
	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (!config.leftClickSlotAction() || !config.highlight())
		{
			return;
		}
		int slot = 0;
		for (int i = 0; i < GE_SLOTS.length && slot == 0; i++)
		{
			slot = event.getActionParam1() == GE_SLOTS[i] ? i + 1 : 0;     // a widget option: param1 is the widget
		}
		if (slot == 0 || guidanceOff() || !isGeOpen() || modifying() != null)
		{
			return;
		}
		OfferAdvice step = nextOfferStep();
		if (step == null || step.offer.slot != slot || OfferAdvice.COLLECT.equals(step.verdict))
		{
			return;
		}
		String want = cancelSlots().contains(slot) ? ABORT : MODIFY;
		String option = event.getOption();
		if (option != null && !option.startsWith(want))
		{
			event.getMenuEntry().setDeprioritized(true);
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
		recordMenu(event);
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
		otherEconomyNow();
		// the GE number box read again every tick from its own title, not only on the chat-mode event: 2026-11-04 06:07:27 a
		// price box opened and the fill key was not caught (an "e" typed into it, no KeyManager "Consuming" line), then
		// "..." was clicked again — what moved the prompt twice (shiftTitle). Why the event path missed it is NOT known.
		int was = prompt;
		updatePrompt();
		if (was == PROMPT_NONE && prompt != PROMPT_NONE && config.clickToFill() && !guidanceOff())
		{
			addFillLine();
		}
		else if (was != PROMPT_NONE && prompt == PROMPT_NONE)
		{
			boxClosed();
		}
		requestChart();
		int setup = setupItem();
		if (setup < 0 && !fillLines.isEmpty())
		{
			hideFillLines();
		}
		brain.setSettingUp(setup);
		watchInstant(setup);
		trackBank();
		tickHeld();
		journalLogin();
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
			// the newer of RuneLite's copy and the plugin's own file (TRADE_LOG_DIR)
			TradeLog t = newer(gson, configManager.getRSProfileConfiguration(FlipSidebarConfig.GROUP, TRADE_LOG_KEY),
				tradeLogFiles.get(profile));
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
			// what it has learned from this account's flips (FlipLearner), saved beside its trade log
			FlipLearner learned = null;
			String lj = configManager.getRSProfileConfiguration(FlipSidebarConfig.GROUP, LEARNING_KEY);
			try
			{
				learned = lj == null ? null : gson.fromJson(lj, FlipLearner.class);
			}
			catch (JsonParseException e)
			{
				log.debug("Flip Sidebar: saved learning unreadable - starting again", e);
			}
			final String forProfile = profile;
			final int gen = ++learnGen;          // a save from an older learner (before an import) is dropped
			teamSavedVersion = -1;
			boolean teamInSettings = learned != null && learned.teamSize() > 0;   // saved before team files: moved out below
			brain.setLearner(learned, l ->
			{
				// under the brain's lock: consistent copies — the learning without the team's flips (RuneLite's settings),
				// the team's flips to their own file only when they changed (TeamSync can bring thousands)
				String saved = l.jsonWithoutTeam(gson);
				int tv = l.teamVersion();
				String team = tv != teamSavedVersion ? gson.toJson(l.teamMap()) : null;
				clientThread.invoke(() ->
				{
					if (gen == learnGen && forProfile.equals(configManager.getRSProfileKey()))
					{
						configManager.setRSProfileConfiguration(FlipSidebarConfig.GROUP, LEARNING_KEY, saved);
						if (team != null)
						{
							teamSavedVersion = tv;
							write(TEAM_DIR, forProfile + ".json", team);
						}
					}
				});
			});
			if (!teamInSettings)
			{
				loadTeamFile(profile);
			}
			tradeLogProfile = profile;
			loadArchive(profile, tradeLog);
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
			GameSnapshot.Offer go = new GameSnapshot.Offer(i + 1, isBuy(st), o.getItemId(), itemName(o.getItemId()), o.getPrice(),
				o.getTotalQuantity(), o.getQuantitySold(),
				st == GrandExchangeOfferState.BOUGHT || st == GrandExchangeOfferState.SOLD,
				st == GrandExchangeOfferState.CANCELLED_BUY || st == GrandExchangeOfferState.CANCELLED_SELL,
				times == null ? 0 : times[0], times == null ? 0 : times[1], t == null ? 0 : t.loweredFrom(i));
			go.recentFilled = t == null ? -1 : t.recentFilled(i, now);
			offers.add(go);
		}
		if (logChanged)
		{
			saveTradeLog(t);
			flashDrops(t);
		}
		if (t != null && trimHoldings(t, counts, all, now))
		{
			saveTradeLog(t);
		}
		List<GameSnapshot.Holding> holdings = new ArrayList<>();
		List<GameSnapshot.Done> history = new ArrayList<>();
		Map<Integer, long[]> limits = Map.of();
		if (t != null)
		{
			// bought over 12h ago and still here (inventory or a GE offer): its price still counts (TradeLog.KEEP_MS)
			Set<Integer> here = new HashSet<>(counts.keySet());
			offers.forEach(o -> here.add(o.itemId));
			Set<Integer> forAlch = t.forAlch();
			for (long[] h : t.holdings(now, here))
			{
				// bought to alch: not a flip — never a sell card, never in Holding (High alch mode's To alch lists it)
				if (!forAlch.contains((int) h[0]))
				{
					holdings.add(new GameSnapshot.Holding((int) h[0], itemName((int) h[0]), h[1], h[2], h[3]));
				}
			}
			limits = t.limitUse(now);
			for (TradeLog.Flip f : t.flips())
			{
				history.add(done(f, itemName(f.itemId)));
			}
		}
		Set<Integer> held = t == null ? Set.of() : t.held();
		Map<Integer, Long> inBank = t == null ? Map.of() : t.inBank();
		// the Holding bank tab: long-term holds AND every flip item deposited (TradeLog.Bought.inBank) — user, 2026-11-05:
		// "when u deposit flip items they go to our tab. and get an icon next to them"
		Set<Integer> tab = new HashSet<>(held);
		tab.addAll(inBank.keySet());
		holdingTab.setHolds(tab);
		GameSnapshot g = new GameSnapshot(coins(inv), bankCoins, counts, offers, holdings, limits, history, sessionStart,
			held, t == null ? Map.of() : t.swing(System.currentTimeMillis()), banked(held));
		g.forAlch = t == null ? Set.of() : t.forAlch();
		g.inBank = inBank;
		g.own = t == null ? Map.of() : t.own();
		g.record = archiveRecord;
		g.week = archiveWeek;
		return g;
	}

	/**
	 * How many of each held item (TradeLog.held) are in the bank, as last seen this login: LONG-TERM holds (user,
	 * 2026-11-01: "a bank tab ... a little icon next to each item in the bank so u know its a flip item. then they just
	 * have you hold it long term"). From the bank as it was last open this login (bankCounts); before that, as it was
	 * last seen on an earlier login (TradeLog.banked). Client thread.
	 */
	private Map<Integer, Integer> banked(Set<Integer> held)
	{
		TradeLog t = tradeLog();
		if (!bankSeen)
		{
			return t == null ? Map.of() : t.banked();   // not open yet this login: as it was last seen (TradeLog.banked)
		}
		Map<Integer, Integer> out = new HashMap<>();
		for (int id : held)
		{
			int n = bankCounts.getOrDefault(id, 0);
			if (n > 0)
			{
				out.put(id, n);
			}
		}
		if (t != null && t.setBanked(out))
		{
			saveTradeLog(t);
		}
		return out;
	}

	/** A container's items, unnoted id -> how many (noted and unnoted together; placeholders left out). Client thread. */
	private Map<Integer, Integer> itemCounts(ItemContainer c)
	{
		Map<Integer, Integer> out = new HashMap<>();
		for (Item it : c == null ? new Item[0] : c.getItems())
		{
			if (it.getId() > 0 && it.getQuantity() > 0)
			{
				out.merge(itemManager.canonicalize(it.getId()), it.getQuantity(), Integer::sum);
			}
		}
		return out;
	}

	/**
	 * Holding can never show more than the player has (user, 2026-10-31: "its saying were holding ranarr
	 * seeds wen we are not" — a sell of 200 was saved at 188 sold: its last 12 sold or were collected while
	 * the plugin was not watching). Only once the inventory AND the bank have loaded this login: what is in
	 * the inventory, worn, in the bank, or still in a GE slot (a sell's unsold part, a buy's bought part).
	 * The missed sale's profit stays unknown; the stock record is corrected. Client thread.
	 */
	private boolean trimHoldings(TradeLog t, Map<Integer, Integer> inventory, GrandExchangeOffer[] offers, long now)
	{
		ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		if (!invSeen || !bankSeen || bank == null || worn == null
			|| client.getTickCount() - loginTick <= LOGIN_SETTLE_TICKS)
		{
			// where everything is is not known yet: never guess it away — nor in the first ticks of a login (a guard;
			// the Dragon harpoon / Pharaoh's sceptre records lost 2026-11-01 were a stale save, TRADE_LOG_DIR)
			return false;
		}
		Map<Integer, Long> have = new HashMap<>();
		inventory.forEach((id, n) -> have.merge(id, (long) n, Long::sum));
		for (ItemContainer c : new ItemContainer[]{bank, worn})
		{
			for (Item it : c == null ? new Item[0] : c.getItems())
			{
				if (it.getId() > 0 && it.getQuantity() > 0)
				{
					have.merge(itemManager.canonicalize(it.getId()), (long) it.getQuantity(), Long::sum);
				}
			}
		}
		for (GrandExchangeOffer o : offers == null ? new GrandExchangeOffer[0] : offers)
		{
			GrandExchangeOfferState st = o == null ? GrandExchangeOfferState.EMPTY : o.getState();
			if (st != GrandExchangeOfferState.EMPTY)
			{
				// a buy's bought part may be collected already (then it is counted twice): counting high
				// only ever trims less
				have.merge(o.getItemId(), (long) (isBuy(st) ? o.getQuantitySold() : o.getTotalQuantity() - o.getQuantitySold()),
					Long::sum);
			}
		}
		boolean changed = false;
		for (long[] h : t.kept())
		{
			changed |= t.trim((int) h[0], have.getOrDefault((int) h[0], 0L));
		}
		return changed;
	}

	private static boolean isBuy(GrandExchangeOfferState st)
	{
		return st == GrandExchangeOfferState.BUYING || st == GrandExchangeOfferState.BOUGHT
			|| st == GrandExchangeOfferState.CANCELLED_BUY;
	}

	/**
	 * ⭐ Where the flip items are (TradeLog: Bought.inBank, own). User, 2026-11-05: "if i take a break and deposit my items
	 * id want all my flip items showing in that bank tab regardless of them being long hold items or not". The plugin
	 * cannot tell a flip's Chaos runes from the player's own, so it WATCHES them move instead of guessing from counts:
	 * <ul>
	 *   <li>with the bank open, an item leaving the inventory as the bank gains it the same tick: deposited; the bank
	 *       losing it as the inventory gains it: withdrawn — the flip units in the bank first, the rest the player's own
	 *       (never offered for sale);</li>
	 *   <li>a buy collected (its slot emptied) whose item's inventory count never went up from its first fill until
	 *       CollectWatch.COLLECT_TICKS after: collected straight to the bank;</li>
	 *   <li>the first open bank with these rules marks what was banked before (TradeLog.traceBank); after it, a record's
	 *       units found nowhere HOLD_MS after the last buy are dropped (TradeLog.dropUntraced).</li>
	 * </ul>
	 * Not on a world with its own economy; nothing during a login's first ticks. Client thread.
	 */
	/** Saved this often while a flip's logged-in time grows (TradeLog.tickHeld): a closed client loses at most this. */
	private static final int HELD_SAVE_TICKS = 500;
	private int heldTicks;

	/** One game tick logged in: what is held unsold outside the bank adds to how long it sat (TradeLog.Bought.heldMs). */
	private void tickHeld()
	{
		TradeLog t = tradeLog();
		if (t == null || otherEconomy || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		if (t.tickHeld(System.currentTimeMillis()) && ++heldTicks >= HELD_SAVE_TICKS)
		{
			heldTicks = 0;
			saveTradeLog(t);
		}
	}

	private void trackBank()
	{
		TradeLog t = tradeLog();
		if (t == null || otherEconomy || client.getGameState() != GameState.LOGGED_IN
			|| client.getTickCount() - loginTick <= LOGIN_SETTLE_TICKS)
		{
			tickInv = null;
			tickBank = null;
			return;
		}
		Map<Integer, Integer> inv = itemCounts(client.getItemContainer(InventoryID.INV));
		Widget items = client.getWidget(InterfaceID.Bankmain.ITEMS);
		Map<Integer, Integer> bank = items != null && !items.isHidden() ? itemCounts(client.getItemContainer(InventoryID.BANK))
			: null;
		if (bank != null && bank.isEmpty())
		{
			bank = null;                       // not loaded yet (a flipper's bank is never empty)
		}
		boolean changed = false;
		if (bank != null && tickBank != null && tickInv != null)
		{
			Set<Integer> ids = new HashSet<>(t.own().keySet());
			for (long[] k : t.kept())
			{
				ids.add((int) k[0]);
			}
			for (int id : ids)
			{
				int dInv = inv.getOrDefault(id, 0) - tickInv.getOrDefault(id, 0);
				int dBank = bank.getOrDefault(id, 0) - tickBank.getOrDefault(id, 0);
				if (dInv < 0 && dBank > 0)
				{
					changed |= t.deposited(id, Math.min(-dInv, dBank), true);
				}
				else if (dInv > 0 && dBank < 0)
				{
					changed |= t.withdrawn(id, Math.min(dInv, -dBank));
				}
			}
		}
		for (long[] c : collectWatch.tick(inv, tickInv, client.getTickCount()))
		{
			changed |= t.deposited((int) c[0], c[1], false);     // nothing reached the inventory: to the bank
		}
		changed |= t.place(inv, bank);
		Map<Integer, Long> here = flipUnitsHere(t, inv);
		if (bank != null)
		{
			changed |= t.traceBank(here, bank);
		}
		if (invSeen)
		{
			changed |= t.dropUntraced(here, System.currentTimeMillis());
		}
		tickInv = inv;
		tickBank = bank;
		if (changed)
		{
			saveTradeLog(t);
			snapshotDirty = true;
		}
	}

	/** Item -> its flip units in the inventory (the player's own taken out) and in the GE offers. Client thread. */
	private Map<Integer, Long> flipUnitsHere(TradeLog t, Map<Integer, Integer> inv)
	{
		Map<Integer, Long> own = t.own();
		Map<Integer, Long> here = new HashMap<>();
		inv.forEach((id, n) -> here.put(id, Math.max(0, n - own.getOrDefault(id, 0L))));
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		for (GrandExchangeOffer o : offers == null ? new GrandExchangeOffer[0] : offers)
		{
			GrandExchangeOfferState st = o == null ? GrandExchangeOfferState.EMPTY : o.getState();
			if (st != GrandExchangeOfferState.EMPTY)
			{
				here.merge(o.getItemId(), (long) (isBuy(st) ? o.getQuantitySold() : o.getTotalQuantity() - o.getQuantitySold()),
					Long::sum);
			}
		}
		return here;
	}

	/** Coins plus platinum tokens (1,000 each) in a container. */
	/**
	 * A fill the game never shows in a slot (TradeLog.instant): while an offer screen is open, remember the item's count
	 * and the coins; INSTANT_TICKS after it closes, with no slot event for that item and no collect in between, the item
	 * gone and the coins up (a sell) — or the item in and the coins down (a buy) — was filled at once. Client thread.
	 */
	private void watchInstant(int setup)
	{
		ItemContainer inv = client.getItemContainer(InventoryID.INV);
		if (setup > 0)
		{
			boolean sell = client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE) == 1;
			if (watch == null || watch.item != setup || watch.sell != sell || watch.closedTick >= 0)
			{
				watch = new Watch(setup, sell);
			}
			watch.have = count(inv, setup);
			watch.coins = coins(inv);
			return;
		}
		Watch w = watch;
		if (w == null)
		{
			return;
		}
		int tick = client.getTickCount();
		if (w.closedTick < 0)
		{
			w.closedTick = tick;
			return;
		}
		if (tick - w.closedTick < INSTANT_TICKS)
		{
			return;
		}
		watch = null;
		long moved = w.sell ? w.have - count(inv, w.item) : count(inv, w.item) - w.have;
		long paid = w.sell ? coins(inv) - w.coins : w.coins - coins(inv);
		TradeLog t = tradeLog();
		if (!w.seen && moved > 0 && paid > 0 && t != null && t.instant(!w.sell, w.item, moved, paid, System.currentTimeMillis()))
		{
			log.debug("Flip Sidebar: {} x{} of item {} filled outside the slots, {} coins", w.sell ? "sold" : "bought", moved,
				w.item, paid);
			journalItem("instant", w.item, o ->
			{
				o.addProperty("sell", w.sell);
				o.addProperty("qty", moved);
				o.addProperty("coins", paid);
			});
			saveTradeLog(t);
			snapshotDirty = true;
		}
	}

	/** Long-term holds in the bank: item -> back at break-even (FlipBrain.Held.banked) — the bank's dots. Any thread. */
	Map<Integer, Boolean> bankHolds()
	{
		FlipBrain.View v = brain == null ? null : brain.view();
		if (v == null)
		{
			return Map.of();
		}
		Map<Integer, Boolean> out = new HashMap<>();
		for (FlipBrain.Held h : v.held)
		{
			if (h.banked)
			{
				out.put(h.itemId, h.ready);
			}
		}
		return out;
	}

	/**
	 * The flip items NOT in the bank (the inventory, a GE offer) — the inventory's dots: item -> its Holding row
	 * ({@code market} is the same 5-minute insta-buy average as the hover tooltip's "now", as of the brain's last view).
	 * Only items with a known buy price. Any thread.
	 */
	Map<Integer, FlipBrain.Held> flipItemsHeld()
	{
		FlipBrain.View v = brain == null ? null : brain.view();
		if (v == null || v.held.isEmpty())
		{
			return Map.of();
		}
		Map<Integer, FlipBrain.Held> out = new HashMap<>();
		for (FlipBrain.Held h : v.held)
		{
			if (!h.banked && h.paidEach > 0)
			{
				out.put(h.itemId, h);
			}
		}
		return out;
	}

	/** How many of {@code itemId} in the bank are flip units (a banked Holding row's quantity); 0 if none. Any thread. */
	long bankHoldQty(int itemId)
	{
		FlipBrain.View v = brain == null ? null : brain.view();
		for (FlipBrain.Held h : v == null ? List.<FlipBrain.Held>of() : v.held)
		{
			if (h.banked && h.itemId == itemId)
			{
				return h.qty;
			}
		}
		return 0;
	}

	/**
	 * A Holding row's "These are mine, not a flip": the record of {@code itemId} is forgotten — no Holding row, no sell
	 * card, not in the bank tab nor the cash (TradeLog.forget). Any thread.
	 */
	void notAFlip(int itemId)
	{
		clientThread.invoke(() ->
		{
			TradeLog t = tradeLog();
			if (t != null && t.forget(itemId))
			{
				saveTradeLog(t);
				snapshotDirty = true;
				journalItem("notAFlip", itemId, o -> { });
			}
		});
	}

	/** The card's Hold button: keep {@code itemId} in the inventory instead of selling it at a loss (TradeLog.held). */
	void holdItem(int itemId)
	{
		brain.keep(itemId);           // a "sell now at the market" chosen before is undone: the hold wins
		clientThread.invoke(() ->
		{
			TradeLog t = tradeLog();
			if (t != null && t.hold(itemId))
			{
				saveTradeLog(t);
				snapshotDirty = true;
				journalItem("hold", itemId, o -> { });
			}
		});
	}

	/** Watched items that turned a good time to buy since the Equipment tab was last opened (the tab flashes). */
	private final Set<Integer> gearNew = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** True while a watched item's "good time to buy" has not been seen on the Equipment tab. Any thread. */
	boolean gearNew()
	{
		return !gearNew.isEmpty();
	}

	/** The Equipment tab was opened: its flashing stops. */
	void gearSeen()
	{
		gearNew.clear();
	}

	/**
	 * A watched item turned a good time to buy ({@code buy}) or stopped being one (FlipBrain.gearStep, the brain's
	 * thread): RuneLite's notification ONCE per turn — remembered in "gearNotified" so a restart does not repeat it — and
	 * the Equipment tab flashes. Back to waiting clears it, so the next good time notifies again.
	 */
	private void gearEvent(FlipBrain.Gear g, boolean buy)
	{
		clientThread.invoke(() ->
		{
			Set<String> done = new LinkedHashSet<>();
			for (String x : (config.gearNotified() == null ? "" : config.gearNotified()).split(","))
			{
				if (!x.trim().isEmpty())
				{
					done.add(x.trim());
				}
			}
			String id = String.valueOf(g.itemId);
			boolean changed;
			if (buy)
			{
				changed = done.add(id);
				if (changed)
				{
					notifier.notify("Flip Sidebar: " + g.name + " - " + g.why);
					gearNew.add(g.itemId);
				}
			}
			else
			{
				changed = done.remove(id);
			}
			if (changed)
			{
				configManager.setConfiguration(FlipSidebarConfig.GROUP, "gearNotified", String.join(",", done));
			}
		});
	}

	/** A swing buy went in (FlipBrain): its item is held for {target, until} (TradeLog.swing). Any thread. */
	private void swingPlaced(int itemId, long target, long until)
	{
		clientThread.invoke(() ->
		{
			TradeLog t = tradeLog();
			if (t != null)
			{
				t.markSwing(itemId, target, until);
				saveTradeLog(t);
				snapshotDirty = true;
				journalItem("swing", itemId, o ->
				{
					o.addProperty("target", target);
					o.addProperty("until", until);
				});
			}
		});
	}

	/** "Hold long-term" unticked on a Holding row: its sell comes back on the card. */
	void releaseItem(int itemId)
	{
		clientThread.invoke(() ->
		{
			TradeLog t = tradeLog();
			if (t != null && t.release(itemId))
			{
				saveTradeLog(t);
				snapshotDirty = true;
				journalItem("release", itemId, o -> { });
			}
		});
	}

	/** The newest readable of the saved trade logs given (by TradeLog.savedAt; an older save without it counts 0); null if none. */
	static TradeLog newer(Gson gson, String... saved)
	{
		TradeLog t = null;
		for (String json : saved)
		{
			try
			{
				TradeLog c = json == null ? null : gson.fromJson(json, TradeLog.class);
				t = c != null && (t == null || c.savedAt > t.savedAt) ? c : t;
			}
			catch (JsonParseException e)
			{
				log.debug("Flip Sidebar: a saved trade log is unreadable - the other copy is used", e);
			}
		}
		return t;
	}

	/** The trade log files (TRADE_LOG_DIR), each RS profile's, into tradeLogFiles — off the client thread, at start. */
	private void readTradeLogFiles()
	{
		try
		{
			Filepath dir = getPluginDirectory().joinSegment(TRADE_LOG_DIR);
			if (!dir.isDirectory())
			{
				return;
			}
			try (java.util.stream.Stream<Filepath> files = dir.walk(1))
			{
				for (Filepath f : (Iterable<Filepath>) files::iterator)
				{
					String name = f.getFileName();
					if (name.endsWith(".json") && f.isFile())
					{
						tradeLogFiles.putIfAbsent(name.substring(0, name.length() - 5),
							new String(f.openInputStream().readAllBytes(), StandardCharsets.UTF_8));
					}
				}
			}
		}
		catch (IOException | IllegalArgumentException e)
		{
			log.debug("Flip Sidebar: trade log files unreadable - RuneLite's copy is used", e);
		}
	}

	// ── EVIDENCE RECORDER (1.60.31) — what is waiting to be collected in the GE ──────────────────────────────────────
	// User, 2026-11-05 19:40, of the cash total (142.56m against 116.14m coins + the GE's 31.6M): "make a tool record data
	// prove something dont guess". The panel's "in GE" counts only what is not yet bought / sold; what IS bought or sold
	// and still in a slot is counted nowhere. Which source says what is still waiting is NOT known yet: the GE_OFFER_* /
	// GE_COLLECT_* item containers (never read by this plugin before), or Flipping Copilot's way (its own count per slot,
	// cleared on a collect click — GrandExchangeCollectHandler). These journal events record all three so
	// ~/flip-finder/tools/collect_evidence.py can PROVE which one matches the inventory at each collect. Local only.

	/** Inventory coins / item counts last journaled ("inv" events only when they change). */
	private Map<Integer, Integer> journaledInv = Map.of();

	/** A container change: any GE-range container (ids 500-560) in full, and the inventory's coins + offer items. */
	private void recordContainer(int id, ItemContainer c)
	{
		TradeJournal j = journal;
		if (j == null || c == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		if (id >= 500 && id <= 560)
		{
			JsonObject o = new JsonObject();
			o.addProperty("id", id);
			JsonArray items = new JsonArray();
			for (Item it : c.getItems())
			{
				if (it.getId() > 0 && it.getQuantity() > 0)
				{
					JsonArray x = new JsonArray();
					x.add(it.getId());
					x.add(it.getQuantity());
					items.add(x);
				}
			}
			o.add("items", items);
			o.addProperty("geOpen", isGeOpen());
			o.addProperty("tick", client.getTickCount());
			j.log("ge_container", o);
		}
		else if (id == InventoryID.INV)
		{
			Map<Integer, Integer> now = new HashMap<>();
			now.put(ItemID.COINS, (int) Math.min(Integer.MAX_VALUE, coins(c)));
			GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
			Map<Integer, Integer> counts = itemCounts(c);
			for (GrandExchangeOffer o : offers == null ? new GrandExchangeOffer[0] : offers)
			{
				if (o != null && o.getItemId() > 0)
				{
					now.put(o.getItemId(), counts.getOrDefault(o.getItemId(), 0));
				}
			}
			if (!now.equals(journaledInv))
			{
				journaledInv = now;
				JsonObject o = new JsonObject();
				o.add("counts", gson.toJsonTree(now));
				o.addProperty("tick", client.getTickCount());
				j.log("inv", o);
			}
		}
	}

	/** A menu click that may collect from the GE or its collection box (or modify / abort an offer). */
	private void recordMenu(MenuOptionClicked e)
	{
		TradeJournal j = journal;
		String option = e.getMenuOption();
		if (j == null || option == null)
		{
			return;
		}
		boolean ge = isGeOpen();
		if (!(option.contains("Collect") || (ge && (option.contains("Bank") || option.startsWith(MODIFY)
			|| option.startsWith("Abort")))))
		{
			return;
		}
		JsonObject o = new JsonObject();
		o.addProperty("option", option);
		o.addProperty("target", e.getMenuTarget() == null ? "" : e.getMenuTarget().replaceAll("<[^>]*>", ""));
		o.addProperty("param0", e.getParam0());
		o.addProperty("param1", e.getParam1());
		o.addProperty("action", e.getMenuAction() == null ? "" : e.getMenuAction().name());
		o.addProperty("geOpen", ge);
		o.addProperty("selectedSlot", client.getVarbitValue(VarbitID.GE_SELECTEDSLOT));
		o.addProperty("tick", client.getTickCount());
		j.log("menu", o);
	}

	/**
	 * The GE highlights changed (FlipSidebarHighlightOverlay.noteLit, 1.60.97): what is lit now ("none" = nothing), and
	 * the card and re-pricing it is for. A sell's box on "inventory[..] item N" with N the note's id is the 1.60.94 fix
	 * working; "slot N[1]" (the Sell arrow) on a sell card means the item was not found in the inventory.
	 */
	void journalHighlight(String lit)
	{
		TradeJournal j = journal;
		if (j == null)
		{
			return;
		}
		JsonObject o = new JsonObject();
		o.addProperty("lit", lit.isEmpty() ? "none" : lit);
		Suggestion s = getSuggestion();
		if (s != null)
		{
			o.addProperty("card", s.action);
			o.addProperty("item", s.itemId);
		}
		Modify m = modifying();
		if (m != null)
		{
			o.addProperty("modify", m.itemId);
		}
		j.log("highlight", o);
	}

	/** One journal event about an item, with the market for it now. */
	private void journalItem(String event, int itemId, Consumer<JsonObject> fill)
	{
		TradeJournal j = journal;
		if (j == null)
		{
			return;
		}
		JsonObject o = new JsonObject();
		o.addProperty("item", itemId);
		fill.accept(o);
		o.add("mk", brain.marketNow(itemId));
		j.log(event, o);
	}

	/**
	 * ⭐ LIVE RULES (FlipEngine.applyRules): plugin-data/flip-sidebar/rules.json, read when it changed, every minute on
	 * the executor — never the client thread. A missing file is written with the numbers in use, to edit. Each change is
	 * a "rules" line in the journal (what was in force when). An unreadable file changes nothing.
	 */
	private void rulesTick()
	{
		try
		{
			Filepath f = getPluginDirectory().joinSegment(RULES_FILE);
			if (!f.exists())
			{
				f.write(gson.newBuilder().setPrettyPrinting().create().toJson(FlipEngine.rules()));
				rulesAt = f.getLastModifiedTime().toMillis();
				return;
			}
			WikiPrices.viaServer = config.serverPrices() && config.livePrices();
			profilesTick();
			bigBrainTick();
			gearAddTick();
			long at = f.getLastModifiedTime().toMillis();
			if (at == rulesAt)
			{
				return;
			}
			JsonObject o;
			try (BufferedReader r = f.openBufferedReader())
			{
				o = gson.fromJson(r, JsonObject.class);
			}
			rulesAt = at;
			JsonObject now = FlipEngine.applyRules(o);
			log.debug("Flip Sidebar: rules {}", now);
			TradeJournal j = journal;
			if (j != null)
			{
				j.log("rules", now.deepCopy());
			}
			brain.rescan();
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("Flip Sidebar: rules.json unreadable - rules unchanged", e);
		}
	}

	/** When the big brain was last asked of the server (bigBrainTick); 0 = not yet. */
	private volatile long bigBrainAt;
	/** The big brain is asked of the server this often (it is rebuilt every 6 hours). */
	static final long BIG_BRAIN_EVERY_MS = 60 * 60_000L;

	/**
	 * ⭐ THE BIG BRAIN FROM THE SERVER (1.60.52; the "bigBrain" setting, off by default, with Live prices): GET
	 * TeamSync.BASE/v1/profiles — the per-item profiles the author's PC uploads after each 6-hourly rebuild
	 * (~/flip-finder/bigbrain/build_profiles.py) — applied when newer than those in use (a local profiles.json may be).
	 * Once an hour at most, on OkHttp's pool. Each new one is a "profiles" line in the journal (source: server).
	 */
	private void bigBrainTick()
	{
		long now = System.currentTimeMillis();
		if (!config.bigBrain() || !config.livePrices() || now - bigBrainAt < BIG_BRAIN_EVERY_MS)
		{
			return;
		}
		bigBrainAt = now;
		okHttpClient.newCall(new okhttp3.Request.Builder().url(TeamSync.BASE + "/v1/profiles")
			.header("User-Agent", WikiPrices.USER_AGENT).build()).enqueue(new okhttp3.Callback()
		{
			@Override
			public void onFailure(okhttp3.Call call, IOException e)
			{
				log.debug("Flip Sidebar: big brain download failed", e);
			}

			@Override
			public void onResponse(okhttp3.Call call, okhttp3.Response response)
			{
				try (okhttp3.Response r = response)
				{
					okhttp3.ResponseBody b = r.body();
					if (!r.isSuccessful() || b == null)
					{
						return;
					}
					JsonObject o = gson.fromJson(b.charStream(), JsonObject.class);
					if (FlipEngine.applyProfilesIfNewer(o))
					{
						TradeJournal j = journal;
						if (j != null)
						{
							JsonObject e = new JsonObject();
							e.addProperty("built", FlipEngine.profilesBuilt);
							e.addProperty("source", "server");
							e.addProperty("cycles", FlipEngine.cycles.size());
							e.addProperty("avg30", FlipEngine.avg30.size());
							j.log("profiles", e);
						}
						brain.rescan();
					}
				}
				catch (RuntimeException e)
				{
					log.debug("Flip Sidebar: big brain unreadable", e);
				}
			}
		});
	}

	/** gear_add.json → the Equipment watch (GEAR_ADD_FILE). The executor; the setting is written on the client thread. */
	private void gearAddTick()
	{
		try
		{
			Filepath f = getPluginDirectory().joinSegment(GEAR_ADD_FILE);
			if (!f.exists())
			{
				return;
			}
			JsonArray a;
			try (BufferedReader r = f.openBufferedReader())
			{
				a = gson.fromJson(r, JsonArray.class);
			}
			f.delete();
			List<Integer> add = new ArrayList<>();
			for (int i = 0; a != null && i < a.size(); i++)
			{
				try
				{
					add.add(a.get(i).getAsInt());
				}
				catch (RuntimeException notANumber)
				{
					List<Integer> hit = brain.searchItems(a.get(i).getAsString(), 1);
					if (!hit.isEmpty())
					{
						add.add(hit.get(0));
					}
				}
			}
			clientThread.invoke(() ->
			{
				List<String> ids = new ArrayList<>();
				for (String x : (config.gearWatch() == null ? "" : config.gearWatch()).split(","))
				{
					if (!x.trim().isEmpty() && !ids.contains(x.trim()))
					{
						ids.add(x.trim());
					}
				}
				for (int id : add)
				{
					if (id > 0 && !ids.contains(String.valueOf(id)) && ids.size() < 12)
					{
						ids.add(String.valueOf(id));
					}
				}
				configManager.setConfiguration(FlipSidebarConfig.GROUP, "gearWatch", String.join(",", ids));
				brain.gearChanged();
			});
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("Flip Sidebar: gear_add.json unreadable", e);
		}
	}

	/**
	 * The big brain's per-item facts: plugin-data/flip-sidebar/profiles.json, read when it changed (rulesTick's minute, the
	 * executor). Missing = none. Each new file is a "profiles" line in the journal (built time, items, cycles).
	 */
	private void profilesTick()
	{
		try
		{
			Filepath f = getPluginDirectory().joinSegment(PROFILES_FILE);
			if (!f.exists())
			{
				return;
			}
			long at = f.getLastModifiedTime().toMillis();
			if (at == profilesAt)
			{
				return;
			}
			JsonObject o;
			try (BufferedReader r = f.openBufferedReader())
			{
				o = gson.fromJson(r, JsonObject.class);
			}
			profilesAt = at;
			if (!FlipEngine.applyProfilesIfNewer(o))
			{
				return;               // the server's are newer (bigBrainTick)
			}
			TradeJournal j = journal;
			if (j != null)
			{
				JsonObject e = new JsonObject();
				e.addProperty("built", o != null && o.has("built") ? o.get("built").getAsLong() : 0);
				e.addProperty("cycles", FlipEngine.cycles.size());
				e.addProperty("avg30", FlipEngine.avg30.size());
				j.log("profiles", e);
			}
			brain.rescan();
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("Flip Sidebar: profiles.json unreadable - profiles unchanged", e);
		}
	}

	/** The journal's folder for the character logged in, and a session line with the settings when it is new. */
	private void journalLogin()
	{
		TradeJournal j = journal;
		if (j == null || client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null
			|| client.getLocalPlayer().getName() == null)
		{
			return;
		}
		try
		{
			if (j.setDir(getPluginDirectory().joinSegment(playerName()).joinSegment("journal")))
			{
				JsonObject o = new JsonObject();
				o.addProperty("format", JOURNAL_FORMAT);
				o.addProperty("livePrices", config.livePrices());
				o.addProperty("risk", config.riskLevel().name());
				o.addProperty("window", config.adjustWindow().name());
				o.addProperty("minProfit", config.minProfit());
				o.addProperty("maxCashMillions", config.maxCashMillions());
				o.addProperty("freeToPlay", config.freeToPlay());
				o.addProperty("sellOnly", config.sellOnly());
				o.addProperty("swingTrades", config.swingTrades());
				o.addProperty("holdLimit", config.holdLimit());
				j.log("session", o);
			}
		}
		catch (IOException | IllegalArgumentException e)
		{
			log.debug("Flip Sidebar: no journal folder", e);
		}
	}

	/** How many of {@code itemId} the container holds, noted ones included. */
	private long count(ItemContainer c, int itemId)
	{
		long n = 0;
		for (Item it : c == null ? new Item[0] : c.getItems())
		{
			if (it.getId() > 0 && itemManager.canonicalize(it.getId()) == itemId)
			{
				n += it.getQuantity();
			}
		}
		return n;
	}

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

	// ── Data sync (TeamSync): this account's flips up, the team's down ──────────

	/** Every 30 minutes, and when the setting is turned on. Executor thread. */
	/**
	 * Data sync: the player's GE offers (OfferShare) to the Worker, at most TeamSync.MAX_OFFERS a time, every 10
	 * minutes; what fails stays waiting for the next. With Data sync and Live prices on only.
	 */
	private void offerTick()
	{
		if (!config.dataSync() || !config.livePrices() || TeamSync.BASE.isEmpty() || otherEconomy
			|| offerShare.waitingCount() == 0)
		{
			return;
		}
		clientThread.invoke(() ->
		{
			if (configManager.getRSProfileKey() == null)
			{
				return;
			}
			String tag = teamTag();
			List<JsonArray> rows = offerShare.take(TeamSync.MAX_OFFERS);
			if (!rows.isEmpty())
			{
				teamSync.uploadOffers(tag, rows, ok ->
				{
					if (ok)
					{
						offerShare.sent(rows);
					}
				});
			}
		});
	}

	private void teamTick()
	{
		if (!config.dataSync() || !config.livePrices() || TeamSync.BASE.isEmpty() || otherEconomy)
		{
			return;
		}
		clientThread.invoke(() ->
		{
			TradeLog t = tradeLog();
			String profile = configManager.getRSProfileKey();
			if (t == null || profile == null)
			{
				return;
			}
			Map<String, double[]> mine = new LinkedHashMap<>(brain.ownFlips(t.flips()));
			mine.keySet().removeAll(teamSent);
			String tag = teamTag();
			if (!mine.isEmpty())
			{
				teamSync.upload(tag, mine, ok ->
				{
					if (ok)
					{
						teamSent.addAll(mine.keySet());
					}
				});
			}
			teamSync.download(team -> clientThread.invoke(() ->
			{
				if (team != null && profile.equals(tradeLogProfile))
				{
					brain.setTeam(team);
				}
			}));
		});
	}

	// ── Accounts (Account): the panel's Log in, and every finished flip to the user's private page ─────────

	/** The account row's text for the panel: {state, Discord name}; state = "in", "out" or an accountNote. */
	String[] accountState()
	{
		String note = accountNote;
		boolean in = !config.accountToken().isEmpty();
		if (note != null && !(in && "waiting".equals(note)))
		{
			return new String[]{note, config.accountName()};
		}
		return new String[]{in ? "in" : "out", config.accountName()};
	}

	private void accountChanged()
	{
		SwingUtilities.invokeLater(() ->
		{
			FlipSidebarPanel now = panel;
			if (now != null)
			{
				now.showAccount();
			}
		});
	}

	/** The panel's Log in: the login link in the browser, then the Worker asked every 3 seconds for 10 minutes. EDT. */
	void accountLogin()
	{
		if (!config.dataSync())
		{
			accountNote = "sync";                          // the account's flips go up with Data sync (its IP warning)
			accountChanged();
			return;
		}
		stopLoginPoll();
		accountNote = "waiting";
		accountChanged();
		account.start(started ->
		{
			if (started == null)
			{
				accountNote = "failed";
				accountChanged();
				return;
			}
			LinkBrowser.browse(started[2]);
			long until = System.currentTimeMillis() + 10 * 60 * 1000L;
			loginPoll = executor.scheduleWithFixedDelay(() ->
			{
				if (System.currentTimeMillis() > until)
				{
					stopLoginPoll();
					accountNote = "expired";
					accountChanged();
					return;
				}
				account.poll(started[0], started[1], got ->
				{
					if (got == null || got.length == 2)
					{
						stopLoginPoll();
					}
					if (got == null)
					{
						accountNote = "expired";
					}
					else if (got.length == 2)
					{
						configManager.setConfiguration(FlipSidebarConfig.GROUP, "accountToken", got[0]);
						configManager.setConfiguration(FlipSidebarConfig.GROUP, "accountName", got[1]);
						accountNote = null;
						accountSent.clear();
						executor.execute(this::accountTick);   // the flips kept so far go up now
					}
					accountChanged();
				});
			}, 3, 3, TimeUnit.SECONDS);
		});
	}

	/** Stop waiting for the browser login (the panel's Cancel). */
	void accountCancel()
	{
		stopLoginPoll();
		accountNote = null;
		accountChanged();
	}

	private void stopLoginPoll()
	{
		ScheduledFuture<?> p = loginPoll;
		loginPoll = null;
		if (p != null)
		{
			p.cancel(false);
		}
	}

	/** The panel's Log out: the login ends on the server too. What is already on the page stays until deleted there. */
	void accountLogout()
	{
		String token = config.accountToken();
		if (!token.isEmpty())
		{
			account.logout(token);
		}
		configManager.unsetConfiguration(FlipSidebarConfig.GROUP, "accountToken");
		configManager.unsetConfiguration(FlipSidebarConfig.GROUP, "accountName");
		accountNote = null;
		accountSent.clear();
		accountChanged();
	}

	/** The panel's "My page": the website's private page in the browser. */
	void accountPage()
	{
		LinkBrowser.browse(Account.PAGE);
	}

	/**
	 * Logged in with Data sync on: the finished flips not yet on the page (or changed since — a sale still filling) go up
	 * with this character's name, at most Account.MAX_FLIPS a time. Every 2 minutes; executor thread.
	 */
	private void accountTick()
	{
		String token = config.accountToken();
		if (token.isEmpty() || !config.dataSync() || otherEconomy)
		{
			return;
		}
		clientThread.invoke(() ->
		{
			TradeLog t = tradeLog();
			Player me = client.getLocalPlayer();
			String who = me == null ? null : me.getName();
			if (t == null || who == null || who.isEmpty() || configManager.getRSProfileKey() == null)
			{
				return;
			}
			String tag = teamTag();
			List<JsonArray> rows = new ArrayList<>();
			Map<String, String> sending = new LinkedHashMap<>();
			for (TradeLog.Flip f : t.flips())
			{
				JsonArray r = Account.row(f);
				String key = tag + "|" + r.get(0).getAsString(), sig = f.qty + ":" + f.profit + ":" + f.at;
				if (!sig.equals(accountSent.get(key)) && rows.size() < Account.MAX_FLIPS)
				{
					rows.add(r);
					sending.put(key, sig);
				}
			}
			if (rows.isEmpty())
			{
				return;
			}
			account.upload(token, tag, who.replace('\u00a0', ' '), rows, code ->
			{
				if (code == 200)
				{
					accountSent.putAll(sending);
				}
				else if (code == 401 && token.equals(config.accountToken()))
				{
					// the login was ended on the server (logged out on the website, or the account deleted)
					configManager.unsetConfiguration(FlipSidebarConfig.GROUP, "accountToken");
					accountNote = "ended";
					accountChanged();
				}
			});
		});
	}

	/** This account's random team tag — never derived from the name. Made once, kept per account. Client thread. */
	private String teamTag()
	{
		String tag = configManager.getRSProfileConfiguration(FlipSidebarConfig.GROUP, TEAM_TAG_KEY);
		if (tag == null || !tag.matches("[0-9a-f]{16}"))
		{
			byte[] b = new byte[8];
			new java.security.SecureRandom().nextBytes(b);
			StringBuilder h = new StringBuilder();
			for (byte x : b)
			{
				h.append(String.format("%02x", x));
			}
			tag = h.toString();
			configManager.setRSProfileConfiguration(FlipSidebarConfig.GROUP, TEAM_TAG_KEY, tag);
		}
		return tag;
	}

	/** The team's flips saved last time (their own file), into the learner. */
	private void loadTeamFile(String profile)
	{
		executor.execute(() ->
		{
			Map<String, double[]> team;
			try
			{
				Filepath f = getPluginDirectory().joinSegment(TEAM_DIR).joinSegment(profile + ".json");
				if (!f.exists())
				{
					return;
				}
				try (BufferedReader r = f.openBufferedReader())
				{
					team = gson.fromJson(r, new com.google.gson.reflect.TypeToken<Map<String, double[]>>() { }.getType());
				}
			}
			catch (IOException | IllegalArgumentException | JsonParseException e)
			{
				log.debug("Flip Sidebar: team file unreadable", e);
				return;
			}
			if (team != null && !team.isEmpty())
			{
				clientThread.invoke(() ->
				{
					if (profile.equals(tradeLogProfile) && brain.teamSize() == 0)
					{
						brain.setTeam(team);
					}
				});
			}
		});
	}

	// ── the flip archive: every finished flip, all time ─────────────────────────

	private Filepath archiveFile(String profile) throws IOException
	{
		return getPluginDirectory().joinSegment(FLIP_ARCHIVE_DIR).joinSegment(profile + ".jsonl");
	}

	/** The account's archive, read off the client thread; the trade log's rows (newer) win; rows missing from the file are written. */
	private void loadArchive(String profile, TradeLog t)
	{
		archiveProfile = profile;
		archive.clear();
		archiveRecord = Map.of();
		archiveWeek = List.of();
		List<TradeLog.Flip> recent = t.flips();
		t.takeFresh();                          // what the trade log holds now goes in below, not twice
		executor.execute(() ->
		{
			Map<String, TradeLog.Flip> read = new LinkedHashMap<>();
			try
			{
				Filepath f = archiveFile(profile);
				if (f.exists())
				{
					try (BufferedReader r = f.openBufferedReader())
					{
						for (String line = r.readLine(); line != null; line = r.readLine())
						{
							try
							{
								TradeLog.Flip x = gson.fromJson(line, TradeLog.Flip.class);
								if (x != null)
								{
									read.put(TradeLog.archiveKey(x), x);
								}
							}
							catch (JsonParseException bad)
							{
								log.debug("Flip Sidebar: archive line skipped", bad);
							}
						}
					}
				}
			}
			catch (IOException | IllegalArgumentException e)
			{
				log.debug("Flip Sidebar: flip archive unreadable", e);
			}
			StringBuilder missing = new StringBuilder();
			List<TradeLog.Flip> oldestFirst = new ArrayList<>(recent);
			Collections.reverse(oldestFirst);
			for (TradeLog.Flip x : oldestFirst)
			{
				TradeLog.Flip had = read.put(TradeLog.archiveKey(x), x);
				if (had == null || had.qty != x.qty)
				{
					missing.append(gson.toJson(x)).append('\n');
				}
			}
			appendArchive(profile, missing.toString());
			if (profile.equals(archiveProfile))
			{
				synchronized (archive)
				{
					Map<String, TradeLog.Flip> merged = new LinkedHashMap<>(read);
					merged.putAll(archive);           // anything recorded while reading
					archive.clear();
					archive.putAll(merged);
				}
				archiveViews();
			}
		});
	}

	/** The rows made or grown since the last save: into the archive (memory and file). Client thread. */
	private void archiveFresh(TradeLog t)
	{
		String profile = archiveProfile;
		List<TradeLog.Flip> fresh = t.takeFresh();
		if (profile == null || fresh.isEmpty())
		{
			return;
		}
		StringBuilder lines = new StringBuilder();
		for (TradeLog.Flip f : fresh)
		{
			archive.put(TradeLog.archiveKey(f), f);
			lines.append(gson.toJson(f)).append('\n');
		}
		archiveViews();
		String text = lines.toString();
		executor.execute(() -> appendArchive(profile, text));
	}

	/** Executor thread. */
	private void appendArchive(String profile, String text)
	{
		if (text.isEmpty())
		{
			return;
		}
		try
		{
			Filepath f = archiveFile(profile);
			f.getParent().createDirectories();
			try (OutputStream out = f.openOutputStream(StandardOpenOption.CREATE, StandardOpenOption.APPEND))
			{
				out.write(text.getBytes(StandardCharsets.UTF_8));
			}
		}
		catch (IOException | IllegalArgumentException e)
		{
			log.debug("Flip Sidebar: could not add to the flip archive", e);
		}
	}

	/** Every finished flip, newest first — the Statistics window (any thread). */
	List<GameSnapshot.Done> allFlips()
	{
		List<TradeLog.Flip> rows;
		synchronized (archive)
		{
			rows = new ArrayList<>(archive.values());
		}
		List<GameSnapshot.Done> out = new ArrayList<>(rows.size());
		for (TradeLog.Flip f : rows)
		{
			String name = brain.itemName(f.itemId);
			out.add(done(f, name == null ? "Item " + f.itemId : name));
		}
		out.sort((a, b) -> Long.compare(b.at, a.at));
		return out;
	}

	private static GameSnapshot.Done done(TradeLog.Flip f, String name)
	{
		return new GameSnapshot.Done(f.itemId, name, f.qty, f.buyEach, f.sellEach, f.profit, f.at, f.offer, f.boughtAt,
			f.heldMs == null ? -1 : f.heldMs);
	}

	/**
	 * The archive's per-item record (all time) and its last week, newest first — GameSnapshot.record and .week, for
	 * FlipLearner (proven, resting). Rebuilt when the archive changes; read by the snapshot. Any thread.
	 */
	private volatile Map<Integer, long[]> archiveRecord = Map.of();
	private volatile List<GameSnapshot.Done> archiveWeek = List.of();

	private void archiveViews()
	{
		List<GameSnapshot.Done> all = allFlips();
		long weekAgo = System.currentTimeMillis() - FlipLearner.WEEK_MS;
		List<GameSnapshot.Done> week = new ArrayList<>();
		for (GameSnapshot.Done d : all)
		{
			if (d.at >= weekAgo)
			{
				week.add(d);
			}
		}
		archiveRecord = all.isEmpty() ? Map.of() : FlipLearner.record(all);
		archiveWeek = week;
		snapshotDirty = true;
	}

	// ── export / import what it has learned (BrainFile) ─────────────────────────

	/** The plugin's own settings (not the per-account ones): key -> value. Client thread. */
	private Map<String, String> settingsNow()
	{
		Map<String, String> out = new TreeMap<>();
		String prefix = FlipSidebarConfig.GROUP + ".";
		for (String full : configManager.getConfigurationKeys(prefix))
		{
			String key = full.substring(prefix.length());
			String value = configManager.getConfiguration(FlipSidebarConfig.GROUP, key);
			if (!key.startsWith("rsprofile.") && value != null)
			{
				out.put(key, value);
			}
		}
		return out;
	}

	/** Everything learned now, as a brain file (the export, and the import's backup). Client thread; null when logged out. */
	private String brainNow()
	{
		TradeLog t = tradeLog();
		if (t == null)
		{
			return null;
		}
		archiveFresh(t);
		List<TradeLog.Flip> rows;
		synchronized (archive)
		{
			rows = new ArrayList<>(archive.values());
		}
		rows.sort((a, b) -> Long.compare(a.at, b.at));
		return BrainFile.write(gson, brain.learnerJson(gson), gson.toJson(t), settingsNow(), playerName(),
			System.currentTimeMillis(), rows);
	}

	private static void tell(Component parent, String text, int kind)
	{
		SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(parent, text, BRAIN_TITLE, kind));
	}

	/** The Statistics window's "Export data": what this account has learned, to a file the user picks. Swing thread. */
	void exportBrain(Component parent)
	{
		clientThread.invoke(() ->
		{
			String json = brainNow();
			if (json == null)
			{
				tell(parent, "Log in first - what the plugin learns is kept per account.", JOptionPane.INFORMATION_MESSAGE);
				return;
			}
			BrainFile.Read now = BrainFile.read(gson, json);
			String summary = BrainFile.summary(now.learning, now.tradeLog, now.settings.size())
				+ (now.flips == null ? "" : String.format(" · %,d flips in all", now.flips.size()));
			String name = "flip-sidebar-data-" + new SimpleDateFormat("yyyy-MM-dd").format(new Date()) + ".json";
			SwingUtilities.invokeLater(() ->
			{
				List<Filepath> picked = new Filepath.Chooser().setIsSave().setAcceptsFiles()
					.setDialogTitle("Export what Flip Sidebar has learned").addExtensionFilter("Flip Sidebar data", "json")
					.setDefaultExtension("json").setFileName(name).showDialog(parent);
				if (picked == null || picked.isEmpty())
				{
					return;
				}
				Filepath out = picked.get(0);
				executor.execute(() ->
				{
					try
					{
						out.write(json.getBytes(StandardCharsets.UTF_8));
						tell(parent, "Saved " + out.getFileName() + "\n\n" + summary + "\n\nLoad it with Import data (the Statistics window) "
							+ "on any PC, logged in to this account.", JOptionPane.INFORMATION_MESSAGE);
					}
					catch (IOException | IllegalArgumentException e)
					{
						log.debug("Flip Sidebar: export failed", e);
						tell(parent, "Could not save the file: " + e.getMessage(), JOptionPane.ERROR_MESSAGE);
					}
				});
			});
		});
	}

	/**
	 * The Statistics window's "Import data": a data file REPLACES what this account has learned, after the user sees both and
	 * says yes — what was there is saved first (plugin-data/flip-sidebar/backups/before-import-*.json). Swing thread.
	 */
	void importBrain(Component parent)
	{
		List<Filepath> picked = new Filepath.Chooser().setIsOpen().setAcceptsFiles()
			.setDialogTitle("Import what Flip Sidebar has learned").addExtensionFilter("Flip Sidebar data", "json")
			.showDialog(parent);
		if (picked == null || picked.isEmpty())
		{
			return;
		}
		Filepath in = picked.get(0);
		executor.execute(() ->
		{
			String text;
			try (InputStream s = in.openInputStream())
			{
				text = new String(s.readAllBytes(), StandardCharsets.UTF_8);
			}
			catch (IOException | IllegalArgumentException e)
			{
				tell(parent, "Could not read the file: " + e.getMessage(), JOptionPane.ERROR_MESSAGE);
				return;
			}
			BrainFile.Read r = BrainFile.read(gson, text);
			if (r.error != null)
			{
				tell(parent, r.error, JOptionPane.ERROR_MESSAGE);
				return;
			}
			clientThread.invoke(() ->
			{
				String backup = brainNow();
				String profile = configManager.getRSProfileKey();
				if (backup == null || profile == null)
				{
					tell(parent, "Log in first - what the plugin learns is kept per account.", JOptionPane.INFORMATION_MESSAGE);
					return;
				}
				BrainFile.Read now = BrainFile.read(gson, backup);
				String me = playerName();
				String other = r.player != null && !r.player.equalsIgnoreCase(me)
					? "\n\n⚠ The file is from " + r.player + " - you are logged in as " + me + "." : "";
				String saved = r.exportedAt > 0 ? " (saved " + new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(r.exportedAt))
					+ ")" : "";
				String ask = r.team
					? "Import the TEAM data " + in.getFileName() + saved + "?\n\n"
					+ "In the file:  " + (r.learning == null ? 0 : r.learning.teamSize()) + " flips from " + r.players + " player"
					+ (r.players == 1 ? "" : "s") + "\nHere now:  " + BrainFile.summary(now.learning, now.tradeLog, now.settings.size())
					+ "\n\nThis ADDS the team's flips to what this account learned - nothing of yours is replaced (a team\n"
					+ "file imported before is swapped for this one). A copy of what is here now is saved first, in the\n"
					+ "plugin's folder: backups/before-import-....json"
					: "Import " + in.getFileName() + saved + "?\n\n"
					+ "In the file:  " + BrainFile.summary(r.learning, r.tradeLog, r.settings.size())
					+ (r.flips == null ? "" : String.format(" · %,d flips in all", r.flips.size())) + "\n"
					+ "Here now:  " + BrainFile.summary(now.learning, now.tradeLog, now.settings.size())
					+ (now.flips == null ? "" : String.format(" · %,d flips in all", now.flips.size())) + other + "\n\n"
					+ "This REPLACES this account's " + replaced(r) + ". What is here now is saved first,\n"
					+ "in the plugin's folder: backups/before-import-....json";
				SwingUtilities.invokeLater(() ->
				{
					if (JOptionPane.showConfirmDialog(parent, ask, BRAIN_TITLE, JOptionPane.YES_NO_OPTION,
						JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION)
					{
						clientThread.invoke(() -> applyImport(parent, profile, r, backup));
					}
				});
			});
		});
	}

	/** What an import replaces, in words: only the parts the file has (a starter file has the learning only). */
	static String replaced(BrainFile.Read r)
	{
		List<String> parts = new ArrayList<>();
		if (r.learning != null)
		{
			parts.add("learning (flips measured, items resting)");
		}
		if (r.tradeLog != null)
		{
			parts.add("trade log (finished flips, holds, buy limits)");
		}
		if (!r.settings.isEmpty())
		{
			parts.add("settings");
		}
		return String.join(", ", parts);
	}

	/** Puts an imported brain in place of this account's. Client thread. */
	private void applyImport(Component parent, String profile, BrainFile.Read r, String backup)
	{
		if (!profile.equals(configManager.getRSProfileKey()))
		{
			tell(parent, "The account changed - nothing was imported.", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		write(BACKUP_DIR, "before-import-" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date()) + ".json", backup);
		if (r.team)
		{
			// a team brain ADDS: this account's learning, with the team's flips (and rests) in
			FlipLearner mine = gson.fromJson(brain.learnerJson(gson), FlipLearner.class);
			configManager.setRSProfileConfiguration(FlipSidebarConfig.GROUP, LEARNING_KEY,
				gson.toJson(mine.withTeam(r.learning)));
			tradeLogProfile = null;       // the learner again, from what was just put there (it learns the floors at once)
			tradeLog();
			snapshotDirty = true;
			tell(parent, "The team's flips are in. What was here before is in the plugin's folder:\n"
				+ "backups/before-import-....json", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		if (r.flips != null)
		{
			// the file's whole flip history replaces this account's archive (written before the reload reads it)
			StringBuilder lines = new StringBuilder();
			for (TradeLog.Flip f : r.flips)
			{
				lines.append(gson.toJson(f)).append('\n');
			}
			String text = lines.toString();
			executor.execute(() ->
			{
				try
				{
					Filepath f = archiveFile(profile);
					f.getParent().createDirectories();
					f.write(text.getBytes(StandardCharsets.UTF_8));
				}
				catch (IOException | IllegalArgumentException e)
				{
					log.debug("Flip Sidebar: could not write the imported flip archive", e);
				}
			});
		}
		if (r.tradeLog != null)
		{
			r.tradeLog.savedAt = System.currentTimeMillis();     // newer than both saved copies: it wins at the reload
			String json = gson.toJson(r.tradeLog);
			configManager.setRSProfileConfiguration(FlipSidebarConfig.GROUP, TRADE_LOG_KEY, json);
			tradeLogFiles.put(profile, json);
			write(TRADE_LOG_DIR, profile + ".json", json);
		}
		if (r.learningJson != null)
		{
			configManager.setRSProfileConfiguration(FlipSidebarConfig.GROUP, LEARNING_KEY, r.learningJson);
		}
		Map<String, String> now = settingsNow();
		for (Map.Entry<String, String> e : r.settings.entrySet())
		{
			if (!e.getValue().equals(now.get(e.getKey())))
			{
				configManager.setConfiguration(FlipSidebarConfig.GROUP, e.getKey(), e.getValue());
			}
		}
		tradeLogProfile = null;           // load the trade log and the learner again, from what was just put there
		tradeLog();
		snapshotDirty = true;
		tell(parent, "Imported. What was here before is in the plugin's folder:\nbackups/before-import-....json",
			JOptionPane.INFORMATION_MESSAGE);
	}

	@Provides
	FlipSidebarConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(FlipSidebarConfig.class);
	}
}
