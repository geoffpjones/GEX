# Project review: FX CLOB

Reviewed 2026-11-08 at commit `11b848b` (`Add Phase 10 operations: node, admin API, ops, warm standby, audit`). Scope: `requirements.md`, `PLAN.md`, build/configuration, operational documentation, production modules, test strategy and benchmark evidence. This review changes documentation only.

## Assessment

The project is a substantial working prototype with a well-chosen deterministic matching core. The separation of matching from clocks and I/O, fixed-point arithmetic, intrusive queues, scenario DSL, replay comparisons and measured allocation checks are strong foundations. The implementation is readable for performance-sensitive Java and already covers considerably more than basic limit matching.

It is **not ready for production acceptance**. The most important blockers are order identity and ownership across FIX reconnect/restart, circular backpressure, expiry while awaiting a race, credit accounting failure atomicity, and recovery that depends on unversioned external configuration. Several operational claims exceed what the current lifecycle and HA code guarantees. Passing the existing tests establishes useful core behaviour, but does not establish safe recovery or sustained end-to-end capacity.

Severity in this document:

| Priority | Meaning |
|---|---|
| P1 | Resolve before production: incorrect trading/accounting, lost control or reporting of orders, unsafe recovery, or a service-wide stall. |
| P2 | Correctness or operational gap that needs a defined fix or explicitly restricted supported configuration. |
| P3 | Maintainability, documentation or tooling improvement. |

Findings marked **reproduced** were exercised with temporary probes; **code inspection** findings follow from the cited control flow. Specification decisions and missing planned functionality are listed separately from implementation defects.

## Verification performed

- Ran `./gradlew test --rerun-tasks`: **238 tests, 0 failures, 0 errors, 0 skipped**. The preceding ordinary `test` invocation reused cached results, so the forced run was necessary.
- Ran **nine additional temporary probes**: five against engine/credit behaviour, three against the FIX gateway, and one against journal recovery. All reproduced the behaviours described below. Their assertions deliberately check the current defective behaviour; a passing probe is evidence of a defect, not an acceptance test passing.
- Probes were supplied through `/tmp/clob-review-probes/init.gradle` and temporary source directories, without modifying repository source or test files. Command: `./gradlew test --init-script /tmp/clob-review-probes/init.gradle`.
- Reviewed the existing benchmark results; did not rerun production-scale load tests, simulate power loss, prove lottery statistics independently, or test network delivery on separate hosts. Performance numbers below are the repository's reported measurements, not new measurements.
- The Gradle commands required access to the existing Gradle cache outside the workspace sandbox. No dependency versions or build configuration were changed.

## Prioritized implementation findings

### R01 — P1: gateway order IDs restart from the same value

**Evidence:** `clob-fix/src/main/java/clob/fix/FixGateway.java:107`, `:485`, `:714`; `clob-app/src/main/java/clob/app/ClobNode.java:42`, `:107`. **Code inspection.**

Every gateway starts `nextOrderId` at `(gatewayId << 40) + 1`; node wiring always supplies gateway ID 1. Recovery restores live engine orders but neither restores the gateway counter nor allocates a new durable epoch. The first post-restart order can therefore collide with a recovered live order. If the old order has completed, its ID can be reused in later audit history. A collision is particularly dangerous because the gateway has already installed the new `GatewayOrder` under that ID: later events about the recovered order can be attributed to the new request.

**Fix:** allocate order IDs from a durable monotonic sequence or a persisted gateway epoch/counter. Restore the high-water mark before accepting traffic, including after promotion. Add an actual FIX restart/failover test with live GTC orders; the existing app recovery tests submit small, manually chosen IDs directly to the pipeline and miss this path.

### R02 — P1: reconnect and restart orphan live FIX orders

**Evidence:** `FixGateway.java:221`, `:554`, `:719`, `:803`; `clob-fix/src/main/java/clob/fix/GatewayOrder.java:12`; `clob-fix/src/main/java/clob/fix/FixSession.java:38`; `ClobNode.java:54`. **Reproduced for reconnect; restart follows the same missing reconstruction.**

`GatewayOrder` belongs to a socket's `FixSession`, and ClOrdID lookup uses that connection's map. A new connection receives an empty map. With cancel-on-disconnect disabled, the old order remains in the engine but a reconnecting client cannot cancel or replace it by its original ClOrdID. Future reports still go to the old session object. Restart/promotion loses the entire gateway order map, while engine replay output is suppressed. Fully matched fixing orders awaiting a rate are also absent from the live engine order map, making reconstruction from live orders alone insufficient.

**Observed:** submit GTC order `LIVE`, log out, reset-logon to the same configured session, and cancel `LIVE`: the gateway returns `Unknown order` while the engine still has one live order.

**Fix:** separate durable logical session/order ownership from the current transport connection. Journal sufficient FIX identity and request metadata, rebuild outstanding orders and pending fixing executions, and reattach ownership on reconnect. A FIX sequence reset does not solve application-order recovery. Include resumed execution reporting and cancellation in acceptance tests.

### R03 — P1: cancel/replace correlation is overwritten

**Evidence:** `FixGateway.java:559–600`, `:747–783`; `GatewayOrder.java:20–21`; `clob-sequencer/src/main/java/clob/sequencer/InputCodec.java:164`, `:191`. **Duplicate-ID failure reproduced; overlapping-request failure identified by inspection.**

