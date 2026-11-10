# Requirement and review traceability

Each row names the automated test that demonstrates the item. "Not automated" means no test can establish it here. Requirement sections refer to `requirements.md`; R-numbers to `review.md`.

## Review findings

| Finding | Fix | Evidence |
|---|---|---|
| R01 order ids restart | Id high-water mark rebuilt from the journal | `NodeLifecycleTest.restartKeepsOrderControlSequenceNumbersAndUniqueIds`, `promotionKeepsOrderControl` |
| R02 reconnect/restart orphans orders | `LogicalSession` and `OrderRegistry`, rebuilt from history | `OrderIdentityTest.reconnectKeepsControlOfLiveOrders`, `reportsMissedWhileDisconnectedAreResent`; `NodeLifecycleTest.pendingFixingMatchIsReportedAfterRestart`, `abruptProcessDeathRecovers` |
| R03 cancel/replace correlation | Request ids end to end; unique ClOrdIDs | `OrderIdentityTest.aRequestCannotReuseAnotherOrdersClOrdId`, `overlappingRequestsAreEachAnswered`; `AgreedBehaviourTest.requestIdsCorrelateEveryOutcome` |
| R04 circular backpressure | Gateway never blocks; market data conflates | `OrderIdentityTest.tinyRingsDoNotDeadlock` |
| R05 GTT after expiry in a race | Expiry scheduled on joining a race; checked at application | `ExpiryInRaceTest` |
| R06 NOP without value dates | Required dates checked before any limited line; business date tracked | `CreditInvariantsTest.nopWithoutValueDateHasNoCapacity`, `rollCannotEraseExposureAndStaleDatesBlock` |
| R07 credit overflow partial commit | One measure per line, bounded buckets, preflight | `CreditInvariantsTest.grossTurnoverCannotOverflowAndBothSidesCommitTogether`, `perDateBucketsAreBoundedAcrossAlternatingDates`, `failedCommitChangesNeitherLine` |
| R08 unversioned recovery config | `JournalManifest`, stable wire ids | `ManifestAndWireTest`, `NodeLifecycleTest.changedSeedOrRefDataFailsStartup`, `incompatibleStandbyIsRefused` |
| R09 corruption destroys suffix | Torn tail told apart from corruption; segment and sequence checks | `JournalTest.corruptionBeforeValidRecordsFailsAndKeepsThem`, `zeroedLengthInTheMiddleIsCorruption`, `sequenceGapIsCorruption`, `wrongSegmentHeaderIsRejected`, `missingRolloverMarkerIsCorruption` |
| R10 invalid admin input | Typed refusals before mutation; HTTP boundary validated | `CreditInvariantsTest.refusedInputsChangeNothing`, `NodeLifecycleTest.invalidAdminInstrumentIsABadRequest` |
| R11 shutdown | Staged close to a watermark | `NodeLifecycleTest.closeAppliesCancelOnDisconnectWhileSessionsAreActive`, `closeDrainsQueuedWorkToOps`, `abruptProcessDeathRecovers` (SIGTERM) |
| R12 slow standby, loopback HA | Replication tails the journal; bind addresses configurable; deadlines | `NodeLifecycleTest.aStandbyThatStopsReadingCannotStallThePrimary`, `aSilentConnectionDoesNotBlockARealStandby`. A two-host run is **not automated** |
| R13 failure not in readiness | Node state machine; liveness and readiness split | `NodeLifecycleTest.aFatalErrorDropsReadinessAndRefusesIngress`, `anUnreadOutputRingCannotHangShutdown` |
| R14 fixing time not enforced | Early rate refused (D5) | `ExpiryInRaceTest.earlyFixingRateIsRefused` |
| R15 MD interval cadence | Intervals validated against the tick; missed grid points honoured | `NodeLifecycleTest.configurationIsValidatedAtStartup`, `HeadroomScreeningTest.aLateTickDoesNotPostponeAnInstrument` |
| R16 retained gateway state | Bounded resend ring, ClOrdID rollover at end of day, timer compaction, MD not stored | `AgreedBehaviourTest.endOfDayCompactsCancelledLongDatedTimers`. Day-long heap soak **not run** |
| R17 FIX validation | Checked numerics, bounds, per-message identity, containment | `OrderIdentityTest.malformedMessagesOnlyAffectTheirConnection`, `mismatchedCompIdsAfterLogonEndTheSession`, `oversizedBodyLengthDropsTheConnection`, `parserRejectsOverflowAndDigitlessNumbers` |
| R18 UDP not wired | FIX subscription to the session's own IP, periodic refresh | `NodeLifecycleTest.udpMarketDataReachesTheSubscriber`, `HeadroomScreeningTest.anUnchangedViewIsResentOnTheRefreshInterval` |
| R19 Prometheus metadata | Family, labels and one TYPE line each | `NodeLifecycleTest.metricsAreValidPrometheus` |
| R20 refdata can't restrict types | `orderTypes=`, `tifs=` | `NodeLifecycleTest.refDataCanRestrictTypesAndTifs` |

