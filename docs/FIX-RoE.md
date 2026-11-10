# FIX Rules of Engagement (draft)

FIX 4.4 order entry for the CLOB. The gateway is the acceptor, with `SenderCompID` CLOB on its messages (configurable).

## Session layer

| Message | Behaviour |
|---|---|
| Logon (A) | **Must be the first message.** It must carry Username (553) and Password (554) for the session's configured CLOB user, and HeartBtInt (108). ResetSeqNumFlag (141=Y) resets both sequence numbers and is echoed back. Unknown SenderCompID, wrong TargetCompID, bad credentials, or a second connection for the same session: the connection is dropped without a reply. |
| Heartbeat (0) / TestRequest (1) | We send a Heartbeat after HeartBtInt of silence. After 1.2 × HeartBtInt without inbound traffic we send a TestRequest. If no reply comes within HeartBtInt, we log out. A TestRequest is answered with a Heartbeat echoing TestReqID (112). |
| ResendRequest (2) | Application messages are resent with PossDupFlag (43=Y) and OrigSendingTime (122). Admin messages are replaced by SequenceReset-GapFill (123=Y). |
| SequenceReset (4) | Both GapFill and Reset modes are honoured. |
| Inbound gaps | If MsgSeqNum is higher than expected, we send one ResendRequest (7=expected, 16=0) and drop messages until the gap is filled. If it is lower than expected without PossDupFlag, we log out. |
| Logout (5) | Answered with a Logout, then the connection is closed. |
| Reject (3) | Sent when a required tag is missing (373=1, with RefTagID 371). |

**Sessions outlive connections.** Sequence numbers, the resend store and order ownership belong to the configured session. A client that reconnects or logs on after a restart without 141=Y resumes its sequence numbers, and can recover reports it missed with a ResendRequest. The last 131,072 application messages are kept; older ones are gap-filled. Market data snapshots are not stored, and are gap-filled.

**After a failover to the standby** sequence numbers do not carry over. Log on with ResetSeqNumFlag=Y and reconcile with OrderStatusRequest (H) using ClOrdID `*`. Your orders and their ClOrdIDs are still yours.

**Message checks.** SenderCompID and TargetCompID must match the session on every message. A malformed message ends only that session.

**Throttling:** each session has a message-rate limit (token bucket). Messages over the limit get a BusinessMessageReject (j) with 380=99 and Text "Throttled", and are not processed.

**Cancel on disconnect:** configurable per session. When enabled, all orders entered by the session's user are cancelled when the connection drops or logs out (CancelReason DISCONNECT, which bypasses min TTL and race holds).

## Order entry

### NewOrderSingle (D)

Required tags: 11, 54, 38, 55, 1.

| Tag | Field | Notes |
|---|---|---|
| 11 | ClOrdID | At most 64 characters, and unique across every new order, cancel and replace of the session until end of day. A duplicate new order is rejected (150=8, 103=6). |
| 1 | Account | Numeric CLOB account. The user must be entitled to it (otherwise rejected with 58=NOT_ENTITLED). |
| 55 | Symbol | e.g. `EUR/USD` |
| 20013 | Tenor | `SPOT` (default), `1M`, … Symbol and tenor together identify the instrument. |
| 54 | Side | 1 = Buy, 2 = Sell |
| 38 | OrderQty | Base-currency units. Must satisfy min qty, max qty and the qty increment. |
| 40 | OrdType | 1 = Market, 2 = Limit, P = Pegged |
| 20010 | ClobOrdType | Optional override: `LIMIT`, `MARKET`, `ICEBERG`, `PEGGED`, `MID`, `FIXING`. Required for MID and FIXING. |
| 44 | Price | LIMIT and ICEBERG. Must not have more decimals than the instrument's precision, and must be on the price increment. |
| 111 | MaxFloor | Show quantity. On a limit order, a value below OrderQty makes it an iceberg. 0 = fully hidden (if min show allows). |
| 1094 | PegPriceType | Pegged orders: 2 = Mid, 5 = Primary (near touch, the default). |
| 20011 | Discretion | Pips, signed, positive = more aggressive. PEGGED and MID. |
| 20012 | FixingEventID | FIXING orders. |
| 59 | TimeInForce | 0 = Day (default), 1 = GTC, 3 = IOC, 4 = FOK, 6 = GTD (treated as GTT, with ExpireTime 126). Market orders must be IOC or FOK. |

