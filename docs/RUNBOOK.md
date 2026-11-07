# Operations runbook (draft)

## Components

One **primary** node (`clob primary node.properties`) runs:
- the sequencer and journal;
- the matching engine;
- the market data replica;
- the FIX gateway (order entry and market data);
- the ops thread (drop copy `data/dropcopy.log`, trade report `data/trades.csv`, metrics);
- the admin/metrics HTTP API (localhost).

An optional **warm standby** (`clob standby node.properties`, with `replication.primaryHost` and `replication.primaryPort` set) follows the primary's sequenced input stream and journals it.

Every state change (orders, credit, rates, value dates, fixings, halts, end of day) is a **sequenced, journalled input**. The journal is the system of record; replaying it reproduces every output exactly.

## Starting and stopping

| Action | How |
|---|---|
| Start | `clob primary config/node.properties`. On start the node replays its journal (recovery), then opens FIX and admin. Check `GET /health` returns `PRIMARY seq=N`. |
| Stop | SIGTERM. The node drains in-flight inputs, flushes the journal and drop copy, and closes FIX sessions. Sessions with cancel-on-disconnect have their orders cancelled. |
| Restart | Same as start. FIX clients must log on with ResetSeqNumFlag=Y (PLAN Q41). |

## Daily lifecycle (all via the admin API, header `X-Admin-Token`)

| When | Call |
|---|---|
| Before trading | `POST /admin/usdrate?ccy=EUR&rate=1.0845` for every base currency; `POST /admin/valuedates?instrument=N&near=YYYY-MM-DD[&far=…]` for every instrument; `POST /admin/session?state=OPEN` if instruments start PRE_OPEN |
| Fixing events | `POST /admin/fixing/schedule?event=0&date=YYYY-MM-DD` opens the day's occurrence (DST-aware). At fixing time, `POST /admin/fixing/rate?event=0&instrument=N&rate=1.08123` for each instrument. The cut-off happens automatically. |
| End of day | `POST /admin/endofday` (cancels DAY orders; GTC roll), then `POST /admin/roll?today=YYYY-MM-DD` (releases settled credit), then new value dates |
| Credit changes | `POST /admin/credit/line?…` and `POST /admin/credit/relationship?…` |

## Incidents

| Situation | Action |
|---|---|
| Halt one instrument | `POST /admin/session?instrument=N&state=HALTED`. Resume with `state=OPEN`; races that expired during the halt resolve at reopen. |
| **Kill switch** | `POST /admin/killswitch` halts every instrument, then cancels every order (admin cancels bypass min TTL and race holds). Reopen per instrument when safe. |
| Cancel one order | `POST /admin/cancel?order=ID` |
| Refused admin input | `clob_admin_refused_total` rises and the reason is in the node's stderr. Nothing changed in the engine. |

## Failover (warm standby)

1. Confirm the primary is down, or stop it: there is **no automatic fencing** (PLAN Q43). Two primaries must never run together.
2. On the standby, check `GET /health` shows `STANDBY seq=N`. N should match the primary's last sequence if it can be read.
3. `POST /admin/promote` on the standby. It starts FIX, market data and admin on its configured ports, continuing the sequence from the replicated state.
4. Point clients (DNS or VIP) at the new primary. Clients log on with ResetSeqNumFlag=Y.
5. Rebuild a new standby from the new primary (it catches up from the journal).

Replication is asynchronous: inputs acknowledged in the last moments before a crash may not have reached the standby (PLAN Q40).

## Monitoring (`GET /metrics`, Prometheus text)

| Metric | Alert when |
|---|---|
| `clob_last_sequence` | stops rising (with market data ticks enabled it rises about 200/s even when idle) |
| `clob_replication_connected` | 0 on a primary that should have a standby |
| `clob_admin_refused_total` | rises |
| `clob_orders_rejected_total`, `clob_cancels_total{reason=…}` | unusual rates |
| `clob_trades_total`, `clob_traded_qty_total`, `clob_races_total`, `clob_session_changes_total` | informational |

Also watch: host CPU steal; the journal disk's free space (about 0.7 GB/day for market data ticks at 5 ms, plus orders); GC and safepoint logs; FIX session disconnects.

## Audit and investigation

`clob audit config/node.properties` prints every journalled input with the engine output it produced, by deterministic replay, including lottery seeds and draws. The drop copy and trade report are convenience copies; the journal plus replay is authoritative.