New orders check duplicate ClOrdID, but cancels and replacements insert their ClOrdID without checking uniqueness. A cancel can overwrite another live order's ClOrdID mapping. Separately, each order has only one mutable pending request slot. Multiple requests read before the engine output is consumed overwrite that slot. Inputs and request-result events identify only the order and request type, so the gateway cannot reliably correlate superseded, rejected or queued results to the right request. A reject for an earlier replacement can clear the newer pending replacement, and a replace acknowledgement can install the wrong ClOrdID.

**Observed:** create orders `A` and `B`; cancel `A` using ClOrdID `B`. The cancel succeeds and overwrites `B`'s mapping. A later cancel of original order `B` returns `Unknown order`, leaving it live.

**Fix:** require session-wide unique IDs for every application request and carry a request identifier through input, pending engine state and output. Either explicitly serialize requests per order or maintain a proper pending-request structure. Test pipelined replace/replace, replace/cancel, rejection of the second request, and completion while a request is queued.

### R04 — P1: bounded queues form a circular wait under load

**Evidence:** `FixGateway.java:658`, `:686`; `clob-sequencer/src/main/java/clob/sequencer/OutputRing.java:21`; `clob-sequencer/src/main/java/clob/sequencer/Pipeline.java:167`. **Code inspection.**

The gateway spins inside `submit()` when the inbound ring is full and stops consuming engine outputs. The engine spins when the gateway output ring is full and stops consuming its input. The sequencer then fills the engine ring and stops draining inbound. All three can wait forever. Market data introduces another cycle: the MD follower spins on a full gateway MD ring; the sequencer waits for the follower; the gateway waits for inbound capacity while no longer reading MD.

This is a deadlock possibility even with healthy peers, not merely a slow-client latency penalty. Queue sizes delay the condition rather than resolve it.

**Fix:** make gateway submission non-blocking at the agent level. Retain bounded pending work and return to output/socket polling, or pause the relevant reads while continuing other duties. Give every retry path a failure/stop policy. Test with deliberately tiny rings and sustained bidirectional traffic, requiring progress and bounded shutdown.

### R05 — P1: GTT orders can execute after expiry while waiting in a race

**Evidence:** `clob-engine/src/main/java/clob/engine/MatchingEngine.java:449–484`, `:703`; `clob-engine/src/main/java/clob/engine/RaceManager.java:163`. **Reproduced.**

GTT scheduling occurs when an order rests. Both race admission paths return before that point, so a new race-pending GTT order has no expiry timer. The lottery applies it without checking `expireTime`. This also affects maker races and races deferred through a halt.

**Observed:** a bid rests at 100; a sell with GTT expiry 2 ms joins a 5 ms taker race at time zero. At 3 ms it is still live; at 5 ms it trades. Its contractual expiry has already passed.

**Fix:** schedule GTT expiry when the accepted order becomes live, including race-pending states, and defensively check expiry before lottery application. Test expiry before, exactly at and after race expiry, and during a halt. Define this explicitly in PLAN Q24 rather than relying on the resting-book path.

### R06 — P1: NOP trades without value dates acquire disposable exposure

**Evidence:** `clob-credit/src/main/java/clob/credit/CreditEngine.java:228–255`, `:294–299`; `clob-credit/src/main/java/clob/credit/CreditLine.java:70`. **Reproduced.**

The missing-date guard is inside the DSL branch only. NOP permits trading with a USD rate but no value date, then records usage against the sentinel date `-1`. A normal value-date roll treats that exposure as settled and releases it. This contradicts the plan's stated default that missing dates block DSL/NOP lines.

**Observed:** NOP limit USD 10,000, rate 1, no dates: capacity is 10,000 units. Commit 1,000 units, then roll to epoch day 20,000: NOP falls from USD 1,000 to zero.

**Fix:** validate required dates before all limited-credit evaluation and reservation. Track the current settlement business date and define rejection of stale/backdated value-date inputs. Add tests proving a roll cannot erase exposure whose settlement date was never established.

### R07 — P1: credit overflow can partially commit one side of a trade

**Evidence:** `CreditEngine.java:222–225`, `:271–299`; `CreditLine.java:51–68`. **Reproduced.**

NOP lines track gross turnover as well as signed net. Net headroom is bounded, but gross turnover can grow without that bound. `CreditLine.add()` uses checked arithmetic, and `commit()` mutates one giver's line before the other. An overflow on the second line leaves the first line debited without the corresponding trade. Multi-leg mutations have the same general failure-atomicity problem. Arithmetic failures are not handled by `EngineDispatcher`'s `IllegalArgumentException` catch.

**Observed:** with USD 10 billion NOP limits and rate 1, nine alternating USD 10 billion trades leave a counterparty's net within its limit but gross at USD 90 billion. A subsequent permitted trade with a fresh account debits the fresh line, then throws on the counterparty's gross accumulation. The first debit remains. A configured instrument can legally allow these quantities; smaller executions eventually reach the same accumulated-turnover bound.

**Fix:** establish arithmetic bounds for accumulated state, not just each trade. Preflight every bilateral/leg/date update before mutating any of them, and maintain only the accounting dimensions needed or use an explicit larger-range representation. Test failure atomicity with uneven pool histories, rate changes, long-lived NOP pools and swaps.

