# FX CLOB

Low-latency, deterministic FX central limit order book in Java 21. See `requirements.md` for the spec and `PLAN.md` for the plan, implementation status and open questions.

```
./gradlew test        # build and run all tests
```

Modules: `clob-common` (primitives), `clob-refdata` (instruments, entitlements), `clob-credit` (bilateral credit), `clob-engine` (pure matching engine), `clob-testkit` (scenario DSL for tests), `clob-sequencer` (codec, journal, sequencer, pipeline), `clob-fix` (FIX 4.4 gateway; see `docs/FIX-RoE.md`), `clob-bench` (throughput/latency harness; see `clob-bench/SPIKE-RESULTS.md`).
