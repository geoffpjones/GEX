# FX CLOB

Low-latency, deterministic FX central limit order book in Java 21. See `requirements.md` for the spec and `PLAN.md` for the plan, implementation status and open questions.

```
./gradlew test        # build and run all tests
```

Modules: `clob-common` (primitives), `clob-refdata` (instruments, entitlements), `clob-engine` (pure matching engine), `clob-testkit` (scenario DSL for tests), `clob-bench` (throughput/latency harness; see `clob-bench/SPIKE-RESULTS.md`).
