# FX CLOB requirements

I want to build a FX CLOB (Central Limit Order Book) in Java. This should be low latency, with deterministic outcomes in terms of performance.

**How to read this document** (revised 2026-11-08 from `PLAN.md`):
- Sections 1–12 are the agreed requirements, including the answers confirmed during planning and implementation.
- Section 13 lists defaults chosen during implementation that have **not** been confirmed. The code behaves this way today, but each one can still change.
- Section 14 lists questions that are still open.
- `Qn` references point to `PLAN.md` §6.

## 1. Instruments

The CLOB will support SPOT, SWAP and NDF instruments. An instrument is defined by symbol, tenor.

Instrument data will specify:
- order-price precision and order-price increments
- min TTL (time to live) and mid TTL
- min quantity, max quantity, quantity increment and min show
- market data price increment, market data throttle and market data subscription levels
- peg order throttle
- supported TIF and supported order types
- match priority (VISIBILITY|PRICE)
- maker race interval and taker race interval
- queue-cancels and reject-on-ttl

### Prices

A pip is **one unit of the instrument's price precision**, not the market convention. All price increments and discretion values (mid and pegged) are in these units. For example:
- EUR/USD, SPOT, 5 decimals, 1 pip increment will allow a user to send an order with increments of 0.00001
- USD/MXN, SPOT, 5 decimals, 5 pip increment will allow a user to send an order with increments of 0.00005

Product price conventions:
- **SWAP:** the order side is the buy/sell direction on the far leg, and the near leg has the opposite direction. The order price is swap points.
- **NDF:** orders are submitted at the outright price (spot + points).

### Quantities

A min quantity of 1,000,000, quantity increment of 100,000 and min show of 0 would mean the minimum order size is 1 million, a user can submit orders of 1.1, 1.2, 1.3 million (increments of 100k above 1 million) and hide the entire order (min show = visible quantity).

Min quantity and quantity increment apply at order entry and to **every execution**:
- Each order and each fill must satisfy `quantity >= minQty` and `(quantity - minQty) % qtyIncrement == 0`. Order quantity must also be at most max quantity.
- After each execution, any positive residual below min quantity is cancelled, on either the aggressor or the resting order. A zero residual means filled, not cancelled.
- Min quantity must be a multiple of the quantity increment. An instrument configured otherwise is rejected.

## 2. Users and accounts

Orders will be placed on behalf of an account by a User. A user can be mapped to multiple accounts. An order is rejected if its user is not mapped to its account.

## 3. Credit

Account orders can only match with each other if they both extend bilateral credit relationships: a match needs a relationship allowing it in **both** directions for the instrument, and both need enough headroom for the fill.

A credit relationship will define the credit extended by account 1 to account 2 as either:
- no credit
- unlimited credit
- a daily settlement limit (DSL), defined in USD, measured by value-date
- a net open position limit (NOP), defined in USD, measured across all future value-dates

A credit relationship can be applied to 1 or more instruments. An account might for example extend unlimited credit to another entity for all instruments except USD/TRY, USD/ILS and USD/PLN which have 100mio DSL limits.

SWAPs will consume credit at the value-date of both the near and far leg. For NOP, a SWAP's two legs net to zero, so a SWAP consumes DSL on both dates but no NOP. Once the near leg settles the far leg remains as NOP exposure, which can put a line over its limit with no trade. The line then accepts only reducing trades, and operations is alerted.

NOP is a single signed USD figure per line, and the limit applies to its absolute value.

USD exposure is base notional x the base currency's reference USD rate, kept exact. The reference rate is expected to tick at a lower frequency than trading (about every 10 seconds) so that rates stay accurate. Each trade uses the latest rate. Usage already recorded stays at its trade-time value and is not revalued.

A credit pool can be shared by several giving accounts belonging to the same legal entity, and by any of their relationships and instruments that map to it. Each relationship still names a receiving account.

If a limit is cut below current usage, trades that increase exposure are blocked and trades that reduce it are still allowed, until usage is back within the limit. Nothing is cancelled automatically.

A credit change never produces a trade by itself. Crossed orders that become tradable stay crossed until one is amended or a new order arrives.

Value dates come from an external reference-data service and arrive as sequenced inputs. The CLOB holds no holiday calendars.

If a USD rate or value date needed by an instrument is missing, the instrument is halted. It resumes automatically when the data arrives.

**Self-trade prevention.** When an aggressor reaches a resting order from its own account or legal entity, the aggressor's remainder is cancelled with an explicit self-trade cancel reason. Fills already made before that point stand.

