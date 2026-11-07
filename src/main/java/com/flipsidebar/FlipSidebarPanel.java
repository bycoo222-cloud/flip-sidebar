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
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.ToolTipManager;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;

/**
 * The side panel: the flip to do now (with Placed / Skip), your coins, your open offers with
 * what to do about each, what you hold, the session, and your finished flips.
 *
 * <p>⚠ NOTHING IS REBUILT WHILE IT IS ON SCREEN. Every row exists from the start and only its
 * text changes, or it is shown / hidden: a list rebuilt while a scan runs flickers and is
 * "really fustrating to look at" (user).</p>
 *
 * <p>Swing thread only: {@link #show} is called through SwingUtilities.invokeLater.</p>
 */
class FlipSidebarPanel extends PluginPanel
{
	// the theme's colours (FlipTheme, the "Look" setting) — set by useTheme() before a panel is built
	private static Color GREEN;
	private static Color ORANGE;
	private static Color YELLOW;
	private static Color RED;
	private static Color AMBER;
	/** The mode banner: dark amber in High alch mode, dark green when flipping (user: "make it more obvios"). */
	private static Color ALCH_BG;
	private static Color FLIP_BG;
	private static Color DIM;
	private static Color WHITE;
	private static Color CARD;
	private static Color HOVER;
	private static Color BACKGROUND;

	static
	{
		useTheme(FlipTheme.now);
	}

	/** The colours of {@code t} for the next panel built (Swing thread, before the constructor). */
	static void useTheme(FlipTheme t)
	{
		GREEN = t.green;
		ORANGE = t.orange;
		YELLOW = t.yellow;
		RED = t.red;
		AMBER = t.amber;
		ALCH_BG = t.alchBg;
		FLIP_BG = t.flipBg;
		DIM = t.dim;
		WHITE = t.text;
		CARD = t.card;
		HOVER = t.hover;
		BACKGROUND = t.background;
	}
	/** Text width for wrapping — narrow enough for the card, whose border and padding are widest. */
	private static final int WRAP_PX = 150;
	private static final int LOOKUP_HITS = 6;
	/** An open lookup is fetched again this often (prices move). */
	private static final long LOOKUP_REFRESH_MS = 60_000;
	private static final int OFFER_ROWS = 8;
	private static final int NEXT_ROWS = 6;
	/** Every flip TradeLog keeps; the newest HISTORY_SHOWN until "Show all" is clicked. */
	private static final int HISTORY_ROWS = TradeLog.FLIPS_KEPT;
	private static final int HISTORY_SHOWN = 25;
	private static final long DAY_MS = 24L * 3600 * 1000;
	private static final int HELD_ROWS = 12;
	/** Rows in Not suggested (the rest are counted in its title). */
	private static final int OFF_ROWS = 40;
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

	/** The item opened in the GE that is not the card's — the overlay's "Your pick", here too (showOwnPick). */
	private final JPanel ownBox = box();
	private final JLabel ownName = label("", WHITE, FlipTheme.now.bold());
	private final JLabel ownLimit = value(WHITE);
	private final JLabel ownResets = value(DIM);
	private final JLabel ownQty = value(YELLOW);
	private final JLabel ownPrice = value(YELLOW);
	private final JLabel ownSell = value(WHITE);
	private final JLabel ownProfit = value(GREEN);
	private final JPanel ownLimitRow = row("Limit left", ownLimit);
	private final JPanel ownResetsRow = row("Resets in", ownResets);
	private final JPanel ownQtyRow = row("Quantity", ownQty);
	private final JPanel ownPriceRow = row("Price", ownPrice);
	private final JPanel ownSellRow = row("Then sell at", ownSell);
	private final JPanel ownProfitRow = row("Profit", ownProfit);
	private final JLabel ownNote = label("", ORANGE, small());
	private final JPanel cardBox = box();
	/**
	 * ⭐ Which mode the panel is in, across its top (user, 2026-11-02: "make it more obvios if your in flip or alch mode.
	 * its easy to confuse right now" — they bought alch items meaning to flip).
	 */
	private final JPanel modeBox = box();
	private final JLabel modeLabel = label("", WHITE, FlipTheme.now.bold());
	/** The game's own High Level Alchemy spell icon, from the client (SpriteManager) — null until it has loaded. */
	private ImageIcon alchIcon;
	private final JLabel cardIcon = new JLabel();
	private final JLabel cardAction = label("", WHITE, FlipTheme.now.bold());
	private final JLabel cardName = label("", WHITE, FlipTheme.now.bold());
	private final JLabel limit = value(WHITE);
	private final JLabel qty = value(YELLOW);
	private final JLabel price = value(YELLOW);
	private final JLabel sellAt = value(WHITE);
	private final JLabel profit = value(GREEN);
	private final JLabel fill = value(WHITE);
	/** A BUY: the profit at the last hour's average sell price (Pick.likely) — what the card's profit usually comes to. */
	private final JLabel likely = value(GREEN);
	/** Rows, not a grid: a hidden row must not leave a gap (a GridLayout keeps its cell). */
	private final JPanel sellRow = row("Then sell at", sellAt);
	/** The GE buy limit left for a buy — "9,000 / 18,000 · 2h05". */
	private final JPanel limitRow = row("Limit left", limit);
	private final JPanel fillRow = row("Fills in", fill);
	private final JPanel likelyRow = row("Likely", likely);
	private final JPanel grid = new JPanel();
	private final JLabel cardSub = label("", DIM, small());
	private final JLabel cardNote = label("", ORANGE, small());
	/** "First: CANCEL Soul rune (slot 3)" — the one offer step before this card (FlipSidebarPlugin.nextOfferStep). */
	private final JLabel cardFirst = label("", RED, small());
	private final JLabel cardHint = label("", DIM, small());
	private final JPanel buttons = new JPanel(new GridLayout(1, 0, 4, 0));
	private final JButton placedButton = new FlipUi.Button("Placed");
	/** On a sell at a loss: keep it in the inventory instead (Holding) — user, 2026-11-01. */
	private final JButton holdButton = new FlipUi.Button("Hold");
	private final JButton skipButton = new FlipUi.Button("Skip");

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

	private final JButton rescanButton = new FlipUi.Button("Scan now");
	/** No new buys while paused; sells and Wait / Collect still guide. */
	private final JButton pauseButton = new FlipUi.Button("Pause");
	/**
	 * ⭐ SELL ONLY in the panel (1.60.54; the "sellOnly" setting — until now only in RuneLite's settings). User, 2026-11-07:
	 * "Can u get the sell only somewhere in the main plugin side bar. So I can switch at the end of my session and sell off
	 * the remaining items before my next session".
	 */
	private final JButton sellOnlyButton = new FlipUi.Button("Sell only");
	private final JButton bigWindowButton = new FlipUi.Button("Statistics");
	/**
	 * ⭐ The top strip (user, 2026-11-07, showing Flipping Copilot's: "we should be uising little icons at the top to log in
	 * and get to website and discord" — "not the big one", no login page): Discord · website · account, and log out on the
	 * right while logged in. A line under it only while there is something to say (waiting, Data sync off, ...).
	 */
	private final JLabel accountText = new JLabel();
	private final IconLink discordIcon = new IconLink("Our Discord: help, bug reports, ideas and flips");
	private final IconLink githubIcon = new IconLink("The plugin's code and its issue tracker on GitHub");
	private final IconLink webIcon = new IconLink("The website: item prices, graphs and your flips");
	private final IconLink accountIcon = new IconLink("");
	private final IconLink logoutIcon = new IconLink("Log out (your page keeps your flips until you delete them there)");
	private final java.awt.image.BufferedImage accountImg = ImageUtil.loadImageResource(FlipSidebarPanel.class, "top_account.png");
	static final String DISCORD_INVITE = "https://discord.gg/jasZpBkaUr";
	static final String WEBSITE = "https://flip-sidebar.pages.dev";
	static final String GITHUB = "https://github.com/bycoo222-cloud/flip-sidebar";

