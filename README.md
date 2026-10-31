# Flip Sidebar

An all-in-one Grand Exchange flipping helper for RuneLite: it finds flips, guides each offer
through the GE, keeps the flips you made — wins and losses — and never acts for you.

## Features

- **Finds flips** (with *Live prices* on): scans the GE every few minutes and shows one flip at a
  time in a side panel — what to buy, how many, the price, what to sell it for, the profit after
  the 2% GE tax and how long it should take to fill. The card stays put while you set the offer up.
- **Prices from the 5-minute averages**, never a single trade, sized to a slice of the item's
  hourly trading and to what is left of its **GE buy limit** (shown above the quantity).
- **Highlights what to click** in the GE: the empty slot, a one-click item in the search, the
  quantity and price buttons, Confirm — and a **click-to-fill** line in the quantity / price box
  (you still press Enter).
- **Your own picks** are priced too: open an offer for any item and it gets a good price and
  quantity, with a note when it is not a recommended flip.
- **Offer advice** on every slot: collect, raise, lower or cancel — a buy that stops paying turns
  red at once; a sell is adjusted (Modify), never cancelled; the Modify screen is guided.
- **Sells**: what you bought is offered back at a profit, never below what you paid plus tax —
  unless the price fell, then it is sold at the market to cut the loss.
- **Offer timers**, and **hover** a GE slot or an item you bought for what you paid, break-even
  and the profit now.
- **Profit flash** on every sell fill (green, or red for a loss), **History** of finished flips,
  **Session** stats (profit, gp/hr, ROI), **Holding** (bought and unsold, valued now), CSV export.
- **Price chart** on the chat box while an item is open in the GE.
- Pause, "never suggest this item", and a cap on the cash it may plan with.

## Live prices and your privacy

*Live prices* is **off by default**. When you turn it on, the plugin asks the OSRS Wiki's price
service (prices.runescape.wiki) for Grand Exchange prices — that service sees your IP address, as
RuneLite's warning says. Nothing about your account is ever sent anywhere: your offers, coins and
flip history stay on your computer (in RuneLite's own settings).

The plugin only draws and, when you click its line, puts a number in the GE box. It never clicks,
types or presses Enter for you.

## License

BSD 2-Clause — see [LICENSE](LICENSE).
