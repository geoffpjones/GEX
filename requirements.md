I want to build a FX CLOB (Central Limit Order Book) in java.
This should be low latency, with deterministic outcomes in terms of performance.

The CLOB will support SPOT, SWAP and NDF instruments. An instrument is defined by symbol, tenor.

Instrument data will specify order-price precision, order-price increments, min TTL (time to live), min quantity, max quantity, quantity increment, min show, market data price increment, market data throttle, market data subscription levels, peg order throttle, mid ttl, supported TIF, supported order types, match priority (VISIBILITY|PRICE), maker race interval, taker race interval, queue-cancels, reject-on-ttl

i.e. 
EUR/USD, SPOT 5 decimals, 1 pip increment will allow a user to send an order with increments of 0.00001
USD/MXN, SPOT 5 decimals, 5 pip increment will allow a user to send an order with increments of 0.00005

A min quantity:1,000,000, quantity increment: 100,000 and min show of 0 would mean the minimum order size is 1 million, a user can submit orders of 1.1, 1.2, 1.3 million (increments of 100k above 1 million) and hide the entire order (min show = visible quantity).

Orders will be placed on behalf of an account by a User.
A user can be mapped to multiple accounts.

Account orders can only match with each other if they both extend bilateral credit relationships

A credit relationship will define the credit extended by account 1 to account 2 as either:
 - no credit
 - unlimited credit
 - a daily settlement limit, defined in USD, measured by value-date
 - a net open position limit, defined in USD, measured across all future value-dates

A credit relationship can be applied to 1 or more instruments. An account might for example extend unlimited credit to another entity for all instruments except USD/TRY, USD/ILS and USD/PLN which have 100mio DSL limits. 

SWAPS will consume credit at the value-date of both the near and far leg.
Matches that breach credit, will result in partial fills, submit to min quantity constraints

It is possible an orderbook will be 'crossed' in the case that bids (or offers) from acccount1 are greater than offers (or bids) from account2 but account1 and account2 do not have a credit relationship.

The CLOB will support limit orders, market orders, iceberg orders, mid orders and pegged orders.

a limit order will specify a side, price, quantity and time in force.
a marker order will specify a side,quantity and time in force.
a iceberg order will specify a side, price, quantiy, time in force and max show
pegged orders will specify a side, quantity, time in force, and optionally a max show and discretion (pips)
mid orders will specify a side, quantity, time in force and optionally a discretion (pips) and can only match with order mid orders.
fixing orders will specify a side, quantity, and the fixing-time and can only match with order fixing orders

The orderbook will support:
Day (orders cancelled at end of day)
GTC (orders rolled at end of day to next session)
GTT (orders cancelled at specific time)
FOK (fill or kill) - order must be filled entirely
IOC (immediate or cancel) - order will not rest on orderbook

Pegged orders can be pegged to MID or NEAR-TOUCH +/- discretion, and orders will be updated on peg update throttle frequency.  We would expect to run the orderbook with minimum order time-to-live of 5ms and a pegged order throttle of 20ms; ensuring that it is not possible to use the 'pegged order' as a 'cheap' mechanism to 'copy' market makers without the investment in market-making.  The peg prices are based on visible top of book (excluding any peg orders). new Peg orders are rejected if there is not visible bid/offers in the book, existing peg orders will be 'suspended' if there is not visible prices at top of book.

Fixing order rates will be sourced external to the orderbook, and provided through an API. We will define fixing events as name, time, timezone and cut-off-time.  Orders that are not matched will be cancelled or partially filled at cut off time, excution rate will be provided at fixing time

Order matching will be based on visibility-price-time basis.  Visible liquidity will always take precidence over invisible liquidity:

in a market (id | side | show | price | qty):
1|BUY|0|93|1000
2|BUY|1000|92|1000
3|BUY|1000|91|1000
4|SELL|1000|94|1000