Matches that breach credit will result in partial fills up to the remaining headroom, subject to the execution-size rules in section 1:
- A credit-clipped fill is rounded down to the largest valid execution size, bounded by both orders' leaves and by bilateral credit.
- If no valid execution size fits, that resting order is skipped and matching continues.

It is possible an orderbook will be 'crossed' in the case that bids (or offers) from account1 are greater than offers (or bids) from account2 but account1 and account2 do not have a credit relationship.

## 4. Order types

The CLOB will support limit orders, market orders, iceberg orders, mid orders, pegged orders and fixing orders.

| Type | Specifies |
|---|---|
| Limit | side, price, quantity, time in force |
| Market | side, quantity, time in force (IOC or FOK only; a resting TIF is rejected) |
| Iceberg | side, price, quantity, time in force, max show |
| Pegged | side, quantity, time in force, and optionally max show and discretion (pips) |
| Mid | side, quantity, time in force, and optionally discretion (pips); can only match with other mid orders |
| Fixing | side, quantity, fixing event, time in force (DAY, GTC or GTT); can only match with other fixing orders |

### Time in force

The orderbook will support:
- Day (orders cancelled at end of day)
- GTC (orders rolled at end of day to next session, keeping their queue priority)
- GTT (orders cancelled at specific time)
- FOK (fill or kill): order must be filled entirely
- IOC (immediate or cancel): order will not rest on orderbook

End of day is 17:00 New York, DST-aware, on one schedule for all instruments.

### Iceberg refresh

When an iceberg's displayed slice is refreshed, it loses time priority. The new slice joins the back of the queue at its price with a new priority sequence.

A non-zero show must itself be a valid execution size (at least min quantity, on the quantity increment); otherwise the order is rejected. If a fill leaves a slice below min quantity, the slice is refreshed and goes to the back of the queue.

### Pegged orders

Pegged orders can be pegged to MID or NEAR-TOUCH +/- discretion, and orders will be updated on peg update throttle frequency. We would expect to run the orderbook with minimum order time-to-live of 5ms and a pegged order throttle of 20ms, ensuring that it is not possible to use the 'pegged order' as a 'cheap' mechanism to 'copy' market makers without the investment in market-making.

- The peg prices are based on the visible top of book, excluding any peg orders. The reference is the unscreened book.
- Near-touch means the same-side best: the best bid for a buy, the best offer for a sell.
- Positive discretion means more aggressive. A peg may cross the spread, in which case it matches immediately or joins a taker race where one applies.
- Repricing follows a fixed wall-clock grid, driven by sequenced per-instrument ticks at the peg throttle.
- Discretion is applied to the raw reference, which may be a half tick. The resulting price then rounds defensively to the order price increment: bids down, offers up.
- Repricing and resumption both lose time priority. The order joins the back of the queue at the resulting price. A tick that leaves the price unchanged keeps priority.
- An engine-driven reprice does not restart min TTL.
- New peg orders are rejected if there are no visible bids/offers in the book.
- Existing peg orders will be 'suspended' (removed from the book, not cancelled) if either side of the visible top of book is missing, including NEAR-TOUCH pegs whose own side is still present. Suspension is checked on peg ticks, so a peg can keep its last price for up to one throttle interval after a side empties. Pegs are reinstated at the next peg tick once both sides have returned.

### Fixing orders

Fixing order rates will be sourced external to the orderbook, and provided through an API. We will define fixing events as name, time, timezone and cut-off-time. Orders that are not matched will be cancelled or partially filled at cut-off time. Execution rate will be provided at fixing time.

- Fixing orders match continuously before cut-off, in time priority.
- A fixing order names a fixing event. It can be cancelled but not amended.
- Credit is reserved at match time, using notional x USD rate, so the fixing price isn't needed.
- A match is reported at once without a price, then as a trade when the rate arrives.
- If the rate is missing or late, matches stay pending until a rate is supplied, and the next occurrence of that fixing can't open until then.
- A rate supplied before the fixing time is refused; the source resends it at fixing time.

## 5. Order matching

Order matching will be based on visibility-price-time basis. Visible liquidity will always take precedence over invisible liquidity.

**VISIBILITY priority.** The aggressor fills against visible liquidity in price-time order. Hidden liquidity is unlocked only once the visible top-of-book level is **exhausted**, meaning the full displayed quantity at that price, not a single order in the level. Reaching that level or partially filling it does not unlock hidden liquidity. Once unlocked, hidden liquidity competes on price, and at the same price visible beats hidden.

