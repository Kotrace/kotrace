# ADR-045: report gate and the always-reading crash sink

Run `20261107-adr045`, measured 7 November 2026. Source, commands, timestamps and integrity checks are in [metadata.md](metadata.md). The raw baseline and candidate measurements are local JSON/log files alongside this document. Production code, the proposed ADR and README figures were not changed.

**Gate: PASS. All nine `REPORT_SKIP` cases pass the specified rule.** Across all 18 outcome cases, no time increase exceeds 5% with disjoint 99.9% intervals. This is a pass of the stated detection rule, not proof that every regression is smaller than 5%. In particular, four-adapter `OK` skip increases 8.42% in mean time but has overlapping intervals.

All 48 cases completed: 18 baseline, 18 candidate outcome and 12 crash-sink cases, with 720 finite primary measurements. The 240 primary/secondary scores are finite. No measurement was discarded or rerun.

## Reading the numbers

Time is **µs per report**; allocation is **bytes per report**, `gc.alloc.rate.norm`, not retained heap. Tables show mean ± JMH 99.9% confidence error. Interval comparisons use unrounded `scoreConfidence` bounds. Relative changes and ratios use means; they have no separately estimated confidence interval. Intervals are not adjusted for multiple comparisons. Overlap means the data do not resolve a difference by this conservative rule, not equivalence.

This is a sequential HotSpot microbenchmark inside the Codex sandbox. It measures a warmed report of a completed 128-span balanced fixture with counting/checksum sinks, not a vendor SDK, network, open-loop application load or attachment registration. The M1 Pro hardware identity is inherited from same-machine metadata; fresh hardware and process inspection were denied.

## 1. Gate: baseline versus candidate

Baseline is `48443f92f2d1d2c91f2b013ae263e83c7a372b68`; candidate is `722b74b95f33ffd47b1bb85857606161f9d3a133` plus the listed uncommitted benchmark changes. `ReportOutcomeBenchmark.java` is byte-identical in both commits and the measured working tree (SHA-1 `3b50fe080e0bc049e0a1c559a020a9a52c804988`). Both runs use the unchanged logical outcome fixture: 128 logs, no exceptions or attachments.

The gate fails a `REPORT_SKIP` case only when the mean increase is greater than 5% **and** the candidate interval lies wholly above baseline. The same predicate supplies an informational PASS/FAIL for consume rows; the acceptance gate applies to skip rows. Allocation is assessed separately. The existing `REPORT_SKIP` sink returns without reading records at every status, including `ERROR`.

| Adapters | Mode | Status | Baseline µs/report | Candidate µs/report | Change | Intervals | Verdict |
|---:|---|---|---:|---:|---:|---|---|
| 1 | REPORT_SKIP | OK | 0.901495 ± 0.050141 | 0.931325 ± 0.064313 | +3.31% | overlap | PASS |
| 1 | REPORT_SKIP | ERROR | 0.890668 ± 0.015483 | 0.901636 ± 0.035044 | +1.23% | overlap | PASS |
| 1 | REPORT_SKIP | CANCELLED | 0.877914 ± 0.021252 | 0.899014 ± 0.016741 | +2.40% | overlap | PASS |
| 1 | REPORT_CONSUME | OK | 12.732254 ± 0.192508 | 12.803568 ± 0.080116 | +0.56% | overlap | PASS |
| 1 | REPORT_CONSUME | ERROR | 12.764775 ± 0.359450 | 12.984844 ± 0.217908 | +1.72% | overlap | PASS |
| 1 | REPORT_CONSUME | CANCELLED | 13.118189 ± 1.465920 | 13.577803 ± 0.313453 | +3.50% | overlap | PASS |
| 4 | REPORT_SKIP | OK | 0.972412 ± 0.028074 | 1.054261 ± 0.064330 | +8.42% | overlap | PASS |
| 4 | REPORT_SKIP | ERROR | 1.002793 ± 0.010531 | 1.006840 ± 0.028347 | +0.40% | overlap | PASS |
| 4 | REPORT_SKIP | CANCELLED | 1.058711 ± 0.151356 | 0.990551 ± 0.026335 | -6.44% | overlap | PASS |
| 4 | REPORT_CONSUME | OK | 27.661814 ± 1.677602 | 26.003649 ± 0.325891 | -5.99% | overlap | PASS |
| 4 | REPORT_CONSUME | ERROR | 26.345034 ± 1.389141 | 25.998564 ± 0.272209 | -1.32% | overlap | PASS |
| 4 | REPORT_CONSUME | CANCELLED | 26.478322 ± 1.156201 | 26.335089 ± 0.536734 | -0.54% | overlap | PASS |
| 16 | REPORT_SKIP | OK | 1.627872 ± 0.083847 | 1.436678 ± 0.259779 | -11.75% | overlap | PASS |
| 16 | REPORT_SKIP | ERROR | 1.498947 ± 0.207713 | 1.320724 ± 0.129456 | -11.89% | overlap | PASS |
| 16 | REPORT_SKIP | CANCELLED | 1.545981 ± 0.185931 | 1.436136 ± 0.260414 | -7.11% | overlap | PASS |
| 16 | REPORT_CONSUME | OK | 80.423959 ± 4.342179 | 76.043804 ± 0.879569 | -5.45% | overlap | PASS |
| 16 | REPORT_CONSUME | ERROR | 78.860584 ± 2.498898 | 75.568948 ± 0.663201 | -4.17% | faster | PASS |
| 16 | REPORT_CONSUME | CANCELLED | 76.576337 ± 3.188471 | 75.958103 ± 1.379809 | -0.81% | overlap | PASS |

