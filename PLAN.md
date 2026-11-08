# FX CLOB: Implementation Plan

Source: `requirements.md`. This plan covers the architecture, the build phases, the testing approach, and the questions the spec leaves open.

**Revision 5 (2026-11-07).** Consistency pass:
- Fixed stale references: the VISIBILITY rule is now "exhaustion", not "reachability"; NDF settlement currency is deferred to Q10a; Q7c moved into the Phase 2 gate; the race risk now cites the open questions rather than answered ones.
- Added Q20–Q26.
- **Note (updated 2026-11-08):** the answers marked "confirmed" in Revision 4 have now been folded into `requirements.md` (Q26).

**Revision 4 (2026-11-07).** Review update: adds explicit decision gates for matching, product economics and credit; specifies sequenced market-data boundaries and race admission rules; and corrects acceptance tests. Previous revision history: Revision 3 (2026-11-07). Revision 2 built in the answers to Q1–Q6, plus partial answers to Q7, Q8 and Q11. Revision 3 adds the latest requirements changes: the corrected visibility line (Q1a/Q1b), partial fills on credit breach (Q6a), pegs excluded from the peg reference (Q13), the `reject-on-ttl` flag (Q14), and fixing cut-off times (Q5). Each answered question in **§6** keeps a short record of the answer, plus any follow-ups the answer raised. The skeleton and throughput experiments can proceed. Production matching implementation is gated on **Q1c, Q6i, Q14a and Q15**; credit and product decisions have separate gates below. Examples validate outcomes but do not establish a complete algorithm.

---

## Implementation status (2026-11-07)

**Done: Phase 0 (skeleton and throughput spike), Phase 1 (SPOT reference data and validation), Phase 2 core, Phase 3 core (credit), Phase 4 core (pegged, mid and fixing orders), Phase 5 core (fairness races), Phase 6 core (sequencing, journal, replay, session lifecycle), Phase 7 core (FIX order entry), Phase 8 core (market data), Phase 9 (performance hardening, first pass), Phase 10 core (operations).** Build with `./gradlew test` (Java 21, Gradle 9.4 wrapper). 238 tests, all green.