### R08 — P1: deterministic recovery lacks an immutable configuration contract

**Evidence:** `ClobNode.java:42–59`; `clob-app/src/main/java/clob/app/NodeConfig.java:17`; `clob-engine/src/main/java/clob/engine/EngineConfig.java:5`; `clob-sequencer/src/main/java/clob/sequencer/Journal.java:133`; `InputCodec.java:19`. **Code inspection.**

Recovery reads today's refdata file, seed and account capacity. They are neither recorded in the journal nor checked against a manifest. The comments saying the random seed is part of journalled configuration are inaccurate. Changing tick rules, entitlements, order types or the seed can change old validation and lotteries during replay; errors and outputs are suppressed during core recovery. A standby with different local configuration likewise applies the same bytes to different semantics.

The journal writes a version field, but scanning checks only its magic. Message enums are stored as Java ordinals with no message schema version. An enum reorder or incompatible deployment can silently reinterpret history.

**Fix:** persist and verify a configuration/refdata/seed/schema manifest before replay and replication. Use stable wire IDs, explicit supported versions and migrations. Fail startup on incompatible history. Journal future effective-dated reference-data changes or reference immutable retained versions. Add mismatch tests for changed seed, entitlements, instrument rules and schema versions.

### R09 — P1: corruption within the last segment silently destroys later valid history

**Evidence:** `Journal.java:71–81`, `:160–195`, `:204–213`. **Reproduced.**

Any invalid record in the last segment is treated as a torn tail. `open()` clears everything from that record onward, even when later complete records exist. Replay also stops there without distinguishing corruption from a terminal partial append. Since a segment is 64 MiB in node wiring, “last segment” can contain a large amount of acknowledged history. Non-final segment scans also accept an ordinary zero-length end without requiring the expected segment-full transition.

**Observed:** write and close three records; flip the first record's CRC in the only segment, leaving records two and three valid. Reopen succeeds with zero records and erases the suffix.

**Fix:** distinguish recoverable terminal incomplete writes from committed-history corruption. Validate segment identity/version, transitions, and sequence continuity; quarantine or fail on ambiguous corruption and recover from a verified replica/backup. Add tests for corruption before a valid suffix, zeroed lengths, wrong headers and missing records. Also resolve Q40: `flush()` forces only the currently mapped segment, not earlier mappings discarded on rollover; there is no periodic flush in node wiring.

### R10 — P1: an invalid admin value-date input bypasses normal refusal handling

**Evidence:** `clob-app/src/main/java/clob/app/AdminHttp.java:140`; `CreditEngine.java:170–173`; `clob-sequencer/src/main/java/clob/sequencer/EngineDispatcher.java:78`; `Pipeline.java:98`. **Exception type reproduced; dispatcher/lifecycle impact by inspection.**

`/admin/valuedates` accepts an arbitrary instrument integer and journals it. `setValueDates()` indexes arrays directly, without checking instrument existence or date relationships. For an out-of-range ID it throws `ArrayIndexOutOfBoundsException`, not the refusal exception the dispatcher catches. A sparse in-range absent instrument is also accepted. A bad journalled command can therefore escape engine processing and later escape recovery rather than becoming a stable rejected command.

**Fix:** validate instrument existence and date contracts before mutation inside the domain API, returning a normal deterministic refusal. Validate the HTTP boundary too, but retain domain protection for replay/replication. Audit other external integer-to-array and long-to-int conversions, and validate entitlement IDs against `accounts.max` at startup.

### R11 — P1: shutdown violates disconnect semantics and does not drain output consumers

**Evidence:** `ClobNode.java:148–158`; `FixGateway.java:125–130`, `:221–239`; `clob-app/src/main/java/clob/app/OpsAgent.java:48`, `:88`. **Cancel-on-disconnect omission reproduced; drain risks by inspection.**

Node close stops the gateway runner and closes sockets directly. That bypasses `disconnect()`, the only path that submits cancel-on-disconnect. The runbook's claim that shutdown cancels those orders is false. If accepted commands remain, the subsequent pipeline drain can fill a gateway output ring with no active consumer and hang. After pipeline close, the ops runner is stopped without proving its output ring is empty, so the convenience drop copy/trade files can omit final events even on clean shutdown. An exception also prevents later resources from being closed because cleanup is sequential without `finally` protection.

**Observed:** log on with cancel-on-disconnect enabled, submit a GTC order, close the gateway/harness: the engine still has one live order.

**Fix:** implement staged shutdown: stop new ingress; submit disconnect actions while processing remains active; drain sequencing, engine, gateway and ops through a captured watermark; flush/close the journal; then close transports and agents. Include bounded failure cleanup. Test SIGTERM-equivalent shutdown with queued inputs, non-empty outputs and active sessions.

### R12 — P1: a slow standby can stall the primary, and HA cannot span hosts as configured

**Evidence:** `clob-app/src/main/java/clob/app/ReplicationSender.java:35`, `:49–61`, `:71–99`; `Pipeline.java:167–175`; `ClobNode.java:152–156`. **Code inspection.**