Allocation, assessed independently:

| Adapters | Mode | Status | Baseline B/report | Candidate B/report | Change B | Change % |
|---:|---|---|---:|---:|---:|---:|
| 1 | REPORT_SKIP | OK | 5440.006214 ± 0.000348 | 5448.006423 ± 0.000446 | +8.000 | +0.147% |
| 1 | REPORT_SKIP | ERROR | 5498.672803 ± 91.810148 | 5448.006204 ± 0.000241 | -50.667 | -0.921% |
| 1 | REPORT_SKIP | CANCELLED | 5440.006052 ± 0.000148 | 5448.006199 ± 0.000113 | +8.000 | +0.147% |
| 1 | REPORT_CONSUME | OK | 51184.087967 ± 0.001591 | 51192.088332 ± 0.000686 | +8.000 | +0.016% |
| 1 | REPORT_CONSUME | ERROR | 51184.087990 ± 0.002627 | 51192.089713 ± 0.001466 | +8.002 | +0.016% |
| 1 | REPORT_CONSUME | CANCELLED | 51184.090544 ± 0.010259 | 51192.093752 ± 0.002172 | +8.003 | +0.016% |
| 4 | REPORT_SKIP | OK | 6504.006696 ± 0.000199 | 6512.007270 ± 0.000426 | +8.001 | +0.123% |
| 4 | REPORT_SKIP | ERROR | 6504.006914 ± 0.000082 | 6512.006942 ± 0.000195 | +8.000 | +0.123% |
| 4 | REPORT_SKIP | CANCELLED | 6504.007296 ± 0.001042 | 6512.006819 ± 0.000179 | +8.000 | +0.123% |
| 4 | REPORT_CONSUME | OK | 77104.191047 ± 0.011532 | 77112.179577 ± 0.002071 | +7.989 | +0.010% |
| 4 | REPORT_CONSUME | ERROR | 77104.181951 ± 0.009698 | 77112.179622 ± 0.002018 | +7.998 | +0.010% |
| 4 | REPORT_CONSUME | CANCELLED | 77104.182703 ± 0.008091 | 77112.181688 ± 0.003693 | +7.999 | +0.010% |
| 16 | REPORT_SKIP | OK | 10056.011215 ± 0.000582 | 10064.009899 ± 0.001794 | +7.999 | +0.080% |
| 16 | REPORT_SKIP | ERROR | 10056.010325 ± 0.001429 | 10064.009094 ± 0.000888 | +7.999 | +0.080% |
| 16 | REPORT_SKIP | CANCELLED | 10056.010660 ± 0.001291 | 10064.009894 ± 0.001795 | +7.999 | +0.080% |
| 16 | REPORT_CONSUME | OK | 180808.557183 ± 0.030217 | 180816.527529 ± 0.006219 | +7.970 | +0.004% |
| 16 | REPORT_CONSUME | ERROR | 180808.547144 ± 0.017197 | 180816.523164 ± 0.006018 | +7.976 | +0.004% |
| 16 | REPORT_CONSUME | CANCELLED | 180808.530747 ± 0.022726 | 180816.526364 ± 0.009563 | +7.996 | +0.004% |