## Requirements

| ID | Requirement | Evidence |
|---|---|---|
| REQ-1 | Instruments, price and quantity rules (§1) | `InstrumentTest`, `RefDataTest`, `ValidationTest`, `IcebergAndMinQtyTest` |
| REQ-2 | User entitlement (§2) | `ValidationTest` |
| REQ-3.1 | Bilateral credit, DSL, NOP, unlimited (§3) | `CreditEngineTest`, `CreditMatchingTest`, `RandomCreditFlowTest` |
| REQ-3.2 | Atomic accounting and value dates (§3) | `CreditInvariantsTest` |
| REQ-3.3 | Entity-owned pools (§3) | `AgreedBehaviourTest.accountsOfOneEntityShareACreditPool` |
| REQ-3.4 | Self-trade prevention (§3) | `AgreedBehaviourTest.selfTrade*`, `fokStoppedBySelfTradeHasNoFillsAndNamesTheReason`, `midAndFixingSelfTrade` |
| REQ-3.5 | Missing-data halt with auto-resume (§3) | `AgreedBehaviourTest.anInstrumentResumesWhenItsDataArrives`, `arrivingDataNeverReopensAnAdminHalt`, `adminOpenDoesNotOverrideMissingData` |
| REQ-3.6 | SWAP far leg after near settlement (§3) | `CreditInvariantsTest.swapFarLegBecomesNopExposureAfterNearSettlesAndIsReported` |
| REQ-4.1 | Order types and TIFs (§4) | `LimitAndTifTest`, `AgreedBehaviourTest.marketOrderWithRestingTifIsRejected` |
| REQ-4.2 | Iceberg refresh (§4) | `IcebergAndMinQtyTest` |
| REQ-4.3 | Pegged orders (§4) | `PeggedOrderTest` |
| REQ-4.4 | Fixing orders (§4) | `FixingOrderTest`, `ExpiryInRaceTest.earlyFixingRateIsRefused` |
| REQ-4.5 | End of day at 17:00 New York (§4) | `SequencerTest.endOfDayFiresAtTheLocalTimeAcrossDstAndOnceAfterAnOutage` |
| REQ-5.1 | VISIBILITY and PRICE priority (§5) | `VisibilityPriorityTest`, `RandomFlowTest` |
| REQ-5.2 | Resting against hidden liquidity (§5) | `AgreedBehaviourTest.newlyRestingDisplayedOrderTradesWithCrossingHiddenAtTheHiddenPrice` and neighbours |
| REQ-6 | Mid orders (§6) | `MidOrderTest` |
| REQ-7.1 | Taker and maker races (§7) | `RaceTest`, `ExpiryInRaceTest` |
| REQ-7.2 | Amends into maker races (§7) | `AgreedBehaviourTest.amendCreatingANewLevelEntersAMakerRace` |
| REQ-7.3 | Halts cancel waiting race orders (§7) | `SessionTest.aHaltCancelsOrdersWaitingInARace`, `AgreedBehaviourTest.haltCancelsMakerRaceOrdersAndReleasesHolds` |
| REQ-7.4 | Pre-open and opening lottery (§7) | `AgreedBehaviourTest.preOpen*` |
| REQ-8 | Cancels, amends, TTL, holds (§8) | `CancelReplaceTest`, `AgreedBehaviourTest.onlyTheSideBeingTakenIsHeld`, `aTakerCannotWithdrawOnceInTheRace`, `holdEndsWithItsOwnRaceNotAnOtherSidesLaterRace` |
| REQ-9.1 | Market data bucketing, rotation, change-only (§9) | `MarketDataServiceTest` |
| REQ-9.2 | Screening reflects headroom (§9) | `HeadroomScreeningTest` |
| REQ-9.3 | Every consumer inside the interval (§9) | **Not met on the development host.** See `clob-bench/SPIKE-RESULTS.md` and D8 |
| REQ-10 | FIX, TCP and UDP (§10) | `QuickFixConformanceTest`, `SessionLayerTest`, `NodeLifecycleTest.udpMarketDataReachesTheSubscriber`. FAST and ITCH are not built |
| REQ-11 | Capacity (§11) | **Not established.** See D8 |
| REQ-12 | Determinism and replay (§12) | `DeterminismTest`, `ReplayTest`, `PipelineTest`, `SnapshotEquivalenceTest` |

Zero allocation on the engine thread (the bounded workload with a null listener, including fixing orders) is checked by `AllocationTest`. It does not describe the whole node.
