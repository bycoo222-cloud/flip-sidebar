# Flip Sidebar

An all-in-one Grand Exchange helper for RuneLite: it finds flips, plans what to buy for High Level
Alchemy, tells you when gear you want for your account is a good buy, guides each offer through the
GE, keeps the flips you made — wins and losses — and never acts for you.

## Three tabs

At the top right of the panel: **Flip**, **Equipment** and **Alch**. The one you are on has a dark box
behind it. Flip and Alch switch what the card does; Equipment is a page you can look at while flipping
or alching keeps going. A tab you are not on **flashes** when it needs you — an offer to collect,
cancel or re-price, or a watched item that has become a good buy.

## Equipment

Search any item you want for your own account and add it (up to 12). For each one the tab shows its
price now, its lowest price of the last 90 days and its 30-day level, and one of:

- **Wait – still falling**: it made a new low in the last 7 days.
- **Good time to buy**: 7 days without a new low, and still within 10% of it.
- **Up X% off its low**: it has turned.

When an item becomes a good time to buy, the tab flashes and RuneLite sends a notification (using
your RuneLite notification settings) — once, until it falls again. The 7-day rule is a rule of thumb,
not a promise: new items from a fresh release often slide for weeks.

> **Beta.** Flip Sidebar is being tested. Please report problems in #bug-reports on the
> [Flip Sidebar Discord](https://discord.gg/jasZpBkaUr) or at
> https://github.com/bycoo222-cloud/flip-sidebar/issues — what the panel showed and what you expected.

## High alch mode

Click the **High Alch** button (a coin with a flame) at the top of the panel, and the sidebar plans
your alching instead of your flips:

- **A buy plan for your next hours of casting**: which items, how many and at what price, so that
  every cast makes the most after the item's price and a nature rune. The plan fits each item's
  **GE buy limit**, what actually trades, your coins and your GE slots (at most 8 items) — a plan you
  can buy, not just a list of good items.
- **A budget you set**: the *Spend on alching* slider under the plan (10m to start; far left = nothing,
  so it only alchs what you already have; far right = no limit) caps the coins alching ties up at once —
  its buy offers and the alch items in your inventory — and never goes past the coins you have. Casting
  frees it for the next buy.
- **The card buys the plan one item at a time**, with its price and an estimated fill time, and the
  same GE highlights as flipping. There are no sell cards in this mode, and a buy made to alch is never
  told to cancel: what you buy is for alching.
- **To alch**: what you hold to alch — how many are left to cast, how many you have cast, and what the
  rest makes. Each plan row says how many remain to alch and about how long they take to cast.
- **Kept for alching**: what you buy in this mode is never offered for sale, in either mode. Right-click
  any held item or sell card → *Save for alching* to keep something else for your casts.
- **One step at a time**: while the card says Buy or Collect, only the GE is outlined (the nearest Grand
  Exchange Clerk while it is closed). Once your buys are placed, the spellbook lights up: on the normal
  spellbook the Magic tab if it is closed, then the High Level Alchemy spell, then the item in your
  inventory to alch. It only draws — you do the clicking.
- Settings (the *High alch mode* section): spend on alching at most, hours to plan (1–4, the buy
  limits reset every 4 hours), casts an hour, buy now or offer low, least profit a cast, and items at most.

## Flipping

- **The nearest Grand Exchange Clerk is outlined** while the GE is closed and the card has something for you to
  do there (green to collect, blue for a buy or a sell). *Outline the GE clerks* turns it off.
- **The icons at the top** open the Discord, GitHub, the website and — after you log in with Discord — your own
  private page of every character's flips (optional; see *Live prices and your privacy*).

- **Finds flips** (with *Live prices* on): scans the GE every few minutes and shows one flip at a
  time in a side panel — what to buy, how many, the price, what to sell it for, the profit after
  the 2% GE tax and how long it should take to fill. The card stays put while you set the offer up.
- **Prices from the 5-minute averages**, never a single trade, sized to a slice of the item's
  hourly trading and to what is left of its **GE buy limit** (shown above the quantity).
- **Expensive items (1m+)** setting (off by default): items of 1m or more are priced on their latest
  trades — only while they are trading now (3+ in the last 5 minutes, an insta-sell and an insta-buy
  within 10 minutes), a little over the last insta-sell, several at a time when they trade enough (up
  to 45m an offer). Their buy waits up to 10 minutes; their sell follows the last insta-buy every 5
  minutes, never under what it cost.
- **Dips first, never spikes**: an item 3%+ under its usual price for the day ranks higher (in the
  wiki's price history, such buys did better than buying at a random time); one 3%+ over it is not
  suggested, and nor is an item someone is dumping. Where an item has a daily low and high that held
  over the last month, buying near its low ranks it higher. The card's "↑" line says why.
- **The goal on the top card**: gp per hour, and that as a share of your cash per hour — green at
  0.33% (500k an hour on 150m).
- **Highlights what to click** in the GE: the empty slot, a one-click item in the search, the
  quantity and price buttons, Confirm — and a **click-to-fill** line in the quantity / price box
  (you still press Enter). An optional key does the same as the click; it is off by default.
- **Your own picks** are priced too: open an offer for any item and it gets a good price and
  quantity, with a note when it is not a recommended flip.
- **Offer advice** on every slot: collect, raise, lower or cancel — a buy that stops paying turns
  red at once, and (with the 5-minute re-price window) a buy with nothing bought after 3 minutes is cancelled to free
  its slot; the Modify screen is guided. With *Left-click cancel / re-price* on, left-clicking a lit
  slot does the Abort or Modify it shows (the same as its right-click option).
- **When a price dips under what you paid**: the sell keeps its price — and its place in the GE's
  line — for up to 2 hours after the buy (or after you log in, whichever is later); most such dips
  came back within that time. After that the card suggests selling at the market price. Items that
  rarely come back are offered at the market sooner.
- **Hold or sell**: when something you hold is worth less than you paid, its row says what to do and why —
  *Hold: sell at break-even unless it trades under X* (its usual price this week, how often it reached your
  price, what selling now loses, what holding costs an hour you play), or *Sell now* with the reason.
- **Hold**: every sell card has a Hold button. A held item gets no sell card — in profit or not —
  until you untick it (right-click it under Holding); it waits in your inventory or bank, and its dot
  turns green when selling would get back what you paid. **Most items held** (default 5) caps how
  many wait in your inventory.
  **Long-term holds**: right-click a Holding row → Hold long-term, and it can go in your bank — it
  stays listed under Holding ("in your bank"), is marked in the bank with a dot (amber waiting, green
  back at break-even), and does not count toward Most items held. **Depositing** a flip item does the
  same by itself: the plugin watches items go into the bank (or a buy collected straight to it), so
  it counts only the flip's units — never your own stock of the same item — and shows the split
  ("410 flips, 5 yours"). Flip items in the bank count toward your cash. Items you take out of the
  bank beyond the flip's units are yours and never get a sell card; right-click a Holding row →
  *These are mine, not a flip* if the plugin ever gets one wrong. Turn on the *Holding bank tab*
  setting (off by default; needs RuneLite's **Bank Tags** plugin) and they also get a "holding" tab
  in the bank.
- **Sells**: what you bought is offered back at a profit, at least what you paid plus tax — unless
  the price fell and stayed down (see above).
- **Offer timers**, and **hover** a GE slot or an item you bought for what you paid, break-even
  and the profit now.
- **Profit flash** on every sell fill (green, or red for a loss), **History** of finished flips,
  **Session** stats (profit, gp/hr, ROI; the *Top graph* setting draws the session's profit or its gp per hour over
  time, with the last 15 minutes' pace, to see when a session levels out), what is bought and unsold valued now, CSV export. Each
  section of the panel can be hidden with a click on its title.
- **Price chart** on the chat box while an item is open in the GE.
- **Statistics** (click an item's picture, or the button at the top of the panel): price or
  volume graphs over a day, week, month or year, your portfolio, profit over a day, week, month, year
  or all time, results per item and every flip you have made with it.
- **Item lookup**: type any item's name for its prices, margin after tax, volume, buy limit and
  whether it is worth flipping.
- **Learns from your flips**: items that keep losing are rested for a day, and the trading activity and
  volume a cheap item needs are learned from what your own flips made (Statistics → Learning). An item
  that sat 2 hours before it sold is rested too — counting only the time you were logged in with it
  outside the bank, never your breaks.
- **Your proven items**: an item with 3 or more of your flips that made money, a third or fewer lost,
  is always looked at when its buy limit has room, judged by its own results, and bought in a bigger
  slice (20% of an hour's trading instead of 10%, when that still fills in time).
- **Most per buy, scaled to your cash**: by default one buy may cost about 30% of the coins you flip
  with (146m: ~44m; 1b: 300m), never so little that your GE slots can't hold all of them. A bigger buy
  gets fewer items, and an item dearer than that is not suggested. The status line shows the amount;
  the *Most per buy* setting overrides it (in millions).
- **Spike guard**: no buy is suggested while an item's price is over 1.5x its middle price of the last
  30 days (a meme or an update spike that is still unwinding looks like a dip from up close). Such items
  are listed under *Not suggested* with the numbers; your own picks of one get a warning.
- **Swing trades** setting (off by default): idle coins also go into items low in their day — bought,
  kept in your inventory (no GE slot), sold when back at their usual price, up to 8 hours — and into
  items 10%+ under their 30-day level, kept up to 7 days for +1% after tax.
- **Free-to-play** setting: plans for 3 GE slots and suggests only items free players can buy.
- **Sell only** setting: no new buys — only what to sell, re-price and collect.
- Pause, "never suggest this item", and a cap on the cash it may plan with.

## Your data — export, import, team files

**Export data** / **Import data** at the top of the Statistics window: save what the plugin has learned
on your account — what your flips taught it, the items it rests, your holds and buy limits — and your
settings to one file, and load it on another PC. Import shows what is in the file next to what you
have, and saves a copy of what you have first.

A **team file** is several players' exports merged into one, with names removed. Importing it *adds*
their flips to what yours learned — nothing of yours is replaced — so the plugin judges items from more
flips than one player makes.

## Live prices and your privacy

*Live prices* is **off by default**. When you turn it on, the plugin asks the OSRS Wiki's price
service (prices.runescape.wiki) for Grand Exchange prices — that service sees your IP address, as
RuneLite's warning says. With *Data sync* off, nothing about your account is sent anywhere: your
offers, coins and flip history stay on your computer (in RuneLite's own settings, and the plugin's folder under
RuneLite's `plugin-data` for the last day of market prices, the CSV export and the **trade journal** —
a local record of your GE offers and fills and what the plugin suggested, kept 60 days, used to measure
and improve the advice; it can be turned off in the settings). An exported data file holds your character's
name and flip history — share it only with people you choose; team files hold no names.

*Data sync* is also **off by default** and needs *Live prices* on. When you turn it on, the plugin
sends to its own server (a Cloudflare Worker run by the plugin's author), under a random id made on
your computer:

- every 30 minutes, what your finished flips taught it — for each flip, what the item looked like just
  before the buy (how steadily it traded, its volume, its margins), what the flip made and its buy
  price;
- every 10 minutes, your Grand Exchange offers — item, buy or sell, price, quantity, how much filled,
  when it was placed, first filled and last changed, and the market's prices when it was placed.

No coin balance, and no character name unless you log in (below). It downloads everyone's finished flips
in return, so the plugin learns from more flips than yours; the offers are for the server's analysis of which
prices fill. That server sees your IP address, as RuneLite's warning says. Turn it off and nothing more is sent.

Two more settings use the plugin's server, both **off by default**: *Prices through the plugin's server* (the
same wiki prices from a cached copy) and *Price patterns* (an analysis of item price history, at most once an
hour). Neither sends anything about you.

**Your account (optional).** The person icon at the top logs you in with Discord in your browser — Discord tells
the server your Discord id, name and picture, never your email or password. While logged in and with *Data sync*
on, the plugin also sends every finished flip with your character's name, so your private page on the website can
show each character. Only you can see it. Log out in the panel and nothing more is sent; *Delete my account* on the
page removes your account and character names. Full details: https://flip-sidebar.pages.dev/privacy

The plugin only draws and, when you click its line, puts a number in the GE box. It never clicks,
types or presses Enter for you.

## License

BSD 2-Clause — see [LICENSE](LICENSE).