Allocation is about +8 B/report in 17 of 18 cases (roughly +0.004% to +0.15%). The exception is one-adapter `ERROR` skip: baseline is 5498.67 ± 91.81 B versus candidate 5448.01 B, an overlapping allocation interval. Do not call that mean reduction a stable saving. The no-attachment candidate does not show a large per-report allocation penalty. These figures do not measure collector construction or registry registration.

The four-adapter `OK` skip intervals are baseline [0.944, 1.001] µs and candidate [0.990, 1.119] µs. Its +8.42% mean increase remains unresolved. The 16-adapter skip means decrease by 7–12%, with broad, overlapping intervals; do not claim a proven improvement. No causal claim is made from these sequential runs.

### Historical machine-drift sanity check

The 2 November reference is `../20261102-fixed/main-gc.json`, matched by benchmark and all parameters. The rounded reference figures in the task were 0.949 µs / 5.5 KB for skip and 13.1 µs / 51.2 KB for consume. This comparison is a sanity check, never the gate.

| One adapter, OK | 2 Nov time µs | Baseline time µs | Candidate time µs | Baseline vs 2 Nov | Candidate vs 2 Nov | 2 Nov B | Baseline B | Candidate B |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| REPORT_SKIP | 0.948504 ± 0.026027 | 0.901495 ± 0.050141 | 0.931325 ± 0.064313 | -4.96% | -1.81% | 5498.67 | 5440.01 | 5448.01 |
| REPORT_CONSUME | 13.103824 ± 0.159250 | 12.732254 ± 0.192508 | 12.803568 ± 0.080116 | -2.84% | -2.29% | 51184.09 | 51184.09 | 51192.09 |

The new one-adapter means are modestly faster than the historical reference, while allocation remains close. Their historical time intervals overlap for skip; consume is faster with disjoint intervals. The two current runs are the gate pair, so this drift is not attributed to ADR-045. The raw files preserve fork/iteration variation; there was no order randomization or counterbalanced rerun.

## 2. Crash sink

The fixture uses the same balanced builder, with 128 logs and one child that throws after its subtree completes. Its parent recovers, and the root ends `OK`. Reports are explicitly submitted as `OK` or `ERROR`. Setup registers 0, 1 or 10 distinct failures through public `attachException` on fixture spans under their collector. One warm-up report resolves lazy messages; subsequent reports do not drain registrations. All fixture work, throwable construction and registration are outside timing.

The crash adapter accepts every report, calls `records.toList()` once, scans logs into a breadcrumb checksum, executes `filterIsInstance<ExceptionRecord>().filter { status == ERROR || origin == ATTACHED }`, then counts/checksums selected crashes. Temporary lists are local; no records are retained by the sink. Scalar diagnostic counts run in the measured code too. The numbers include this counting sink rather than a production crash SDK.

`ALONE` has only that adapter. `MIXED` adds three adapters whose non-`ERROR` branch returns without iterating, as `REPORT_SKIP` does. On `ERROR`, those three consume and checksum all records. This status-first behavior is intentional: the original outcome `REPORT_SKIP` benchmark skips even `ERROR`.

### All 12 cases beside candidate outcome anchors

The comparison uses the candidate's own outcome run: one adapter for `ALONE`, four for `MIXED`, at matching status. Anchors contain 128 logs and no recovered exception; the crash fixture contains one extra birthplace plus attachments. Thus these are comparable pipeline anchors, not a controlled measurement of only the sink branch. On `MIXED / OK`, one consumer and three skippers lie between homogeneous skip and consume. On `MIXED / ERROR`, all four read records.

