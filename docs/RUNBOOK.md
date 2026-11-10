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
| Start | `clob primary config/node.properties`. The node checks the journal manifest, loads the newest snapshot, replays the journal from there, then opens FIX and admin. It refuses to start if reference data, seed, capacities or schema differ from the journal's. `GET /ready` returns 200 when it should receive traffic. |
| Stop | SIGTERM. Staged and time-limited: FIX ingress stops, cancel-on-disconnect is applied while sessions are still connected, everything is drained to a watermark, the journal is flushed, then transports close. |
| Restart | Same as start. FIX clients can resume their sequence numbers, or log on with ResetSeqNumFlag=Y. Their orders stay under their control either way. |
| Change reference data | A journal only replays under the reference data that wrote it. Agree a controlled reset: archive the journal and start a new one at a quiet point. Effective-dated changes are not built. |

## Daily lifecycle (all via the admin API, header `X-Admin-Token`)

| When | Call |
|---|---|
| Before trading | `POST /admin/usdrate?ccy=EUR&rate=1.0845` for every base currency; `POST /admin/valuedates?instrument=N&near=YYYY-MM-DD[&far=…]` for every instrument; `POST /admin/session?state=OPEN` if instruments start PRE_OPEN |
| Fixing events | `POST /admin/fixing/schedule?event=0&date=YYYY-MM-DD` opens the day's occurrence (DST-aware). At fixing time, `POST /admin/fixing/rate?event=0&instrument=N&rate=1.08123` for each instrument. The cut-off happens automatically. |
| End of day | Automatic at 17:00 New York (`eod.schedule`, DST-aware), and a snapshot is taken in the background. Then send new value dates, followed by `POST /admin/roll?today=YYYY-MM-DD`. In that order: an instrument whose value date falls behind the business date halts until new dates arrive, and resumes by itself. |
| Credit changes | `POST /admin/credit/line?…` and `POST /admin/credit/relationship?…` |

## Incidents

| Situation | Action |
|---|---|
| Halt one instrument | `POST /admin/session?instrument=N&state=HALTED`. Orders waiting in its races are cancelled. Resume with `state=OPEN`. Arriving data never reopens an operator halt. |
| Instrument halted, no one halted it | A USD rate or value date it needs is missing or past. Supply it and the instrument resumes by itself. A merely old rate does not halt: only never-received or invalid data does. |
| Credit line over limit with no trade | `clob_credit_limit_breaches_total` rises: a SWAP's near leg settled, leaving its far leg as NOP exposure. The line accepts only reducing trades until it is back inside its limit. |
| Node `FAILED` | A fatal error stopped it: ingress is refused and `/health` returns 503. The reason is in stderr. Restart it, or fail over. |
| Journal corruption at start | The node refuses to start and keeps the file unchanged. Recover from the standby or a backup. Only a torn final record is repaired automatically. |
| **Kill switch** | `POST /admin/killswitch` halts every instrument, then cancels every order (admin cancels bypass min TTL and race holds). Reopen per instrument when safe. |
| Cancel one order | `POST /admin/cancel?order=ID` |
| Refused admin input | `clob_admin_refused_total` rises and the reason is in the node's stderr. Nothing changed in the engine. |

## Failover (warm standby)

1. Confirm the primary is down, or stop it: there is **no automatic fencing**. Two primaries must never run together.
2. On the standby, check `GET /health` shows `STANDBY seq=N`. N should match the primary's last sequence if it can be read.
3. `POST /admin/promote` on the standby. It starts FIX, market data and admin on its configured ports, continuing the sequence from the replicated state.
4. Point clients (DNS or VIP) at the new primary. Clients log on with ResetSeqNumFlag=Y and reconcile with OrderStatusRequest.
5. Rebuild a new standby from the new primary (it catches up from the journal).

Set `replication.bindHost` and `fix.bindHost` to reachable addresses for a two-host deployment.

**What can be lost.** A process crash loses nothing the journal received. A host or power failure can lose at most the last `journal.flushMillis` (default 10 ms) on that host. A failover additionally loses whatever had not reached the standby, which `clob_replication_lag` measures. A slow or dead standby cannot slow the primary: it is disconnected after `replication.deadlineMillis` and catches up when it returns.

## Monitoring (`GET /metrics`, Prometheus text)

| Metric | Alert when |
|---|---|
| `/ready` | not 200 |
| `clob_fatal_errors_total`, `clob_snapshot_failures_total` | rises |
| `clob_last_sequence` minus `clob_engine_processed_sequence` or `clob_follower_processed_sequence` | grows |
| `clob_inbound_queued_bytes`, `clob_gateway_output_queued_bytes`, `clob_ops_output_queued_bytes`, `clob_gateway_backlog` | stays high |
| `clob_md_cycle_overruns`, `clob_md_dropped_snapshots`, `clob_md_max_tick_lateness_nanos` | rises |
| `clob_replication_lag` | grows |
| `clob_credit_limit_breaches_total` | rises |
| `clob_last_sequence` | stops rising (with market data ticks enabled it rises about 200/s even when idle) |
| `clob_replication_connected` | 0 on a primary that should have a standby |
| `clob_admin_refused_total` | rises |
| `clob_orders_rejected_total`, `clob_cancels_total{reason=…}` | unusual rates |
| `clob_trades_total`, `clob_traded_qty_total`, `clob_races_total`, `clob_session_changes_total` | informational |

Also watch: host CPU steal; the journal disk's free space (about 0.7 GB/day for market data ticks at 5 ms, plus orders); GC and safepoint logs; FIX session disconnects.

## Snapshots and journal retention

A snapshot is written in the background at each end of day, into `data/snapshots`, and the last three are kept. `POST /admin/snapshot` takes one at the journal's current end. A damaged snapshot is skipped, falling back to an older one or to the journal alone.

Journal segments are never deleted by the node. Archive them yourself, keeping every segment after the oldest retained snapshot and anything a rebuilding standby would need from its last sequence.

## Queues and what happens when they fill

| Edge | Owner | Bound | When full | If the consumer fails |
|---|---|---|---|---|
| Gateway to inbound ring | FIX gateway | 1 MiB, then a small backlog | The gateway stops reading sockets and keeps consuming engine output | Node fails |
| Admin to inbound ring | Admin thread | 1 MiB | HTTP 503 after 2 s | Node fails |
| Sequencer to engine and followers | Sequencer | 1 MiB each | Sequencer waits. These are critical: the journal already holds the input | Node fails, retry loops exit |
| Engine to gateway and ops rings | Engine | 4 MiB each | Engine waits. Reports and the drop copy are mandatory | Node fails, rings abort |
| Market data to gateway | MD follower | 1 MiB | Snapshot dropped (`clob_md_dropped_snapshots`); the next cycle replaces it | Node fails |
| Journal to standby | Replication thread | None: it reads the files | The standby falls behind and is disconnected at the deadline | Standby reconnects |
| FIX session output | Gateway | 1 MiB per connection | Connection dropped; reports stay in the store for resend | Session only |

## Audit and investigation

`clob audit config/node.properties` prints every journalled input with the engine output it produced, by deterministic replay, including lottery seeds and draws. The drop copy and trade report are convenience copies; the journal plus replay is authoritative.