The replication follower uses blocking socket writes with no progress deadline. If a connected standby stops reading, the follower blocks, its ring fills, and the sequencer stops forwarding. Shutdown attempts to drain the pipeline before closing replication, so it cannot reliably break this condition. Reconnecting also scans the entire journal synchronously in the follower, creating growing stalls as history accumulates. The implementation documents the backpressure, but does not define an acceptable bound or overload policy.

The replication listener is bound to `127.0.0.1`. A standby on a different host cannot reach it despite the configurable primary host. FIX is also loopback-only. Same-host tests demonstrate a process standby, not machine-failure resilience. Replacing an active replication connection does not close the old one; an incomplete handshake can block the sole acceptor.

**Fix:** configure listen addresses explicitly; add non-blocking replication, deadlines, connection cleanup and sequence-indexed catch-up. Choose bounded lag/disconnect semantics for asynchronous replication, or an explicit synchronous commit policy. Test a non-reading standby, handshake timeout, reconnection after long history and two-host deployment.

### R13 — P1: failure state is disconnected from readiness and admission

**Evidence:** `Pipeline.java:98–100`, `:123–155`, `:167`; `ClobNode.java:109–120`; `clob-app/src/main/java/clob/app/StandbyNode.java:63–77`, `:99–114`; `AdminHttp.java:79`. **Code inspection.**

Pipeline errors merely set a field checked during drain/close. Gateway and ops agent errors are printed. HTTP health still returns 200 with role and sequenced count; submit can continue accepting inputs. No readiness decision includes engine/follower progress or fatal errors. A stopped or repeatedly failing component can therefore appear available while reports or trades stop progressing. The standby accepts any increasing sequence, including a gap, and its receiver handles only I/O exceptions; it can die without making health unhealthy. Promotion does not verify the receiver actually terminated after its timed join.

**Fix:** add an explicit node state machine with fatal-error propagation, ingress refusal and readiness based on processed/replicated watermarks. Check contiguous replication sequence and frame bounds before append. Ensure promotion has exclusive state ownership. Make all retry loops stop-aware, and test agent failure, disk-full, malformed replication and stuck shutdown. Keep liveness and readiness distinct.

### R14 — P2: fixing time is stored but never enforced

**Evidence:** `clob-engine/src/main/java/clob/engine/FixingDesk.java:84`, `:197–213`; `clob-engine/src/test/java/clob/engine/FixingOrderTest.java:29`. **Reproduced.**

Once cut-off marks a session as awaiting a rate, `rate()` accepts a rate immediately. It never checks the scheduled fixing time. The existing lifecycle test checks rejection before cut-off, then advances all the way to fixing, leaving the gap untested.

**Observed:** schedule cut-off 100 and fixing 200; match orders; advance to 100; supplying a rate returns true and publishes the trade at 100.

**Fix:** enforce `time >= fixingTime[eventId]`, or explicitly define buffering for early-arriving rates with publication at the fixing instant. Test the cut-off/fixing gap, equal boundaries, late rates and corrections. Rate validity must also be reconciled with signed SWAP pricing if fixing orders are supported for swaps.

### R15 — P2: independently configurable MD intervals can miss their required cadence

**Evidence:** `clob-sequencer/src/main/java/clob/sequencer/Sequencer.java:58–79`; `clob-marketdata/src/main/java/clob/marketdata/MarketDataService.java:244–254`; `NodeConfig.java:47`; `RefDataLoader.java:73`. **Code inspection.**

There is one global tick period and each instrument publishes only when its interval exactly divides the boundary. No validation relates those periods. With global ticks every 5 ms and an instrument interval of 3 ms, that instrument publishes only at common 15 ms boundaries, not every 3 ms. Skipping late global boundaries can postpone it further. Separately, ticks are polled after a batch of commands, and snapshot engine time is the stamped processing time rather than the boundary carried in the tick; the contract for including inputs after the nominal boundary needs to be explicit.

**Fix:** derive the global tick grid from supported instrument intervals or validate that every positive instrument interval is a multiple of the global period. Define late-boundary behaviour and measure boundary-to-receiver delay. Test incompatible intervals, stalls, clock adjustments and batch-boundary membership.

### R16 — P2: retained gateway state grows with total session traffic

**Evidence:** `FixSession.java:29–38`, `:61–69`; `FixGateway.java:418–423`, `:840`; `clob-engine/src/main/java/clob/engine/TimerQueue.java:5–9`. **Code inspection.**

Every outbound message appends resend-store entries; every order and request leaves a ClOrdID entry, including completed orders. There is no retention bound, rollover or durable store policy. Closed sessions can remain referenced by outstanding `GatewayOrder` objects. Market-data snapshots are also stored for resend, multiplying retention during high churn. The 1 MiB socket queue limit does not bound these stores. Cancelled long-dated GTT timers are lazily retained until expiry, so timer memory is bounded by historical submissions before that horizon rather than live orders.

**Fix:** define session/day retention and rollover, bounded durable resend storage, order tombstone/uniqueness policy, and removable or compactable long-lived timers. Run a day-length soak test with realistic MD and request traffic, measuring retained heap and GC latency. Document capacity limits separately from the live-order cap.

### R17 — P2: the custom FIX boundary needs stronger validation and error isolation

**Evidence:** `clob-fix/src/main/java/clob/fix/FixParser.java:25–55`, `:145–166`, `:170–209`; `FixGateway.java:187–218`, `:259–291`, `:354–380`. **Code inspection.**