| Attached | Layout | Status | Crash µs/report | Crash B/report | Skip µs/report | Skip B/report | Consume µs/report | Consume B/report |
|---:|---|---|---:|---:|---:|---:|---:|---:|
| 0 | ALONE | OK | 17.308638 ± 1.202646 | 60336.119457 ± 0.008184 | 0.931325 ± 0.064313 | 5448.006423 ± 0.000446 | 12.803568 ± 0.080116 | 51192.088332 ± 0.000686 |
| 0 | ALONE | ERROR | 17.278342 ± 1.028880 | 60392.119139 ± 0.007019 | 0.901636 ± 0.035044 | 5448.006204 ± 0.000241 | 12.984844 ± 0.217908 | 51192.089713 ± 0.001466 |
| 0 | MIXED | OK | 17.282276 ± 0.448775 | 61224.119269 ± 0.003110 | 1.054261 ± 0.064330 | 6512.007270 ± 0.000426 | 26.003649 ± 0.325891 | 77112.179577 ± 0.002071 |
| 0 | MIXED | ERROR | 33.090242 ± 3.242353 | 86480.228721 ± 0.022508 | 1.006840 ± 0.028347 | 6512.006942 ± 0.000195 | 25.998564 ± 0.272209 | 77112.179622 ± 0.002018 |
| 1 | ALONE | OK | 17.712208 ± 0.203164 | 64632.122232 ± 0.001225 | 0.931325 ± 0.064313 | 5448.006423 ± 0.000446 | 12.803568 ± 0.080116 | 51192.088332 ± 0.000686 |
| 1 | ALONE | ERROR | 17.862953 ± 0.404191 | 64632.123438 ± 0.003039 | 0.901636 ± 0.035044 | 5448.006204 ± 0.000241 | 12.984844 ± 0.217908 | 51192.089713 ± 0.001466 |
| 1 | MIXED | OK | 17.775651 ± 0.202820 | 65520.122705 ± 0.001289 | 1.054261 ± 0.064330 | 6512.007270 ± 0.000426 | 26.003649 ± 0.325891 | 77112.179577 ± 0.002071 |
| 1 | MIXED | ERROR | 33.227572 ± 0.686772 | 90888.229559 ± 0.004721 | 1.006840 ± 0.028347 | 6512.006942 ± 0.000195 | 25.998564 ± 0.272209 | 77112.179622 ± 0.002018 |
| 10 | ALONE | OK | 18.138837 ± 0.626747 | 65896.124892 ± 0.004375 | 0.931325 ± 0.064313 | 5448.006423 ± 0.000446 | 12.803568 ± 0.080116 | 51192.088332 ± 0.000686 |
| 10 | ALONE | ERROR | 17.990220 ± 0.352389 | 65976.123621 ± 0.002437 | 0.901636 ± 0.035044 | 5448.006204 ± 0.000241 | 12.984844 ± 0.217908 | 51192.089713 ± 0.001466 |
| 10 | MIXED | OK | 17.799587 ± 0.176183 | 66784.122250 ± 0.001489 | 1.054261 ± 0.064330 | 6512.007270 ± 0.000426 | 26.003649 ± 0.325891 | 77112.179577 ± 0.002071 |
| 10 | MIXED | ERROR | 33.351968 ± 0.135985 | 93744.229234 ± 0.000743 | 1.006840 ± 0.028347 | 6512.006942 ± 0.000195 | 25.998564 ± 0.272209 | 77112.179622 ± 0.002018 |

### Zero attachments on OK: the potential signal saving

| Layout | Crash mean µs | Skip mean µs | Avoidable difference µs | Crash / skip | Allocation difference B | Crash / skip allocation |
|---|---:|---:|---:|---:|---:|---:|
| ALONE | 17.309 | 0.931 | 16.377 | 18.58× | 54888.11 | 11.07× |
| MIXED | 17.282 | 1.054 | 16.228 | 16.39× | 54712.11 | 9.40× |

The zero-attachment `OK` crash sink pays **16.377 µs and 54,888 B/report above a single skipping adapter**: 18.58× the total time and 11.07× the total allocation. In `MIXED`, it pays **16.228 µs and 54,712 B/report above four skippers**: 16.39× time and 9.40× allocation. The time gaps are far larger than interval uncertainty. Subtracting endpoints gives [15.110, 17.644] µs for `ALONE` and [15.715, 16.741] µs for `MIXED`; these are conservative endpoint envelopes, not newly estimated 99.9% difference intervals.

