# Phase 0 throughput spike: results (2026-11-07)

**Question.** Can one single-writer engine thread carry 200 books × 10,000 updates/sec, which is 2M events/sec if every book peaks at once?

**Short answer.** On this development box, just about, with no headroom: about 2.1M events/s flat out, but latency queues badly when paced at 2M/s. Server-class hardware should be roughly 2–3× faster per core, which would make one thread viable at 25–40% load. That is an estimate, not yet measured. Recommendation: **stay on one shard for Phase 3, and re-run this spike on production-class hardware before Phase 3 finishes.** Keep the engine shard-ready.

## Environment (caveat: not representative of production)

- Intel N100 (4 low-power E-cores, 6 MB L3) inside a ChromeOS Linux VM, with 2 GB RAM.
- No isolated cores, no tuning, and a noisy hypervisor.
- OpenJDK 21.0.10 (Temurin), G1, `-Xms512m -Xmx512m`.

Platform noise baseline (`--mode hiccup`): an empty spin loop reading the clock saw gaps of p99.99 = 4.9 µs and max = 2.3 ms. In 10 s there were 957 gaps over 10 µs, 45 over 100 µs and 2 over 1 ms. **Engine latency tails at or above about 5 µs are therefore dominated by the platform.**

## Workload

`FlowGenerator`, deterministic:
- 200 books and 256 accounts, at most 64 live orders tracked per book, giving about 18–20 price levels per book and about 12k live orders in total.
- Event mix:

  | Share | Event |
  |---|---|
  | 45% | passive DAY adds (10% iceberg, 10% hidden) |
  | 40% | participant cancels |
  | 10% | price amends |
  | 5% | aggressive IOCs |

- Mid prices drift, so resting orders get crossed.
- Min TTL is 5 ms in queue mode, so about half of all cancels are queued and released by timer.

On the engine thread:
- the real `MatchingEngine`,
- a **credit stub** on every trade (bilateral eligibility bitset, plus DSL accumulation in dense arrays),
- **output encoding** of every event into an off-heap ring.

## Results

| Run | Throughput | Service time / latency (ns) |
|---|---|---|
| Engine only (no credit stub), saturate, untimed | 2.9–4.2M ev/s (240–350 ns/event; VM variance) | – |
| Engine + credit + output, saturate, untimed | 2.6–2.7M ev/s (365–380 ns/event) | – |
| Engine + credit + output, saturate, timed per event* | 2.1M ev/s | p50 322 · p90 720 · p99 1,352 · p99.9 2,745 · p99.99 12,991 |
| Same, PRICE priority | 2.1M ev/s | p50 317 · p99 1,342 · p99.9 2,621 |
| Paced 1.0M/s (latency from scheduled time) | sustained | p50 364 · p99 11,223 · p99.9 106,879 · max 1.2 ms |
| Paced 1.5M/s | sustained | p50 402 · p99 142,719 · p99.9 2.7 ms |
| Paced 2.0M/s | **falls behind** | p50 9,087 · p99 5.6 ms (queueing) |
| Journal **inline** on engine thread, saturate, timed | 1.2M ev/s | p50 483 · p99 9,343 (page faults, no pre-touch) |
| Journal stage alone, own thread, pre-touched | 7.9M rec/s (127 ns, 378 MB/s) | – |

\* Per-event timing adds about 36 ns (one extra clock read).

Service time by event type (engine + credit + output, timed):

| Event | Share | mean | p50 | p99 | p99.9 |
|---|---|---|---|---|---|
| passive add | 42.5% | 411 | 327 | 1,072 | 2,185 |
| aggressive IOC | 5.0% | 935 | 850 | 2,267 | 11,039 |
| cancel | 42.5% | 345 | 284 | 973 | 1,692 |
| replace | 10.0% | 417 | 362 | 1,108 | 2,331 |

**Allocation:** 0 bytes per event on the engine thread in steady state. A few KB in total came from the measurement code. There were **no GCs** during any measured run. An earlier version of the credit stub, using a hash map, allocated and was about 5× slower; dense arrays fixed both.

## Findings

1. **One thread is at the limit on this box.** Mean cost is about 370–480 ns/event against a 500 ns budget at 2M/s, which means close to 100% load, so any hiccup queues. Stable tail latency needs load at or below about 50–60%, i.e. at or below about 250–300 ns/event at the 2M/s design point.
2. **The journal must not run on the engine thread.** Inline, it more than doubled the per-event cost. As its own pipeline stage it does 7.9M records/s, about 4× the target. This confirms the planned layout (sequencer and journal, then the engine).
3. **Cancels and passive adds dominate the cost** (85% of events at about 300–400 ns). On this CPU that is mostly cache misses: about 12k live orders plus levels exceed the 2 MB L2. Optimisation candidates:
   - smaller `Order` objects, or a struct-of-arrays layout,
   - a cheaper order-id map (dense ids assigned by the gateway → array index),
   - avoiding the timer-heap insert for queued cancels when TTL expiry can be computed from a per-book FIFO (TTL is uniform per instrument).
4. **Allocation discipline holds**: zero steady-state allocation, so GC isn't a factor.

## Phase 3 update: real credit in the matching walk

With the credit stub replaced by `CreditEngine`, configured per directed pair as 10% none, 60% unlimited, 20% DSL (USD 50–500m) and 10% NOP (USD 100–1,000m):

| Run | Result |
|---|---|
| Untimed throughput | 2.63M ev/s (same as an unlimited mesh, 2.64M) |
| Timed, saturate | p50 323 · p99 1,766 · p99.9 5,283 ns |
| Aggressive IOC | mean 1,245 ns (was 935): the walk skips ineligible orders and checks credit per fill |
| Paced 1M/s | p50 361 · p99 8,471 ns |
| Allocation | 0 bytes per event, no GCs |

## Not yet included (each will add cost)

- Fairness races.
- Peg repricing storms (every peg re-evaluated every 20 ms tick).
- Mid matching.
- Book-delta output for market data.
- The sequencer → engine IPC handoff (Aeron IPC, typically tens of ns per message).

## Decision and next steps

- **Proceed single-shard into Phase 3**, keeping the engine shard-ready (books are already independent; credit is the only shared state).
- **Re-run this spike on production-class hardware**, ideally the target server, with isolated cores and the real Aeron sequencer in front. Trigger for sharding: at the agreed aggregate peak (Q11), load above 50% or p99 above the latency target.
- **Get Q11 answered.** Is 2M/s (all books peaking together) really the design point? A realistic aggregate peak is likely far lower, which changes the conclusion.
- Add the event types above to the harness as they're built, and keep it as a CI regression gate (Phase 9).

## Reproduce

```
./gradlew :clob-bench:installDist
export JAVA_OPTS="-Xms512m -Xmx512m -XX:+UseG1GC"
B=clob-bench/build/install/clob-bench/bin/clob-bench
$B --mode hiccup --seconds 10                                     # platform noise baseline
$B --events 20000000 --warmup 5000000 --timing true               # saturate, per-event service time
$B --events 10000000 --warmup 5000000 --rate 2000000              # paced, CO-corrected latency
$B --events 20000000 --warmup 5000000 --credit false --timing false
$B --mode journal --journal /path/journal.dat --events 20000000   # journal stage alone
```