Integer parsing has unchecked multiplication/addition and accepts a lone minus as zero. Decimal parsing checks multiplication but not the following addition, and accepts digitless values such as `.` as zero. Message BodyLength may exceed the receive buffer. Several session/application numeric reads are outside exception handling, and resend ranges are not constrained before index arithmetic. SenderCompID/TargetCompID are verified at logon, but not on subsequent messages. These behaviours allow malformed or inconsistent inputs to escape normal per-session rejection and affect the shared gateway loop.

**Fix:** enforce checked numeric bounds, required digit counts, allowed message size, valid sequence/reset/resend ranges, and per-message session identity. Contain parsing failures to the offending connection. Add malformed-wire and overflow tests, including split frames and invalid repeating groups. Expand independent conformance tests; dictionary validation of outgoing messages does not validate the inbound state machine.

### R18 — P2: UDP market data is not reachable through the node configuration

**Evidence:** `clob-marketdata/src/main/java/clob/marketdata/UdpPublisher.java:18`; `ClobNode.java:95–108`; `NodeConfig.java:17`; `FixGateway.java:618–654`. **Code inspection.**

UDP publication exists as a component and has a delivery test, but node wiring never constructs it and exposes no endpoint-registration or UDP subscription path. The running application offers FIX/TCP snapshots only. The binary format is custom; naming it ITCH/SBE-style does not establish FAST or ITCH interoperability. UDP sequence numbers detect gaps, but change-only publication supplies no guaranteed refresh after a final lost snapshot.

**Fix:** either declare UDP a library-only prototype and narrow the runtime delivery claim, or wire authenticated subscription/endpoint management into the app and specify the protocol. Define heartbeat, periodic refresh or explicit snapshot recovery. Add node-level transport tests rather than only testing `UdpPublisher` in isolation.

### R19 — P2: Prometheus metadata uses labelled samples as metric names

**Evidence:** `clob-app/src/main/java/clob/app/OpsAgent.java:76–77`; `clob-app/src/main/java/clob/app/Metrics.java:29–33`. **Code inspection.**

Counters are keyed by the complete labelled sample string. Rendering then produces metadata such as `# TYPE clob_cancels_total{reason="ADMIN"} counter`; the TYPE declaration needs the metric family name, with labels only on sample lines. Tests assert substring content rather than parsing the exposition, so they do not establish scrape compatibility.

**Fix:** represent family name, type and labels separately, emit one family declaration, and validate `/metrics` with an exposition parser. Expose processed/follower sequence, replication lag, fatal errors, queue occupancy and MD lateness as well; the last sequenced input alone is not a trading-health measure.

### R20 — P2: runtime refdata cannot express supported type/TIF restrictions

**Evidence:** `clob-refdata/src/main/java/clob/refdata/Instrument.java:142–143`, `:176–177`; `clob-app/src/main/java/clob/app/RefDataLoader.java:54–83`. **Code inspection.**

The domain model and validation support per-instrument sets of allowed order types and TIFs, but the production text loader exposes neither setting. Every loaded instrument inherits all types/TIFs. Operators cannot restrict a product to its agreed subset through the supplied runtime configuration, which matters particularly while SWAP/NDF/fixing economics remain provisional.

**Fix:** expose and validate both sets in the loader, document their syntax, and test a node loaded with a restricted instrument. Add configuration validation for unsupported combinations, zero peg throttle with pegs enabled, account IDs, and numeric overflow in time-unit conversion.

## Requirements and plan review

### The acceptance contract needs consolidation

`requirements.md` is an informal feature specification with useful examples, but many implemented semantics are found only in `PLAN.md`. Revision 5 explicitly recognizes this. The plan simultaneously contains unresolved questions, purported confirmations, implementation defaults and completed-phase claims. For example, its Phase 3 summary says self-trading is blocked by absent self-credit, while Q29 still says an account can trade with itself. The engine has no unconditional self-trade rule; its behaviour depends on configured relationships.

The decision gates at `PLAN.md:309–315` are sound, but implementation has proceeded through those gates using defaults rather than recorded approval. That is acceptable for a prototype only if the deployment scope identifies those defaults as provisional. A test proving the chosen default does not establish that the business requirement was settled.

Recommended requirement decisions before acceptance:

| Area | Decisions still needed | Why it affects architecture/correctness |
|---|---|---|
| VISIBILITY | Screened versus unscreened exhaustion; remaining credit-ineligible visible orders; iceberg reserve/refresh treatment. | Determines the matching traversal and FOK oracle, not just a parameter. |
| Products | SWAP scaling, near rate, leg notionals/outrights; NDF fixing and settlement formula/currency; trade-event fields/version. | Current events carry one price and quantity, not resolved product legs or NDF settlement economics. Signed points alone do not complete SWAP support. |
| Credit | Pool ownership/sharing, NOP netting dimensions, rate source and revaluation, limit-type changes, settlement and fixing reservation lifecycle. | Current choices have material risk/accounting consequences. A swap netting to zero initially can acquire remaining far-leg exposure after the near leg settles. |
| Races/TTL | Admission timestamp, backlog semantics, pending-order expiry, maker/amend interactions, cancellation release and exemptions. | Determines fairness and whether an order may execute or be withdrawn at a boundary. |
| Fixing | Continuous versus cut-off allocation, pending-fill reporting, early/missing/corrected rates and cancellation rights. | Pending executions need durable ownership, credit and reporting state across recovery. |
| Market data | Relationship-only versus headroom screening, entitlements, snapshot versus streaming requests, transport matrix, delivery deadline and overload behaviour. | The literal requirement says a screened order must be matchable; relationship-only screening can advertise exhausted credit. Q6e still leaves the intended rule unresolved. |
| Operations | End-of-day timezone, GTC rollover economics, durability/RPO, recovery/RTO and manual promotion policy. | Controls storage, snapshots, session state and failover design. |
| Scale | Aggregate versus per-book peak, update definition, accounts, subscribers, depth, price levels, outstanding timers and burst sizes. | A 2M/s aggregate input target alone cannot bound crossed-book walks, lottery bursts or per-account MD work. |

Create one normative behavioural specification with stable requirement IDs, decision owner/status and approved examples. Keep the open-question log and measured implementation status separately. Add a requirement-to-test matrix, including negative and lifecycle cases, so “core done” cannot be mistaken for full acceptance.

### Plan-to-implementation alignment

| Planned capability | Actual state | Review judgement |
|---|---|---|
| Pure single-writer matching | Implemented, including credit and timers. | Strong foundation; keep it. |
| Aeron/Archive, SBE, Artio | Agrona rings, custom mmap journal/codecs and custom FIX engine. | Explicit prototype substitutions, but substantial production obligations remain. These are not automatically interchangeable without lifecycle/protocol work. |
| Shared cross-book credit | One owner plus simulation overlay. | Simplifies serial correctness; leasing/sharding remains an unproven future design. |
| Coordinated MD cut | Sequenced ticks and a full replica engine. | Good sequence consistency, but deadline and overload acceptance are incomplete. |
| Complete SPOT/SWAP/NDF support | Instrument types and some credit leg handling; economics/calendars are deferred. | Treat product support as partial. Restrict deployed types until contracts are implemented. |
| Operations/HA | Local admin, convenience reports, asynchronous process standby and manual promotion. | Useful prototype; does not meet an unspecified durable, host-resilient HA contract. |
| Performance hardening | Allocation guard, microbenchmarks and development-host measurements. | Evidence exists, but complete application capacity and tail latency are unaccepted. |
| CI/style/property-test tooling | Strict javac and JUnit tests; no repository CI workflow, formatter/static-style task or jqwik setup found. | The plan overstates automated enforcement. |

The time estimates and phase checkboxes should be revised around remaining deliverables and measurable exit criteria. Separate functional completion, fault tolerance and performance acceptance. Update stale references in `docs/TUNING.md` to Aeron Archive “Phase 10”, the early-phase benchmark next steps, and Phase 7's “not built” lists that now conflict with later status sections.

## Architecture and design review

### Preserve the single owner of book and credit state

Keeping all instruments and shared credit on one engine thread is a defensible starting design. It avoids distributed bilateral reservations and makes FOK simulation and replay tractable. Arrays and intrusive queues make ownership visible and avoid per-operation wrapper allocation. `Instrument`/`RefData` immutability, fixed-point prices and explicit time inputs support that model well.

Do not implement credit leasing merely because the code is called “shard-ready”. Signed NOP across dates, pooled headroom and cross-instrument exposure require a proven reservation/netting protocol. First obtain a full application profile at the approved envelope. If needed, try isolating publication work while keeping credit/matching single-writer before distributing credit ownership.

The sorted price-level arrays assume shallow books: insertion/removal moves the remaining array, and credit-crossed matching can inspect many ineligible orders. Race ticket lookup is linear in participants and maker-race lookup is linear in active maker races. These choices are reasonable only with explicit depth/account/race limits and adversarial benchmarks. The live-order cap alone does not bound the latency of an individual command.

### Make ownership and backpressure explicit across every stage

The actual application graph is:

```text
FIX/admin -> inbound ring -> sequencer + mmap journal -> engine ring -> matching engine
                                      |                                  |
                                      +-> MD follower/replica -> FIX MD ring
                                      +-> replication follower           +-> FIX output ring
                                                                         +-> ops output ring
```

Every mandatory branch can backpressure the sequencer or engine. The MD follower repeats all matching, credit, race and timer work even with no subscribers. Ops disk latency can stop matching via its output ring. Therefore “the engine never blocks” in tuning documentation is too broad: the matching algorithm avoids I/O, but its synchronous listener spins on full rings.

For every edge, document its owner, queue bound, ordering guarantee, overload action and failure effect. Choose which consumers are critical and which can recover asynchronously from the journal. Use agent work budgets and explicit progress metrics. Preserve mandatory execution-report delivery through a durable handoff rather than silently dropping or indefinitely blocking.

`Pipeline.drain()` is also not a stable sequence barrier: it reads inbound size and a `sequenced` value updated only after a batch, while the sequencer can already have published a message. Engine processing may be in flight while those observations appear drained. A stronger API captures a submission watermark, closes or fences new ingress as appropriate, and waits for every required consumer to acknowledge that watermark.

### Treat the replay contract as a deployed protocol