an aggressive order (sell 1000 at 93) will not match if instrument-match-priority is VISIBILITY 
an aggressive order (sell 1000 at 92) will match at 92 if instrument-match-priority is VISIBILITY or 93 otherwise
an aggressive order (sell 2000 at 92) will match first with id 2 and then with id 1
an aggressive order (sell 3000 at 91) will match first with id 2 and then with id 1 and then id 3  

mid orders will match at the mid of visible market.
orders are not eligable to match if no visible bid, or offer in CLOB

assume market is 92/94 visible 1000 x 1000

id|side|qty|discretion
1|BUY|1000|0
2|SELL|1000|0

Outcome: 1 matches 2 at 93

id|side|qty|discretion
1|BUY|1000|0
2|SELL|1000|-1

Outcome: no match: buy order is effectively at 93, sell order is at 94

id|side|qty|discretion
1|BUY|1000|+1
2|SELL|1000|-1

Outcome: 1 matches 2 at 94

id|side|qty|discretion
1|BUY|1000|+1
2|SELL|1000|+1

Outcome: 1 matches 2 at 93 (both orders see improvement in price) bid was at 94; offer at 92. 

Instruments with taker-race interval > 0 will be subject to a 'fairness race'.

- fairness races are designed to reduce the benefit gained by  low-latency / high-frequency participants and give all users and 'equal opportuntiy' to match at a particular level.
- for any given book there can be 1 active taker race, by side; and 1 active maker race by side, price. 

given a market with a taker-race interval of 5ms

id|side|qty|price
1|BUY|1000|92

any order sent to sell <= 92 will join the taker race if one exists, or creates a new one that will last precisely 5ms.
an order entering the race will add the account (of the order) to a 'lottery' if it is not currently participating, or add the order to the accounts 'lottery ticket' if it is
an account can only have a single entry to the lottery, but can have multiple orders attached to the ticket.

when the race window expires: run lottery
 - randomly pick an account to win and apply the first order for the account to the orderbook. remove account from lottery if no orders left on ticket.
 - continue randomly picking from remaining accounts until all accounts in the lottery have had first order applied
 - if any accounts remain (had > 1 order on ticket) repeat lottery

Instruments with maker-race interval > 0 will be subject to a lottery to see which order gets price-time priority.

Cancels from market-makers, market takers will be cancelled immediately if queue-cancel is false or no taker races are running, otherwise will be applied after the taker race completes 
Cancels received inside the TTL will either be rejected or queued depending on reject-on-ttl


Market Data:

Accounts will be eligible to subcribe to market data.
The system will support a subscription for top of book, or depth of book (subject to instrument market depth levels) changes for the screened, or unscreened book.
The screened book will only show those orders that the account can match with, subject to the bilateral lines extended between counterparties.
The unscreened book will show all orders in the book.
The levels will be defined not by the price levels in the book, but by the market data price increment (i.e 5dp, increment 1 of orders, increment 5 of market data) would aggregate liquidity between levels to the level:

i.e. 4 dp, 1 pip increment for orders, 5 pip increment for market data:

side|price| size
BUY|1.0000|1000
BUY|0.9999|1000
BUY|0.9998|1000
BUY|0.9994|1000

we would show bids of 1.0000 for 1000; 0.9995 for 2000 and 0.9990 for 1000 

Account market data updates should all be based off the same version of the book.  We will produce market data updates at every 'market data interval' (by wall clock) 00:00:00.000; 00:00:00.005 (if 5ms increment); at the start of the 'market data cycle' we take a snapshot of the orderbook.  For all subscribers we use that snapshot to produce and distribute either a 'screened' or unscreened market data update.

It is critical that all consumers are able to receive a market data update within the market-data interval.  Subscribers will receive updates in a specific order, where the first subscriber to receive the update in interval 1, will be last in interval 2, and keep moving up the subscriber list until it reaches the top again and the process repeats.

Messaging

We expect to accept orders via FIX apis, and produce market data with TCP. UDP subscriptions using FIX, FAST / ITCH

We expect the CLOB to support 200 instruments in indepedent orderbooks; each orderbook should be able to scale to 10,000 updates / sec