The visible top level is **screened per aggressor**: a level whose displayed orders the aggressor can't trade with doesn't count as its top. For example, take an unscreened 92/94 market with hidden liquidity at 93, where a client can't trade with the bid at 92 and so sees 91/94. If that client sells at 91, the visible orders at 91 are eligible and trade before the hidden order at 93. *Noted for further consideration: a client looking at the unscreened price may behave differently from one looking at its screened price.*

Refreshed iceberg slices count as displayed liquidity, so hidden liquidity stays locked until every order at the top level has been drained, including all of an iceberg's quantity.

An aggressor can take more than an iceberg's displayed slice through the **match cycle**. It executes at the displayed amount, the iceberg refreshes to the back of the queue at that price, and the cycle continues until the aggressor is done or the level is drained:
- An iceberg of quantity 10, show 1, alone at the top level, against an opposing order for 5: five fills of 1 against the iceberg.
- The same iceberg with another order of quantity 1 behind it at the same price: iceberg tip, the second order, then the iceberg tip 3 more times.

**PRICE priority.** Hidden liquidity can be matched at its price. Priority is price, then visible before hidden at the same price, then time.

In a market:

```
id|side|show|price|qty
1|BUY|0|93|1000
2|BUY|1000|92|1000
3|BUY|1000|91|1000
4|SELL|1000|94|1000
```

- An aggressive IOC or FOK order (sell 1000 at 93) will not match if instrument-match-priority is VISIBILITY. Under PRICE it matches id 1 at 93. An order that can rest is covered below.
- An aggressive order (sell 1000 at 92) will match at 92 if instrument-match-priority is VISIBILITY, or at 93 otherwise.
- An aggressive order (sell 2000 at 92) will match first with id 2 and then with id 1.
- An aggressive order (sell 3000 at 91) will match first with id 2, then with id 1, and then id 3.

The fill sequence in VISIBILITY mode is therefore not always monotonic in price (92, then 93).

**Resting against hidden liquidity (VISIBILITY).** An order that can't reach the visible top level, and whose TIF lets it rest, rests on the book. A hidden order on the opposite side that crosses its **displayed quantity** then trades with it at once, within the same input, at the **hidden order's price**. For example, with a hidden bid at 93 and a best visible bid of 91, a DAY sell at 92 rests and then trades with the hidden bid at 93. A fully hidden resting order triggers nothing, and an iceberg exposes only its tip, so two hidden orders can remain crossed.

## 6. Mid orders

Mid orders will match at the mid of the visible market, excluding peg orders so that pegs can't move the mid. Mid orders are not eligible to match if there is no visible bid or offer in the CLOB. This rule applies to mid orders only.

Among mid orders that can match, priority is pure time, regardless of discretion.

Each order's effective limit is the mid +/- its discretion in pips, where positive discretion means more aggressive. The trade price is `clamp(mid, sellLimit, buyLimit)`.

Mid trades may execute at **one decimal place beyond the instrument's precision**, so the midpoint is exact and is not rounded. In a 92/93 market, two zero-discretion mid orders match at 92.5. Mid trades ignore the order price increment.

Mid TTL is the minimum resting time for a mid order, with the same cancel and amend restrictions as min TTL. It replaces min TTL for mid orders.

The mid is taken from the unscreened book, so it is the same for every account.

Assume the market is 92/94, visible 1000 x 1000:

```
id|side|qty|discretion
1|BUY|1000|0
2|SELL|1000|0
```

Outcome: 1 matches 2 at 93.

```
id|side|qty|discretion
1|BUY|1000|0
2|SELL|1000|-1
```

Outcome: no match. The buy order is effectively at 93, the sell order is at 94.

```
id|side|qty|discretion
1|BUY|1000|+1
2|SELL|1000|-1
```

Outcome: 1 matches 2 at 94.

```
id|side|qty|discretion
1|BUY|1000|+1
2|SELL|1000|+1
```

Outcome: 1 matches 2 at 93 (both orders see improvement in price). The bid was at 94, the offer at 92.

## 7. Fairness races

Fairness races are designed to reduce the benefit gained by low-latency / high-frequency participants and give all users an 'equal opportunity' to match at a particular level. For any given book there can be 1 active taker race, by side, and 1 active maker race by side, price.

### Taker races

Instruments with taker-race interval > 0 will be subject to a 'fairness race'.

Given a market with a taker-race interval of 5ms:

```
id|side|qty|price
1|BUY|1000|92
```