The existing replay tests are valuable: they compare byte output and exercise concurrent ingress. Their guarantee currently assumes the same executable semantics and starting refdata. Production recovery additionally needs configuration binding, schema compatibility, storage integrity, contiguous sequence checks and externally visible application state. FIX identifiers, order ownership and reporting cannot be reconstructed from the current input bytes alone.

Define acknowledgement versus durability explicitly. The current node acknowledges after mmap append, has no periodic force, uses asynchronous replication and lacks fencing. This is not a synchronous durable commit. Manual failover can be a legitimate v1 scope, but the RPO and operator checks must be explicit. Add snapshots/checkpoints and journal retention only after the complete state and versioning contract is settled; full replay twice at startup and full-history replication scans will otherwise make recovery grow indefinitely.

Construction and close also need transactional resource ownership: `ClobNode` starts threads before all downstream resources and ports are successfully created. A later constructor failure can leave a partly running node. Acquire resources with rollback, then transition to serving once every required component is ready.

### Market-data consistency is promising; timeliness is unproven

The replica approach gives each subscriber a coherent book-and-credit version without locking the matching engine. Grouping equivalent eligibility views and rotating subscribers are good optimizations. The trade-off is another full engine plus publication CPU, memory and lag. At maximum configured accounts, cached eligibility rows scale approximately as instruments × accounts² bits, in addition to dense relationship tables and replica order pools. Capacity tests must include this footprint and credit-update invalidation cost.

The repository reports **22.9 ms** for 200 changed books × 64 screened subscribers, against an example 5 ms interval. Even eight subscribers/book reportedly takes 5.5 ms. Those are build-cycle measurements on a development VM, not receiver deadlines or proof of target-server failure, but they plainly do not establish the required delivery contract. `cycleNanos` measures one instrument's build/handoff, excluding prior stream backlog and actual receipt. Capture tick lateness, follower lag, complete-cycle time and receiver arrival latency separately.

Use stable per-cycle frozen state if publishing across threads, with explicit ownership of reusable `BookView` buffers. Define whether an interval miss drops/conflates an old snapshot, disconnects a subscriber or backpressures upstream. The content fingerprint is a useful fast filter, but hash equality alone is not proof of unchanged content; consider exact/version-derived invalidation if a missed update is unacceptable.

## Code style and maintainability review

### What works well

- Packages largely follow domain responsibilities; matching/credit remain free of filesystem and network dependencies.
- Naming is generally concrete and consistent. Small enums and command objects make the supported operations discoverable.
- The comments explaining time boundaries, visibility traversal, credit assumptions and queue mechanics are useful. The scenario DSL closely matches the requirement examples.
- Builders make large immutable instrument definitions manageable. Public records express configuration clearly.
- Reused commands, primitive arrays and intrusive links are justified by measured goals. Avoid replacing these wholesale with streams or allocating abstractions.
- `-Xlint` plus `-Werror` provides useful compiler discipline, and randomized conservation/FOK tests complement example tests.

### Improvements to prioritize

| Area | Finding and recommendation |
|---|---|
| Class responsibility | `MatchingEngine` is 972 lines and `FixGateway` 900. The gateway combines framing, session state, authentication, resend storage, order/request ownership, MD and encoding. Extract logical session storage, request correlation and transport adapters first; retain allocation-free dispatch interfaces. The engine already delegates pegs/mid/fixing/races, so improve it around lifecycle/invariants rather than arbitrary file-size limits. |
| Codec maintenance | `InputCodec`, `OutputEncoder` and `OutputReader` use positional offsets and ordinal IDs. A generated or declarative versioned schema would reduce coordinated-edit errors. Until then, centralize field offsets and validate type/length before decoding. Round-trip tests alone can miss encoder/decoder mistakes shared by both. |
| Type/unit clarity | Account/user/instrument/event IDs are interchangeable ints; dates are ints; USD limits, E8 usage, ticks, sub-ticks, milliseconds and nanoseconds are longs. Keep primitive hot paths, but use explicit names such as `rateE8`, `priceTicks`, `expiryNanos` and stable boundary types/validation. Avoid one-letter variables in operational/protocol code when they obscure ownership. |
| Sentinels | `-1`, `Long.MIN_VALUE`, `Long.MAX_VALUE`, zero and fully-visible sentinels have several meanings. Consolidate and document them, especially fields where zero is a legitimate SWAP price. Prefer explicit state flags when a sentinel can collide with accepted numeric values. |
| Errors | Catching `IllegalArgumentException` around a whole dispatch is not proof of “no mutation on refusal”; even `setRelationship()` increments version before validating the instrument. Validate all relevant fields first, return typed refusals, and separate external rejection from invariant/fatal failure. Avoid broad `RuntimeException` catches that turn programming errors into generic invalid-field messages. |
| Wire/request semantics | Replace the mutable gateway-wide `pendingStore`/`pendingType`/`pendingMark` builder convention and per-order pending slot with APIs whose ownership is explicit. Document which original order fields an amend preserves; currently missing MaxFloor/discretion defaults can reject iceberg replacements or change peg/mid behaviour. |
| Allocation claims | The allocation guard covers its measured bounded workload with a null listener, not the entire running node. Pools and timer arrays can grow, and the guard's advertised “every order type” excludes fixing orders. `SnapshotCodec.decode()` allocates a temporary view and arrays on every snapshot; FIX encoding builds strings/byte arrays. State the scope precisely and profile the application heap under load. |
| Numeric policy | The core is fixed-point, but gateway average price uses `double` and `Math.pow`. Define output rounding/precision explicitly, especially for mid sub-ticks and large cumulative quantities. Use tested conversion utilities and range checks at ingress rather than relying on wraparound or downstream validation. |
| Thread visibility | Public diagnostics expose mutable engines; metrics read fields such as MD publish count and replication `active` across threads without a publication policy. Declare ownership, return snapshots/watermarks, and use appropriate volatile/atomic publication for cross-thread health data. |
| Formatting/tooling | Compiler warnings do not enforce layout, imports, line length or API style. Add a consistent formatter and a small, agreed static-analysis baseline; avoid a large cosmetic rewrite mixed with correctness fixes. Several fully qualified names and compressed one-line methods are inconsistent, but lower priority than protocol/lifecycle work. |
| Documentation | Keep contract comments synchronized with behaviour. “Seed is journalled”, “never blocks”, and broad “all order types” allocation claims are examples needing correction. Trim duplicated historical status in `PLAN.md` so current scope is easy to locate. |
| Repository hygiene | Add a CI workflow for fresh tests and meaningful checks. Ignore generated runtime `var/` data from the example config. Add a documented contribution/check command and avoid keeping credentials in real committed configuration; the current change-me values are clearly examples. |

