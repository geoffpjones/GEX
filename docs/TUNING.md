# Performance tuning and measurement

This is how to run the CLOB for deterministic low latency, and how to measure it. Numbers from the development VM are in `clob-bench/SPIKE-RESULTS.md`. That VM has 4 shared vCPUs, so its latency tails reflect CPU oversubscription, not the design.

## Threads and cores

Each latency-critical thread gets its own **isolated physical core**, pinned, with a **busy-spin** idle strategy:

| Thread | Agent | Notes |
|---|---|---|
| Sequencer | `Pipeline` sequencer agent | stamps, journals, forwards |
| Engine | `Pipeline` engine agent | matching; never blocks or allocates |
| FIX gateway | `FixGateway` | NIO; one gateway per few hundred sessions |
| Market data | `Pipeline` follower (`MarketDataService`) | screened builds can saturate it (see Phase 8 results) |

Everything else (JIT and GC threads, logging, admin, the OS) runs on the remaining cores.

The pipeline agents currently use `BackoffIdleStrategy`, which parks for up to 100 µs when idle. That suits a shared development box but adds tens to hundreds of µs per hop. **In production, use `BusySpinIdleStrategy` (or `NoOpIdleStrategy`) on isolated cores.** Making this configurable is a small change.

## Operating system (Linux)

- **Kernel boot parameters:**
  - `isolcpus=<cores> nohz_full=<cores> rcu_nocbs=<cores>` for the pinned cores;
  - `intel_idle.max_cstate=0 processor.max_cstate=1` (or the AMD equivalent);
  - `nosoftlockup`, `mce=ignore_ce`.
- **CPU frequency:** governor `performance`, turbo fixed or disabled for repeatable latency, SMT siblings of pinned cores left idle.
- **IRQ affinity:** steer NIC and disk interrupts away from pinned cores (`irqbalance` off, set `/proc/irq/*/smp_affinity`).
- **Memory:** transparent huge pages set to `madvise`, with `-XX:+UseTransparentHugePages` or explicit huge pages; swap off; `vm.zone_reclaim_mode=0`; NUMA-local memory (`numactl --cpunodebind --membind`).
- **Network:** `SO_BUSY_POLL` or kernel bypass (Solarflare Onload, or VMA) for FIX and UDP market data; `tcp_nodelay` is already set; larger socket buffers for market data.
- **Journal disk:** NVMe with XFS, on its own device. With Aeron Archive (Phase 10) the journal is replicated, and fsync per batch becomes a choice (Q40).

## JVM

```
-Xms<N> -Xmx<N> -XX:+AlwaysPreTouch               # no heap growth or first-touch faults
-XX:+UseZGC -XX:+ZGenerational                     # or Azul Prime (C4); benchmark both
-XX:+UseTransparentHugePages
-Xlog:safepoint,gc:file=gc.log                     # watch time-to-safepoint
--add-exports java.base/jdk.internal.misc=ALL-UNNAMED   # Agrona 2.x
```

- **Allocation:** the engine allocates nothing in steady state. `AllocationTest` enforces this in CI after warm-up. With no allocation there's no GC on the hot path; GC only touches the gateway (string building in execution reports) and admin paths.
- **Warm-up:** replay a recent journal (or a synthetic flow) through a throwaway engine before opening, so the JIT has compiled the hot paths. Alternatives are Azul ReadyNow or a CDS/AOT archive.

## Measurement tools (`clob-bench`)

| Tool | Measures |
|---|---|
| `ThroughputSpike --mode hiccup` | the platform's own jitter: run first, on the target cores |
| `ThroughputSpike` | engine events/s and per-event service time; paced (CO-corrected) latency; race and credit options |
| `org.openjdk.jmh.Main EngineBenchmarks` | add/cancel, sweep, crossed-book walk, peg tick |
| `MarketDataBench` | screened market data cycle cost vs subscribers and churn |
| `clob.fix.FixLatencyBench` | FIX in → execution report out, paced and CO-corrected |

Acceptance should be measured on production-class hardware with the settings above, at the agreed peak (Q11, Q19, Q42).