Any order sent to sell <= 92 will join the taker race if one exists, or create a new one that will last precisely 5ms.

An order entering the race will add the account (of the order) to a 'lottery' if it is not currently participating, or add the order to the account's 'lottery ticket' if it is. An account can only have a single entry to the lottery, but can have multiple orders attached to the ticket.

When the race window expires, run the lottery:
- Randomly pick an account to win and apply the first order for the account to the orderbook. Remove the account from the lottery if no orders are left on its ticket.
- Continue randomly picking from remaining accounts until all accounts in the lottery have had their first order applied.
- If any accounts remain (had > 1 order on ticket), repeat the lottery.

A DAY or GTT order that reaches its expiry while waiting in a race expires on time and leaves its ticket.

An order enters a taker race only if it would execute now, including credit and VISIBILITY rules. An order crossing only orders it can't trade with, or hidden liquidity it can't unlock, doesn't start or join a race. Mid and fixing orders aren't subject to races.

Market, IOC and FOK orders may participate:
- A FOK is checked against the current book and credit when it becomes eligible for application in the lottery, not at race admission. It either fills completely or is cancelled with no executions.
- An order that no longer crosses when applied follows its TIF. IOC and FOK orders are cancelled.

### Maker races

Instruments with maker-race interval > 0 will be subject to a lottery to see which order gets price-time priority.

A maker race starts when a new order or an amend creates a new price level. A peg reprice does not start one. An order joining an existing level does not start another maker race.

Later new orders at the race's price join it during the window, even if the level reappears meanwhile. Orders waiting in a maker race are off the book: not visible and not matchable. Under VISIBILITY, a hidden order can trade with such an order only after the race resolves and the order is applied.

At window close, the same account-ticket lottery as a taker race is run: one ticket per account with its orders in arrival order, and each round randomly visits accounts without replacement and applies each account's next order to the book, until all tickets are empty. Orders receive book priority in that application order.

### Halts and pre-open

When an instrument halts, orders waiting in its races are cancelled.

A pre-open phase accepts orders without matching them. Clients can cancel and amend during pre-open, with no TTL restriction. At the open, the collected orders go into a single account-ticket lottery per book, whatever their price or side, and each is applied as an ordinary order, so crossing orders trade as they land.

## 8. Cancels and amends

Cancels from market-makers and market takers will be applied immediately if queue-cancels is false or no taker races are running; otherwise they will be applied after the taker race completes. Only requests on the side being taken are held: a race of sell aggressors holds requests on resting bids, and resting offers remain free to change. As an exception, a taker's own orders waiting in the race are also held, so a taker can't withdraw once it has entered.

A queued cancel doesn't stop the order filling. If a fill completes the order first, the cancel is answered "too late to cancel".

Cancels received inside the TTL will either be rejected or queued depending on reject-on-ttl.

- Amends (cancel/replace) follow the same TTL reject-or-queue rule. An amend resets the TTL when it is applied. A rejected amend does not reset it, and a queued amend resets it on application.
- A request queued for both TTL and a race is released only after the TTL has expired **and** the race has completed.
- Disconnect and administrative cancels bypass TTL constraints.
- With reject-on-ttl on, a cancel inside TTL is rejected even while a race is running.
- Participant mass cancels obey TTL per order, so orders inside TTL are rejected or queued individually.
- An amend loses time priority on a price change or quantity increase, and keeps it on a quantity decrease.

## 9. Market data

Accounts will be eligible to subscribe to market data. The system will support a subscription for top of book, or depth of book (subject to instrument market depth levels), changes for the screened or unscreened book.

- The screened book will only show those orders that the account can match with, subject to the bilateral lines extended between counterparties. Screening takes account of remaining credit headroom as well as the existence of a relationship, so it shows only quantity the account could trade now.
- The unscreened book will show all orders in the book.

The levels will be defined not by the price levels in the book, but by the market data price increment. Liquidity is aggregated to the market data level: **bids round down and offers round up**.

For example, 4 dp, 1 pip increment for orders, 5 pip increment for market data:

```
side|price|size
BUY|1.0000|1000
BUY|0.9999|1000
BUY|0.9998|1000
BUY|0.9994|1000
```

We would show bids of 1.0000 for 1000, 0.9995 for 2000 and 0.9990 for 1000.

Account market data updates should all be based off the same version of the book. We will produce market data updates at every 'market data interval' (by wall clock): 00:00:00.000, 00:00:00.005 (if 5ms increment). At the start of the 'market data cycle' we take a snapshot of the orderbook. For all subscribers we use that snapshot to produce and distribute either a 'screened' or unscreened market data update.

