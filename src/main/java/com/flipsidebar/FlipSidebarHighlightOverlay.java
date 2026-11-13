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
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Highlights the ONE next thing to click in the Grand Exchange for the flip on the card:
 * <ol>
 *   <li>the 8 slots: for a buy, the Buy arrow of the first empty slot; for a sell, the item in
 *       your inventory (the Sell arrow only if it cannot be found)</li>
 *   <li>set-up, no item yet: the item search button (buy) / the item in your inventory (sell)</li>
 *   <li>set-up, the card's item — or one you picked yourself, priced for it (live prices): the
 *       quantity "..." and price "..." buttons until they match</li>
 *   <li>everything matches: Confirm</li>
 *   <li>item search open: the one-click row the plugin filled in</li>
 *   <li>all 8 slots in use with a finished offer: the Collect button</li>
 * </ol>
 * And, like Flipping Copilot: in RED an offer the advice says to cancel — its slot, then its abort
 * (X) button once it is opened (user, 2026-10-31: "itll mark the offer red and then have u click the
 * x to cancel the offer" — buys only: a sell is never cancelled); in AMBER one to raise or lower —
 * its slot, its Modify button, then on the re-placing screen the price, the quantity and Confirm
 * ("when it says raise it should be highlighting the offer i need to raise").
 * It only draws. Nothing is clicked or typed.
 */
class FlipSidebarHighlightOverlay extends Overlay
{
	private static final Color FILL = new Color(0, 190, 255, 60);
	private static final Color EDGE = new Color(0, 190, 255, 220);
	private static final Color CANCEL_FILL = new Color(255, 60, 60, 70);
	private static final Color CANCEL_EDGE = new Color(255, 60, 60, 230);
	private static final Color RAISE_FILL = new Color(255, 176, 0, 70);
	private static final Color RAISE_EDGE = new Color(255, 176, 0, 230);
	private static final Color COLLECT_FILL = new Color(80, 220, 100, 70);
	private static final Color COLLECT_EDGE = new Color(80, 220, 100, 230);
	/** Right-click options on an offer, which the buttons inside an open offer also carry. */
	private static final String ABORT = "Abort";
	private static final String MODIFY = "Modify";
	private static final String COLLECT = "Collect";

	/** The 8 offer slots, in order. */
	private static final int[] SLOTS = {
		InterfaceID.GeOffers.INDEX_0, InterfaceID.GeOffers.INDEX_1, InterfaceID.GeOffers.INDEX_2,
		InterfaceID.GeOffers.INDEX_3, InterfaceID.GeOffers.INDEX_4, InterfaceID.GeOffers.INDEX_5,
		InterfaceID.GeOffers.INDEX_6, InterfaceID.GeOffers.INDEX_7,
	};
	/** Inside an empty slot: the Buy and Sell arrows. */
	private static final int SLOT_BUY = 0;
	private static final int SLOT_SELL = 1;
	/**
	 * Children of the offer set-up screen (InterfaceID.GeOffers.SETUP). They are created by the
	 * game's own scripts and have no named constants, so they are addressed by index.
	 */
	private static final int SETUP_ITEM_SEARCH = 0;
	/** A sell offer's "All" quantity button (Flipping Copilot's GrandExchange.getSetQuantityAllButton). */
	private static final int SETUP_QUANTITY_ALL = 50;
	private static final int SETUP_QUANTITY_CUSTOM = 51;
	private static final int SETUP_PRICE_CUSTOM = 54;
	private static final int SETUP_CONFIRM = 58;
	/** GE_NEWOFFER_TYPE: 0 = buy offer, 1 = sell offer. */
	private static final int OFFER_TYPE_SELL = 1;

	private final Client client;
	private final FlipSidebarPlugin plugin;
	private final FlipSidebarConfig config;
	/**
	 * The set-up screen's item and its quantity when "All" was first lit there (-1 = none): once the quantity has moved
	 * off that and is still not right, the "..." button instead — All is never lit in a loop (the 2026-11-02 Quantity
	 * loop: 1,738 tries at a number the game would not take).
	 */
	private int allItem = -1;
	private int allFromQuantity;

	@Inject
	FlipSidebarHighlightOverlay(Client client, FlipSidebarPlugin plugin, FlipSidebarConfig config)
	{
		super(plugin);
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.highlight() || plugin.guidanceOff() || !plugin.isGeOpen())
		{
			return null;              // paused: nothing highlighted at all
		}
		FlipSidebarPlugin.Modify m = plugin.modifying();
		if (m != null)
		{
			drawModify(g, m);         // re-placing an offer: the card's highlights would lead away from it
			return null;
		}
		// ONE step at a time: a cancel, else a re-price, before the card's next offer is started
		boolean offerFirst = drawOfferActions(g);
		Suggestion s = plugin.getSuggestion();
		boolean cardOffer = s != null && ("BUY".equals(s.action) || "SELL".equals(s.action));
		boolean buy = cardOffer && "BUY".equals(s.action);

		// ⚠ "Search open" = the results box is ON SCREEN. The chatbox-mode setting is not a reliable
		// sign: it stayed on "search" after an item was picked, so the set-up screen got no
		// highlights at all (in-game test, 2026-10-31).
		Widget results = client.getWidget(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
		if (results != null && !results.isHidden())
		{
			if (cardOffer)
			{
				draw(g, plugin.filledSearchRow());
			}
			return null;
		}

		// the set-up screen is open when its Confirm button is on screen
		Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
		Widget confirm = setup == null ? null : setup.getChild(SETUP_CONFIRM);
		if (confirm == null || confirm.isHidden())
		{
			allItem = -1;             // set-up closed: the next one may use All again
			if (offerFirst)
			{
				return null;          // the offer to cancel / re-price first; the card's next offer after it
			}
			if (s != null && "COLLECT".equals(s.action))
			{
				draw(g, withOption(client.getWidget(InterfaceID.GeOffers.COLLECTALL), COLLECT));
				return null;
			}
			if (!cardOffer)
			{
				return null;          // WAIT, or nothing worth doing
			}
			// a SELL starts from the item in the inventory: clicking it opens the sell offer itself
			// (user, 2026-10-31: "should highlight the item in the inventory not the sell button")
			Widget item = buy ? null : inventoryItem(s.itemId);
			draw(g, item != null ? item : emptySlotArrow(buy));
			return null;
		}

		// the set-up screen: filled in for the card — or for the user's own pick, priced for it
		Suggestion o = plugin.offerSuggestion();
		if (o != null)
		{
			if (o.quantity > 0 && !o.discouraged)
			{
				drawFill(g, setup, confirm, o);
			}
			return null;
		}
		int item = client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
		boolean sellOffer = client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE) == OFFER_TYPE_SELL;
		if (!cardOffer || sellOffer == buy || (item > 0 && config.livePrices()))
		{
			return null;              // another kind of offer, or the user's own pick still being priced
		}
		draw(g, buy ? setup.getChild(SETUP_ITEM_SEARCH) : inventoryItem(s.itemId));
		return null;
	}

	/** Quantity "..." and price "..." until they match, then Confirm. */
	private void drawFill(Graphics2D g, Widget setup, Widget confirm, Suggestion o)
	{
		if (plugin.settling() || plugin.numberBoxOpen())
		{
			return;                   // the number just entered is not on the set-up screen yet, or a box is open (see below)
		}
		// a sell asks for no more than is held (FlipSidebarPlugin.setupQuantity) — the game does not take more, and
		// this would never match
		boolean sell = "SELL".equals(o.action);
		long want = plugin.setupQuantity(o.quantity, sell, o.itemId);
		boolean quantityOk = client.getVarbitValue(VarbitID.GE_NEWOFFER_QUANTITY) == want;
		boolean priceOk = client.getVarpLongValue(VarPlayerID.GE_NEWOFFER_PRICE) == o.price;
		// ONE BOX AT A TIME: the quantity, then the price, then Confirm (before, quantity and price were lit together).
		// And none while a quantity or price box is open: what comes next there is the number, never "..." again — a
		// "..." left lit with its box open was clicked a second time, and the number typed next never showed in the box.
		if (!quantityOk)
		{
			draw(g, quantityButton(setup, sell, o.itemId, want));
		}
		else if (!priceOk)
		{
			draw(g, setup.getChild(SETUP_PRICE_CUSTOM));
		}
		else
		{
			draw(g, confirm);
		}
	}

	/**
	 * The ONE offer to act on now — green to collect (a finished or cancelled offer: its slot, then once opened its coin
	 * and item boxes), red to cancel, amber to re-price — every collect first, then every cancel, then every RAISE /
	 * LOWER, then the card's next offer (user, 2026-11-01: "if i have 1 cancel 3 adjusts and a buy right it would show just the
	 * red cancel. then it would move direcltly to one of the adjusts and then it would cylce threw all the adjusts to
	 * the but offer. not show them all at once. itll confuse people"). The slot while the 8 slots are on screen (for a
	 * collect, the Collect button at the top right — every finished offer at once); once
	 * an offer is opened, its abort (X) or Modify button. Those buttons have no named component, so they are found by
	 * their own right-click option — the game's text, not a guessed position. True while one is waiting.
	 */
	private boolean drawOfferActions(Graphics2D g)
	{
		Set<Integer> cancel = plugin.cancelSlots();
		Set<Integer> raise = plugin.adjustSlots();
		// an offer is OPEN and finished or cancelled: its coins and items to collect, before anything else
		Widget box = client.getWidget(InterfaceID.GeOffers.DETAILS_COLLECT);
		if (box != null && !box.isHidden())
		{
			int open = client.getVarbitValue(VarbitID.GE_SELECTEDSLOT);
			GrandExchangeOffer[] all = client.getGrandExchangeOffers();
			GrandExchangeOffer o = all == null || open < 1 || open > all.length ? null : all[open - 1];
			if (o != null && finished(o.getState()))
			{
				drawCollect(g, box);
				return true;
			}
		}
		OfferAdvice step = plugin.nextOfferStep();
		int next = step == null ? 0 : step.offer.slot;
		if (next < 1 || next > SLOTS.length)
		{
			return false;
		}
		boolean collect = OfferAdvice.COLLECT.equals(step.verdict);
		boolean c = cancel.contains(next);
		Widget w = client.getWidget(SLOTS[next - 1]);
		if (w != null && !w.isHidden())
		{
			// a collect: the Collect button at the top right takes every finished offer in one click (user, 2026-11-01:
			// "its faster the clicking each offer when multiple finish at once") — the slot only when it is not shown
			Widget all = collect ? withOption(client.getWidget(InterfaceID.GeOffers.COLLECTALL), COLLECT) : null;
			draw(g, all != null ? all : w, collect ? COLLECT_FILL : c ? CANCEL_FILL : RAISE_FILL,
				collect ? COLLECT_EDGE : c ? CANCEL_EDGE : RAISE_EDGE);
			return true;
		}
		// an offer is open: its own button when it is one to act on (the user may take them in any order)
		int open = client.getVarbitValue(VarbitID.GE_SELECTEDSLOT);
		Widget root = client.getWidget(InterfaceID.GeOffers.UNIVERSE);
		if (cancel.contains(open))
		{
			draw(g, withOption(root, ABORT), CANCEL_FILL, CANCEL_EDGE);
		}
		else if (raise.contains(open))
		{
			draw(g, withOption(root, MODIFY), RAISE_FILL, RAISE_EDGE);
		}
		return true;
	}

	private static boolean finished(GrandExchangeOfferState st)
	{
		return st == GrandExchangeOfferState.BOUGHT || st == GrandExchangeOfferState.SOLD
			|| st == GrandExchangeOfferState.CANCELLED_BUY || st == GrandExchangeOfferState.CANCELLED_SELL;
	}

	/** The coin and item boxes of an open offer, green; the whole collect area when they cannot be told apart. */
	private void drawCollect(Graphics2D g, Widget box)
	{
		boolean any = false;
		for (Widget[] kids : new Widget[][]{box.getDynamicChildren(), box.getStaticChildren(), box.getNestedChildren()})
		{
			for (Widget c : kids == null ? new Widget[0] : kids)
			{
				if (c != null && !c.isHidden() && c.getItemId() > 0)
				{
					draw(g, c, COLLECT_FILL, COLLECT_EDGE);
					any = true;
				}
			}
		}
		if (!any)
		{
			draw(g, box, COLLECT_FILL, COLLECT_EDGE);
		}
	}

	/**
	 * The re-placing screen after "Modify offer" (the offer is already cancelled; only Confirm puts
	 * it back): the price until it is the advice's, the quantity until it is what was left, then
	 * Confirm — all amber.
	 */
	private void drawModify(Graphics2D g, FlipSidebarPlugin.Modify m)
	{
		Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
		if (setup == null || plugin.settling() || plugin.numberBoxOpen())
		{
			return;                   // settling: the number just entered is not on the set-up screen yet; or a box is open
		}
		boolean priceOk = m.target <= 0 || client.getVarpLongValue(VarPlayerID.GE_NEWOFFER_PRICE) == m.target;
		long want = plugin.setupQuantity(m.quantity, !m.buy, m.itemId);
		boolean quantityOk = client.getVarbitValue(VarbitID.GE_NEWOFFER_QUANTITY) == want;
		// one box at a time, as drawFill: the price, then the quantity, then Confirm
		if (!priceOk)
		{
			draw(g, setup.getChild(SETUP_PRICE_CUSTOM), RAISE_FILL, RAISE_EDGE);
		}
		else if (!quantityOk)
		{
			draw(g, quantityButton(setup, !m.buy, m.itemId, want), RAISE_FILL, RAISE_EDGE);
		}
		else
		{
			draw(g, setup.getChild(SETUP_CONFIRM), RAISE_FILL, RAISE_EDGE);
		}
	}

	/**
	 * The quantity button to light: "All" for a sell of everything held — one click, nothing to type (user, 2026-11-03:
	 * "instead of me typing the quanity how about we click the all button ... were going to be selling all of that item";
	 * Flipping Copilot does the same, its HighlightController.highlightQuantity) — else "...". All only until the
	 * quantity moves off what it was when All was lit (allItem).
	 */
	private Widget quantityButton(Widget setup, boolean sell, int itemId, long want)
	{
		int now = client.getVarbitValue(VarbitID.GE_NEWOFFER_QUANTITY);
		if (sell && want > 0 && want == plugin.heldCount(itemId))
		{
			if (allItem != itemId || now <= 1)
			{
				allItem = itemId;     // 0 or 1: not set by anything yet (a new set-up starts there)
				allFromQuantity = now;
			}
			if (now == allFromQuantity)
			{
				return setup.getChild(SETUP_QUANTITY_ALL);
			}
		}
		return setup.getChild(SETUP_QUANTITY_CUSTOM);
	}

	/** The visible widget under {@code w} with a right-click option starting with {@code option}. */
	private static Widget withOption(Widget w, String option)
	{
		if (w == null || w.isHidden())
		{
			return null;
		}
		String[] actions = w.getActions();
		if (actions != null)
		{
			for (String a : actions)
			{
				if (a != null && a.trim().regionMatches(true, 0, option, 0, option.length()))
				{
					return w;
				}
			}
		}
		for (Widget[] kids : new Widget[][]{w.getStaticChildren(), w.getDynamicChildren(), w.getNestedChildren()})
		{
			if (kids == null)
			{
				continue;
			}
			for (Widget k : kids)
			{
				Widget found = withOption(k, option);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}

	/** The Buy / Sell arrow of the first empty slot that is on screen — of the 3 a free-to-play account has, in that mode. */
	private Widget emptySlotArrow(boolean buy)
	{
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		for (int i = 0; i < Math.min(SLOTS.length, FlipSidebarConfig.geSlots(config)); i++)
		{
			GrandExchangeOffer o = offers == null || i >= offers.length ? null : offers[i];
			if (o != null && o.getState() != GrandExchangeOfferState.EMPTY)
			{
				continue;
			}
			Widget slot = client.getWidget(SLOTS[i]);
			if (slot == null || slot.isHidden())
			{
				continue;
			}
			Widget arrow = slot.getChild(buy ? SLOT_BUY : SLOT_SELL);
			if (arrow != null && !arrow.isHidden())
			{
				return arrow;
			}
		}
		return null;
	}

	/**
	 * The item to sell in the GE's side inventory, noted or not. ⚠ A NOTE has its own item id (1.60.94): 11,000 Mahogany logs
	 * collected from the GE came as notes (ItemID.Cert.MAHOGANY_LOGS = 8836) while the card's item is 6332, and the user saw
	 * the empty slot's Sell arrow lit instead of the logs (2026-11-10, screenshot: "its highilighting the sell button and not
	 * the sell item. it was better before"). Matched by the canonical id (ItemManager.canonicalize: note / placeholder → the
	 * item, from the game's own item definitions), then by name. Never "id + 1": MEASURED in RuneLite 1.13.6's gameval, 217
	 * of 4,730 notes are not their item's id + 1 — Mahogany logs among them.
	 */
	private Widget inventoryItem(int itemId)
	{
		Widget items = client.getWidget(InterfaceID.GeOffersSide.ITEMS);
		if (items == null || items.isHidden() || items.getDynamicChildren() == null)
		{
			return null;
		}
		String want = plugin.itemName(itemId);
		for (Widget w : items.getDynamicChildren())
		{
			if (w.getItemId() == itemId || (w.getItemId() > 0 && plugin.canonical(w.getItemId()) == itemId)
				|| (want != null && want.equals(plugin.itemName(w.getItemId()))))
			{
				return w;
			}
		}
		return null;
	}

	private void draw(Graphics2D g, Widget w)
	{
		draw(g, w, FILL, EDGE);
	}

	private void draw(Graphics2D g, Widget w, Color fill, Color edge)
	{
		if (w == null || w.isHidden())
		{
			return;
		}
		Rectangle r = w.getBounds();
		if (r == null || r.width <= 0 || r.height <= 0)
		{
			return;
		}
		g.setColor(fill);
		g.fill(r);
		g.setColor(edge);
		g.setStroke(new BasicStroke(2));
		g.draw(r);
	}
}