## Testing gaps and remediation sequence

The existing suite is strongest around deterministic core examples, conservation, replay equality and ordinary protocol behaviour. Its app/HA tests use orderly `close()` and direct pipeline orders; a test named “crash” reconstructs after closing/flushing a journal. Those are useful recovery tests, but do not simulate abrupt process death, partial external delivery or power failure. Dictionary validation and self-codec raw clients are useful but cannot replace an independently exercised inbound session state machine.

Prioritize regression coverage as follows:

1. **Identity and control:** real FIX reconnect/restart/promotion with live GTC, queued requests, partially matched fixing orders and new order submission. Prove correct recipients, unique IDs and continued cancel/replace control.
2. **Trading invariants:** GTT through every race/halt state; credit missing-date refusal; bilateral/leg failure atomicity and accumulated arithmetic bounds; the fixing cut-off/fixing-time gap.
3. **Liveness and failure:** tiny-ring overload, slow/disconnected consumers, stalled replication, disk failures and staged shutdown. Require bounded progress/timeouts rather than tests that can hang indefinitely.
4. **Recovery integrity:** abrupt subprocess termination, version/config mismatch, corruption before valid suffixes, missing/gapped sequences, rollover durability and snapshot equivalence. Keep power-loss durability a separate hardware/storage test.
5. **Capacity acceptance:** agreed aggregate input and burst envelope, MD subscriber mix and receiver deadlines, accounts/depth/race/timer maxima, and a long-duration heap/GC soak. Run the complete node with all required consumers; keep microbenchmarks as diagnostic tools.

Recommended delivery order: first stabilize IDs/request ownership, expiry and credit invariants; then make backpressure, readiness and shutdown safe; then bind replay/replication to versioned state and durability policy; finally accept transport coverage and measured capacity against the consolidated requirements. Defer broad stylistic refactoring until those boundaries have regression coverage.

## Reproduction notes

The nine temporary probes were:

| Probe | Observed defect |
|---|---|
| `gttTradesAfterExpiryWhileWaitingInTakerRace` | Expiry 2 ms, trade at race close 5 ms. |
| `fixingCanBePricedBetweenCutoffAndFixingTime` | Cut-off 100, fixing 200, trade published at 100. |
| `nopWithoutValueDateIsAllowedAndThenErasedByRoll` | Sentinel-date NOP exposure disappears on roll. |
| `invalidValueDateInstrumentThrowsUncaughtExceptionType` | Invalid instrument throws `ArrayIndexOutOfBoundsException`. |
| `nopGrossOverflowLeavesFirstBilateralPoolMutated` | Second line overflows after first line has committed. |
| `reconnectLosesAccessToStillLiveOrder` | Reconnected owner cannot cancel outstanding GTC order. |
| `duplicateCancelClOrdIdOverwritesAnotherLiveOrderMapping` | Cancel ID replaces another order's mapping. |
| `gatewayCloseSkipsCancelOnDisconnect` | Closing gateway leaves cancel-on-disconnect order live. |
| `corruptionBeforeValidTailSilentlyDiscardsAllLaterRecords` | Corrupt first CRC among three records; reopen erases all three. |

For example, the GTT reproduction using the existing testkit is:

```java
Scenario s = Scenario.of(TestInstruments.example(MatchPriority.PRICE)
        .takerRaceNanos(5_000_000).build());
s.buy(1).limit(100).qty(1000).gtc().send();
s.sell(2).limit(100).qty(1000).gtt(2_000_000).send();
s.advance(3_000_000); // order 2 is incorrectly still live
s.advance(2_000_000); // order 2 incorrectly trades at 5 ms
```

Turn these into permanent tests asserting the required corrected behaviour when implementing the fixes. Temporary probe files and Gradle reports are local review artifacts; this document records the results so it does not depend on retaining `/tmp`.