### OrderCancelRequest (F)

Required tags: 11, 41. OrigClOrdID (41) may be the original ClOrdID or the latest replacement's. A ClOrdID (11) already used in this session is refused with OrderCancelReject, 102=6.

### OrderCancelReplaceRequest (G)

Required tags: 11, 41, 38.
- OrderQty is the new total quantity.
- Price (44), MaxFloor (111) and Discretion (20011) are optional. Price defaults to the current price.
- Priority is lost when the price changes, the quantity increases or the show changes.
- A peg's discretion change takes effect at its next peg tick.

### OrderMassCancelRequest (q)

Required tags: 11, 530. With Account (1), cancels that account's orders; otherwise cancels all orders of the session's user. Answered with OrderMassCancelReport (r): 531=7 if accepted, 0 if not entitled.

### OrderStatusRequest (H)

Required tag: 11. Answers with an Execution Report (150=I) for that order, or with one report per live order when 11 is `*`.

### MarketDataRequest (V), UDP delivery

Where UDP is enabled on the node, tag 20016 (a port number) delivers the subscription as binary snapshots to that port at the session's own IP address. Every datagram is a full snapshot with a per-subscription sequence number, re-sent periodically even if unchanged. The format is this project's own, not FAST or ITCH.

## Execution reports (8)

Every report carries:

| Tag | Field |
|---|---|
| 37 | OrderID (engine order id) |
| 11 / 41 | ClOrdID / OrigClOrdID |
| 17 | ExecID |
| 150 | ExecType |
| 39 | OrdStatus |
| 1 | Account |
| 55 / 20013 | Symbol / Tenor |
| 54 | Side |
| 38 | OrderQty |
| 40 | OrdType |
| 59 | TimeInForce |
| 44 | Price (if priced) |
| 151 | LeavesQty |
| 14 | CumQty |
| 6 | AvgPx |
| 60 | TransactTime |

| Engine event | 150 ExecType | 39 OrdStatus | Extra |
|---|---|---|---|
| Accepted | 0 New | 0 | |
| Rejected | 8 Rejected | 8 | 103 (1 unknown symbol, 6 duplicate, 99 other), 58 = engine reason |
| Fill | F Trade | 1 or 2 | 32 LastQty, 31 LastPx, 20004 TradeID (T{id}{A\|P}) |
| Cancelled (incl. IOC/FOK remainder, residual below min qty, expiry, end of day, disconnect, fixing cut-off, SELF_TRADE, HALTED) | 4 Canceled | 4 | 58 = reason |
| Replaced | 5 Replaced | current | new ClOrdID in 11, previous in 41 |
| Cancel/replace queued (min TTL or a running race) | 6 Pending Cancel / E Pending Replace | 6 / E | |
| Peg repriced or resumed | D Restated | current | 378=3, new Price |
| Peg suspended | D Restated | current | 378=99, 58=SUSPENDED |
| Joined a fairness race | I Order Status | current | 20003 RaceID, 58=RACE |
| Fixing match (price to follow) | I Order Status | current | 20001 FixingMatchedQty; the Trade report follows when the fixing rate arrives |

**Mid trades** may carry LastPx with one more decimal place than the instrument's precision (for example a half pip).

**OrderCancelReject (9):** 434 CxlRejResponseTo (1 = cancel, 2 = replace) and 102 CxlRejReason (0 = too late, 1 = unknown order, 6 = duplicate ClOrdID, 99 = other), with 58 = engine reason. 11 always names the request being answered.

**TradingSessionStatus (h):** sent to every logged-on session when an instrument changes state. 336 = CLOB, 340 TradSesStatus (1 = halted, 2 = open, 3 = closed, 4 = pre-open), 20014 Symbol, 20013 Tenor.