	/** Item lookup: type a name, pick a match, see what the item is doing and whether it is worth flipping. */
	private final IconTextField lookupBox = new IconTextField();
	private final JLabel[] lookupHits = new JLabel[LOOKUP_HITS];
	private final int[] lookupIds = new int[LOOKUP_HITS];
	private final JLabel lookupNone = label("", DIM, small());
	private final JPanel lookupCard = new FlipUi.Card()
	{
		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}
	};
	private final JLabel lookupName = label("", WHITE, FlipTheme.now.bold());
	private final JLabel lookupClose = label("close", ORANGE, small());
	private final JLabel lookupPrices = label("", WHITE, small());
	private final JLabel lookupVolume = label("", DIM, small());
	private final JLabel lookupVerdict = label("", GREEN, small());
	private final JLabel lookupGraph = label("price graph", ORANGE, small());
	/** The box's text is being set by the panel, not typed: no new matches. */
	private boolean lookupPicking;
	private int lookupItem = -1;
	private long lookupAt;
	private FlipBrain.Lookup lookupShown;
	private final JLabel blockedLabel = label("", ORANGE, small());
	/** Beside Placed / Skip on a buy card (a small link under them went unfound, 2026-10-31). */
	private final JButton neverButton = new FlipUi.Button("Never");

	/** Bought and not sold, at today's price (Copilot's portfolio). */
	private final JLabel holdTotal = label("", DIM, small());
	/** Holding: bought items kept in the inventory until the price is back at break-even (FlipBrain.underWater). */
	private final JLabel parkTotal = label("", DIM, small());
	private final HeldRow[] parkRows = new HeldRow[HELD_ROWS];
	/** Section headers that hide / show their section on a click, as Next flips does (user, 2026-11-01). */
	private final JLabel offersTitle = header("Offers");
	private final JLabel parkTitle = header("Holding");
	private final JLabel historyTitle = header("History");
	private boolean offersHidden;
	private boolean parkHidden;
	private boolean historyHidden;
	/**
	 * ⭐ HIGH ALCH MODE (1.59.0; user, 2026-11-02: "in the tip of the main page the high alch icon that puts it into high
	 * alch mode changes the ui"): the button at the top turns it on and off; the plan shows under the card, instead of
	 * Next flips.
	 */
	private final JToggleButton alchButton = new JToggleButton();
	/**
	 * ⭐ THE MODE TABS (1.60.46; user, 2026-11-07: "there should be 3 icons up there flip equipment and alch. Then the one
	 * your using shows its selected somehow. Dark box under the icon"): Flip and Alch switch the mode (the "alchMode"
	 * setting, as the old High Alch button did); Equipment is a VIEW — flipping or alching keeps running under it. A tab
	 * FLASHES when its side has something to do and you are not on it ("if your flipping and your in the equipment mode
	 * and you habe stuff to do on the flip side that should flash same with alch mode"): an offer to collect / cancel /
	 * re-price for the mode in use, a watched item that turned a good time to buy for Equipment.
	 */
	private final ModeTab flipTab = new ModeTab("<html>Flipping: the card buys flips and sells them back</html>");
	private final ModeTab gearTab = new ModeTab("<html>Equipment: gear you want for your account - it tells you when each "
		+ "is a good time to buy<br>(flipping or alching keeps running)</html>");
	private final ModeTab alchTab = new ModeTab("<html>High alch mode: the card buys items to cast High Level Alchemy on, "
		+ "instead of flipping</html>");
	/** The Equipment tab is open (a view: the mode under it is the "alchMode" setting). */
	private boolean gearView;
	/** The flashing's on / off phase (paintAges, every second). */
	private boolean blink;
	private JPanel mainPage;
	private final JPanel gearPage = new JPanel();
	private final IconTextField gearBox = new IconTextField();
	private static final int GEAR_HITS = 6;
	private static final int GEAR_ROWS = 12;
	/** Width the Equipment rows' text wraps to: the sidebar less the picture, the ✕ and the padding. */
	private static final int GEAR_TEXT_PX = 110;   // HTML px (WRAP_PX 150 fills the full width)
	private final JLabel[] gearHits = new JLabel[GEAR_HITS];
	private final int[] gearHitIds = new int[GEAR_HITS];
	private final GearRow[] gearRows = new GearRow[GEAR_ROWS];
	private final JLabel gearEmpty = label("", DIM, small());
	/** High alch mode: what you hold to alch and how far along (FlipBrain.AlchHeld). */
	private final JLabel heldAlchTitle = header("To alch", "What you hold to cast High Level Alchemy on: how many are "
		+ "left in your inventory, how many your buy offers still bring, how many you have cast since it showed up, and "
		+ "what the rest makes. Cast it on the normal spellbook - the spell and the item are outlined.");
	private final HeldAlchRow[] heldAlchRows = new HeldAlchRow[8];
	private final JLabel alchTitle = header("High alch plan");
	private final JLabel alchSummary = label("", DIM, small());
	/**
	 * ⭐ "Spend on alching at most" as a slider (user, 2026-11-03: "There should be some kind of drag able bar that sets
	 * how much u Wanna spend", "Aching shouldnt be speming all our cash just a little", "Some guys might thats why some
	 * kinda of dragable bar"). Millions; 0 = no limit (the last stop).
	 */
	/** in thousands (1.60.74): 0 = nothing bought, then 100k up to 1b, the last = no limit */
	private static final int[] ALCH_SPEND_STOPS = {0, 100, 250, 500, 1_000, 2_000, 3_000, 5_000, 10_000, 15_000, 20_000, 30_000,
		50_000, 75_000, 100_000, 150_000, 200_000, 300_000, 500_000, 1_000_000, FlipSidebarConfig.ALCH_NO_LIMIT_K};
	private final JLabel alchSpendLabel = label("", AMBER, small());
	private final JSlider alchSpend = new JSlider(0, ALCH_SPEND_STOPS.length - 1, 4);
	private long alchInUse;
	/** One per plan item (at most 8, one GE slot each) — built once, only their text changes (the rule above). */
	private final AlchRow[] alchRows = new AlchRow[8];
	/** Not suggested — at the BOTTOM, collapsible, closed at the start (user, 2026-11-02: "Put it at the bottom", "Collapsible"). */
	private final JLabel offTitle = header("Not suggested");
	private final JLabel offEmpty = label("", DIM, small());
	private final OffRow[] offRows = new OffRow[OFF_ROWS];
	private boolean offHidden = true;
	/** Since the session began: profit, gp/hr, ROI, won / lost — Copilot's session stats. */
	private final JLabel sessionProfit = label("", WHITE, FlipTheme.now.bold());
	private final JLabel sessionLine = label("", DIM, small());
	private final JLabel newSession = label("new session", ORANGE, small());
	/** The top card (FlipUi.SessionCard) — made in the constructor, round the High alch button. */
	private FlipUi.SessionCard sessionCard;
	/** The Statistics window (FlipsWindow), also opened from the History. */
	private final JLabel flipsLink = label("Open Statistics", ORANGE, small());
	private FlipsWindow flipsWindow;
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
		// the sections as the user left them (1.60.76; user, 2026-11-07: "can it also remember what tabs i close i close
		// offers every time i start the plugin")
		offersHidden = remembered("hideOffers", false);
		parkHidden = remembered("hideHolding", false);
		historyHidden = remembered("hideHistory", false);
		offHidden = remembered("hideNotSuggested", true);

		setLayout(new BorderLayout());
		// the top card, then the page of the tab in use: flipping / alching (content) or Equipment (gearPage)
		JPanel outer = new JPanel();
		outer.setLayout(new BoxLayout(outer, BoxLayout.Y_AXIS));
		outer.setBackground(BACKGROUND);
		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(BACKGROUND);
		setBackground(BACKGROUND);
		getWrappedPanel().setBackground(BACKGROUND);
		add(outer, BorderLayout.NORTH);
		mainPage = content;

		alchButton.setIcon(new ImageIcon(ImageUtil.loadImageResource(FlipSidebarPanel.class, "alch_icon.png")));
		alchButton.setToolTipText("<html>High alch mode: buy items to cast High Level Alchemy on, instead of flipping.<br>"
			+ "The card buys the alch plan's items; the spell (normal spellbook) and the item to alch are outlined.</html>");
		alchButton.setFocusable(false);
		alchButton.setMargin(new Insets(1, 3, 1, 3));
		alchButton.addActionListener(e -> setConfig("alchMode", String.valueOf(alchButton.isSelected())));
		// ⭐ the mode tabs (1.60.46): Flip · Equipment · Alch, top right of the card (buildTabs)
		JPanel tabs = buildTabs();
		// ⭐ the top card (FlipUi.SessionCard): title, mode, the session's profit, gp an hour and its line
		sessionCard = new FlipUi.SessionCard(tabs);
		sessionCard.setToolTipText("<html>FLIPPING: the card buys flips and sells them back.<br>HIGH ALCH MODE: the card buys "
			+ "items to cast High Level Alchemy on - nothing is sold.<br>The button at the top right switches.<br><br>"
			+ "The session: profit after tax since it began, gp an hour, and the profit after each flip as a line.</html>");
		put(outer, buildTopIcons(), 2);
		accountText.setFont(small());
		put(outer, accountText, 0);
		showAccount();
		put(outer, sessionCard, 6);
		put(outer, content, 0);
		buildGear();
		put(outer, gearPage, 0);
		gearPage.setVisible(false);
		paintMode(config.alchMode());
		// Risk and Re-price every at the TOP (user, 2026-11-03: "the high 5m that section should be at the top")
		JPanel settings = new JPanel(new GridLayout(2, 2, 4, 2));
		settings.setBackground(BACKGROUND);
		settings.add(label("Risk", DIM, small()));
		settings.add(label("Re-price every", DIM, small()));
		settings.add(risk);
		settings.add(window);
		for (JComboBox<?> box : new JComboBox<?>[]{risk, window})
		{
			box.setBackground(CARD);
			box.setForeground(WHITE);
		}
		risk.setToolTipText("Low: only steady items. High: allows thinner, jumpier ones");
		window.setToolTipText("How often you come back to adjust offers - sets how much of an hour's trading one offer takes");
		risk.addActionListener(e -> setConfig("riskLevel", ((RiskLevel) risk.getSelectedItem()).name()));
		window.addActionListener(e -> setConfig("adjustWindow", ((AdjustWindow) window.getSelectedItem()).name()));
		put(content, settings, 4);
		put(content, status, 2);
		// the session at the TOP (user, 2026-11-01: "the session stuff should be at the top")
		link(newSession, "Start counting the session from now", plugin::newSession);
		put(content, newSession, 4);
		// the coins right under the session (user, 2026-11-03: "the cash like how much u have I feel should be right
		// under session")
		put(content, cash, 0);
		put(content, cashSplit, 8);

		// live prices are off: say what turning them on means, with RuneLite's own warning
		offBox.add(label(wrap("Live prices are off. Turn them on and this panel finds flips by itself - "
			+ "no other program needed. Prices come from the OSRS Wiki (prices.runescape.wiki)."), WHITE, small()));
		offBox.add(Box.createVerticalStrut(6));
		JButton turnOn = new FlipUi.Button("Turn on live prices...");
		turnOn.setAlignmentX(Component.LEFT_ALIGNMENT);
		turnOn.addActionListener(e -> askLivePrices());
		offBox.add(turnOn);
		put(content, offBox, 8);


		// Scan now / Pause at the TOP — at the bottom, under the history, nobody found them
		// (user, 2026-10-31: "theres no pause button ... i see no button like that")
		rescanButton.setToolTipText("Look for flips again now instead of waiting for the next scan");
		rescanButton.addActionListener(e -> brain.rescan());
		pauseButton.setToolTipText("No flips or guidance (no box, highlights or fill lines) until you press Resume. "
			+ "Timers, hovers, the profit flash and History keep going");
		pauseButton.addActionListener(e -> setConfig("paused", String.valueOf(!config.paused())));
		sellOnlyButton.setToolTipText("<html>Sell only - for the end of a session: no new buys, and every open buy offer is told<br>"
			+ "to cancel (what it bought is sold). The card only says what to sell, re-price and collect, to empty<br>"
			+ "your slots and get your coins back. Click again to flip as normal.</html>");
		sellOnlyButton.addActionListener(e -> setConfig("sellOnly", String.valueOf(!config.sellOnly())));
		JPanel controls = new JPanel(new GridLayout(1, 3, 4, 0));
		controls.setOpaque(false);
		controls.add(rescanButton);
		controls.add(sellOnlyButton);
		controls.add(pauseButton);
		put(content, controls, 4);
		// the Statistics window without going through an item (user, 2026-10-31: "atleast a button to open
		// that window thats not just the item")
		bigWindowButton.setToolTipText("<html>Price graphs, your portfolio, profit over time,<br>which items make or lose money "
			+ "and every kept flip.<br>Click an item's picture or a row for its price graph.</html>");
		bigWindowButton.addActionListener(e ->
		{
			window().showDefault();
			openWindow();
		});
		JPanel bigRow = new JPanel(new GridLayout(1, 1));
		bigRow.setOpaque(false);
		bigRow.add(bigWindowButton);
		put(content, bigRow, 4);
		buildLookup(content);
		link(blockedLabel, "Let the plugin suggest all of them again (to allow just one, edit 'Never suggest' in the settings)", () ->
			configManager.setConfiguration(FlipSidebarConfig.GROUP, "blockedItems", ""));
		put(content, blockedLabel, 8);

		// the card
		JPanel top = new JPanel(new BorderLayout(6, 0));
		top.setOpaque(false);
		cardIcon.setPreferredSize(new Dimension(36, 32));
		top.add(cardIcon, BorderLayout.WEST);
		// a column, not a 2-row grid: a grid kept the hidden "Buy / Sell" row as tall as the note, so a note-only card
		// (user, 2026-11-04, "Your alch budget ... is in use": "this just looks a little strange") had an empty top half
		JPanel names = new JPanel();
		names.setLayout(new BoxLayout(names, BoxLayout.Y_AXIS));
		names.setOpaque(false);
		cardAction.setAlignmentX(Component.LEFT_ALIGNMENT);
		cardName.setAlignmentX(Component.LEFT_ALIGNMENT);
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
		likelyRow.setToolTipText("<html>If it sells at the last hour's average price instead of the card's 'Then sell at'.<br>"
			+ "Measured on 159 of your flips under 100k: this came true 74%, the card's Profit 42%.</html>");
		grid.add(likelyRow);
		grid.add(fillRow);
		cardBox.add(grid);
		cardBox.add(Box.createVerticalStrut(4));
		cardBox.add(cardSub);
		cardBox.add(cardFirst);
		cardBox.add(cardNote);
		cardBox.add(cardHint);
		cardBox.add(Box.createVerticalStrut(6));
		buttons.setOpaque(false);
		buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
		placedButton.setToolTipText("You put this offer in - show the next thing to do");
		skipButton.setToolTipText("Not this one - show the next");
		placedButton.addActionListener(e -> brain.placed());
		skipButton.addActionListener(e -> brain.skip());
		holdButton.setToolTipText("<html>Keep it - in profit or not: no sell card for it while you hold it. It waits in<br>"
			+ "your inventory or bank (Holding; its dot is green when selling now gets back what you paid + tax).<br>"
			+ "No time limit. To sell it: right-click it under Holding and untick Hold long-term.</html>");
		holdButton.addActionListener(e ->
		{
			FlipBrain.View v = shown;
			if (v != null && v.card != null && "SELL".equals(v.card.action))
			{
				plugin.holdItem(v.card.itemId);
			}
		});
		for (JButton b : new JButton[]{placedButton, skipButton, neverButton, holdButton})
		{
			b.setMargin(new Insets(2, 2, 2, 2));    // three across: "Placed" / "Never" were cut to "Plac..."
		}
		buttons.add(placedButton);
		buttons.add(skipButton);
		cardBox.add(buttons);
		// right there, not only in a right-click (user, 2026-10-31: "i see no button like that", then
		// of the small link that replaced it: "i cant figure out how to do")
		neverButton.setToolTipText("Never suggest this item again (undo: right-click it under Not suggested, at the bottom). "
			+ "Right-click the card to keep it off for a day or a week instead");
		neverButton.addActionListener(e ->
		{
			FlipBrain.View v = shown;
			if (v != null && v.card != null && "BUY".equals(v.card.action))
			{
				block(v.card.itemId);
				brain.skip();
			}
		});
		// "Your pick" — the floating box's twin, for those who turn the box off (user, 2026-11-01: "that same message
		// on the text box should also be in the sidebar. some people will turn off the floating window")
		ownBox.add(label("Your pick", AMBER, FlipTheme.now.bold()));
		ownBox.add(ownName);
		ownBox.add(Box.createVerticalStrut(4));
		for (JPanel r : new JPanel[]{ownLimitRow, ownResetsRow, ownQtyRow, ownPriceRow, ownSellRow, ownProfitRow})
		{
			ownBox.add(r);
		}
		ownBox.add(Box.createVerticalStrut(4));
		ownBox.add(ownNote);
		ownBox.setVisible(false);
		put(content, ownBox, 8);
		put(content, cardBox, 8);
		put(content, heldAlchTitle, 2);
		for (int i = 0; i < heldAlchRows.length; i++)
		{
			heldAlchRows[i] = new HeldAlchRow();
			put(content, withGap(heldAlchRows[i], 3), 0);
		}
		put(content, alchTitle, 2);
		alchSpend.setOpaque(false);
		alchSpend.setFocusable(false);
		alchSpend.setToolTipText("<html>Coins alching may tie up at once: its buy offers and the alch items in your "
			+ "inventory.<br>Casting frees it for the next buy. Far left = nothing (alch only what you have), far right = "
			+ "no limit.<br>Never more than the coins you have.</html>");
		alchSpend.addChangeListener(e ->
		{
			int k = ALCH_SPEND_STOPS[alchSpend.getValue()];
			alchSpendLabel.setText(wrap(spendText(k, alchInUse)));
			if (!alchSpend.getValueIsAdjusting())
			{
				setConfig("alchSpendK", String.valueOf(k));
			}
		});
		put(content, alchSpendLabel, 0);
		put(content, alchSpend, 2);
		put(content, alchSummary, 4);
		for (int i = 0; i < alchRows.length; i++)
		{
			alchRows[i] = new AlchRow();
			put(content, withGap(alchRows[i], 3), 0);
		}
		put(content, Box.createVerticalStrut(5), 0);

		cashSplit.setToolTipText("<html>Total = your inventory + in the GE + your bank.<br>In GE = the coins left in your "
			+ "buy offers, and what is left to sell at its price after the 2% tax.<br>Offers you ignore are not counted.</html>");

		// Offers, Active offers, Holding and History hide / show on a click of their header — user, 2026-11-01: "make
		// it collapsable too", "i want the selling offers to be collapsible too same with history"
		collapsible(offersTitle, "Your eight GE slots, each with advice from the 5-minute prices: COLLECT when done; "
			+ "RAISE / LOWER to re-price through the GE's Modify; CANCEL a buy whose flip no longer pays after tax; a sell "
			+ "under what you paid keeps its price (and its place in the line) until " + FlipEngine.SELL_PATIENCE_MS / 60_000
			+ " minutes after the buy or your login, then LOWER to the market; "
			+ "otherwise WAIT. A sell shows what you paid. The time is since the "
			+ "last fill. The line under the title: what is bought and not sold yet (outside Holding), valued at today's "
			+ "5-minute insta-buy after the 2% tax, and the coins still in buy offers. Right-click an offer to ignore it.",
			() -> offersHidden = remember("hideOffers", !offersHidden));
		put(content, offersTitle, 2);
		put(content, holdTotal, 2);
		put(content, offersEmpty, 2);
		for (int i = 0; i < OFFER_ROWS; i++)
		{
			offerRows[i] = new OfferRow();
			put(content, withGap(offerRows[i], 3), 0);
		}

		put(content, Box.createVerticalStrut(8), 0);
		// Holding = parked items only; what is being sold is under Offers — user, 2026-11-01: "the holding should be what
		// i described for items parked in inventory" ("im confused with offers vs holding currently. those tow tabs
		// should be together"); "i could visually watch it come into profit at sell it"
		collapsible(parkTitle, "What you bought and have not sold. A swing waits in your inventory, off the GE, for its "
			+ "sell price; an item you Hold gets no sell card until you untick Hold. A losing buy is not kept: it is sold - "
			+ "at break-even until " + FlipEngine.SELL_PATIENCE_MS / 60_000 + " minutes after the buy or your login, then at "
			+ "the market. The red number is what selling now would lose. "
			+ "Right-click a row to sell it now at the market or to keep it LONG-TERM (Hold long-term) - then it can go in "
			+ "your bank's 'holding' tab, marked with a dot (amber waiting, green back at break-even) and still listed here.",
			() -> parkHidden = remember("hideHolding", !parkHidden));
		put(content, parkTitle, 2);
		put(content, parkTotal, 2);
		for (int i = 0; i < HELD_ROWS; i++)
		{
			parkRows[i] = new HeldRow();
			put(content, withGap(parkRows[i], 3), 0);
		}


		put(content, Box.createVerticalStrut(8), 0);
		nextTitle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		nextTitle.setToolTipText("<html><div style='width:220px'>Click to show or hide. The next best flips from the "
			+ "last scan, by gp per hour - click one to make it the card; right-click to never suggest it.</div></html>");
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

		put(content, Box.createVerticalStrut(8), 0);
		collapsible(historyTitle, "Every completed flip, matched against what you paid for it - profit after the 2% GE "
			+ "tax; losses in red. 'All kept' covers the last 200 flips.", () -> historyHidden = remember("hideHistory", !historyHidden));
		put(content, historyTitle, 2);
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
		link(flipsLink, "<html>Price graphs, your portfolio, profit over time,<br>which items make or lose money and every kept flip.<br>"
			+ "Click an item's picture or row for its price graph.</html>", () ->
		{
			window().showDefault();
			openWindow();
		});
		put(content, flipsLink, 0);

		// ⭐ Not suggested, at the bottom: every item kept off the cards and why, editable with a right-click (1.56.0;
		// user, 2026-11-02: "I want to have that list transparent and editable"). Here, not in the settings: RuneLite's
		// settings page does not refresh while open (ConfigPanel listens to ProfileChanged only) — a rest added then
		// would be undone by editing the stale copy.
		put(content, Box.createVerticalStrut(8), 0);
		collapsible(offTitle, "Every item the plugin will not suggest now, and until when: your Never suggest list (the "
			+ "settings), the ones you keep off for a day or a week (right-click a card or a Next flip), and the plugin's "
			+ "own rests - a problem item that sat for hours or sold at a loss. Right-click a row to suggest it again or "
			+ "to keep it off longer.", () -> offHidden = remember("hideNotSuggested", !offHidden));
		put(content, offTitle, 2);
		put(content, offEmpty, 2);
		for (int i = 0; i < OFF_ROWS; i++)
		{
			offRows[i] = new OffRow();
			put(content, withGap(offRows[i], 3), 0);
		}

		// click the card's item picture or name: its price graph in the Statistics window
		for (JLabel l : new JLabel[]{cardIcon, cardName})
		{
			l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			l.setToolTipText("Click: this item's price graph (Statistics window)");
			l.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					Suggestion s = shown == null ? null : shown.card;
					if (SwingUtilities.isLeftMouseButton(e) && s != null && iconItem > 0)
					{
						boolean buy = "BUY".equals(s.action);
						// a sell: what was paid (Holding) beside the ask (user: "only shows the current step")
						long paid = buy ? s.price : paidFor(s.itemId);
						openItem(s.itemId, s.name, paid, buy ? "buy at" : "you paid", buy ? s.sellPrice : s.price, "sell at",
							paid <= 0 || s.quantity <= 0 ? null
								: new FlipsWindow.Outcome("Expected profit", s.profit / s.quantity, s.profit, s.quantity));
					}
				}
			});
		}

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

	/** The lookup box, its match rows (made once, only their text changes) and its result card. */
	private void buildLookup(JPanel content)
	{
		lookupBox.setIcon(IconTextField.Icon.SEARCH);
		lookupBox.setBackground(CARD);
		lookupBox.setHoverBackgroundColor(HOVER);
		lookupBox.setToolTipText("Look up any item: type 2+ letters of its name and pick it");
		lookupBox.setPreferredSize(new Dimension(100, 30));
		lookupBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		lookupBox.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				lookupTyped();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				lookupTyped();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				lookupTyped();
			}
		});
		lookupBox.addActionListener(e -> lookupPick(lookupHits[0].isVisible() ? lookupIds[0] : -1));    // Enter: the best match
		lookupBox.addClearListener(this::lookupClear);
		put(content, lookupBox, 2);
		for (int i = 0; i < LOOKUP_HITS; i++)
		{
			final int k = i;
			JLabel hit = label("", WHITE, small());
			hit.setBorder(new EmptyBorder(2, 6, 2, 6));
			hit.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			hit.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					lookupPick(lookupIds[k]);
				}

				@Override
				public void mouseEntered(MouseEvent e)
				{
					hit.setForeground(ORANGE);
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					hit.setForeground(WHITE);
				}
			});
			hit.setVisible(false);
			lookupHits[i] = hit;
			put(content, hit, 0);
		}
		lookupNone.setBorder(new EmptyBorder(2, 6, 2, 6));
		lookupNone.setVisible(false);
		put(content, lookupNone, 0);

		lookupCard.setLayout(new BoxLayout(lookupCard, BoxLayout.Y_AXIS));
		lookupCard.setBackground(CARD);
		lookupCard.setBorder(FlipUi.stripe(ORANGE, 6, 8, 6, 8));
		JPanel head = new JPanel(new BorderLayout(4, 0));
		head.setOpaque(false);
		head.setAlignmentX(Component.LEFT_ALIGNMENT);
		head.add(lookupName, BorderLayout.CENTER);
		head.add(lookupClose, BorderLayout.EAST);
		lookupCard.add(head);
		lookupCard.add(Box.createVerticalStrut(3));
		lookupCard.add(lookupPrices);
		lookupCard.add(lookupVolume);
		lookupCard.add(Box.createVerticalStrut(3));
		lookupCard.add(lookupVerdict);
		lookupCard.add(Box.createVerticalStrut(3));
		lookupCard.add(lookupGraph);
		link(lookupClose, "Close the lookup", this::lookupClear);
		link(lookupGraph, "This item's price graph in the Statistics window", () ->
		{
			FlipBrain.Lookup l = lookupShown;
			if (l == null && lookupItem > 0)
			{
				openItem(lookupItem, brain.itemName(lookupItem), 0, null, 0, null, null);   // before its lookup is in
			}
			else if (l != null)
			{
				openItem(l.itemId, l.name, l.bid, "buy at", l.ask, "sell at", l.bid <= 0 || l.ask <= 0 ? null
					: new FlipsWindow.Outcome("Expected profit", l.each, l.pick == null ? 0 : l.each * l.pick.qty,
						l.pick == null ? 0 : l.pick.qty));
			}
		});
		lookupCard.setVisible(false);
		put(content, lookupCard, 4);
	}

	/** Matches for what is typed (2+ letters), best first. */
	private void lookupTyped()
	{
		if (lookupPicking)
		{
			return;
		}
		String q = lookupBox.getText();
		if (lookupItem > 0 && !String.valueOf(q).equals(brain.itemName(lookupItem)))
		{
			lookupItem = -1;                  // a new name is being typed: the old result goes
			lookupShown = null;
			lookupCard.setVisible(false);
		}
		boolean typed = q != null && q.trim().length() >= 2;
		List<Integer> ids = typed ? brain.searchItems(q, LOOKUP_HITS) : List.of();
		for (int i = 0; i < LOOKUP_HITS; i++)
		{
			boolean on = i < ids.size();
			lookupIds[i] = on ? ids.get(i) : -1;
			lookupHits[i].setText(on ? brain.itemName(ids.get(i)) : "");
			lookupHits[i].setVisible(on);
		}
		lookupNone.setText(brain.hasItemNames() ? "no tradeable item called that" : "item names load with the first scan");
		lookupNone.setVisible(typed && ids.isEmpty());
		revalidate();
		repaint();
	}

	private void lookupPick(int itemId)
	{
		if (itemId <= 0)
		{
			return;
		}
		String name = brain.itemName(itemId);
		lookupPicking = true;
		lookupBox.setText(name);
		lookupPicking = false;
		for (JLabel hit : lookupHits)
		{
			hit.setVisible(false);
		}
		lookupNone.setVisible(false);
		lookupItem = itemId;
		lookupShown = null;
		lookupGraph.setVisible(true);         // the graph link at once (user: "I tried searching it ... nope")
		lookupName.setText(wrap(name));
		lookupPrices.setText("Looking it up...");
		lookupVolume.setText("");
		lookupVerdict.setText("");
		lookupGraph.setVisible(false);
		lookupCard.setVisible(true);
		lookupFetch();
		revalidate();
		repaint();
	}

	private void lookupFetch()
	{
		final int id = lookupItem;
		lookupAt = System.currentTimeMillis();
		brain.lookup(id, l -> SwingUtilities.invokeLater(() ->
		{
			if (lookupItem == id)
			{
				showLookup(l);
			}
		}));
	}

	/** "Buy at 316 → sell at 341 · +19 each after tax", the hour's volume and the limit, then the verdict. */
	private void showLookup(FlipBrain.Lookup l)
	{
		lookupShown = l;
		long now = System.currentTimeMillis();
		boolean priced = l.bid > 0 && l.ask > 0;
		lookupPrices.setText(wrap(!priced ? "No trades on one side in the last hour"
			: String.format("Buy at %,d → sell at %,d · %s%,d each after tax", l.bid, l.ask, l.each >= 0 ? "+" : "", l.each)));
		String limit = l.limit <= 0 ? "buy limit unknown"
			: String.format("limit %,d", l.limit) + (l.left >= 0 && l.left < l.limit ? String.format(" (%,d left)", l.left) : "");
		lookupVolume.setText(wrap(FlipBrain.gp(l.hourVolume) + " traded in the last hour · " + limit));
		Pick p = l.pick;
		if (l.left == 0)
		{
			lookupVerdict.setText(wrap("Buy limit reached - resets in " + FlipSidebarSlotOverlay.clock(l.resetsAt - now)));
			lookupVerdict.setForeground(RED);
		}
		else if (p == null)
		{
			lookupVerdict.setText(wrap(priced ? "Could not judge it - its trade history did not load" : "Nothing to judge it on"));
			lookupVerdict.setForeground(DIM);
		}
		else if (p.rejected != null)
		{
			lookupVerdict.setText(wrap("Not recommended: " + p.rejected));
			lookupVerdict.setForeground(ORANGE);
		}
		else if (p.qty <= 0)
		{
			lookupVerdict.setText(wrap("The margin holds, but there are no coins to plan a buy with"));
			lookupVerdict.setForeground(GREEN);
		}
		else
		{
			lookupVerdict.setText(wrap(String.format("Worth flipping: %,d for %s%s (~%.0f min)", p.qty,
				p.total >= 0 ? "+" : "", FlipBrain.gp(p.total), Math.max(1, p.fillMinutes))));
			lookupVerdict.setForeground(GREEN);
		}
		lookupGraph.setVisible(true);
		revalidate();
		repaint();
	}

	private void lookupClear()
	{
		lookupItem = -1;
		lookupShown = null;
		lookupCard.setVisible(false);
		if (!lookupBox.getText().isEmpty())
		{
			lookupBox.setText("");           // hides the matches too (lookupTyped)
		}
		revalidate();
		repaint();
	}

	/** What was paid each for an item held, 0 when it is not in Holding. */
	private long paidFor(int itemId)
	{
		for (FlipBrain.Held h : shown == null ? List.<FlipBrain.Held>of() : shown.held)
		{
			if (h.itemId == itemId)
			{
				return h.paidEach;
			}
		}
		return 0;
	}

	/** The Statistics window, made on first use. */
	private FlipsWindow window()
	{
		if (flipsWindow == null)
		{
			FlipsWindow.useTheme(FlipTheme.now);
			flipsWindow = new FlipsWindow(SwingUtilities.getWindowAncestor(this), (id, range, done) ->
			{
				if (!config.livePrices())
				{
					done.accept(null);          // the window says to turn Live prices on
					return;
				}
				brain.chart(id, range, done);
			}, plugin::exportFlips, plugin::exportBrain, plugin::importBrain, plugin::allFlips);
			flipsWindow.setItemSearch(brain::searchItems, brain::itemName);
		}
		return flipsWindow;
	}

	private void openWindow()
	{
		FlipsWindow w = window();
		w.show(shown);
		w.showLearning(brain.learned());
		w.showLimits(brain.limitTable());
		if (w.isVisible())
		{
			// hide + show: a dialog the window manager minimized has no taskbar entry to restore it from,
			// and setVisible(true) / toFront() alone leave it minimized. (2026-10-31 the button seemed to
			// do nothing after working earlier; no error logged — a minimized window is the ASSUMED cause.)
			w.setVisible(false);
		}
		w.setVisible(true);
		w.toFront();
	}

	/** An item's price graph in the Statistics window, with dashed lines at {@code buyAt} / {@code sellAt} (0 = none). */
	private void openItem(int itemId, String name, long buyAt, String buyName, long sellAt, String sellName,
		FlipsWindow.Outcome outcome)
	{
		window().showItem(itemId, name, buyAt, buyName, sellAt, sellName, outcome);
		openWindow();
	}

	/** Plugin shutDown: stop the age clock, close the flips window. */
	void stop()
	{
		clock.stop();
		if (flipsWindow != null)
		{
			flipsWindow.dispose();
			flipsWindow = null;
		}
	}

	/** Risk / re-price changed in the wrench menu: match the boxes without writing back. */
	void syncSettings()
	{
		syncing = true;
		risk.setSelectedItem(config.riskLevel());
		window.setSelectedItem(config.adjustWindow());
		syncing = false;
	}

	/** The top strip: Discord · website · account in the middle, log out at the right (kept centred by a twin space). */
	private JPanel buildTopIcons()
	{
		discordIcon.picture(ImageUtil.loadImageResource(FlipSidebarPanel.class, "top_discord.png"));
		discordIcon.onClick = () -> LinkBrowser.browse(DISCORD_INVITE);
		githubIcon.picture(ImageUtil.loadImageResource(FlipSidebarPanel.class, "top_github.png"));
		githubIcon.onClick = () -> LinkBrowser.browse(GITHUB);
		webIcon.picture(ImageUtil.loadImageResource(FlipSidebarPanel.class, "top_web.png"));
		webIcon.onClick = () -> LinkBrowser.browse(WEBSITE);
		logoutIcon.picture(ImageUtil.loadImageResource(FlipSidebarPanel.class, "top_logout.png"));
		logoutIcon.onClick = plugin::accountLogout;
		JPanel middle = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0));
		middle.setOpaque(false);
		middle.add(discordIcon);
		middle.add(githubIcon);
		middle.add(webIcon);
		middle.add(accountIcon);
		JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
		right.setOpaque(false);
		right.add(logoutIcon);
		Dimension side = new Dimension(30, 22);
		right.setPreferredSize(side);
		JPanel left = new JPanel();
		left.setOpaque(false);
		left.setPreferredSize(side);
		JPanel strip = new JPanel(new BorderLayout());
		strip.setOpaque(false);
		strip.add(left, BorderLayout.WEST);
		strip.add(middle, BorderLayout.CENTER);
		strip.add(right, BorderLayout.EAST);
		return strip;
	}

	/** The account icon, log out and the note line, from the plugin's accountState. Swing thread. */
	void showAccount()
	{
		String[] st = plugin.accountState();
		String state = st[0];
		String name = st[1].replace("&", "&amp;").replace("<", "&lt;");
		boolean in = "in".equals(state), waiting = "waiting".equals(state);
		accountIcon.picture(in ? ImageUtil.fillImage(accountImg, GREEN) : waiting ? ImageUtil.fillImage(accountImg, AMBER) : accountImg);
		accountIcon.setToolTipText(in ? "<html>Logged in as " + (name.isEmpty() ? "you" : name)
			+ "<br>Click: your page with every character's flips</html>"
			: waiting ? "Waiting for you to log in in the browser - click to stop waiting"
			: "<html>Log in with Discord: every character's flips on your own private page<br>"
			+ "(needs Data sync on, in the settings)</html>");
		accountIcon.onClick = in ? plugin::accountPage : waiting ? plugin::accountCancel : plugin::accountLogin;
		logoutIcon.setVisible(in);
		String note;
		switch (state)
		{
			case "waiting":
				note = "Log in with Discord in the browser that opened...";
				break;
			case "sync":
				note = "To log in, turn on Data sync in the settings first - your flips go to your page with it";
				break;
			case "expired":
				note = "The login wasn't finished in time - click the person icon to try again";
				break;
			case "failed":
				note = "Couldn't reach the server - try again in a minute";
				break;
			case "ended":
				note = "You were logged out on the website - click the person icon to log in again";
				break;
			default:
				note = null;
		}
		accountText.setVisible(note != null);
		accountText.setText(note == null ? "" : wrap(note));
		accountText.setForeground(AMBER);
		accountText.setBorder(BorderFactory.createEmptyBorder(0, 0, note == null ? 0 : 4, 0));
	}

	void show(FlipBrain.View v)
	{
		shown = v;
		mainPage.setVisible(!gearView);
		gearPage.setVisible(gearView);
		paintTabs(v);
		if (gearView)
		{
			paintGear();
		}
		boolean live = v != null && v.live;
		status.setText(wrap(v == null ? "Starting..." : v.status));
		offBox.setVisible(!live);
		rescanButton.setVisible(live);
		rescanButton.setEnabled(live && !v.scanning && !v.paused);
		pauseButton.setVisible(live);
		pauseButton.setText(v != null && v.paused ? "Resume" : "Pause");
		sellOnlyButton.setVisible(live);
		sellOnlyButton.setText(config.sellOnly() ? "Selling only" : "Sell only");
		sellOnlyButton.setForeground(config.sellOnly() ? AMBER : pauseButton.getForeground());
		namesForBlocked();
		lookupBox.setVisible(live);
		if (!live && lookupItem > 0)
		{
			lookupClear();
		}
		else if (lookupItem > 0 && System.currentTimeMillis() - lookupAt > LOOKUP_REFRESH_MS)
		{
			lookupFetch();
		}
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
			// ⭐ in the GE too (user, 2026-11-02: "cash should include the value of the items in the ge ... it should have
			// in inv in ge and total"): the coins left in buy offers + what is left to sell at its price after the tax
			long inGe = inGe(v.offers) + v.uncollected;
			// and the inventory's items (user, 2026-11-03: "its not counting the value if the items in the inventory")
			long total = v.inventoryCoins + v.inventoryItems + inGe + Math.max(0, v.bankCoins) + v.bankItems;
			// "Use at most": a cap on ALL flipping together — buy offers + Holding at cost (FlipBrain.budget)
			long inFlips = v.inBuys;
			for (FlipBrain.Held h : v.held)
			{
				inFlips += h.qty * h.paidEach;
			}
			cash.setText(wrap("Cash: " + FlipBrain.gp(total) + " total" + (v.cashCap <= 0 ? ""
				: " · at most " + FlipBrain.gp(v.cashCap) + " in flips, " + FlipBrain.gp(Math.max(0, v.cashCap - inFlips)) + " left")));
			cashSplit.setText(wrap("inventory " + FlipBrain.gp(v.inventoryCoins)
				+ (v.inventoryItems > 0 ? " + items " + FlipBrain.gp(v.inventoryItems) : "") + " · in GE " + FlipBrain.gp(inGe) + " · bank "
				+ (v.bankCoins < 0 ? "not seen yet - open your bank once" : FlipBrain.gp(v.bankCoins))
				+ (v.bankItems > 0 ? " + flip items " + FlipBrain.gp(v.bankItems) : "")
				// the GE pays from the inventory, so buys are sized from it (user, 2026-10-31, with 130m banked and
				// under 2m in offers: "what do i need to do to get it use the money")
				+ (v.bankCoins > v.inventoryCoins && v.bankCoins > 1_000_000
				? " · withdraw the coins you want flipped - buys are sized from your inventory" : "")));
			cashSplit.setForeground(v.bankCoins > v.inventoryCoins && v.bankCoins > 1_000_000 ? ORANGE : DIM);
		}

		List<OfferAdvice> offers = v == null ? List.of() : v.offers;
		offersTitle.setText(title("Offers", offersHidden, offers.size()));
		offersEmpty.setVisible(!offersHidden && offers.isEmpty());
		offersEmpty.setText(v == null || !v.loggedIn ? "log in to see your offers" : "no open offers");
		for (int i = 0; i < OFFER_ROWS; i++)
		{
			offerRows[i].show(!offersHidden && i < offers.size() ? offers.get(i) : null);
		}

		paintHolding(v);
		paintSession(v);
		if (flipsWindow != null && flipsWindow.isVisible())
		{
			flipsWindow.show(v);
			flipsWindow.showLearning(brain.learned());
			flipsWindow.showLimits(brain.limitTable());
		}
		List<GameSnapshot.Done> history = v == null ? List.of() : v.history;
		historyTitle.setText(title("History", historyHidden, history.size()));
		paintHistorySummary(history);
		if (historyHidden)
		{
			historySummary.setVisible(false);
			historyTotal.setVisible(false);
		}
		historyEmpty.setVisible(!historyHidden && history.isEmpty());
		historyEmpty.setText(v == null || !v.loggedIn ? "log in to see your flips"
			: "no finished flips yet - each sell shows here as it fills");
		int rows = historyHidden ? 0 : historyAll ? history.size() : Math.min(HISTORY_SHOWN, history.size());
		for (int i = 0; i < HISTORY_ROWS; i++)
		{
			historyRows[i].show(i < rows && i < history.size() ? history.get(i) : null);
		}
		historyMore.setVisible(!historyHidden && history.size() > HISTORY_SHOWN);
		historyMore.setText(historyAll ? "Show fewer" : "Show all " + history.size() + " flips");

		List<FlipBrain.Off> off = v == null ? List.of() : v.off;
		offTitle.setText(title("Not suggested", offHidden, off.size()));
		offEmpty.setVisible(!offHidden && off.isEmpty());
		offEmpty.setText("nothing - every item can be suggested");
		for (int i = 0; i < OFF_ROWS; i++)
		{
			offRows[i].show(!offHidden && i < off.size() ? off.get(i) : null);
		}

		boolean alch = live && config.alchMode();
		syncing = true;
		alchButton.setSelected(config.alchMode());
		paintMode(config.alchMode());
		syncing = false;
		alchTitle.setVisible(alch);
		alchSummary.setVisible(alch);
		alchSpendLabel.setVisible(alch);
		alchSpend.setVisible(alch);
		List<FlipBrain.AlchHeld> heldAlch = alch ? v.alchHeld : List.of();
		if (alch)
		{
			// the top card's alch stats: what the casts made, what the rest makes, casts done / left, the time to cast
			long made = 0;
			long toCome = 0;
			long casts = 0;
			long left = 0;
			for (FlipBrain.AlchHeld h : heldAlch)
			{
				made += h.cast * h.each;
				toCome += (h.left + h.buying) * h.each;
				casts += h.cast;
				left += h.left + h.buying;
			}
			sessionCard.alchStats(made, toCome, casts, left, castTime(left, Math.max(1, config.alchCastsPerHour())),
				alchLine(made));
		}
		heldAlchTitle.setVisible(!heldAlch.isEmpty());
		for (int i = 0; i < heldAlchRows.length; i++)
		{
			heldAlchRows[i].show(i < heldAlch.size() ? heldAlch.get(i) : null);
		}
		alchInUse = alch ? v.alchInUse : 0;
		if (!alchSpend.getValueIsAdjusting())
		{
			syncing = true;
			alchSpend.setValue(spendStop(config.alchSpendK()));
			syncing = false;
			alchSpendLabel.setText(wrap(spendText(config.alchSpendK(), alchInUse)));
		}
		paintAlch(alch ? v : null);
		List<Pick> next = live && !alch ? v.next : List.of();
		nextTitle.setVisible(live && !alch);
		nextTitle.setText(nextOpen ? "Next flips - hide" : "Next flips (" + next.size() + ") - show");
		nextEmpty.setVisible(live && !alch && nextOpen && next.isEmpty());
		nextEmpty.setText(v != null && v.scanning ? "scanning..." : "nothing else worth doing right now");
		for (int i = 0; i < NEXT_ROWS; i++)
		{
			nextRows[i].show(nextOpen && i < next.size() ? next.get(i) : null);
		}
		paintAges();
		revalidate();
	}

	/** How long {@code casts} take at {@code perHour}: "6 min", "1h 25 min" (never "6m" — it read as 6 million). */
	static String castTime(long casts, int perHour)
	{
		long min = Math.max(1, Math.round(casts * 60.0 / Math.max(1, perHour)));
		return min < 60 ? min + " min" : (min / 60) + "h" + (min % 60 == 0 ? "" : " " + (min % 60) + " min");
	}

	/** The slider's stop for {@code k} thousands (0 = nothing, the last stop = no limit): the nearest stop. */
	static int spendStop(int k)
	{
		int best = 0;
		for (int i = 0; i < ALCH_SPEND_STOPS.length; i++)
		{
			if (Math.abs((long) ALCH_SPEND_STOPS[i] - k) < Math.abs((long) ALCH_SPEND_STOPS[best] - k))
			{
				best = i;
			}
		}
		return best;
	}

	static String spendText(int k, long inUse)
	{
		return (k >= FlipSidebarConfig.ALCH_NO_LIMIT_K ? "Spend on alching: no limit"
			: k <= 0 ? "Spend on alching: nothing - alch only what you have"
			: "Spend on alching: at most " + FlipBrain.gp(k * 1_000L))
			+ (inUse > 0 ? " · " + FlipBrain.gp(inUse) + " in use" : "");
	}

	/** High alch mode's plan: its totals, then each item — how many at what price, what a cast makes, how long it fills. */
	private void paintAlch(FlipBrain.View v)
	{
		AlchPlan.Plan p = v == null ? null : v.alch;
		AlchPlan.Settings s = v == null ? null : v.alchSettings;
		for (int i = 0; i < alchRows.length; i++)
		{
			alchRows[i].show(p != null && i < p.rows.size() ? p.rows.get(i) : null, s == null ? 1200 : s.castsPerHour);
		}
		if (p == null || s == null)
		{
			alchSummary.setText(wrap("getting prices..."));
			return;
		}
		long casts = p.casts();
		// plain lines (user, 2026-11-03, of the old one-liner "+70k · 757k an hour of casting · 111 casts (5m) · items
		// 976k, plus 111 nature runes (17k)": "this second picture is confusing to read" — and "5m" read as 5 million)
		alchSummary.setText(wrap(casts <= 0 ? "Nothing pays at least " + s.minProfit + " a cast right now"
			: String.format("%,d casts · about %s of casting · +%s · costs %s + %,d nature runes (%s)", casts,
				castTime(casts, s.castsPerHour), FlipBrain.gp(p.profit()), FlipBrain.gp(p.cash()), casts,
				FlipBrain.gp(casts * p.nature))));
	}

	/** One item held to alch: how many to cast, still buying, cast so far, what the rest makes. */
	private final class HeldAlchRow extends FlipUi.Card
	{
		private final JLabel icon = new JLabel();
		private final JLabel name = label("", WHITE, FlipTheme.now.bold());
		private final JLabel left = label("", AMBER, small());
		private final JLabel gain = label("", GREEN, small());
		private int iconOf = -1;

		private int itemId = -1;
		private String itemName;

		HeldAlchRow()
		{
			// undo "for alching": offered for sale again
			addMouseListener(new java.awt.event.MouseAdapter()
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
			setLayout(new BorderLayout(6, 0));
			setBackground(CARD);
			setBorder(FlipUi.stripe(GREEN, 4, 6, 4, 6));
			add(icon, BorderLayout.WEST);
			JPanel text = new JPanel();
			text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
			text.setOpaque(false);
			text.add(name);
			text.add(left);
			text.add(gain);
			add(text, BorderLayout.CENTER);
		}

		void show(FlipBrain.AlchHeld h)
		{
			showRow(this, h != null);
			if (h == null)
			{
				return;
			}
			if (h.itemId != iconOf && itemManager != null)     // null only in the off-screen preview
			{
				iconOf = h.itemId;
				itemManager.getImage(h.itemId).addTo(icon);
			}
			itemId = h.itemId;
			itemName = h.name;
			name.setText(h.name);
			// short lines that wrap: the row is narrow (the alch_held preview cut "+608 a cast · +..." off)
			left.setText(wrap(String.format("%,d to cast", h.left) + (h.buying > 0 ? String.format(" · %,d buying", h.buying) : "")
				+ (h.cast > 0 ? String.format(" · %,d cast", h.cast) : "")));
			long rest = h.each * (h.left + h.buying);
			gain.setText(wrap(String.format("%+,d a cast · %s%s to come", h.each, rest >= 0 ? "+" : "-", FlipBrain.gp(Math.abs(rest)))));
			gain.setForeground(h.each >= 0 ? GREEN : RED);
		}

		private void menu(MouseEvent e)
		{
			if (!e.isPopupTrigger() || itemId <= 0)
			{
				return;
			}
			JPopupMenu m = new JPopupMenu();
			JMenuItem sell = new JMenuItem("Don't alch " + (itemName == null ? "it" : itemName) + " - offer it for sale again");
			int id = itemId;
			sell.addActionListener(ev -> plugin.saveForAlch(id, false));
			m.add(sell);
			showMenu(m, this, e.getX(), e.getY());
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}
	}

	/** One item of the alch plan: its picture, how many at what price and how long it fills, what a cast makes. */
	private final class AlchRow extends FlipUi.Card
	{
		private final JLabel icon = new JLabel();
		private final JLabel name = label("", WHITE, FlipTheme.now.bold());
		private final JLabel buyLine = label("", DIM, small());
		private final JLabel gain = label("", GREEN, small());
		private int iconOf = -1;

		AlchRow()
		{
			setLayout(new BorderLayout(6, 0));
			setBackground(CARD);
			setBorder(FlipUi.stripe(AMBER, 4, 6, 4, 6));
			add(icon, BorderLayout.WEST);
			JPanel text = new JPanel();
			text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
			text.setOpaque(false);
			text.add(name);
			text.add(buyLine);
			text.add(gain);
			add(text, BorderLayout.CENTER);
		}

		void show(AlchPlan.Row r, int castsPerHour)
		{
			showRow(this, r != null);
			if (r == null)
			{
				return;
			}
			if (r.item.id != iconOf && itemManager != null)     // null only in the off-screen preview
			{
				iconOf = r.item.id;
				itemManager.getImage(r.item.id).addTo(icon);
			}
			name.setText(r.item.name);
			// how many to alch and how long that takes to cast — the price and the fill time are on the buy card (user,
			// 2026-11-03: "where it says buy it should say xx remaining to alch. and a fill time isnt help ful here.
			// estimated alch time would be")
			buyLine.setText(wrap(String.format("%,d remaining to alch · ~%s to cast", r.qty, castTime(r.qty, castsPerHour))));
			gain.setText(wrap(String.format("+%,d a cast · +%s", r.each, FlipBrain.gp(r.total()))));
			setToolTipText(String.format("<html>High alch %,d<br>- paid %,d<br>- a nature rune<br>= %+,d a cast<br>"
				+ "Cast time: at your Casts an hour setting</html>", r.item.highAlch, r.price,
				r.each));
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}
	}

	private void paintCard(Suggestion s, FlipBrain.View v)
	{
		boolean buy = "BUY".equals(s.action);
		boolean sell = "SELL".equals(s.action);
		boolean none = !buy && !sell;
		boolean toAlch = buy && s.note != null && s.note.startsWith("High alch:");      // High alch mode's buy
		String label = toAlch ? "Buy to alch" : buy ? "Buy" : sell ? "Sell" : "WAIT".equals(s.action) ? "Wait"
			: "COLLECT".equals(s.action) ? "Collect" : "";
		cardAction.setText(label);
		cardAction.setForeground(toAlch ? AMBER : buy ? GREEN : ORANGE);
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
		sellRow.setVisible(buy && s.sellPrice > 0);       // an alch buy (High alch mode) is never sold
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
		boolean showLikely = buy && s.likely != Long.MIN_VALUE && s.likely < s.profit;
		likelyRow.setVisible(showLikely);
		if (showLikely)
		{
			likely.setText((s.likely >= 0 ? "+" : "") + FlipBrain.gp(s.likely));
			likely.setForeground(s.likely > 0 ? GREEN : AMBER);
		}
		fill.setText(s.fillMinutes < 0 ? "" : String.format("~%.0f min", Math.max(1, s.fillMinutes)));
		fillRow.setVisible(s.fillMinutes >= 0);
		cardSub.setText(wrap(v.cardSub));
		cardSub.setVisible(!v.cardSub.isEmpty());
		OfferAdvice first = none ? null : FlipSidebarPlugin.nextOfferStep(v);
		cardFirst.setVisible(first != null);
		cardFirst.setText(first == null ? "" : wrap("First: " + FlipSidebarPlugin.stepText(first) + " - then this"));
		cardFirst.setForeground(first == null || OfferAdvice.CANCEL.equals(first.verdict) ? RED
			: OfferAdvice.COLLECT.equals(first.verdict) ? GREEN : AMBER);
		boolean info = s.note != null && s.note.startsWith("High alch:");      // High alch mode's line: not a warning
		cardNote.setText(wrap(s.note == null || none ? "" : (info ? "" : "⚠ ") + s.note));
		cardNote.setVisible(!none && s.note != null && !s.note.isEmpty());
		buttons.setVisible(!none);
		// Placed | Skip | Never on a buy; Placed | Skip | Hold on every sell (1.60.40 — until then only one that would
		// lose; user: "realistically player should be able to hold what ever they want"), added / removed on a change
		boolean hold = !buy && !none && "SELL".equals(s.action);
		if (buy != (neverButton.getParent() == buttons) || hold != (holdButton.getParent() == buttons))
		{
			buttons.remove(neverButton);
			buttons.remove(holdButton);
			if (buy)
			{
				buttons.add(neverButton);
			}
			else if (hold)
			{
				buttons.add(holdButton);
			}
			buttons.revalidate();
		}
	}

	/**
	 * Under Offers: what is bought and not sold yet, valued now, and the coins in buy offers. Holding: what is parked
	 * in the inventory for its price to come back (FlipBrain.Held.parked), one row each.
	 */
	private void paintHolding(FlipBrain.View v)
	{
		List<FlipBrain.Held> held = new ArrayList<>();
		List<FlipBrain.Held> parked = new ArrayList<>();
		for (FlipBrain.Held h : v == null ? List.<FlipBrain.Held>of() : v.held)
		{
			(h.parked() ? parked : held).add(h);
		}
		long ifSold = 0;
		int banked = 0;
		for (FlipBrain.Held h : parked)
		{
			ifSold += h.profit;
			banked += h.banked ? 1 : 0;
		}
		parkTitle.setText(title("Holding", parkHidden, parked.size()));
		parkTotal.setVisible(!parkHidden);
		// "Most items held" (FlipBrain.holdingFull): full = no new swings (a losing sell is still kept here, 1.54.0)
		int most = Math.max(0, config.holdLimit());
		String cap = v != null && v.holdingFull ? String.format(" · FULL (%d max): no new swings", most)
			: String.format(" · %d max", most);
		parkTotal.setText(wrap(parked.isEmpty() ? "nothing held - an item whose price swings back waits here instead of "
			+ "selling at a loss" + cap : String.format("%d item%s%s · %s%s if sold now · each comes back as a sell at "
			+ "break-even%s", parked.size(), parked.size() == 1 ? "" : "s", banked == 0 ? "" : " (" + banked + " in your bank)",
			ifSold >= 0 ? "+" : "-", FlipBrain.gp(Math.abs(ifSold)), cap)));
		for (int i = 0; i < HELD_ROWS; i++)
		{
			parkRows[i].show(!parkHidden && i < parked.size() ? parked.get(i) : null);
		}
		holdTotal.setVisible(!offersHidden);
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
			: String.format("%d item%s bought, not sold · worth %s (%s%s)%s", held.size(), held.size() == 1 ? "" : "s",
				FlipBrain.gp(worth), profit >= 0 ? "+" : "", FlipBrain.gp(profit), buys)));
	}

	/** A section header that hides / shows its section on a click, as Next flips does. */
	private void collapsible(JLabel head, String explain, Runnable toggle)
	{
		head.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		head.setToolTipText("<html><div style='width:220px'>Click to show or hide. " + explain + "</div></html>");
		head.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				toggle.run();
				show(shown);
			}
		});
	}

	/** "Offers - hide", or "Offers (8) - show" while hidden. */
	private static String title(String name, boolean hidden, int count)
	{
		return hidden ? name + " (" + count + ") - show" : name + " - hide";
	}

	/**
	 * What the casts made, sampled each time it changes (the top card's alch line); a smaller total than the last means a
	 * new list (FlipBrain.alchHeld starts again): the line starts again. Kept across a new Look (static).
	 */
	private static final List<Long> ALCH_LINE = new ArrayList<>();

	private static long[] alchLine(long made)
	{
		if (!ALCH_LINE.isEmpty() && made < ALCH_LINE.get(ALCH_LINE.size() - 1))
		{
			ALCH_LINE.clear();
		}
		if (ALCH_LINE.isEmpty() || made != ALCH_LINE.get(ALCH_LINE.size() - 1))
		{
			ALCH_LINE.add(made);
			if (ALCH_LINE.size() > 300)
			{
				ALCH_LINE.remove(0);
			}
		}
		long[] out = new long[ALCH_LINE.size()];
		for (int i = 0; i < out.length; i++)
		{
			out[i] = ALCH_LINE.get(i);
		}
		return out;
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
			sessionCard.session(false, 0, 0, "", new long[0]);
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
		// ⭐ the goal that scales with any cash (1.60.44): gp per hour as a share of the coins in flipping — coins in the
		// inventory, in buy offers and what is bought and not sold yet (at cost). User, 2026-11-07: "Our goal here is gp
		// per hour no matter wat the cash it should be making good roi" — 500k an hour on 150m = 0.33% (FlipEngine.ROI_GOAL_PCT_HOUR)
		long capital = Math.max(0, v.inventoryCoins) + Math.max(0, v.inBuys);
		for (FlipBrain.Held h : v.held)
		{
			capital += Math.max(0, h.paidEach) * Math.max(0, h.qty);
		}
		double cashPct = capital > 0 ? profit / hours * 100.0 / capital : Double.NaN;
		sessionProfit.setText(String.format("%s%s · %s/hr", profit >= 0 ? "+" : "-", FlipBrain.gp(Math.abs(profit)),
			FlipBrain.gp((long) (profit / hours))));
		sessionProfit.setForeground(profit >= 0 ? GREEN : RED);
		// ROI first: in a narrow sidebar the line is cut at its end (user: "roi is cut off in the side bar. It reads 0.....")
		String line = String.format("%s%s · %d flip%s\n%d won · %d lost",
			cost > 0 ? String.format("ROI %.1f%% · ", profit * 100.0 / cost) : "",
			FlipSidebarSlotOverlay.clock(now - v.sessionStart), flips, flips == 1 ? "" : "s", won, lost);
		sessionLine.setText(wrap(line.replace("\n", " · ")));
		// the running profit after each flip of the session, oldest first — the top card's line
		List<GameSnapshot.Done> done = new ArrayList<>();
		for (GameSnapshot.Done d : v.history)
		{
			if (d.at >= v.sessionStart)
			{
				done.add(d);
			}
		}
		done.sort(java.util.Comparator.comparingLong(d -> d.at));
		long[] points = new long[done.size() + 1];
		for (int i = 0; i < done.size(); i++)
		{
			points[i + 1] = points[i] + done.get(i).profit;
		}
		sessionCard.session(true, profit, (long) (profit / hours), line, points);
		sessionCard.cashRate(cashPct, FlipEngine.ROI_GOAL_PCT_HOUR);
		boolean rate = config.topGraph() == FlipSidebarConfig.TopGraph.GP_PER_HOUR;
		long[][] rl = rate ? rateLine(done, v.sessionStart, now) : new long[][]{new long[0], new long[0]};
		sessionCard.rate(rate, rl[0], rl[1]);
	}

	/** The gp-an-hour line starts this many minutes in: before it a sell or two over a minute or two reads 1m+ an hour. */
	static final int RATE_FROM_MIN = 5;
	/** The thin line: the pace of this many minutes back, as gp an hour. */
	static final int PACE_MIN = 15;
	/** At most this many points on the line (a long session takes every few minutes). */
	static final int RATE_POINTS = 240;

	/**
	 * The top card's "gp an hour" line (Top graph setting; user, 2026-11-05: "see when it levels out so I know when weve
	 * flat lined"): minute by minute from {@link #RATE_FROM_MIN} in, the session's profit so far / its hours so far —
	 * the panel's own gp an hour, which ends the line — and the last {@link #PACE_MIN} minutes' profit as an hour's pace
	 * ({@code Long.MIN_VALUE} before that much has passed). {@code done}: the session's flips, oldest first.
	 * Returns {rate, pace}, the same length; both empty before RATE_FROM_MIN.
	 */
	static long[][] rateLine(List<GameSnapshot.Done> done, long start, long now)
	{
		long minMs = 60_000L;
		long elapsed = now - start;
		if (elapsed < RATE_FROM_MIN * minMs)
		{
			return new long[][]{new long[0], new long[0]};
		}
		long lastMin = elapsed / minMs;
		long step = Math.max(1, (lastMin - RATE_FROM_MIN + RATE_POINTS - 1) / RATE_POINTS);
		List<Long> at = new ArrayList<>();
		for (long m = RATE_FROM_MIN; m <= lastMin; m += step)
		{
			at.add(start + m * minMs);
		}
		if (at.get(at.size() - 1) < now)
		{
			at.add(now);                // the line ends at the panel's own number
		}
		long[] rate = new long[at.size()];
		long[] pace = new long[at.size()];
		for (int i = 0; i < at.size(); i++)
		{
			long t = at.get(i);
			long made = 0;
			long recent = 0;
			for (GameSnapshot.Done d : done)
			{
				if (d.at <= t)
				{
					made += d.profit;
					recent += d.at > t - PACE_MIN * minMs ? d.profit : 0;
				}
			}
			rate[i] = (long) (made / ((t - start) / 3_600_000.0));
			pace[i] = t - start >= PACE_MIN * minMs ? recent * 60 / PACE_MIN : Long.MIN_VALUE;
		}
		return new long[][]{rate, pace};
	}

	/** Right-click the card: never suggest its item again. */
	private void cardMenu(MouseEvent e)
	{
		FlipBrain.View v = shown;
		Suggestion s = v == null ? null : v.card;
		if (!e.isPopupTrigger() || s == null || !("BUY".equals(s.action) || "SELL".equals(s.action)))
		{
			return;
		}
		JPopupMenu m = new JPopupMenu();
		if ("SELL".equals(s.action))
		{
			// keep it to alch instead (user, 2026-11-03: "there needs to be a right click save it for alch")
			JMenuItem alch = new JMenuItem("Save " + (s.name == null ? "it" : s.name) + " for alching (never sold)");
			alch.addActionListener(ev -> plugin.saveForAlch(s.itemId, true));
			m.add(alch);
		}
		else
		{
			keepOffItems(m, s.itemId, s.name, false, brain::skip);
		}
		showMenu(m, e.getComponent(), e.getX(), e.getY());
	}

	/**
	 * A right-click menu, the hover tip closed first — it stayed on top of the menu's first lines (the user's screen
	 * recording 2026-11-03 19:28: a Holding row's "Click: this item's price graph" over "Sell ... now at the market").
	 */
	private static void showMenu(JPopupMenu m, Component at, int x, int y)
	{
		ToolTipManager tips = ToolTipManager.sharedInstance();
		tips.setEnabled(false);       // hides the tip on screen
		tips.setEnabled(true);
		m.show(at, x, y);
	}

	/**
	 * "Don't suggest X for a day", "... for a week", "Never suggest X" (user, 2026-11-02: "an option in the right click
	 * menu that instead of dont show this item again dont show it for a week or a day"). {@code replace}: a Not suggested
	 * row — the new choice replaces what keeps it off now (a day can shorten a week's rest). {@code then}: after it.
	 */
	private void keepOffItems(JPopupMenu m, int itemId, String name, boolean replace, Runnable then)
	{
		String n = name == null ? "it" : name;
		JMenuItem day = new JMenuItem((replace ? "Keep " + n + " off for a day" : "Don't suggest " + n + " for a day"));
		day.addActionListener(ev -> keepOff(itemId, FlipLearner.REST_MS, replace, then));
		m.add(day);
		JMenuItem week = new JMenuItem((replace ? "Keep " + n + " off for a week" : "Don't suggest " + n + " for a week"));
		week.addActionListener(ev -> keepOff(itemId, FlipLearner.WEEK_MS, replace, then));
		m.add(week);
		JMenuItem never = new JMenuItem("Never suggest " + n);
		never.addActionListener(ev ->
		{
			block(itemId);
			then.run();
		});
		m.add(never);
	}

	private void keepOff(int itemId, long forMs, boolean replace, Runnable then)
	{
		if (replace)
		{
			unblock(itemId);
			brain.suggestAgain(itemId);
		}
		brain.keepOff(itemId, forMs);
		then.run();
	}

	/** "Suggest again": off your Never suggest list, and its rest and your keep-off end. */
	private void suggestAgain(int itemId)
	{
		unblock(itemId);
		brain.suggestAgain(itemId);
	}

	/** Take an item off the "Never suggest" list (its name, or an old numeric entry). */
	private void unblock(int itemId)
	{
		String name = brain.itemName(itemId);
		List<String> keep = new ArrayList<>();
		boolean changed = false;
		for (String k : FlipSidebarConfig.parseNames(config.blockedItems()))
		{
			boolean it = k.equals(String.valueOf(itemId)) || (name != null && k.equalsIgnoreCase(name));
			changed |= it;
			if (!it)
			{
				keep.add(k);
			}
		}
		if (changed)
		{
			configManager.setConfiguration(FlipSidebarConfig.GROUP, "blockedItems", String.join("\n", keep));
		}
	}

	/**
	 * "Never suggest" entries saved as item ids (before the list was by name) become names as soon as the
	 * item names are loaded (user, 2026-10-31, seeing "573" in it: "we players dont know what the numbers
	 * mean"). An id with no known name stays as it is.
	 */
	private void namesForBlocked()
	{
		if (configManager == null || !brain.hasItemNames())
		{
			return;                   // the off-screen preview, or before the first scan
		}
		List<String> names = new ArrayList<>();
		boolean changed = false;
		for (String k : FlipSidebarConfig.parseNames(config.blockedItems()))
		{
			String n = k.matches("\\d{1,9}") ? brain.itemName(Integer.parseInt(k)) : null;
			changed |= n != null;
			names.add(n != null ? n : k);
		}
		if (changed)
		{
			configManager.setConfiguration(FlipSidebarConfig.GROUP, "blockedItems", String.join("\n", names));
		}
	}

	/** Add an item to the "Never suggest" list, by name (old numeric entries are turned into names too). */
	private void block(int itemId)
	{
		List<String> names = new ArrayList<>();
		for (String k : FlipSidebarConfig.parseNames(config.blockedItems()))
		{
			String n = k.matches("\\d{1,9}") ? brain.itemName(Integer.parseInt(k)) : k;
			names.add(n == null ? k : n);
		}
		String name = brain.itemName(itemId);
		String add = name == null ? String.valueOf(itemId) : name;
		if (names.stream().noneMatch(add::equalsIgnoreCase))
		{
			names.add(add);
		}
		configManager.setConfiguration(FlipSidebarConfig.GROUP, "blockedItems", String.join("\n", names));
	}

	/** A small orange text that acts as a link. */
	/** A row that opens its item's price graph when clicked. */
	private static void clickToGraph(JPanel row, Runnable open)
	{
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.setToolTipText("Click: this item's price graph (Statistics window)");
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (SwingUtilities.isLeftMouseButton(e))
				{
					open.run();
				}
			}
		});
	}

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
		blink = !blink;
		paintTabs(shown);
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
		rescanButton.setText(v.scanning ? "Scanning..." : "Scan now");
		rescanButton.setToolTipText(v.scannedAt <= 0 ? "Look for flips again now"
			: "Look for flips again now - last scan " + ago(System.currentTimeMillis() - v.scannedAt) + " ago");
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

	/** A section's hidden / shown state as last left (saved in RuneLite's settings, this account's profile). */
	private boolean remembered(String key, boolean otherwise)
	{
		String v = configManager.getConfiguration(FlipSidebarConfig.GROUP, key);
		return v == null ? otherwise : Boolean.parseBoolean(v);
	}

	/** Saves a section's state and hands it back (for the toggle's assignment). */
	private boolean remember(String key, boolean hidden)
	{
		configManager.setConfiguration(FlipSidebarConfig.GROUP, key, String.valueOf(hidden));
		return hidden;
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
	private final class OfferRow extends FlipUi.Card
	{
		private final JLabel name = label("", WHITE, FlipTheme.now.bold());
		private final JLabel verdict = label("", DIM, FlipTheme.now.bold());
		/** The offer's expected profit after tax (OfferAdvice.expected), left of the verdict. */
		private final JLabel gain = label("", GREEN, FlipTheme.now.bold());
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
			JPanel right = new JPanel(new BorderLayout(6, 0));
			right.setOpaque(false);
			right.add(gain, BorderLayout.WEST);
			right.add(verdict, BorderLayout.EAST);
			head.add(right, BorderLayout.EAST);
			gain.setToolTipText("Expected profit after tax: a sell at its price against what you paid; a buy if sold at "
				+ "today's insta-buy");
			add(head);
			add(line);
			add(why);
			setToolTipText("Right-click to ignore this offer");
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
			// its price graph, with your offer's price marked (user, 2026-11-07: "I tried right clicking the offer nope")
			JMenuItem chart = new JMenuItem("Price graph");
			chart.addActionListener(ev -> openItem(a.offer.itemId, brain.itemName(a.offer.itemId), a.offer.buy ? a.offer.price : 0,
				"your bid", a.offer.buy ? 0 : a.offer.price, "your ask", null));
			m.add(chart);
			JMenuItem item = new JMenuItem(a.ignored() ? "Stop ignoring this offer" : "Ignore this offer");
			item.addActionListener(ev -> toggleIgnored(a.offer.key()));
			m.add(item);
			showMenu(m, this, e.getX(), e.getY());
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
			boolean known = a.expected != Long.MIN_VALUE && !ig;
			gain.setVisible(known);
			gain.setText(!known ? "" : (a.expected >= 0 ? "+" : "-") + FlipBrain.gp(Math.abs(a.expected)));
			gain.setForeground(a.expected >= 0 ? GREEN : RED);
			Color need = ig ? DIM : OfferAdvice.COLLECT.equals(a.verdict) ? GREEN
				: OfferAdvice.CANCEL.equals(a.verdict) ? RED : a.adjust() ? AMBER : DIM;
			verdict.setForeground(need);
			setBorder(FlipUi.stripe(need == DIM ? null : need, 4, 6, 4, 6));      // green: collect · red: cancel · amber: re-price
			String text = String.format("%s %,d/%,d @ %,d", o.buy ? "Buy" : "Sell", o.filled, o.total, o.price);
			long[] lim = o.buy && shown != null ? shown.limits.get(o.itemId) : null;
			if (lim != null && !ig)
			{
				text += String.format(" · limit %,d left", lim[0]);
			}
			for (FlipBrain.Held h : !o.buy && !ig && shown != null ? shown.held : List.<FlipBrain.Held>of())
			{
				if (h.itemId == o.itemId && h.paidEach > 0)
				{
					text += String.format(" · paid %,d", h.paidEach);       // the old Holding row's, now on the offer
				}
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
	/** One item not suggested now: why, and until when. Right-click: suggest it again, or keep it off longer. */
	private final class OffRow extends FlipUi.Card
	{
		private final JLabel name = label("", WHITE, FlipTheme.now.bold());
		private final JLabel until = label("", AMBER, small());
		private final JLabel line = label("", DIM, small());
		private FlipBrain.Off off;
		// the name on its own line, whole; when it comes back and why under it ("Ancient ess..." beside the date)

		OffRow()
		{
			setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
			setBackground(CARD);
			setBorder(new EmptyBorder(4, 6, 4, 6));
			setToolTipText("Right-click: suggest it again, or keep it off for a day, a week or for good");
			add(name);
			add(until);
			add(line);
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
			FlipBrain.Off o = off;
			if (!e.isPopupTrigger() || o == null)
			{
				return;
			}
			JPopupMenu m = new JPopupMenu();
			if (o.kind != FlipBrain.Off.SPIKE)         // a spike comes back by itself when its price settles
			{
				JMenuItem again = new JMenuItem("Suggest " + o.name + " again");
				again.addActionListener(ev -> suggestAgain(o.itemId));
				m.add(again);
			}
			keepOffItems(m, o.itemId, o.name, true, () -> { });
			showMenu(m, this, e.getX(), e.getY());
		}

		void show(FlipBrain.Off o)
		{
			off = o;
			showRow(this, o != null);
			if (o == null)
			{
				return;
			}
			name.setText(o.name);
			boolean plugin = o.kind == FlipBrain.Off.REST || o.kind == FlipBrain.Off.SPIKE;
			until.setText(o.kind == FlipBrain.Off.SPIKE ? "back when its price settles"
				: o.until <= 0 ? "never suggested" : "back " + new SimpleDateFormat("EEE d MMM HH:mm").format(new Date(o.until)));
			until.setForeground(plugin ? AMBER : ORANGE);
			line.setText(wrap(plugin ? "the plugin: " + o.why : o.why));
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}
	}

	private final class HistoryRow extends FlipUi.Card
	{
		private final JLabel name = label("", WHITE, FlipTheme.now.bold());
		private final JLabel gain = label("", GREEN, FlipTheme.now.bold());
		private final JLabel line = label("", DIM, small());
		private GameSnapshot.Done done;

		HistoryRow()
		{
			setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
			setBackground(CARD);
			clickToGraph(this, () ->
			{
				GameSnapshot.Done d = done;
				if (d != null)
				{
					openItem(d.itemId, d.name, d.buyEach, "you bought at", d.sellEach, "you sold at",
						new FlipsWindow.Outcome("Profit", d.qty > 0 ? d.profit / d.qty : 0, d.profit, d.qty));
				}
			});
			JPanel head = new JPanel(new BorderLayout(4, 0));
			head.setOpaque(false);
			head.setAlignmentX(Component.LEFT_ALIGNMENT);
			head.add(name, BorderLayout.CENTER);
			head.add(gain, BorderLayout.EAST);
			add(head);
			add(line);
			// right-click: keep its item off the cards — Not suggested (user, 2026-11-02: "I should be able to right
			// click an item in history and send it to the not suggested list")
			setToolTipText("Click: this item's price graph · right-click: keep it off the cards (Not suggested)");
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
			GameSnapshot.Done d = done;
			if (!e.isPopupTrigger() || d == null)
			{
				return;
			}
			JPopupMenu m = new JPopupMenu();
			keepOffItems(m, d.itemId, d.name == null ? "Item " + d.itemId : d.name, false, () -> { });
			showMenu(m, this, e.getX(), e.getY());
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
			setBorder(FlipUi.stripe(loss ? RED : GREEN, 4, 6, 4, 6));
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
	private final class HeldRow extends FlipUi.Card
	{
		private final JLabel name = label("", WHITE, FlipTheme.now.bold());
		private final JLabel gain = label("", GREEN, FlipTheme.now.bold());
		private final JLabel line = label("", DIM, small());
		private final JLabel note = label("", AMBER, small());
		private FlipBrain.Held held;

		HeldRow()
		{
			setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
			setBackground(CARD);
			setBorder(new EmptyBorder(4, 6, 4, 6));
			clickToGraph(this, () ->
			{
				FlipBrain.Held h = held;
				if (h != null)
				{
					graph(h);
				}
			});
			JPanel head = new JPanel(new BorderLayout(4, 0));
			head.setOpaque(false);
			head.setAlignmentX(Component.LEFT_ALIGNMENT);
			head.add(name, BorderLayout.CENTER);
			head.add(gain, BorderLayout.EAST);
			add(head);
			add(line);
			add(note);
			// selling at a loss is in the right-click menu only — a one-click link here let the Ascension shards go on a
			// stray click (user, 2026-11-01: "i clicked them and it put them back into rotation ... that should be right
			// click menu only")
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

		/**
		 * Right-click a Holding row: sell it now at the market (taking the loss); hold it long-term, or untick that to stop
		 * holding it — it is parked again only if the rule says so.
		 */
		private void menu(MouseEvent e)
		{
			FlipBrain.Held h = held;
			if (!e.isPopupTrigger() || h == null)
			{
				return;
			}
			String n = h.name == null ? "it" : h.name;
			JPopupMenu m = new JPopupMenu();
			// the big price graph, as a left-click opens it (user, 2026-11-01: "in the right click menu on hold item an
			// option to view price graph that opens up the big graph")
			JMenuItem chart = new JMenuItem("Price graph");
			chart.addActionListener(ev -> graph(h));
			m.add(chart);
			if (h.parked() && !h.ready && !h.banked)
			{
				JMenuItem sell = new JMenuItem("Sell " + n + " now at the market (take the loss)");
				sell.addActionListener(ev -> letGo(h));
				m.add(sell);
			}
			// LONG-TERM: its price is kept with no time limit; banked, it stays here and is marked in the bank (user,
			// 2026-11-01, of Flipping Copilot's bank tab: "a little icon next to each item in the bank so u know its a
			// flip item. then they just have you hold it long term")
			// ONE tick-box, always there: ticked = held long-term, untick = stop holding (user, 2026-11-03, with a screen
			// recording of the Robin Hood hat's menu, already held, showing only "Stop holding": "i should be able to long
			// term hold it though from there")
			if (h.parked() || h.mine)
			{
				JCheckBoxMenuItem keep = new JCheckBoxMenuItem("Hold " + n + " long-term (you can bank it)", h.mine);
				keep.addActionListener(ev ->
				{
					if (keep.isSelected())
					{
						plugin.holdItem(h.itemId);
					}
					else
					{
						plugin.releaseItem(h.itemId);
					}
				});
				m.add(keep);
			}
			JMenuItem alch = new JMenuItem("Save " + n + " for alching (never sold)");
			alch.addActionListener(ev -> plugin.saveForAlch(h.itemId, true));
			m.add(alch);
			// the plugin cannot tell a flip's items from the player's own of the same kind: the player can say (user,
			// 2026-11-05, of 4,000 of their own Chaos runes offered for sale from a wrong record)
			JMenuItem mine = new JMenuItem("These " + n + " are mine, not a flip (forget them)");
			mine.addActionListener(ev -> plugin.notAFlip(h.itemId));
			m.add(mine);
			m.addSeparator();
			keepOffItems(m, h.itemId, h.name, false, () -> { });     // not bought again: Not suggested
			showMenu(m, this, e.getX(), e.getY());
		}

		/** The Statistics window's price graph for it, at today's price: what selling it all now would make or lose. */
		private void graph(FlipBrain.Held h)
		{
			openItem(h.itemId, h.name, h.paidEach, "you paid", h.market, "sells now at", h.market <= 0 ? null
				: new FlipsWindow.Outcome("If sold now", h.market - FlipEngine.tax(h.market, h.itemId) - h.paidEach,
					h.profit, h.qty));
		}

		/** Sell it now: no longer held by the user either. */
		private void letGo(FlipBrain.Held h)
		{
			if (h.mine)
			{
				plugin.releaseItem(h.itemId);
			}
			brain.sellAtLoss(h.itemId);
		}

		void show(FlipBrain.Held h)
		{
			held = h;
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
			note.setVisible(h.parked());
			note.setText(h.parked() ? wrap(h.note) : "");
			note.setForeground(h.ready ? GREEN : AMBER);
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}
	}

	/** One of the next best flips. Click it to make it the card. */
	private final class NextRow extends FlipUi.Card
	{
		private final JLabel name = label("", WHITE, FlipTheme.now.bold());
		private final JLabel gain = label("", GREEN, FlipTheme.now.bold());
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
					keepOffItems(m, p.itemId, p.name, false, () -> { });
					showMenu(m, NextRow.this, e.getX(), e.getY());
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

	/**
	 * The item the user opened in the GE themselves, as the floating box shows it ("Your pick"); {@code lim} = its
	 * {left, limit, resets at} or null. Null hides it. Swing thread.
	 */
	void showOwnPick(Suggestion s, long[] lim)
	{
		ownBox.setVisible(s != null);
		if (s != null)
		{
			boolean buy = "BUY".equals(s.action);
			boolean priced = !s.discouraged;
			ownName.setText(wrap((buy ? "Buy " : "Sell ") + (s.name == null ? "?" : s.name)));
			ownName.setForeground(buy ? GREEN : ORANGE);
			ownLimitRow.setVisible(priced && buy && lim != null);       // not priced: the name and why, as the box does
			ownLimit.setText(lim == null ? "" : String.format("%,d / %,d", lim[0], lim[1]));
			ownLimit.setForeground(lim != null && lim[0] == 0 ? RED : WHITE);
			ownResetsRow.setVisible(priced && buy && lim != null && lim[2] > 0);
			ownResets.setText(lim == null ? "" : FlipSidebarSlotOverlay.clock(lim[2] - System.currentTimeMillis()));
			ownQtyRow.setVisible(priced);
			ownQty.setText(String.format("%,d", s.quantity));
			ownPriceRow.setVisible(priced);
			ownPrice.setText(String.format("%,d", s.price));
			ownSellRow.setVisible(priced && buy && s.sellPrice > 0);
			ownSell.setText(String.format("%,d", s.sellPrice));
			ownProfitRow.setVisible(priced && !(!buy && s.profit == 0));     // a sell with no buy price on record: unknown
			ownProfit.setText((s.profit >= 0 ? "+" : "") + String.format("%,d", s.profit));
			ownProfit.setForeground(s.profit >= 0 ? GREEN : RED);
			boolean note = s.note != null && !s.note.isEmpty();
			ownNote.setVisible(note);
			ownNote.setText(note ? wrap(s.note) : "");
			ownNote.setForeground(s.discouraged ? RED : ORANGE);
		}
		revalidate();
		repaint();
	}

	/** A padded dark box that stacks its children and never grows taller than they need. */
	/**
	 * The game's High Level Alchemy spell icon on the mode button and banner, loaded from the client when it is ready
	 * (the plugin carries no copy of it). Until then — and in the off-screen previews — the plugin's own coin icon.
	 */
	void useGameIcons(SpriteManager sprites)
	{
		if (sprites == null)
		{
			return;
		}
		// the mode tabs' own game icons (user, 2026-11-07: "equipment not a rune plate body and the flip should be the ge
		// icon"): the Grand Exchange map icon for Flip, the worn-equipment tab icon for Equipment
		sprites.getSpriteAsync(net.runelite.api.gameval.SpriteID.Mapfunction.GRAND_EXCHANGE, 0, img ->
			SwingUtilities.invokeLater(() -> flipTab.icon(new ImageIcon(fit(img, 24, 22)))));
		sprites.getSpriteAsync(net.runelite.api.gameval.SpriteID.SideiconsInterface.EQUIPMENT, 0, img ->
			SwingUtilities.invokeLater(() -> gearTab.icon(new ImageIcon(fit(img, 24, 22)))));
		sprites.getSpriteAsync(net.runelite.api.gameval.SpriteID.Magicon.HIGH_LEVEL_ALCHEMY, 0, img ->
			SwingUtilities.invokeLater(() ->
			{
				alchIcon = new ImageIcon(img);
				alchButton.setIcon(alchIcon);
				alchTab.icon(alchIcon);
				paintMode(config.alchMode());
			}));
	}

	static final Color GEAR_BG = new Color(0x26, 0x45, 0x6e);

	/** {@code img} scaled to fit {@code w} × {@code h}, its shape kept (never enlarged past 2x). */
	private static java.awt.image.BufferedImage fit(java.awt.image.BufferedImage img, int w, int h)
	{
		double k = Math.min(2.0, Math.min((double) w / img.getWidth(), (double) h / img.getHeight()));
		return ImageUtil.resizeImage(img, Math.max(1, (int) Math.round(img.getWidth() * k)),
			Math.max(1, (int) Math.round(img.getHeight() * k)));
	}

	/** The three tabs in a row (Flip · Equipment · Alch), for the top card's corner. */
	private JPanel buildTabs()
	{
		JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
		bar.setOpaque(false);
		itemIcon(flipTab, ItemID.COINS_10000);
		itemIcon(gearTab, ItemID.RUNE_PLATEBODY);
		alchTab.icon(new ImageIcon(ImageUtil.resizeImage(ImageUtil.loadImageResource(FlipSidebarPanel.class, "alch_icon.png"),
			22, 22)));
		flipTab.onClick = () -> pickTab(false, false);
		gearTab.onClick = () -> pickTab(true, config.alchMode());
		alchTab.onClick = () -> pickTab(false, true);
		bar.add(flipTab);
		bar.add(gearTab);
		bar.add(alchTab);
		return bar;
	}

	/** An item's picture on a tab, scaled to fit, once it has loaded. */
	private void itemIcon(ModeTab tab, int itemId)
	{
		net.runelite.client.util.AsyncBufferedImage img = itemManager.getImage(itemId);
		img.onLoaded(() -> SwingUtilities.invokeLater(() -> tab.icon(new ImageIcon(ImageUtil.resizeImage(img, 24, 22)))));
		tab.icon(new ImageIcon(ImageUtil.resizeImage(img, 24, 22)));
	}

	/** A tab clicked: the Equipment view, or flipping / alching (the "alchMode" setting). */
	private void pickTab(boolean gear, boolean alch)
	{
		gearView = gear;
		if (gear)
		{
			plugin.gearSeen();
		}
		if (alch != config.alchMode())
		{
			setConfig("alchMode", String.valueOf(alch));
		}
		paintMode(alch);
		show(shown);
		revalidate();
		repaint();
	}

	/** Which tab is selected, and which flash (something to do there, and you are elsewhere). */
	private void paintTabs(FlipBrain.View v)
	{
		boolean alch = config.alchMode();
		boolean work = v != null && v.live && FlipSidebarPlugin.nextOfferStep(v) != null;
		flipTab.state(!gearView && !alch, gearView && !alch && work, blink);
		alchTab.state(!gearView && alch, gearView && alch && work, blink);
		gearTab.state(gearView, !gearView && plugin.gearNew(), blink);
	}

	/** The Equipment page: what it is, the search, and the watched items (built once; paintGear fills it). */
	private void buildGear()
	{
		gearPage.setLayout(new BoxLayout(gearPage, BoxLayout.Y_AXIS));
		gearPage.setBackground(BACKGROUND);
		put(gearPage, header("Equipment", "Gear you want for your own account. Each item: its price now, its lowest of the "
			+ "last 90 days and its 30-day level, and when to buy: WAIT while it keeps making new lows, a GOOD TIME once it "
			+ "has gone " + FlipEngine.GEAR_FLOOR_DAYS + " days without a new low and is still near it, UP when it has "
			+ "turned. When one turns a good time, this tab flashes and RuneLite notifies you (its notification settings)."),
			2);
		JLabel help = label(wrap("Search gear you want - it tells you when each is a good time to buy."), DIM, small());
		put(gearPage, help, 4);
		gearBox.setIcon(IconTextField.Icon.SEARCH);
		gearBox.setBackground(CARD);
		gearBox.setHoverBackgroundColor(HOVER);
		gearBox.setToolTipText("Type 2+ letters of an item's name, then click it to watch it");
		gearBox.setPreferredSize(new Dimension(100, 30));
		gearBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		gearBox.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				gearTyped();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				gearTyped();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				gearTyped();
			}
		});
		gearBox.addActionListener(e ->
		{
			if (gearHits[0].isVisible())
			{
				gearAdd(gearHitIds[0]);
			}
		});
		put(gearPage, gearBox, 2);
		for (int i = 0; i < GEAR_HITS; i++)
		{
			final int k = i;
			JLabel hit = label("", WHITE, small());
			hit.setBorder(new EmptyBorder(2, 6, 2, 6));
			hit.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			hit.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					gearAdd(gearHitIds[k]);
				}

				@Override
				public void mouseEntered(MouseEvent e)
				{
					hit.setForeground(ORANGE);
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					hit.setForeground(WHITE);
				}
			});
			hit.setVisible(false);
			gearHits[i] = hit;
			put(gearPage, hit, 0);
		}
		put(gearPage, Box.createVerticalStrut(6), 0);
		gearEmpty.setText(wrap("Nothing watched yet - search an item above, e.g. a Rondache."));
		put(gearPage, gearEmpty, 4);
		for (int i = 0; i < GEAR_ROWS; i++)
		{
			gearRows[i] = new GearRow();
			put(gearPage, gearRows[i], 4);
		}
	}

	private void gearTyped()
	{
		List<Integer> ids = brain.searchItems(gearBox.getText(), GEAR_HITS);
		for (int i = 0; i < GEAR_HITS; i++)
		{
			boolean on = i < ids.size();
			gearHitIds[i] = on ? ids.get(i) : -1;
			gearHits[i].setText(on ? "+ " + brain.itemName(ids.get(i)) : "");
			gearHits[i].setVisible(on);
		}
		gearPage.revalidate();
	}

	/** The watch list's ids, in order (the "gearWatch" setting). */
	private List<Integer> gearIds()
	{
		List<Integer> out = new ArrayList<>();
		for (String x : (config.gearWatch() == null ? "" : config.gearWatch()).split(","))
		{
			try
			{
				int id = Integer.parseInt(x.trim());
				if (!out.contains(id))
				{
					out.add(id);
				}
			}
			catch (NumberFormatException e)
			{
				// skipped
			}
		}
		return out;
	}

	private void gearSave(List<Integer> ids)
	{
		StringBuilder b = new StringBuilder();
		for (int id : ids)
		{
			b.append(b.length() == 0 ? "" : ",").append(id);
		}
		setConfig("gearWatch", b.toString());
		brain.gearChanged();
	}

	private void gearAdd(int itemId)
	{
		if (itemId <= 0)
		{
			return;
		}
		List<Integer> ids = gearIds();
		if (!ids.contains(itemId) && ids.size() < GEAR_ROWS)
		{
			ids.add(itemId);
			gearSave(ids);
		}
		gearBox.setText("");
		paintGear();
	}

	private void gearRemove(int itemId)
	{
		List<Integer> ids = gearIds();
		if (ids.remove(Integer.valueOf(itemId)))
		{
			gearSave(ids);
		}
		paintGear();
	}

	/** The watched items, each with its verdict (FlipBrain.gear — "checking..." until the brain has judged it). */
	private void paintGear()
	{
		List<Integer> ids = gearIds();
		Map<Integer, FlipBrain.Gear> judged = new HashMap<>();
		for (FlipBrain.Gear g : brain.gear())
		{
			judged.put(g.itemId, g);
		}
		gearEmpty.setVisible(ids.isEmpty());
		for (int i = 0; i < GEAR_ROWS; i++)
		{
			int id = i < ids.size() ? ids.get(i) : -1;
			gearRows[i].show(id, judged.get(id));
		}
		gearPage.revalidate();
		gearPage.repaint();
	}

	/** One watched item: picture, name, prices, the verdict (coloured), and "remove". Built once; only its text changes. */
	private final class GearRow extends JPanel
	{
		private final JLabel icon = new JLabel();
		private final JLabel name = label("", WHITE, FlipTheme.now.bold());
		private final JLabel prices = label("", DIM, small());
		private final JLabel verdict = label("", AMBER, small());
		private final JLabel remove = label("✕", RED, FlipTheme.now.bold());
		private int itemId = -1;

		GearRow()
		{
			super(new BorderLayout(6, 0));
			setBackground(CARD);
			setBorder(new EmptyBorder(5, 6, 5, 6));
			icon.setPreferredSize(new Dimension(36, 32));
			add(icon, BorderLayout.WEST);
			JPanel mid = new JPanel();
			mid.setLayout(new BoxLayout(mid, BoxLayout.Y_AXIS));
			mid.setOpaque(false);
			mid.add(name);
			mid.add(prices);
			mid.add(verdict);
			add(mid, BorderLayout.CENTER);
			remove.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			remove.setToolTipText("Stop watching this item");
			remove.setVerticalAlignment(JLabel.TOP);
			remove.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					gearRemove(itemId);
				}
			});
			add(remove, BorderLayout.EAST);
			setVisible(false);
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}

		void show(int id, FlipBrain.Gear g)
		{
			setVisible(id > 0);
			if (id <= 0)
			{
				itemId = -1;
				return;
			}
			if (id != itemId)
			{
				itemId = id;
				itemManager.getImage(id).addTo(icon);
			}
			// text beside the picture and the ✕: wrapped to GEAR_TEXT_PX so nothing runs off the sidebar (user: "half the
			// information is cut off")
			name.setText(wrap(brain.itemName(id), GEAR_TEXT_PX));
			if (g == null)
			{
				prices.setText(wrap("checking its price history...", GEAR_TEXT_PX));
				verdict.setText("");
				return;
			}
			// its usual price (6 months), or — a new item — its age and that it is too new to call; and its high-alch floor
			String usual = g.usual > 0 ? "Usual price (6 months) " + FlipBrain.gp(g.usual)
				: g.daysOld >= 0 ? "New item (" + g.daysOld + " days traded) - too new to say where it settles" : "";
			String floor = g.floor > 0 && g.floor * 10 > g.now ? " · can't go much under " + FlipBrain.gp(g.floor) + " (high alch)"
				: "";
			prices.setText(wrap(String.format("Now %s · 90-day low %s · 30-day %s%s%s", FlipBrain.gp(g.now),
				FlipBrain.gp(g.low), FlipBrain.gp(g.avg30), usual.isEmpty() ? "" : " · " + usual, floor), GEAR_TEXT_PX));
			prices.setToolTipText(g.usual > 0 ? null : "<html><div style='width:220px'>New items are hard to call: of 35 past raid "
				+ "and boss drops, a curve fitted to their first weeks guessed their price 3-6 months later 30-60% too low, "
				+ "and many rose after their first month (Soulreaper axe, Oathplate, Avernic treads). Its price about a month "
				+ "after release was the closest simple guide - within 25-40%, either way.</div></html>");
			verdict.setText(wrap(g.why, GEAR_TEXT_PX));
			verdict.setForeground(g.state == FlipBrain.Gear.BUY ? GREEN : g.state == FlipBrain.Gear.WAIT ? AMBER
				: g.state == FlipBrain.Gear.UP ? ORANGE : DIM);
		}
	}

	/** A mode tab: an icon; a dark box behind it when selected; an orange ring that blinks when it flashes. */
	/** A small picture in the top strip that opens or does something: dim until the mouse is over it. */
	private static final class IconLink extends JLabel
	{
		private javax.swing.Icon normal;
		private javax.swing.Icon hover;
		private Runnable onClick;

		IconLink(String tip)
		{
			setToolTipText(tip);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					if (onClick != null)
					{
						onClick.run();
					}
				}

				@Override
				public void mouseEntered(MouseEvent e)
				{
					setIcon(hover);
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					setIcon(normal);
				}
			});
		}

		void picture(java.awt.image.BufferedImage img)
		{
			normal = new ImageIcon(ImageUtil.alphaOffset(img, 0.7f));
			hover = new ImageIcon(img);
			setIcon(getMousePosition() != null ? hover : normal);
		}
	}

	private static final class ModeTab extends JComponent
	{
		private javax.swing.Icon icon;
		private boolean selected;
		private boolean flash;
		private boolean phase;
		private Runnable onClick;

		ModeTab(String tip)
		{
			setToolTipText(tip);
			setPreferredSize(new Dimension(32, 28));
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			setOpaque(false);
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					if (onClick != null)
					{
						onClick.run();
					}
				}
			});
		}

		void icon(javax.swing.Icon i)
		{
			icon = i;
			repaint();
		}

		void state(boolean sel, boolean fl, boolean ph)
		{
			if (sel != selected || fl != flash || (fl && ph != phase))
			{
				selected = sel;
				flash = fl;
				phase = ph;
				repaint();
			}
		}

		@Override
		protected void paintComponent(Graphics g0)
		{
			Graphics2D g = (Graphics2D) g0.create();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int w = getWidth();
			int h = getHeight();
			if (selected)
			{
				g.setColor(new Color(0, 0, 0, 160));
				g.fillRoundRect(1, 1, w - 2, h - 2, 8, 8);
			}
			if (flash && phase)
			{
				g.setColor(new Color(255, 152, 31));
				g.setStroke(new BasicStroke(2f));
				g.drawRoundRect(1, 1, w - 3, h - 3, 8, 8);
			}
			if (icon != null)
			{
				icon.paintIcon(this, g, (w - icon.getIconWidth()) / 2, (h - icon.getIconHeight()) / 2);
			}
			g.dispose();
		}
	}

	/** The banner across the top: HIGH ALCH MODE (dark amber, the spell's icon) or FLIPPING (dark green). */
	private void paintMode(boolean alch)
	{
		if (sessionCard != null)
		{
			sessionCard.mode(alch, alchIcon);
		}
		if (sessionCard != null)
		{
			sessionCard.gear(gearView);
		}
		modeBox.setBackground(gearView ? GEAR_BG : alch ? ALCH_BG : FLIP_BG);
		modeLabel.setText(gearView ? "EQUIPMENT" : alch ? "HIGH ALCH MODE" : "FLIPPING");
		modeLabel.setIcon(!gearView && alch ? alchIcon : null);
	}

	/**
	 * What is tied up in the GE now: the coins left in buy offers (left to buy × the price), what is left to sell (left
	 * to sell × the price, after the 2% tax) and — 1.60.77 — what a finished offer holds until collected: a cancelled
	 * buy's unspent coins, a finished sell's coins (at its price after tax: never less). Bought items waiting in a slot
	 * come from the brain (View.uncollected). Offers you ignore (right-click) are not yours to flip: left out. Not known:
	 * whether an OPEN sell's coins so far were collected — they are not counted until it finishes.
	 */
	static long inGe(List<OfferAdvice> offers)
	{
		long in = 0;
		for (OfferAdvice a : offers)
		{
			GameSnapshot.Offer o = a.offer;
			if (OfferAdvice.IGNORED.equals(a.verdict))
			{
				continue;
			}
			if (o.complete || o.cancelled)
			{
				in += o.buy ? (long) Math.max(0, o.total - o.filled) * o.price
					: (long) o.filled * (o.price - FlipEngine.tax(o.price, o.itemId));
				continue;
			}
			long left = Math.max(0, o.total - o.filled);
			in += o.buy ? left * o.price : left * (o.price - FlipEngine.tax(o.price, o.itemId));
		}
		return in;
	}

	private static JPanel box()
	{
		JPanel p = new FlipUi.Card()
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(CARD);
		p.setBorder(FlipUi.stripe(ORANGE, 8, 8, 8, 8));
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
		return new FlipUi.Header(text, ORANGE, FlipTheme.now.bold());
	}

	/**
	 * A section header that explains itself on hover — the panel stays uncluttered, the terms are
	 * explained where they are used (user, 2026-10-31: "just an explanation when I hover. It doesnt
	 * need to sound that basic").
	 */
	private static JLabel header(String text, String explain)
	{
		JLabel l = header(text);
		l.setToolTipText("<html><div style='width:220px'>" + explain + "</div></html>");
		return l;
	}

	private static Font normal()
	{
		return FlipTheme.now.plain();
	}

	private static Font small()
	{
		return FlipTheme.now.small();
	}

	/** HTML so a long line wraps inside the panel instead of being cut off. */
	/** {@link #wrap} to a narrower width — for text beside a picture (the Equipment rows). */
	private static String wrap(String text, int px)
	{
		String t = text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
		return "<html><div style='width:" + px + "px'>" + t + "</div></html>";
	}

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
