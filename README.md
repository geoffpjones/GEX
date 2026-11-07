# FX CLOB

Low-latency, deterministic FX central limit order book in Java 21. See `requirements.md` for the spec and `PLAN.md` for the plan, implementation status and open questions.

```
./gradlew test                       # build and run all tests
./gradlew :clob-app:installDist      # build the node launcher
clob-app/build/install/clob-app/bin/clob-app primary config/node.properties
```

Docs: `docs/FIX-RoE.md` (FIX rules of engagement), `docs/RUNBOOK.md` (operations), `docs/TUNING.md` (performance).

Modules: `clob-common` (primitives), `clob-refdata` (instruments, entitlements), `clob-credit` (bilateral credit), `clob-engine` (pure matching engine), `clob-testkit` (scenario DSL for tests), `clob-sequencer` (codec, journal, sequencer, pipeline), `clob-marketdata` (market data), `clob-fix` (FIX 4.4 gateway), `clob-app` (node, admin, HA, audit), `clob-bench` (throughput, latency, JMH and market data benchmarks; see `clob-bench/SPIKE-RESULTS.md` and `docs/TUNING.md`).