| Module | Contents |
|---|---|
| `clob-common` | `Side`, `Prices` (decimal ↔ fixed-point ticks) |
| `clob-refdata` | `Instrument` (all spec parameters plus base/term currency, validated), `RefData` (instruments, user↔account entitlements) |
| `clob-credit` | `CreditEngine`: lines (pools) with a giver, type and USD limit; relationships (default plus per-instrument overrides); DSL per value date, NOP net; exact USD×10⁸ arithmetic; dry-run overlay for FOK |
| `clob-engine` | `MatchingEngine` (pure, single-threaded, no clock), array-based `BookSide`, intrusive `PriceLevel` FIFOs (displayed and hidden queues), pooled orders and levels, timer heap, `FillSimulator` for FOK |
| `clob-sequencer` | `InputCodec` (all 14 input types), `EngineDispatcher`, `OutputEncoder`, `Journal` (mmap segments, CRC32C, torn-tail recovery), `Sequencer` (stamp, journal, timer injection), `Pipeline` (gateway → sequencer → engine threads over Agrona rings) |
| `clob-marketdata` | `MarketDataService` (replica engine on the input stream, bucketing, screening by equivalence class, change-only publishing, rotation), `SnapshotCodec`, `UdpPublisher` |
| `clob-fix` | FIX 4.4 acceptor: zero-copy tag=value codec, session layer, order translation, execution reports (`docs/FIX-RoE.md`) |
| `clob-app` | Node wiring (`clob primary|standby|audit`), refdata file loader, admin and metrics HTTP API, ops agent (drop copy, trade report, metrics), warm-standby replication and promotion, audit tool (`docs/RUNBOOK.md`) |
| `clob-testkit` | `Scenario` DSL (reads the requirements' `id\|side\|show\|price\|qty` tables), credit setup helpers, recorded events |
| `clob-bench` | Throughput/latency harness (`SPIKE-RESULTS.md`) |

What the engine does today:
- LIMIT, MARKET and ICEBERG orders, with all five TIFs.
- VISIBILITY and PRICE priority. Every requirements example passes as a scenario test.
- Min qty and qty increment are enforced on every fill, and residuals below min qty are cancelled.
- Cancel, cancel/replace and mass cancel, with the min TTL reject-or-queue rule. Disconnect and admin cancels bypass min TTL.
- Timers fire before an input with the same timestamp.

Checks beyond the scenario tests:
- A random-flow test confirms quantity is conserved and every fill is a valid size.
- A FOK/IOC equivalence test is mutation-checked: a planted simulator bug fails it.
- A determinism test replays the same inputs and compares the outputs.

Phase 3 credit, as built:
- Credit is bilateral: a match needs a line in both directions for the instrument.
- Lines are UNLIMITED, DSL or NOP. No credit means no line.
- A line is a pool extended by one account (its giver). Any of the giver's relationships and instruments may share it.
- The matching walk skips orders it can't trade with, and orders whose credit can't cover a minimum-size fill. It clips fills down to the largest valid size, so books can be crossed.
- SWAPs consume DSL on both legs' dates.
- Value dates and USD rates arrive as sequenced inputs. Value-date roll releases settled usage.
- Credit admin changes (lines, relationships, rates, value dates) are sequenced engine inputs.
- Self-trades are blocked because nobody has a credit line to themselves (see Q29).
- The bench now uses the real credit engine (10% none, 60% unlimited, 20% DSL, 10% NOP per pair):
  - throughput is unchanged at about 2.6M events/s untimed;
  - aggressive IOCs are about 300 ns slower;
  - allocation is still zero.

Phase 4, as built (`PegManager`, `MidMatcher`, `FixingDesk` in `clob-engine`):
- **Pegged.**
  - Pegs to MID or NEAR-TOUCH of the visible top of book, unscreened and excluding pegs, ± discretion (+ = more aggressive).
  - Priced on entry, then repriced only on the instrument's peg-throttle grid (20ms), from a reference frozen at each tick, in arrival order.
  - Rejected at entry, and suspended at a tick, when either visible side is missing; resumes at the first tick both are back.
  - A reprice or resumption loses priority. A crossing peg trades.
  - A discretion amend takes effect at the next tick, so it can't be used to beat the throttle.
- **Mid.**
  - Separate mid book per instrument, matching only mid orders.
  - Mid trades may execute at **one decimal place beyond the instrument's precision** (confirmed 2026-11-07). The half-tick mid is exact, so nothing is rounded: limits are mid ± discretion, price is `clamp(mid, sellLimit, buyLimit)`. A 92/93 market matches two zero-discretion mid orders at 92.5. Mid trades ignore the order price increment.
  - Trade events carry `priceDecimals`: the instrument precision for book and fixing trades, precision+1 for mid trades. FIX execution reports and market data must honour it.
  - All four requirements examples pass.
  - Resting mid orders are re-checked whenever the reference moves.
  - Mid TTL replaces min TTL. Credit and min-qty rules apply. FOK has its own dry run.
- **Fixing.**
  - `FixingEvent` (name, time, zone, cut-off) in refdata, with DST-aware occurrence instants. Each occurrence is opened by a sequenced `onFixingSchedule` input.
  - Fixing orders match only each other, continuously in time priority, with credit reserved at match time.
  - Unmatched quantity is cancelled at cut-off. `onFixingRate` prices every pending match as a trade.
- **Engine output:** new `onOrderRepriced`, `onOrderSuspended` and `onFixingMatched` events.
- **Performance:** with no peg, mid or fixing orders in the flow, the engine is about 2–10% slower than at Phase 3 (back-to-back runs: about 2.3–2.4M vs 2.4–2.6M events/s). The likely cause is the larger `Order` object. To recover in Phase 9 by moving rarely used fields out of `Order`.

Phase 5, as built (`RaceManager`, `SplitMix64` in `clob-engine`):
- **Taker race.** One per book per aggressor side, over the half-open window [start, start + interval).
  - An order that would execute now joins it, or starts one. "Would execute" uses the FOK dry run, so credit and VISIBILITY apply.
  - New orders, crossing amends and crossing peg reprices all join, including market, IOC and FOK orders. FOK is checked when applied.
- **Maker race.** One per book per (side, price), started by a new order creating a level. Later new orders at that price join. Pending orders are held off the book until the window closes.
- **Lottery.** One ticket per account, holding its orders in arrival order. Rounds shuffle the accounts that still have orders (Fisher–Yates over SplitMix64) and apply each one's next order as an ordinary order, until all tickets are empty.
  - The seed comes from `EngineConfig.randomSeed` and the race id, and is published in `onRaceStarted` for audit.
  - Fairness tests confirm about 50/50 between an early and a late account. Disabling the shuffle makes them fail.
- **queue-cancels.** While a taker race runs, participant cancels and amends on the book are held. A request held by both min TTL and a race applies once both have cleared (confirmed Q15). Disconnect and admin cancels bypass the hold. Orders waiting in a race can't be amended (`PENDING_IN_RACE`).
- **Engine output:** new `onRaceStarted` and `onOrderJoinedRace` events.
- **Bench.** With 5ms taker and maker races on every book (a stress case: every new price level starts a maker race, and `queue-cancels` holds about half of all cancels):
  - throughput is about 1.19M events/s, against 2.25M without races; p99 is 8.3 µs;
  - each lottery's burst of orders runs inside whichever input triggers the expiry timer;
  - allocation is 172 KB over 10M events, from pool growth.

  Options for Phase 9: run race expiries as their own sequenced inputs; pre-size race, ticket and held-order pools.

Phase 6, as built (`clob-sequencer`, plus session state in the engine):
- **Input codec.** A fixed-layout little-endian binary codec for every engine input (SBE-style, so generated SBE can replace it). Header: length, type, sequence, time.
- **Sequencer.** Stamps each input with a sequence number and a monotonic epoch-nanos time, appends it to the journal, then forwards it. It injects a time input whenever the clock passes the engine's next timer deadline, so expiries, TTL releases, peg ticks, races and fixing cut-offs happen with no order flow. Those inputs are journalled too.
- **Journal.** Memory-mapped, pre-touched segment files, with a CRC32C per record and the length written last. On open, a torn tail in the last segment is truncated and appending resumes; corruption in an earlier segment fails the open. Durability is page cache plus an explicit `flush()`. This stands in for Aeron Archive.
- **Pipeline.** Gateway threads feed a many-to-one ring into the sequencer thread (stamp, journal), which feeds a one-to-one ring into the engine thread (decode, match, emit encoded output). The journal stays off the engine thread.
- **Replay and recovery.** Replaying the journal into a fresh engine reproduces the output byte for byte, including with three concurrent gateway threads. Recovering after a crash (replay, then continue) is byte-identical to an uninterrupted run. Mutation-checked: journalling before stamping makes these tests fail.
- **Session lifecycle.** Each instrument is PRE_OPEN, OPEN, HALTED or CLOSED, reported as `onSessionState`.
  - Outside OPEN, new orders and amends are rejected (`MARKET_NOT_OPEN` / `MARKET_HALTED`); cancels always work.
  - Peg reprices, mid matching and race lotteries wait. A race that expires during a halt resolves at reopen.
  - GTT expiry and min-TTL releases continue.
- **Admin inputs** the engine refuses (invalid credit configuration, an inapplicable fixing schedule or rate) are reported to an `ErrorListener`. They leave state unchanged, identically in live running and replay.
- **Not built yet:**
  - state snapshots, so recovery currently replays the whole journal (Q39);
  - Aeron Archive and Aeron Cluster (Phase 10 HA);
  - a flush policy (Q40);
  - an end-to-end pipeline benchmark (Phase 9).

Phase 7, as built (`clob-fix`):
- **FIX engine.** A compact FIX 4.4 acceptor instead of Artio. Artio needs its own Aeron media driver and archive, which is heavy for this environment. Artio stays a drop-in replacement behind the same gateway role.
- **Gateway thread** (an Agrona agent, NIO, non-blocking):
  - Logon with Username/Password; heartbeats and TestRequest timeouts; ResendRequest (application messages resent with PossDupFlag, admin messages gap-filled); SequenceReset; inbound gap detection; Logout.
  - Per-session token-bucket throttling; cancel-on-disconnect, which also fires on a clean logout.
- **Order entry:** D, F, G and q translate into engine inputs through the pipeline. Engine output reaches the gateway via an `OutputRing` and becomes execution reports, OrderCancelRejects, mass-cancel reports and TradingSessionStatus.
- **Rules of engagement:** `docs/FIX-RoE.md`, including the custom tags (tenor, CLOB order type, discretion, fixing event, race id, trade id) and the mapping from engine events to ExecType and OrdStatus.
- **Tests.** 7 QuickFIX/J conformance tests (full FIX 4.4 dictionary validation of everything we send) and 6 raw-socket session-layer tests. The disconnect test found a real bug: a clean logout skipped cancel-on-disconnect. Fixed.
- **Not built yet:**
  - persistent FIX sequence numbers and resend store (Q41);
  - market data over FIX (Phase 8);
  - drop copy (Phase 10);
  - TLS;
  - hashed passwords;
  - multi-gateway output fan-out.

Phase 8, as built (`clob-marketdata`, plus market data ticks in the sequencer and market data over FIX):
- **Replica, not deltas.** The market data service is a pipeline follower running a replica engine on the sequenced input stream. Determinism makes its books and credit identical to the matching engine's at every sequence number, so the engine needs no book-delta output.
- **Coordinated cut.** The sequencer injects journalled `MD_TICK` inputs on the wall-clock grid (00:00:00.000, .005, …; missed boundaries are skipped and lateness recorded). Every subscriber's update in a cycle comes from the same point in the stream.
- **Per cycle and instrument** (honouring each instrument's market data interval):
  - collect displayed orders (hidden quantity never shown);
  - bucket by the market data price increment, bids down and offers up, up to the instrument's levels;
  - unscreened: one view; screened: orders from bilaterally eligible counterparties only (relationship-based, Q6e default), shared across subscribers with identical eligibility over the accounts present;
  - publish only changed views, in a send order rotated by one per cycle per instrument;
  - skip books whose version and displayed-content fingerprint are unchanged.
- **Transports.**
  - FIX: MarketDataRequest (V) subscribes or unsubscribes, with depth, tenor (20013), and screened (20015) plus Account. MarketDataSnapshotFullRefresh (W) carries the updates; MarketDataRequestReject (Y) the refusals.
  - Unicast UDP: a binary snapshot with per-subscription sequence numbers.
  - Market data cycle timing is recorded in a histogram (`cycleNanos`).
- **Tests:** the requirements bucketing example, offer rounding, depth, hidden quantity, screening, equivalence-class sharing, change-only publishing, rotation, interval, the coordinated cut against the engine, UDP delivery, ticks driving a live pipeline, and QuickFIX/J subscriptions.
- **Measured (`SPIKE-RESULTS.md`).** With 200 books × 64 screened subscribers, an unchanged-content cycle takes 1.7 ms. When every book changes every cycle it takes 44 ms, over the 5 ms budget, at about 2–3 µs per subscriber per changed book. Worst-case scale needs multi-threaded publishing (Phase 9).
- **Not built yet:** incremental (delta) updates, a multicast transport for unscreened data, a gap-recovery/snapshot channel, and screening by credit headroom (Q6e).

Phase 9, as built:
- **Zero-allocation CI guard** (`AllocationTest`): 0 bytes over 1M measured events of mixed flow after warm-up. It found that `queue-cancels` holds could starve under back-to-back races; holds now end when the race running at request time resolves (Q7b refined). It also found race pool growth; the pools are now pre-sized.
- **JMH microbenchmarks** (`EngineBenchmarks`): add/cancel 197 ns, a 5-level sweep with adds 1.1 µs, a crossed-book walk about 15 ns per skipped order, a peg tick about 20 ns per peg.
- **Market data:** per-instrument eligibility matrices plus pooled keys and views halve the worst case (44 → 23 ms).
- **End-to-end FIX latency bench** (`FixLatencyBench`). This VM can't give meaningful wire-to-wire numbers (4 shared vCPUs, backoff idle strategies).
- **`docs/TUNING.md`:** core isolation and pinning, busy-spin idle strategies, OS, JVM and NIC settings, and the measurement tools.
- **Still to do:**
  - configurable busy-spin idle strategies in the pipeline;
  - multi-threaded market data publishing for worst-case churn;
  - moving rarely used fields out of `Order` (the Phase 4 regression);
  - running race expiries as their own sequenced inputs;
  - re-running everything on production-class hardware.

Phase 10, as built (`clob-app`):
- **Node.** A node is built from `node.properties` and a refdata text file (`config/`). Recovery replays the journal, with output suppressed during replay so the drop copy isn't duplicated. The node then runs the pipeline, market data, FIX gateway, ops thread and admin API.
- **Engine output fan-out** goes to the gateway and the ops thread. The ops thread writes the drop copy (`dropcopy.log`), the trade report (`trades.csv`), and Prometheus metrics (`GET /metrics`).
- **Admin HTTP API** (token-authenticated, localhost):
  - session halt and reopen; the kill switch (halt all, then admin-cancel every order); end of day; admin cancel;
  - credit lines and relationships; USD rates; value dates and roll;
  - fixing schedule (DST-aware occurrence from refdata) and fixing rate.

  Every action is a sequenced, journalled input.
- **Warm standby HA.** The primary streams its sequenced input stream to a standby, catching it up from the journal when it connects or reconnects. The standby journals and applies everything (identical state) with output suppressed. `POST /admin/promote` turns it into a primary continuing the sequence. Tests fail the primary, promote the standby (including one that joined late), check the journals are byte-identical, and trade against pre-failover orders.
- **Audit tool** (`clob audit`): every journalled input with the output it produced, by replay.
- **`docs/RUNBOOK.md`:** start and stop, daily lifecycle, incidents and kill switch, failover, monitoring, audit.
- **Not built yet:**
  - Aeron Cluster (Raft), for automatic leader election and fencing (Q43);
  - synchronous replication (Q40);
  - persistent FIX sessions (Q41);
  - TLS and auth hardening on the admin API;
  - a FIX drop-copy session;
  - multi-gateway fan-out.

Defaults chosen for open questions (each is easy to change):
- Q6i: `minQty` must be a multiple of `qtyIncrement`.
- Q7c: market orders never rest, whatever their TIF.
- Q14a: participant mass cancels obey min TTL per order.
- Q1c: refreshed iceberg slices count as displayed liquidity.
- Q18: timers fire before inputs with the same timestamp.
- Q1c (with credit): the visible top level is screened per aggressor. A level whose displayed orders the aggressor can't trade with doesn't count as its top.
- Q6b: a SWAP's legs net to zero for NOP.
- Q6c: NOP is a single signed USD figure per line, measured from the giver's side.
- Q6d: USD amount = base notional × base currency's USD rate, kept exact. Usage is recorded at the trade-time rate and never revalued.
- Q6e: not decided yet. It's needed for screened market data (Phase 8).
- Q6g: lines are pools owned by one giver and shared by whichever relationships and instruments map to them.
- Q6h: a limit cut below usage blocks trades that increase exposure; trades that reduce exposure are still allowed.
- A missing USD rate or value date blocks DSL/NOP lines; UNLIMITED lines still work.
- Q4f (pegs only): discretion is applied to the raw reference (the mid may be half a tick), then rounded defensively to the order price increment: bids down, offers up. Pegs rest in the book, so they stay on the order price grid.
- Q4e: peg ticks fall on a fixed grid (t = k × throttle). The reference is frozen per tick, and pegs are processed in arrival order.
- Q5b: fixing credit is reserved at match time. Exposure is notional × USD rate, so the fixing price isn't needed.
- Q5c: a fixing order names a fixing event, must be DAY, GTC or GTT, and can't be amended.
- Q5e: fixing orders match continuously before cut-off, in time priority.
- Q5f: a fixing match is reported at once without a price, then as a trade when the rate arrives.
- Q5g: pending fixing matches wait for a rate; the next occurrence can't open until then.
- Q21: mid TTL replaces min TTL for mid orders.
- Q22: an engine reprice doesn't restart min TTL. A tick with an unchanged price keeps priority.
- Q7b: cancels and amends are held book-wide (either side's taker race), including a taker's own race orders. Disconnect and admin cancels bypass the hold.
- Q8: later new orders at a maker race's price join it, even if the level reappears meanwhile. Pending maker-race orders are off the book: not visible, not matchable.
- Q15: with `reject-on-ttl`, a cancel inside TTL is rejected even while a race is running.
- Q20: race admission means "would execute now", by the FOK dry run. Mid and fixing orders aren't subject to races.
- Q37: only newly submitted orders start or join maker races. Amends and peg reprices rest normally. Orders waiting in a race can't be amended.
- Q27–Q30: see below.

**Phase 0 throughput spike: done** (`clob-bench`, results in `clob-bench/SPIKE-RESULTS.md`). On an Intel N100 development VM, one engine thread with a credit stub and output encoding runs at about 2.1M events/s flat out:
- p50 is 322 ns, p99 1.35 µs and p99.9 2.7 µs;
- steady-state allocation is zero, with no GCs;
- latency is stable when paced at 1M/s, but the thread falls behind when paced at 2M/s;
- the journal must run on its own thread (inline, it doubles the per-event cost).

**Decision:** stay on one shard for Phase 3, keep the engine shard-ready, and re-run the spike on production-class hardware before Phase 3 finishes. Sharding is triggered if load exceeds 50%, or p99 exceeds the latency target, at the agreed aggregate peak (Q11).

Not built yet in Phase 3: holiday calendars and value-date calculation (dates are supplied as inputs for now); credit events in engine output (needed for the market-data credit replica and risk UI); an account limit of 4,096 (dense pair table). Not built yet elsewhere: see the per-phase lists above, and book-delta output for market data.

---

## 1. Architectural principles

"Low latency with deterministic performance" leads to these decisions:

| Concern | Decision |
|---|---|
| Concurrency model | **Single-writer, deterministic matching engine(s).** Credit limits (DSL/NOP) apply across instruments, so the default is one engine thread that owns every book and all credit state. The new throughput target (200 books × 10,000 updates/sec, so up to **2M events/sec** in total if every book peaks at once) leaves about **500 ns per event** on one thread. That is achievable for plain add/cancel, but tight once crossed-book walks, races and peg reprices are added. So the engine is built **shard-ready** from day one (see below), and a Phase 0 spike decides between one shard and several. |
| Sharding path | Books are partitioned across N engine shards by instrument. Credit is the only state shared across shards, and it is handled by **credit leasing**: a credit service grants each shard a slice of headroom per explicit credit pool and exposure dimension. DSL reservations are value-date scoped; NOP reservations span dates and require a separately proven signed-exposure protocol. A date-bucket lease alone is insufficient for NOP. Shards ask for top-ups through the sequencer, so each shard's input log stays deterministic. This is only used if the Phase 0 spike shows one thread cannot meet the target and the credit protocol passes the Phase 3 correctness gate. The spike measures candidate architectures; it does not approve leasing semantics. |
| Determinism | Event-sourced. A sequencer gives every input an order and a timestamp. **The engine never reads the clock and never calls an unseeded RNG.** Timers (race expiry, GTT, end of day, fixing cut-off, fixing time, MD interval, peg throttle, min TTL expiry) arrive as sequenced events. Lottery RNG is seeded from the journal. Replaying the journal gives bit-identical output. |
| Latency stability | Zero allocation on the hot path: pre-sized object pools, primitive collections (Agrona / Eclipse Collections), and fixed-point `long` prices and quantities (no `BigDecimal` or `double`). Busy-spin idle strategies, core pinning, and a low-pause JVM (Azul Prime or ZGC; benchmark both). |
| Messaging/transport | **Aeron** for internal IPC/UDP and the journal (Aeron Archive), **SBE** for internal message encoding, **Artio** for the FIX engine. Aeron Cluster (Raft) later for HA. |
| Language/build | Java 21 LTS, Gradle multi-module, JUnit 5, jqwik (property tests), JMH, HdrHistogram. |

### Component view

```
 FIX clients ──TCP──► [FIX Order Gateway (Artio)] ──┐
 Fixing rate API ───► [Fixing Rate Adapter] ────────┤
 Admin (refdata, credit) ───────────────────────────┤
                                                     ▼
                                         [Sequencer + Journal (Aeron Archive)]
                                                     │  sequenced inputs + timer events
                                                     ▼
                ┌──────────── Matching Engine shard(s) (single thread each) ────────────┐
                │ RefData · Books (per instrument) · Credit (or leases) · Races · Pegs │
                │ Timers                                                               │
                └───────────────────────────┬──────────────────────────────────────────┘
                                            │ output events (exec reports, book deltas)
              ┌─────────────────────────────┼──────────────────────────────┐
              ▼                             ▼                              ▼
   [FIX Gateway: ExecReports]   [Market Data Service]              [Drop copy / Audit / Risk UI]
                                  book replica → snapshot @ interval
                                  ├─ unscreened → UDP multicast (FAST / ITCH)
                                  └─ screened  → per-account TCP FIX (rotating order)
```

### Module layout

```
clob-common      fixed-point Price/Qty, ids, time, SBE schemas, codecs
clob-refdata     Instrument, Tenor, calendars, value-date calc, USD conversion rates, fixing events
clob-engine      OrderBook, matching, order types, TIF, credit, races, pegs, timers (pure, no I/O)
clob-credit      credit relationships, DSL/NOP accounting, eligibility bitsets, leasing (if sharded)
clob-sequencer   input sequencing, journal, replay
clob-fix         Artio order-entry gateway, FIX ↔ SBE mapping
clob-fixing      external fixing-rate adapter → sequenced FixingRate events
clob-marketdata  book replica, snapshotting, bucketing, screening, publishers
clob-app         wiring, config, lifecycle (start / EOD / roll)
clob-bench       JMH + end-to-end latency harness
clob-testkit     scenario DSL used by all tests
```

`clob-engine` and `clob-credit` have **no I/O dependencies**. Every test drives them with a list of inputs and compares the list of outputs.

---

## 2. Core domain model

- **Pip.** In this spec a pip is **one unit of the instrument's price precision**, not the market convention. For EUR/USD at 5dp, 1 pip = 0.00001. All discretion values (mid and pegged) and price increments are in these units.
- **Instrument** = (symbol, tenor, type ∈ {SPOT, SWAP, NDF}) plus parameters: price precision (dp), price increment (pips), min TTL, min/max qty, qty increment, min show, MD price increment, MD throttle/interval, MD subscription levels, peg order throttle, mid TTL, supported TIFs, supported order types, match priority (VISIBILITY | PRICE), maker race interval, taker race interval, **queue-cancels** (boolean), **reject-on-ttl** (boolean).
  - Prices are stored as `long` pips. EUR/USD at 5dp: 1.08123 → 108123. Valid when `price % increment == 0`. NDF order prices are outright prices (spot + points). SWAP prices represent signed swap points rather than outright rates; zero/negative values must be supported subject to the agreed product validation and points scale (Q10a). (USD/MXN at 5dp with a 5-pip increment accepts multiples of 0.00005.)
  - Qty is a `long` in base-currency units. Valid when `qty >= min`, `qty <= max`, and `(qty - min) % increment == 0`.
  - **Execution sizes (confirmed):** min qty and quantity increment apply at order entry and to each execution. Each fill must satisfy `fill >= minQty` and `(fill - minQty) % qtyIncrement == 0`, using the same increment convention as entry. After each execution, any positive residual below min qty is cancelled, on either order. A zero residual means filled, not cancelled.
  - Show qty is valid when `show >= minShow` (or the order is fully visible). A min show of 0 allows fully hidden orders.
- **User** ↔ **Account** (many-to-many entitlement). Every order carries both and is rejected if the user isn't mapped to the account.
- **Order**: id, clOrdId, account, user, instrument, side, type ∈ {LIMIT, MARKET, ICEBERG, PEGGED, MID, FIXING}, TIF ∈ {DAY, GTC, GTT, FOK, IOC}, price?, qty, leaves, maxShow?, discretion?, pegReference ∈ {MID, NEAR_TOUCH}?, fixingEvent?, expireTime?, entry seq/time, priority seq, state (incl. SUSPENDED for pegs).
- **CreditRelationship** (directional, A → B): a default plus a per-instrument mapping to an explicit `CreditPoolId`. Multiple instruments may reference the same pool; an override does not automatically create an independent allowance. Pool scope requires confirmation (Q6g). Type ∈ {NONE, UNLIMITED, DSL(usdLimit), NOP(usdLimit)}. Example: UNLIMITED by default, with DSL 100m for USD/TRY, USD/ILS and USD/PLN.
  - **A and B can only trade if A→B and B→A both allow it** for the instrument (confirmed).
- **Product economics (decision required, Q10a).** Define SPOT outright pricing. **Confirmed SWAP convention:** the order side is the buy/sell direction on the far leg, and the order price is swap points; the near leg has the opposite direction. Define near/far dates, near-leg rate source, leg notionals, points scaling and the resulting far-leg outright price; **Confirmed NDF convention:** orders are submitted at the outright price (spot + points). Define NDF reference fixing, settlement currency, dates and settlement calculation. The single-price order model above is provisional until these contracts are agreed. Trade events must carry the resolved legs and applicable reference-data versions.
- **FixingEvent**: name, time, timezone, **cut-off time** (e.g. "WMR 16:00 Europe/London, cut-off 15:55"). This is reference data. The rate for each occurrence arrives from an external API at fixing time as a sequenced `FixingRate` input.

---

## 3. Phased delivery

Each phase ends with green scenario tests and a JMH baseline, so performance regressions show up early.

### Phase 0: Spec clarification, skeleton and throughput spike (≈1.5 wks)
- Close the remaining questions in §6 and turn every example in the requirements into an executable scenario test, written before the code.
- Gradle multi-module skeleton, CI, code style, SBE schema stub.
- Build the `clob-testkit` scenario DSL, for example:
  ```
  given instrument EURUSD SPOT priority=VISIBILITY
  given book: 1|BUY|show=0|93|1000, 2|BUY|show=1000|92|1000, 3|BUY|show=1000|91|1000
  when SELL 3000 @ 91 LIMIT IOC
  then fills: (2, 92, 1000), (1, 93, 1000), (3, 91, 1000)
  ```
- **Throughput spike (new).** Build a throwaway single-thread engine with a price ladder, a credit check and journalling. Drive it with 200 books at 10k events/sec each, using a realistic mix of add, cancel and match. Measure the per-event cost and the p99.9. This decides whether we run one shard or several (§1).

### Decision gates
- **Before Phase 2 matching:** agree a complete VISIBILITY traversal algorithm (Q1c), the remaining off-increment residual rule (Q6i), remaining TTL exemptions (Q14a), combined cancel-release rules (Q15), market-order residuals for resting TIFs (Q7c, a Phase 2 order-type rule), and the scope of the "no visible bid/offer" rule (Q23). Phase 2 data structures and testkit work may proceed independently.
- **Before Phase 3 credit:** approve shared pool scope, exposure/netting formulas, USD conversion and rounding, bilateral atomic updates, limit reductions, and revaluation (Q6b–Q6h). If sharded, prove leasing for each supported credit type before adopting it.
- **Before SWAP/NDF matching and credit integration:** approve product economics and trade-event contracts (Q10/Q10a). SPOT development can proceed separately.
- **Before Phase 4:** resolve relevant mid, peg and fixing questions, including missing rates and credit reservations.
- **Before Phase 5:** resolve race admission, maker allocation, boundary ordering and cancellation interactions (Q7b/Q8/Q15/Q18/Q20/Q22).
- **Before accepting Phase 8 performance:** agree subscriber/load envelopes, delivery measurement and overload policy (Q11/Q19).

### Phase 1: Reference data and validation (≈1 wk)
- Instrument model, loading from config (YAML/JSON → immutable, array-indexed tables).
- Order validation: tick, qty bounds and increment, min show, supported type/TIF, user↔account entitlement.
- Holiday calendars and value-date calculation for SPOT (T+2, T+1 for USD/CAD, USD/TRY, etc.), SWAP near/far legs, NDF fixing and settlement dates. A daily USD conversion rate table for credit.
- Fixing event calendar (name, time, timezone, cut-off → UTC cut-off and fixing instants per business day, DST-aware).

### Phase 2: Order book and limit matching (≈2–3 wks)
- Per-side price ladder: array or open-addressing map from price → level, with separate visible and hidden FIFO queues per level (intrusive doubly linked lists), best visible and best overall price tracked. O(1) cancel via id → order handle.
- Order types **LIMIT, MARKET, ICEBERG**. TIFs **IOC, FOK** (dry-run fill check before committing), **DAY, GTC, GTT** (timer wheel keyed on sequenced time).
- **Matching priority**, provisional generalisation of the examples (Q1/Q1c):
  - `VISIBILITY`: the aggressor fills against visible liquidity in price-time order. **Hidden liquidity is unlocked only once the visible top-of-book level is exhausted** (confirmed). Reaching that level or partially filling it is insufficient. Once unlocked, hidden liquidity competes on price, and at the same price visible beats hidden. The visible top-of-book level means the full displayed quantity at that price, rather than a single order within the level. Worked example (bids hidden 93, visible 92, visible 91):
    - sell 1000 @ 93 → no fill (the visible top at 92 is out of reach, so hidden id 1 never unlocks),
    - sell 1000 @ 92 → fills id 2 @ 92,
    - sell 2000 @ 92 → id 2 @ 92, then id 1 @ 93,
    - sell 3000 @ 91 → id 2 @ 92, id 1 @ 93, id 3 @ 91.
  - `PRICE`: hidden liquidity can be matched at its price. Priority is price, then visible before hidden at the same price, then time. (Confirmed: sell 1000 @ 92 fills the hidden id 1 @ 93. By the same rule, sell 1000 @ 93 fills id 1 @ 93.)
  - Add scenarios proving that a partial fill of the visible top level does not unlock hidden liquidity, and that all displayed orders at that price must be exhausted before hidden liquidity unlocks. Still confirm whether the top level is account-screened or unscreened, whether unlock state is local to one aggressor, and how iceberg refresh, credit clipping and skipped counterparties affect exhaustion.
  - Note the fill sequence in VISIBILITY mode is not monotonic in price (92, then 93). The exec reports and the trade-price audit must handle that.
- Icebergs: displayed slice = `min(maxShow, leaves)`. A slice refresh **loses time priority** and joins the back of the queue at that price with a new priority sequence (confirmed, Q16).
- **Min TTL** (5ms): a cancel that arrives before the order has rested for min TTL is **rejected** if `reject-on-ttl` is true. Otherwise it is **queued** and applied by a sequenced timer when the TTL expires. A queued cancel doesn't stop the order filling in the meantime. If a fill completes the order, the queued cancel is answered with "too late to cancel". Amends follow the same TTL reject-or-queue rule and reset the TTL when applied (confirmed). Rejected amends do not reset TTL; queued amends reset it on application. Disconnect and administrative cancels bypass TTL constraints (confirmed). Whether they also bypass race queues remains Q7b/Q15.
- Cancel, cancel/replace (priority lost on price change or qty increase; kept on qty decrease), mass cancel per account and per user.
- Output events: Ack, Reject, Fill (both sides, with trade id), Cancel, Expire, BookDelta.

### Phase 3: Accounts and credit (≈2–3 wks)
- Eligibility is **bilateral** (confirmed): a match needs a non-NONE relationship in both directions for the instrument, and both relationships need enough headroom for the fill.
- Matching walks past ineligible orders. **The book can be crossed** across non-eligible pairs (confirmed), so "best price" is no longer enough to decide whether a match is possible. Two mitigations:
  1. Precompute an account×account eligibility bitset per credit "profile" (`long[]` rows), updated when credit changes or a limit is exhausted. Most instruments share the default relationship, so store the default bitset once plus sparse per-instrument overrides.
  2. Bound the walk: stop once the next price is beyond the aggressor's limit. Benchmark worst-case books with many crossed, ineligible orders.
- **DSL**: USD-equivalent exposure per (directional credit pool, value date), checked against the limit for that date. Instrument mappings select the pool, so usage is aggregated across every instrument sharing it.
- **NOP**: exposure per directional credit pool across all future value dates. Netting dimensions, sign, absolute-value checks, valuation and rounding remain gated on Q6b–Q6d; do not implement as a simple sum until agreed.
- **SWAP**: consumes credit at **both** the near and far leg value dates (confirmed for DSL; NOP treatment is Q6b).
- **NDF**: exposure measured on its settlement date. Settlement currency and formula are not yet agreed (Q10a); don't assume USD.
- **Headroom shortfall** (confirmed): a match that would breach credit is partially filled up to the remaining headroom, subject to execution-size constraints. Round down to the largest fill satisfying `fill >= minQty` and `(fill - minQty) % qtyIncrement == 0`, bounded by both orders' leaves and bilateral credit. If no valid execution size fits, skip that resting order and continue the walk. Apply minimum quantity and increments to ordinary fills too. After each fill, cancel any positive residual below min qty on both aggressor and resting order, with an explicit residual-below-minimum cancellation reason. A fully filled order has no residual cancellation. Headroom is calculated against every affected credit pool/date/leg; for a SWAP both legs must permit the fill. Q6f is answered; the treatment of residuals at or above min qty but off the entry increment remains Q6i.
- Each trade atomically checks and commits both directional relationships and every affected pool/date/leg. FOK simulation uses the same reservation logic and commits all fills or none. Define how offsetting trades change headroom and how rate updates revalue usage.
- Credit admin inputs (set or modify relationships and limits) are sequenced events like everything else, so they replay deterministically.
- End of day: roll value dates and release settled DSL buckets.
- If the spike favours sharding: first prove a credit lease protocol for DSL and NOP separately, covering grants, transfers, releases, exhaustion, recovery, limit reductions and valuation changes. Demonstrate that aggregate reservations plus usage cannot overrun an approved limit, and that offsetting signed NOP trades are handled correctly. If that proof or performance fails, revisit architecture before proceeding.

### Phase 4: Pegged, mid and fixing orders (≈2–3 wks)
- **Pegged** (Q4 answered):
  - Reference is **MID** or **NEAR-TOUCH** (same-side best: best bid for a buy) of the **visible top of book, excluding peg orders** (confirmed), ± discretion in pips. The book tracks a second best-visible price per side that ignores pegs. Pegs never move their own reference, so there is no feedback loop. Positive discretion means more aggressive (confirmed). Pegs may cross the spread, triggering an immediate match or joining a taker race when enabled. References use the unscreened book (confirmed).
  - Re-pegged at most once per **peg order throttle** (20ms in the expected configuration), on a fixed wall-clock grid, driven by a sequenced per-instrument timer (confirmed). Min TTL (5ms) still applies. Together these stop pegs being a cheap way to shadow market makers.
  - **A new peg is rejected** when the reference can't be computed (no visible bid/offer). **A resting peg is SUSPENDED** (removed from the book, not cancelled) whenever either side of the non-peg visible top of book is missing, regardless of peg reference. All pegs suspend in that case and are reinstated at the next peg tick once both sides return (confirmed).
  - Optional maxShow, so pegs can be partly or fully hidden.
  - Repricing and resumption lose time priority: assign a new priority sequence and join the back of the queue at the resulting price (confirmed). MID-reference prices between valid ticks round defensively: bids down and offers up to the order-entry price increment (confirmed). Rounding of discretion-derived off-increment prices remains Q4f.
- **Mid** (Q3 answered):
  - Kept in a separate mid book per instrument. Matches only other mid orders.
  - Effective limit = unscreened visible mid ± discretion (pips). **Positive discretion = more aggressive**, negative = less aggressive.
  - Trade price = `clamp(mid, sellLimit, buyLimit)`. This reproduces all four examples (93, no match, 94, 93).
  - Not eligible when there is no visible bid or no visible offer. The mid is recalculated on every visible top-of-book change, which can trigger mid matches.
  - **Mid TTL is a minimum resting time** (confirmed), using the same cancel/amend restrictions as min TTL. Mid execution prices must use the instrument order-entry price increment; fractional-tick prices are not allowed (confirmed). When the raw midpoint lies between valid ticks, round bids down and offers up to the order-entry price increment (confirmed). For a 92/93 market with increment 1 and zero discretion, the rounded bid is 92 and offer is 93, so there is no match. The order of midpoint rounding versus discretion and selection of a valid execution price when rounded limits overlap remain Q3b. Never emit an off-increment trade or a trade outside either effective limit.
- **Fixing** (Q5 partly answered; most sub-questions still open):
  - Fixing events are reference data (name, time, timezone, cut-off). Rates come from an **external API**, through a `clob-fixing` adapter that turns each rate into a sequenced `FixingRate(event, date, instrument, rate)` input.
  - Each fixing order is held in a per-(instrument, fixing event occurrence) book and matched only against other fixing orders.
  - **Lifecycle has two steps:**
    1. **Matching window → cut-off.** Orders are matched against the other side (assumed: continuously, in time priority, credit permitting; see Q5e). At the cut-off timer, any unmatched remainder is cancelled, and partly matched orders stay partly filled.
    2. **Cut-off → fixing time.** Matched quantity is locked in as *pending fills* with no price. When the `FixingRate` input arrives at fixing time, every pending fill is priced at the rate, and exec reports and trade records go out.
  - New states: matched-awaiting-rate. New exec report flavour: a fill with its price to follow, or a single report sent at fixing time (Q5f).
  - Credit has to be reserved at match time, before the rate is known, using an indicative rate (Q5b).
  - Still to define: credit treatment, a missing or late rate, and how the order identifies its event (Q5b, Q5c, Q5g). Also, fixing isn't in the "will support" order-type list, so confirm it's in scope for v1 (Q5d).

### Phase 5: Fairness races (≈2 wks)
- **Taker race** (scope confirmed: **one active race per book per side**):
  - An incoming order that would execute against resting liquidity joins the open race on that side, or opens one that lasts exactly the taker-race interval (expiry is a sequenced timer event).
  - Boundary contract (Q18): define the authoritative admission timestamp and stage, interval inclusion (proposed `[start, expiry)`), and precedence for equal-time orders and timers. Before admitting an input at or after a deadline, sequence all due expiry events according to that contract; a delayed timer must not extend the admission window. Record logical deadline and actual processing time separately, and measure expiry lateness under backlog.
  - Entries are held per account as a **ticket**, an ordered list of that account's orders. Each account has at most one entry.
  - At expiry, repeat rounds. In each round, pick accounts in random order without replacement and apply each account's next order to the book. Accounts with no orders left drop out. Stop when all tickets are empty.
  - **Cancels during a race**: if `queue-cancels` is true and a taker race is running on the book, cancels (from makers and takers) are held and applied after the race completes. Otherwise they apply immediately. (Is this per side or per book? Q7b.)
  - **Combined release (confirmed):** a queued cancel subject to both TTL and a race is released only after TTL has expired and the applicable race has completed. Check both conditions before applying it.
  - **Order types/TIF (confirmed):** market, IOC and FOK orders may participate. Check FOK atomically against the current book and credit when that order becomes eligible for application in the lottery, not at race admission; either fill it completely or cancel with no executions. If an order no longer crosses when applied, follow its TIF: IOC/FOK are cancelled; other orders follow their supported type/TIF resting rules. Market-order residual handling for resting TIFs still needs definition (Q7c).
- **Maker race** (scope confirmed: **one active race per book per side per price**): a race starts when a new price level is created (confirmed). Orders joining an existing level join that level rather than starting another maker race. At window close, use the same account-ticket lottery as a taker race: one ticket per account with orders in arrival order; each round randomly visits accounts without replacement and applies each account's next order to the book. Repeat rounds until all tickets are empty. Orders receive book priority in that application order (confirmed). Pending maker-race orders are applied at lottery resolution. Still define which arrivals join an active lottery and recreation of an emptied level (Q8).
- RNG: a fast seeded PRNG (SplitMix64 / xoroshiro128+), seeded per race from the journal so replay is exact. It must be fair, and auditors may ask to see the seed scheme.

### Phase 6: Sequencing, journaling and session lifecycle (≈2 wks)
- Sequencer stamps (seq, time). Aeron Archive journal. Snapshot plus replay recovery. Sized for a 2M events/sec peak.
- Timer service emits sequenced events for GTT, end of day, race expiry, peg throttle, min TTL expiry (queued cancels), fixing cut-offs, fixing times and MD ticks.
- Session lifecycle: open, close, end of day (cancel DAY orders, roll GTC orders to the next session with priority kept, value-date roll), and trading halts.
- **Determinism test**: replay a production-like journal twice and check the outputs are byte-identical.

### Phase 7: FIX order entry (≈2–3 wks)
- Artio acceptor: Logon with user authentication, NewOrderSingle (D), OrderCancelRequest (F), OrderCancelReplaceRequest (G), OrderMassCancelRequest (q), ExecutionReport (8), OrderCancelReject (9), BusinessMessageReject.
- Rules of engagement doc: custom tags for discretion, maxShow, peg reference (MID / NEAR-TOUCH), fixing event and tenor. Exec report states for peg SUSPENDED, race-pending, queued-cancel (race or min TTL), and fixing matched-awaiting-rate. OrderCancelReject reason for "rejected inside min TTL".
- Cancel on disconnect (configurable), throttling per session, and resend/replay of exec reports from the journal.

### Phase 8: Market data (≈3–4 wks)
- **MD service** keeps its own book and credit replica, built from versioned engine output. Replicate order visibility, credit relationships, pool usage, valuation and other state required by the agreed screening rule. Publishing runs outside the matching thread.
- **Cycle**: aligned to wall clock (`00:00:00.000`, `.005`, …). The sequencer emits a cycle marker under the agreed timer/input precedence rule. Each engine publishes a marker after all effects of preceding inputs, including credit changes. The MD service drains through that marker before freezing the corresponding immutable book-and-credit snapshot. Never freeze an arbitrary lagging replica and label it current. With shards, define a coordinated cut and record its per-shard sequence vector. Every subscriber in a cycle uses the same cut. Record scheduled boundary, marker processing time and replica lag; backlog consumes the delivery budget rather than moving its start.
- **Aggregation** (confirmed by the corrected example): bucket by MD price increment. **Bids round down** to the MD increment; offers round up (confirmed). Output is limited to the instrument's subscription levels. Top-of-book and depth subscriptions.
- **Unscreened**: one computation per instrument, then multicast over UDP (ITCH / FAST) and fanned out over TCP FIX (MarketDataSnapshot W / IncrementalRefresh X).
- **Screened**: per account, show only orders from counterparties the account is bilaterally eligible to trade with. To keep inside the interval:
  - screened UDP delivery may use per-account unicast (confirmed); retain the same per-instrument rotation and cycle snapshot across transport subscribers,
  - group accounts into **screening equivalence classes** (identical full screening state → identical book; eligibility bitsets alone suffice only if screening ignores available quantities/headroom) and compute once per class,
  - compute incrementally where possible, and only for instruments that changed this cycle.
- **Fair distribution**: rotate the subscriber send order by one each cycle, so the first subscriber in interval 1 is last in interval 2. Maintain a separate rotation per instrument (confirmed, Q9).
- **Delivery acceptance**: measure scheduled-boundary-to-receipt at every test receiver within the agreed network envelope, including sequencing, replica catch-up, screening, serialization, queues and transport. Snapshot-to-last-send is a diagnostic, not the receipt SLA. Set target subscriber counts, subscription mix, depth, MD intervals, hardware/network envelope and clock-synchronization accuracy before accepting capacity. Define slow-consumer detection, bounded queues, recovery and overload/admission policy (Q19); demonstrate the target with receiver-side load tests and monitor both send and receive latency where observable.
- Late joiners get a full snapshot, then deltas. Sequence numbers allow gap detection on UDP, plus a recovery/retransmit channel.

### Phase 9: Performance hardening (ongoing, focus ≈2 wks)
- JMH microbenchmarks: add, cancel, match, sweep, crossed-ineligible walk, peg reprice storm (all pegs on a book re-pegging in one tick), race lottery with many accounts, screened MD build.
- End-to-end harness: FIX in → exec report out, measuring p50 / p99 / p99.9 / max with coordinated-omission-corrected load (HdrHistogram). Load target: 200 books × 10k updates/sec.
- Allocation profiling (async-profiler `-e alloc`) with a CI check that the hot path allocates nothing after warmup.
- OS tuning: isolcpus, IRQ affinity, huge pages, C-states off. JVM flags, GC choice and AOT/warmup strategy.

### Phase 10: Operations (≈2–3 wks)
- HA with Aeron Cluster (3 nodes). Admin API for refdata, credit, fixing events, halts and kill switch.
- Drop copy, trade reporting, audit trail, metrics (Prometheus via a non-hot-path agent), runbooks.

**Rough total: 5–7 months for one or two engineers to reach production quality**, plus about 3–4 weeks if the spike shows credit leasing and sharding are needed. A functional engine (Phases 0–5, in-process, no FIX) is ≈2.5–3 months.

---

## 4. Testing strategy

1. **Scenario tests** from every example in the requirements, written first in the testkit DSL. That covers the visibility examples, the four mid examples, the MD bucketing example, the taker-race lottery, and the queued-cancel behaviour.
2. **Property-based tests** (jqwik) over random order flow, checking these invariants:
   - quantity is conserved (filled + leaves + cancelled = original),
   - no immediately executable pair remains after matching reaches quiescence, under the agreed visibility, credit, minimum-fill, order-type and race rules; relationship eligibility alone does not imply executability,
   - credit usage never exceeds its limit (including across shards, if sharded),
   - every fill respects both orders' limits,
   - priority ordering holds, including the VISIBILITY exhaustion rule (no hidden fill before the visible top level is exhausted),
   - no peg order rests while its reference is unavailable, and no peg's price depends on another peg,
   - every entry and execution satisfies minimum quantity and `(quantity - minQty) % qtyIncrement == 0`; positive residuals below min qty are cancelled on either side, and other residuals obey Q6i,
   - participant cancels obey min TTL and race release rules, with only explicitly approved administrative/lifecycle exceptions (Q14a/Q15),
   - each account has at most one lottery entry per race,
   - each refreshed iceberg slice receives a new priority sequence and ranks behind existing orders in the same queue at its price.
3. **Determinism**: the same journal gives the same output, including lottery results.
4. **Reference model**: a slow, obviously-correct matcher written for clarity. Fuzz the fast engine against it and diff the outputs.
5. **Latency regression gates** in CI, using JMH thresholds on dedicated hardware.
6. Boundary and recovery scenarios: equal-time timer/order inputs, delayed expiry, race backlog, credit-only changes in MD, lagging replicas, sharded cycle cuts, shared-pool usage, atomic bilateral failures, limit reductions and valuation updates. Receiver-side MD acceptance tests use the agreed capacity envelope.
7. FIX conformance tests (QuickFIX/J as the client simulator).

---

## 5. Key risks

| Risk | Mitigation |
|---|---|
| 2M events/sec aggregate exceeds one engine thread | Phase 0 spike; shard-ready engine; credit leasing as the scale-out path |
| Crossed books with many ineligible orders → unbounded match walks and latency spikes | Eligibility bitsets, worst-case benchmarks, possibly per-level eligibility summaries |
| Credit-clipped partial fills interacting with min qty and increments, giving odd residuals | Q6f confirmed; property tests that entry and execution sizes meet min/increment rules and positive residuals below minimum are cancelled; settle Q6i for other residuals |
| Fixing fills priced after the match (missing or late rate, credit reserved at an indicative rate) | Settle Q5b and Q5g; explicit pending-fill state with its own recovery tests |
| Screened MD cost grows with accounts × instruments × levels and misses the interval | Equivalence classes, incremental builds, a separate MD process and cores, a load test at target subscriber counts |
| Credit model complexity (value dates, swaps, NDFs, USD conversion, per-instrument overrides) | Isolate it in `clob-credit` behind a `CreditCheck` interface with its own test suite. Get the rules confirmed by business users early. |
| Remaining race semantics unclear → rework | Settle Q7b, Q8, Q18, Q20 and Q22 before Phase 5 |
| GC or JIT jitter | Zero-alloc discipline, CI allocation check, warmup, Azul/ZGC evaluation |

---

## 6. Open questions / spec issues

Status key: ✅ answered · 🟡 partly answered, follow-ups listed · ❓ open.

1. 🟡 **Visibility matching.** The example now uses visible bids at 92 and 91 and a hidden bid at 93. Confirmed: hidden liquidity unlocks once the visible top-of-book level is exhausted; a partial fill does not unlock it. The supplied examples are captured in Phase 2. Remaining edge cases are in Q1c.
   - ✅ 1a. Fixed: sell 1000 at 92 matches at 92 under VISIBILITY, and at 93 otherwise.
   - ✅ 1b. PRICE mode confirmed: hidden liquidity is matched on price.
   - ❓ 1c. **VISIBILITY edge cases**: the visible top-of-book level must be exhausted before hidden liquidity unlocks (confirmed). Is that level defined by the account-screened or unscreened book? If an ineligible or credit-constrained order remains at that level, does it prevent unlocking? Does an iceberg refresh keep that level unexhausted? Is unlock state reset for each new aggressor?
2. ✅ **MD bucketing.** Corrected to 1.0000×1000, 0.9995×2000, 0.9990×1000, which confirms bids round down. Offers round up (confirmed).
3. 🟡 **Mid orders.** Answered: discretion is in pips, positive means more aggressive, and `clamp(mid, sellLimit, buyLimit)` fits all four examples.
   - ✅ 3a. **Mid TTL** is the minimum resting time for a mid order, like min TTL.
   - ✅ 3b. **Superseded (2026-11-07):** mid orders may match at one decimal place beyond the instrument's precision, so a 92/93 market with zero discretion matches at 92.5 and no rounding is needed. Previous note, kept for history: fractional-tick prices are not allowed. Use order-entry increments and round midpoints defensively: bids down, offers up (confirmed). A 92/93 market with increment 1 and zero discretion therefore does not match. Still define whether rounding precedes discretion, how discretion-derived off-increment limits are rounded, and which valid execution price is used when rounded limits overlap around an off-tick raw midpoint.
4. 🟡 **Pegged orders.** Core semantics confirmed; off-increment price rounding remains Q4f. Answered: reference is MID or NEAR-TOUCH ± discretion, from the visible top of book excluding peg orders. The peg throttle is 20ms. New pegs are rejected and resting pegs suspended when there's no visible price.
   - ✅ 4a. Positive discretion means more aggressive. Pegs may cross the spread and trigger a match or taker race.
   - ✅ 4b. Repricing and resumption both lose time priority and rejoin at the back of the queue at the resulting price.
   - ✅ 4c. If either visible side is missing, all pegs suspend, including NEAR-TOUCH pegs.
   - ✅ 4d. Reference prices are unscreened. Peg references continue to exclude all peg orders.
   - ✅ 4e. Peg throttling follows a fixed wall-clock grid, with sequenced per-instrument ticks.
   - ❓ 4f. **Peg price rounding:** MID references between ticks round defensively: bids down, offers up (confirmed). Still define rounding order relative to discretion and rounding of any resulting off-increment peg price, including its interaction with crossing/taker-race admission.
5. 🟡 **Fixing orders.** Answered: rates come from an external API at fixing time. Fixing events are defined as name, time, timezone and cut-off. Unmatched orders are cancelled (or left partly filled) at cut-off.
   - 🟡 5a. **Imbalance allocation**: the cut-off rule implies matching happens before cut-off, with the residual imbalance cancelled. See 5e for how it is allocated.
   - ❓ 5e. **When does matching happen?** Is it continuous (a fixing order matches an eligible opposite order as soon as it arrives, in time priority), or a single uncross at cut-off (where allocation could be time priority, pro-rata or lottery)? We've planned for continuous matching.
   - ❓ 5f. **Reporting**: send a fill exec report at match time with the price to follow, then a price update at fixing time? Or a single exec report at fixing time? Can a matched fixing order be cancelled before cut-off?
   - ❓ 5g. **Missing or late rate**: what happens if the external rate doesn't arrive, or is corrected after publication? Do pending fills stay open, get busted, or wait for a manual rate?
   - ❓ 5b. **Credit**: the price is unknown until the fix arrives. Is credit checked at order entry with an indicative rate, at the fix, or both?
   - ❓ 5c. Does the order name a fixing **event** (e.g. "WMR 16:00 London"), or just a time? When can orders be entered, cancelled or amended relative to the fixing time? What TIF do they carry?
   - ❓ 5d. Fixing isn't in the "the CLOB will support" order-type list. Is it in scope for v1?
6. 🟡 **Credit.** Answered: eligibility is bilateral, there are four relationship types with per-instrument overrides, SWAPs consume credit on both legs' value dates, crossed books are allowed, and a fill that would breach credit is partially filled, subject to min quantity.
   - ✅ 6a. Partial fill up to the remaining headroom, subject to min quantity.
   - ✅ 6f. **Execution sizes and small residuals**: minimum quantity and quantity increment apply to order entry and every execution. Credit-clipped fills are rounded down to a valid execution size. Any positive residual below min qty is cancelled, for both the aggressor and resting order.
   - ❓ 6i. **Off-increment residuals at or above minimum**: entry and fill validity currently use `(qty - minQty) % increment == 0`. If min qty is not a multiple of increment, subtracting a valid fill can leave an above-minimum residual that fails the entry increment. May it remain, must it be cancelled, or should instrument validation require `minQty % increment == 0`?
   - ❓ 6b. **NOP for SWAPs**: the near and far legs offset each other in net position. Does a swap consume NOP (gross of both legs, or net), or only DSL?
   - ❓ 6c. **NOP netting**: net per currency, per currency pair, or a single USD figure? Are limits checked on the absolute net?
   - ❓ 6d. **USD conversion**: where do the rates come from, how often do they update, and which rate applies (mid, or the trade price for the traded pair)?
   - ❓ 6e. **Screened book**: hide orders only where there's no relationship, or also where the headroom is used up?
   - ❓ 6g. **Shared pools**: when a relationship applies to several instruments, do they share one allowance or have independent allowances? Define pool identity and precedence for default/override mappings.
   - ❓ 6h. **Credit lifecycle**: specify atomic bilateral/multi-leg reservations, limit reductions below usage, conversion-rate revaluation, offsetting trades, and treatment of outstanding fixing reservations. What prevents further exposure while a pool is over its revised limit?
7. 🟡 **Taker race.** Answered: one race per book per side; lottery by account ticket; cancels queued while a race runs if `queue-cancels` is true.
   - ✅ 7a. Market, IOC and FOK orders may join a race. FOK is checked when the order becomes eligible for application in the lottery, using current book and credit state. An order that no longer crosses follows its TIF; IOC/FOK are cancelled.
   - ❓ 7c. **Market residuals:** for market orders submitted with a resting TIF, is an unfilled residual cancelled or is that type/TIF combination rejected?
   - ❓ 7b. Are queued cancels held for a race on either side of the book, or only for cancels of orders on the side the race is consuming? Do queued cancels include the taker's own race orders (so a taker can't withdraw from a race)? Are amends queued too?
8. 🟡 **Maker race.** Answered: one race per book per side per price.
   - ✅ **Window start:** a maker race starts when a new price level is created. Otherwise, an order joins the existing level without opening another race.
   - ✅ **Allocation:** same account-ticket lottery and rounds as taker races. One ticket per account, FIFO orders on each ticket; each round randomly visits accounts without replacement and applies one order per account. Repeat until all orders have been applied to the book, assigning priority in application order.
   - ❓ **Admission and lifecycle:** do orders arriving at a level with an active maker race join its lottery or queue behind it? How is a pending level represented before lottery application? If the level empties and is recreated during the window, is the race retained or restarted?
9. ✅ **MD rotation**: rotate subscribers by one each cycle, independently per instrument (confirmed).
10. ❓ **Instrument key**: is (symbol, tenor) enough, or does SWAP need near+far tenors (e.g. SPOT/1M) and NDF need a fixing date?
    - ❓ 10a. **Product economics**: SWAP side and quote convention are confirmed: BUY/SELL refers to the far leg, and price is swap points (near leg is opposite). Still define points scaling, signed/zero price validation, near-leg rate source, leg notionals and far-leg outright calculation; **NDF order pricing is confirmed as outright (spot + points).** Still define NDF fixing source, settlement currency and settlement formula. Specify trade-event fields and reference-data versioning.
11. 🟡 **Non-functional targets.** Answered: 200 independent books, each scaling to 10,000 updates/sec.
    - ❓ Is 2M/sec in total (all books at peak together) the design target, or is there a lower aggregate peak? What are the latency targets (e.g. p99 < 10µs wire-to-wire)? How many accounts and MD subscribers, and what range of MD intervals?
12. 🟡 **Messaging**: unicast UDP is acceptable for screened market data (confirmed). Still define the required protocol/transport combinations (FIX, FAST, ITCH over TCP/UDP) and whether screened UDP support is mandatory for v1.

**New questions raised by the updated requirements**

13. ✅ **Peg self-reference.** The peg reference is the visible top of book excluding peg orders.
14. ✅ **Min TTL semantics.** A cancel inside the TTL is rejected or queued, depending on the instrument's `reject-on-ttl` flag.
    - 🟡 14a. **Confirmed:** amends (cancel/replace) follow TTL restrictions and reset TTL when applied. Disconnect and administrative cancels bypass TTL constraints. **Still open:** do participant mass cancels follow TTL, and does TTL apply to IOC/FOK (currently assumed no)? Race-queue exemptions for disconnect/admin cancels are separate from TTL exemptions (Q7b/Q15).
15. 🟡 **Queue-cancels and min TTL together.** **Confirmed:** a queued cancel must wait for both TTL expiry and completion of the applicable race; apply it only when both conditions are satisfied. TTL exemptions for disconnect and administrative cancels remain as defined in Q14a. **Still open:** if `reject-on-ttl` is true, is a cancel inside TTL rejected even when a race would otherwise queue it? Race-queue exemptions for disconnect/admin cancels remain Q7b.
16. 🟡 **Iceberg refresh.** Confirmed: a refreshed slice loses time priority and joins the back of the queue at its price with a new priority sequence. Still open: in VISIBILITY mode, is the hidden remainder treated as hidden liquidity at that price, and does immediate slice refresh prevent the visible level from being considered exhausted (Q1c)?
17. ❓ **Typos to confirm**: "marker order" = market order; "match with order mid/fixing orders" = other mid/fixing orders. ("TTF" is now fixed.)

18. ❓ **Time and race boundaries**: where is the authoritative admission timestamp assigned? Are windows half-open `[start, expiry)`? Which event wins at equal timestamps, and how are due timers sequenced during backlog? Specify wall-clock adjustment behaviour and measure processing lateness separately from logical deadlines.
19. ❓ **MD delivery contract**: define subscriber counts and subscription mix, intervals, supported network/receiver envelope, clock accuracy, and whether “receive” means packet arrival or application consumption. Define slow-consumer and overload policy, and receiver-side acceptance tests. For sharding, approve a coordinated book-and-credit cycle cut.

**Raised in the Revision 5 consistency pass**

20. ❓ **Taker race trigger.** What makes an order "cross" and enter a race?
    - Is it the unscreened best price, the visible best price, or only liquidity the account could actually execute against (eligible, with headroom)?
    - Under VISIBILITY, does an order that crosses only hidden liquidity (e.g. sell 1000 @ 93 against hidden id 1) open a race, even though it couldn't match?
    - Are mid orders subject to taker or maker races?
21. ❓ **Mid TTL vs min TTL.** For mid orders, does mid TTL replace min TTL or apply in addition? Does `reject-on-ttl` govern cancels inside mid TTL as well?
22. ❓ **Peg repricing side effects.**
    - Does an engine-driven reprice reset the order's min TTL, as an amend does?
    - Does a peg tick that leaves the price unchanged keep priority?
    - A reprice that crosses the spread can join a taker race (4a). Does the peg leave the book while its race entry is pending?
23. ❓ **"Orders are not eligible to match if no visible bid or offer."** This sentence sits in the mid section. Does it apply to mid orders only (as planned), or to every order type? If it applies to all, a one-sided book could never trade.
24. ❓ **Session and end of day.**
    - What is the end-of-day time and timezone (e.g. 17:00 New York)?
    - Do rolled GTC orders keep priority (currently assumed)?
    - How are rolled orders treated for the value-date change and any credit reservations?
    - What happens to DAY/GTT expiry that falls inside a race window, a TTL, or a maker-race lottery?
25. ❓ **Parameter naming.** Are "market data throttle" (instrument data) and "market data interval" (MD section) the same parameter? Likewise "market data subscription levels" and "instrument market depth levels"?
26. ✅ **Requirements sync (done 2026-11-08).** `requirements.md` now contains the confirmed answers below. Unconfirmed defaults are listed separately in its §13 and open questions in §14. Answers folded in:
    - the VISIBILITY exhaustion rule,
    - offers round up,
    - the meaning of mid TTL, and mid rounding,
    - peg semantics (4a–4e),
    - iceberg refresh losing priority,
    - TTL rules for amends, disconnect cancels and admin cancels,
    - combined release of queued cancels,
    - race order types,
    - the maker-race window and allocation,
    - MD rotation per instrument,
    - unicast UDP for screened data,
    - mid trades at one extra decimal place (2026-11-07),
    - SWAP and NDF price conventions,
    - min-qty residual cancellation.

    Note that Q3b and Q4f are the same question (whether rounding to a tick happens before or after discretion is applied), so one answer can cover both.

**Resolved in Q&A (2026-11-08)**
- Q1c: confirmed: the visible top level is screened per aggressor. Noted for further consideration: clients looking at the unscreened price may behave differently.
- Q1c/Q16: confirmed: refreshed iceberg slices count as displayed liquidity.
- Q27: confirmed: an aggressor takes more than a slice through a match cycle (tip, others in the queue, tip again). Hidden liquidity unlocks only after the whole top level is drained. A non-zero show below a valid execution size is rejected.
- Q28: confirmed: under VISIBILITY a non-IOC/FOK order that can't reach the visible top rests, and a crossing hidden order trades with it at once at the hidden order's price. The "sell 1000 at 93 will not match" example applies to IOC/FOK.
- Q7c: confirmed: market orders accept IOC and FOK only.
- Q28 scope: confirmed: only displayed quantity on the resting order triggers the hidden trade.
- Amend priority: confirmed: lost on a price change or quantity increase, kept on a quantity decrease.
- Q30: confirmed: the gateway guarantees client order ids are unique for the whole session; the engine keeps its live-order check.
- Q18: confirmed: timers fire before same-time inputs, and race windows are half-open.

- Q6b: confirmed: a SWAP's legs net to zero for NOP.
- Q6c: confirmed: a single signed USD figure per line, limit on the absolute value.
- Q6d: confirmed: a reference rate ticking about every 10 seconds. New trades use the latest rate; recorded usage is not revalued.
- Q6g/Q32: confirmed: a pool can be shared by giving accounts of the same legal entity. Each relationship still names a receiving account.
- Q6h: confirmed: a limit cut below usage blocks exposure-increasing trades and allows reducing ones.
- Q29: confirmed: an aggressor reaching its own account's or entity's resting order has its remainder cancelled, with an explicit cancel reason.
- Q6i: confirmed: min quantity must be a multiple of the quantity increment.
- Q31: confirmed: a credit change never produces a trade by itself.
- Q33: confirmed: value dates come from an external service as sequenced inputs. (Rate source: Q6d.)
- Missing USD rate or value date: confirmed: the instrument halts and resumes automatically when the data arrives.
- Q3b/Q4f: confirmed: discretion on the raw reference, then round (pegs only; mid isn't rounded).
- Q35: confirmed: pegs suspend at the next peg tick.
- Q22: confirmed: an engine reprice doesn't restart min TTL, and an unchanged tick keeps priority.
- Q23: confirmed: the no-visible-bid-or-offer rule applies to mid orders only.
- Q34: confirmed: the mid excludes peg orders.
- Mid increment: confirmed: mid trades ignore the order price increment.
- Q36: confirmed: pure time priority among mid orders.
- Q5d: confirmed: fixing orders are in scope for v1.
- Q5e: confirmed: continuous matching before cut-off, in time priority.
- Q5c: confirmed: the order names an event, must be DAY, GTC or GTT, and can be cancelled but not amended.
- Q5b: confirmed: credit is reserved at match time.
- Q5f: confirmed: a match is reported at once without a price, then as a trade when the rate arrives.
- Q5g: confirmed: pending matches wait for a rate.
- Q21: confirmed: mid TTL replaces min TTL for mid orders.
- Mid reference: confirmed: the unscreened book.
- Q20: confirmed: "would execute now"; mid and fixing orders aren't subject to races.
- Q7b: confirmed: only requests on the side being taken are held, plus a taker's own waiting race orders.
- Q8: confirmed: later new orders at a maker race's price join it; pending orders are off the book. Under VISIBILITY, a hidden order can trade with such an order only after the race resolves.
- Queued cancels: confirmed: an order can still fill while its cancel is queued.
- Q37: confirmed: a new order or an amend creating a new level starts a maker race; a peg reprice doesn't.
- Q38: confirmed: a halt cancels orders waiting in races. A pre-open phase accepts orders, applied through the lottery at the open.
- Q6e: confirmed: screening is headroom-aware.
- Q38 pre-open: confirmed: one lottery per book at the open; cancels and amends allowed with no TTL.
- Q24 (partly): confirmed: end of day is 17:00 New York, DST-aware; rolled GTC orders keep priority.
- Q15: confirmed: reject-on-ttl rejects inside TTL even during a race.
- Q14a: confirmed: participant mass cancels obey TTL per order.

**Code changes required by these answers (not yet made):**
- Q28: today the resting order and the hidden order stay locked.
- Q7c: today a market order with a resting TIF is accepted and its remainder cancelled.
- Q6g/Q32: reference data needs a legal-entity concept, and the credit model must allow a pool across several giving accounts.
- Q29: new self-trade check in the matching walk and FOK dry run, a new cancel reason, and FIX mapping. Today such orders are skipped.
- Q7b: hold only requests on the side being taken. Today requests are held book-wide for a race on either side.
- Q37: amends that create a new level start a maker race. Today only new orders do.
- Q38: a halt cancels race-waiting orders (today they resolve at reopen). Add a pre-open phase that accepts orders and applies them by lottery at the open (today rejected).
- Q6e: headroom-aware screening. Needs credit usage in the market data replica, and will weaken view sharing, so the over-budget worst case (Q42) gets harder.
- Q24: an end-of-day schedule at 17:00 New York. Today end of day needs an admin input.
- Missing data: halt the instrument and auto-resume on arrival. Today DSL and NOP lines are blocked and unlimited lines still trade.

**Raised during implementation**

27. ❓ **Display slices and min quantity.** Every fill must be at least min qty (confirmed). But an aggressor can't take more than an iceberg's displayed slice. The engine therefore makes two assumptions:
    - a non-zero show must itself be a valid execution size (at least min qty, on the increment);
    - if a fill leaves a slice below min qty, the slice is refreshed and goes to the back of the queue.

    Is that right? The alternative is to let a fill take more than the displayed slice.
28. ❓ **Locked or crossed books with hidden orders (VISIBILITY).** A sell at 93 can't match a hidden bid at 93 (by the confirmed rule), so if it isn't IOC it rests. The visible offer then sits at 93 against the hidden bid at 93, and the hidden order never trades as an aggressor. Is that intended, or should a resting hidden order match an incoming visible order at its price?
29. ❓ **Self-trade prevention.** The spec doesn't mention it. Today self-trades are blocked only because an account holds no credit line to itself; there is no dedicated rule. Should same-account (or same-user) matches be prevented, and how: cancel the resting order, cancel the incoming one, or cancel both?
30. ❓ **Order id uniqueness.** The engine rejects ids that duplicate a *live* order. Should the gateway or sequencer guarantee ids are unique for the whole session (recommended), or should the engine also track ids of finished orders?
31. ❓ **Credit changes and crossed books.** If a credit change (a new relationship, a raised limit or a value-date roll) makes crossed resting orders tradable, should they match straight away? If so, which order is the aggressor and at what price? Today they stay crossed until one of them is amended or a new order arrives.
32. ❓ **Who owns a credit pool.** The engine assumes a line (pool) is credit extended by *one* account, shared across that account's counterparties and instruments. Is that how pools work? Should a pool ever span several giving accounts, for example accounts in the same legal entity?
33. ❓ **Value dates and USD rates source.** For now both arrive as sequenced inputs. Should the CLOB compute value dates from holiday calendars itself (Phase 1 plan), or receive them from a reference-data service? Which rate source and update frequency should credit use (Q6d)?
34. ❓ **Mid reference.** Mid orders use the same reference as pegs: the visible top of book *excluding peg orders*. That stops pegs moving the mid. Should the mid include visible peg orders instead?
35. ❓ **When pegs suspend.** Pegs are checked only on peg ticks. So if one side of the book empties between ticks, resting pegs keep their last price for up to one throttle interval (20ms) before suspending. Is that acceptable, or must they suspend immediately?
36. ❓ **Mid priority.** Among mid orders that cross, priority is pure time. Should a more aggressive discretion win over an older but less aggressive order?
37. ❓ **Maker races and amends.** Only newly submitted orders start or join a maker race. Should an amend, or a peg reprice, that creates a new price level start one too? And can orders waiting in a race be amended (today they can only be cancelled)?
38. ❓ **Halt and pre-open behaviour.** Orders are rejected outside OPEN, not queued for the open. Races that expire during a halt resolve at reopen, and resting orders stay on the book. Should halts instead cancel pending race orders? Is a pre-open order-entry phase (accept orders, no matching) needed?
39. ❓ **Snapshots.** Recovery replays the whole journal. Since GTC orders live across sessions, should we snapshot engine state (for example, at each end of day) and start new journals from it? Is a recovery-time target set?
40. ❓ **Journal durability.** Appends are durable against a process crash but not a power loss until flushed. Options: flush every N ms (latency cost), or rely on replication (Aeron Cluster, Phase 10). Which?
41. ❓ **FIX session persistence.** Sequence numbers and the resend store are held in memory, so clients must reset sequences after a gateway restart. Should they persist (for example, rebuilt from the journal and engine output) so sessions survive a restart without a reset? Do we need TLS and hashed credentials for v1?
42. ❓ **Market data scale target.** How many screened subscribers per instrument, and how many books change per 5 ms interval, at peak? The worst case measured here (200 books all changing, 64 subscribers each) takes about 44 ms per cycle on one thread. That decides whether we need multi-threaded publishing, a larger interval for screened data, or both.
43. ❓ **Automatic failover.** Promotion is a manual (or external-arbiter) decision, with no fencing, so running two primaries would split history. Is manual failover acceptable for v1, or is Aeron Cluster (Raft: automatic election and fencing, synchronous replication) required? Raft costs one network round trip to a majority on every input.
44. ❓ **Journalling market data ticks.** MD ticks are journalled so every consumer cuts at the same point. At 5 ms that is about 200 records/s, roughly 0.7 GB/day, even when idle. Options: journal ticks only while there are subscribers; derive cuts from a sequence-number grid instead; or accept it and rotate journals daily (with snapshots, Q39).