It is critical that all consumers are able to receive a market data update within the market-data interval. Subscribers will receive updates in a specific order, where the first subscriber to receive the update in interval 1 will be last in interval 2, and keep moving up the subscriber list until it reaches the top again and the process repeats. This rotation is maintained separately per instrument.

## 10. Messaging

We expect to accept orders via FIX APIs, and produce market data with TCP. UDP subscriptions using FIX, FAST / ITCH. Screened market data over UDP may use per-account unicast.

## 11. Capacity

We expect the CLOB to support 200 instruments in independent orderbooks; each orderbook should be able to scale to 10,000 updates / sec.

## 12. Determinism

Replaying the same sequenced inputs must produce the same outcomes, including lottery results. Race lotteries use a seeded random source that can be audited.

When a timer and an input have the same sequenced time, the timer fires first. Race windows are half-open, `[start, start + interval)`, so an order arriving at exactly the end of the window is outside the race.

The gateway guarantees that client order ids are unique for the whole trading session. The engine additionally rejects an id that duplicates a live order.

## 13. Provisional: implemented defaults awaiting confirmation

These decisions were made during implementation so that work could continue. None has been agreed yet.

### Matching and order entry

| Topic | Current behaviour | Ref |
|---|---|---|
| VISIBILITY unlock state | Applies to one aggression only. | Q1c |

### Credit

| Topic | Current behaviour | Ref |
|---|---|---|
| NOP sign | Measured from the giver's side. | Q6c |

### Pegged, mid and fixing orders

| Topic | Current behaviour | Ref |
|---|---|---|
| Peg ticks | The reference is frozen per tick, and pegs are processed in arrival order. | Q4e |
| Peg amends | A discretion amend takes effect at the next tick. | – |

### Races and cancels

| Topic | Current behaviour | Ref |
|---|---|---|
| Queued requests | Amends are held as well as cancels. A hold ends when the race running at request time resolves. Disconnect and admin cancels bypass it. | Q7b |
| Orders waiting in a race | Can be cancelled but not amended. | Q37 |

### Sessions and market data

| Topic | Current behaviour | Ref |
|---|---|---|
| Session states | PRE_OPEN, OPEN, HALTED, CLOSED. Outside OPEN and PRE_OPEN, new orders and amends are rejected and cancels always work. Resting orders stay on the book. | Q38 |

### Adopted with the next-stage build (2026-11-08), not separately confirmed

These were the plan's recommended defaults and are built in. Each can still change.

| Topic | Current behaviour | Ref |
|---|---|---|
| FIX persistence | Sequence numbers and resend store survive a restart, not a failover. Passwords are salted hashes; TLS is left to a proxy. | D2 |
| Stale rate | A rate that has merely aged does not halt; only a never-received or invalid one does. | D3 |
| Pre-open order types | Limit and iceberg only. | D4 |
| Durability and failover | Manual failover; asynchronous replication; journal forced every 10 ms; snapshot at each end of day. | D7 |
| Transports | FIX over TCP, and a project-specific binary format over unicast UDP. FAST and ITCH are not built. | D9 |
| Reference data changes | A journal replays only under the reference data that wrote it; changes need a controlled reset. | R08 |

## 14. Open questions

These need a decision and have no agreed answer:

- **Products:** does SWAP need near and far tenors, and NDF a fixing date, in the instrument key? (Q10) SWAP points scaling, zero and negative price validation, near-leg rate source, leg notionals and far-leg calculation. NDF fixing source, settlement currency and settlement formula. Trade-event fields. (Q10a)
- **Performance targets:** is 2M updates/sec in total (all books peaking together) the design point? Latency targets, account counts and subscriber counts. (Q11)
- **Market data delivery:** subscriber mix, how many books change per interval at peak, what "receive" means, slow-consumer and overload policy. (Q19, Q42)
- **Transports:** which FIX, FAST and ITCH over TCP and UDP combinations are required, and whether screened UDP is mandatory for v1. (Q12)
- **End of day:** how rolled orders are treated for the value-date change. (Q24)
- **Parameter naming:** are "market data throttle" and "market data interval" the same parameter? Likewise "market data subscription levels" and "instrument market depth levels". (Q25)
- **Wording to confirm:** "marker order" was read as market order, and "match with order mid/fixing orders" as other mid/fixing orders. Both are corrected above. (Q17)
- **Operations:**
  - a recovery-time target (Q39);
  - automatic failover (Q43);
  - journalling market data ticks (Q44).