A hypothetical zero-attachment signal could avoid approximately these gaps if the crash sink returns before consuming. That is an estimate, not a benchmark of an implemented signal: it assumes negligible signal overhead, leaves shared report freeze/dispatch work in place, and compares against a fixture with one fewer exception. It also assumes the consumer does not require breadcrumbs when there is no crash. At `ERROR`, a crash sink must still read birthplace exceptions regardless of whether anything is attached.

### Marginal attachments versus zero in the same workload

| Layout | Status | Attached | Extra mean µs vs 0 | Extra B/report vs 0 | Time intervals vs 0 |
|---|---|---:|---:|---:|---|
| ALONE | OK | 1 | +0.404 | +4296.00 | overlap |
| ALONE | OK | 10 | +0.830 | +5560.01 | overlap |
| ALONE | ERROR | 1 | +0.585 | +4240.00 | overlap |
| ALONE | ERROR | 10 | +0.712 | +5584.00 | overlap |
| MIXED | OK | 1 | +0.493 | +4296.00 | overlap |
| MIXED | OK | 10 | +0.517 | +5560.00 | overlap |
| MIXED | ERROR | 1 | +0.137 | +4408.00 | overlap |
| MIXED | ERROR | 10 | +0.262 | +7264.00 | overlap |

Every attachment-versus-zero time interval overlaps: the positive mean deltas are not resolved timing regressions. The first attachment adds about 4.2–4.4 KB/report; ten add 5.6–7.3 KB/report. These are report allocation costs, not the setup cost of allocating Throwable objects or registering them. The first-attachment step is not linear per failure; this run does not profile its cause.

### MIXED versus ALONE

| Attached | Status | Extra mean µs for MIXED | MIXED / ALONE | Extra B/report | Time intervals |
|---:|---|---:|---:|---:|---|
| 0 | OK | -0.026 | 0.998× | +888.00 | overlap |
| 0 | ERROR | +15.812 | 1.915× | +26088.11 | slower |
| 1 | OK | +0.063 | 1.004× | +888.00 | overlap |
| 1 | ERROR | +15.365 | 1.860× | +26256.11 | slower |
| 10 | OK | -0.339 | 0.981× | +888.00 | overlap |
| 10 | ERROR | +15.362 | 1.854× | +27768.11 | slower |

On `OK`, adding three skippers changes the mean by −0.339 to +0.063 µs with overlapping time intervals, and adds 888 B/report. The negative mean is noise, not evidence that adding adapters makes the pipeline faster. On `ERROR`, the three additional consumers add about 15–16 µs and 26–28 KB/report with disjoint time intervals; this is the expected different workload, not a skip regression.

## 3. hasAttached recommendation

**Do not add `hasAttached` yet; retain the ADR default.** The measured avoidable work is real and material as a ratio: an ideal signal on zero-attachment `OK` reports could cut roughly 16.3 µs and 54.8 KB per report, removing about 94% of the always-reading report time and 89–91% of its allocation in these layouts. This is a concrete optimization opportunity, not a demonstrated application-budget failure. No report frequency, non-error fraction, attached-failure frequency, end-to-end budget or actual signal implementation was measured.

For scale only, 100 such empty-attachment `OK` reports per second would turn the `ALONE` mean difference into roughly 1.64 ms accumulated report time and 5.49 MB allocation per second; 1,000 reports/s into 16.38 ms and 54.89 MB/s. These are arithmetic projections, not throughput or production-load measurements. A consumer's report volume could make the allocation saving important; these numbers do not establish that volume.

This is **HotSpot on the same-machine M1 Pro reference rig, not ART on a low-end Android device**. Sandbox restrictions also prevent independently ruling out unrelated application activity. Do not scale the microseconds to Android or infer a device threshold. If the consumer's measured report rate and device allocation/latency budget make the estimated saving necessary, benchmark a minimal signal on that target before expanding the API. Attachment-count mean timing differences and the small `OK` layout differences remain unresolved noise in this run. The skip gate passes; it does not decide ADR acceptance or Android performance.
