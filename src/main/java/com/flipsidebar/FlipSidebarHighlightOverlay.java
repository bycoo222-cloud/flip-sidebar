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
	private static final int SETUP_QUANTITY_CUSTOM = 51;
	private static final int SETUP_PRICE_CUSTOM = 54;
	private static final int SETUP_CONFIRM = 58;
	/** GE_NEWOFFER_TYPE: 0 = buy offer, 1 = sell offer. */
	private static final int OFFER_TYPE_SELL = 1;

	private final Client client;
	private final FlipSidebarPlugin plugin;
	private final FlipSidebarConfig config;

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
		if (!config.highlight() || !plugin.isGeOpen())
		{
			return null;
		}
		drawOfferActions(g);
		FlipSidebarPlugin.Modify m = plugin.modifying();
		if (m != null)
		{
			drawModify(g, m);         // re-placing an offer: the card's highlights would lead away from it
			return null;
		}
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
		boolean quantityOk = client.getVarbitValue(VarbitID.GE_NEWOFFER_QUANTITY) == o.quantity;
		boolean priceOk = client.getVarpLongValue(VarPlayerID.GE_NEWOFFER_PRICE) == o.price;
		if (!quantityOk)
		{
			draw(g, setup.getChild(SETUP_QUANTITY_CUSTOM));
		}
		if (!priceOk)
		{
			draw(g, setup.getChild(SETUP_PRICE_CUSTOM));
		}
		if (quantityOk && priceOk)
		{
			draw(g, confirm);
		}
	}

	/**
	 * Offers to act on: red to cancel, amber to raise — the slot while the 8 slots are on screen;
	 * once one is opened, its abort (X) or Modify button. Those buttons have no named component, so
	 * they are found by their own right-click option — the game's text, not a guessed position.
	 */
	private void drawOfferActions(Graphics2D g)
	{
		Set<Integer> cancel = plugin.cancelSlots();
		Set<Integer> raise = plugin.adjustSlots();
		if (cancel.isEmpty() && raise.isEmpty())
		{
			return;
		}
		boolean slotsShown = false;
		for (int slot = 1; slot <= SLOTS.length; slot++)
		{
			boolean c = cancel.contains(slot);
			if (!c && !raise.contains(slot))
			{
				continue;
			}
			Widget w = client.getWidget(SLOTS[slot - 1]);
			if (w != null && !w.isHidden())
			{
				slotsShown = true;
				draw(g, w, c ? CANCEL_FILL : RAISE_FILL, c ? CANCEL_EDGE : RAISE_EDGE);
			}
		}
		if (slotsShown)
		{
			return;
		}
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
	}

	/**
	 * The re-placing screen after "Modify offer" (the offer is already cancelled; only Confirm puts
	 * it back): the price until it is the advice's, the quantity until it is what was left, then
	 * Confirm — all amber.
	 */
	private void drawModify(Graphics2D g, FlipSidebarPlugin.Modify m)
	{
		Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
		if (setup == null)
		{
			return;
		}
		boolean priceOk = m.target <= 0 || client.getVarpLongValue(VarPlayerID.GE_NEWOFFER_PRICE) == m.target;
		boolean quantityOk = client.getVarbitValue(VarbitID.GE_NEWOFFER_QUANTITY) == m.quantity;
		if (!priceOk)
		{
			draw(g, setup.getChild(SETUP_PRICE_CUSTOM), RAISE_FILL, RAISE_EDGE);
		}
		if (!quantityOk)
		{
			draw(g, setup.getChild(SETUP_QUANTITY_CUSTOM), RAISE_FILL, RAISE_EDGE);
		}
		if (priceOk && quantityOk)
		{
			draw(g, setup.getChild(SETUP_CONFIRM), RAISE_FILL, RAISE_EDGE);
		}
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

	/** The Buy / Sell arrow of the first empty slot that is on screen. */
	private Widget emptySlotArrow(boolean buy)
	{
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		for (int i = 0; i < SLOTS.length; i++)
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

	/** The item to sell in the GE's side inventory (noted or not — matched by name). */
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
			if (w.getItemId() == itemId || (want != null && want.equals(plugin.itemName(w.getItemId()))))
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
