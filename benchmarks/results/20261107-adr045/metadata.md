# kotrace benchmark run: ADR-045 attachException

- Run identifier: `20261107-adr045`.
- Purpose: gate the no-attachment report path against the parent commit, then measure the Camailux-shaped crash sink that reads every report to deliver attached failures independently of outcome. ADR-045 remains Proposed; no acceptance decision or README figure was changed.
- First measurement launcher started: 2026-11-07T15:30:11.311446+00:00; last finished: 2026-11-07T15:56:00.508230+00:00.
- Baseline: `48443f92f2d1d2c91f2b013ae263e83c7a372b68` (`48443f9`, ADR text only; no core change since 0.7.0), detached worktree.
- Candidate measured HEAD: `722b74b95f33ffd47b1bb85857606161f9d3a133` (`722b74b`, local unpushed ADR-045 implementation), plus the uncommitted benchmark changes listed below.
- After this run, `722b74b` was amended into `2dc9645` to accept ADR-045. The amend changed only `DECISIONS.md` and ADR text (`git diff --name-only 722b74b 2dc9645`), so `2dc9645` holds the measured source; `722b74b` is no longer on a branch. The benchmark changes are committed in the next commit.
- Machine: arm64 confirmed with `uname -m`. Apple M1 Pro, 10 cores (8 performance / 2 efficiency), 10 logical processors and 32 GiB memory are inherited from the same-machine `20261102-fixed` reference. A fresh `sysctl` query was denied; this session could not independently confirm model, core split, memory, CPU affinity, clock/power state or thermal conditions.
- OS: macOS 27.0.1, build 26A434, confirmed with `sw_vers`, matching the reference.
- Environment caveat: all commands and measurements ran inside the **Codex sandbox**. Hardware inspection and `ps` process enumeration were denied. Only short log polls ran during measurement; no agent-initiated build, test, analysis or second benchmark ran alongside a standard-profile measurement. Unrelated application activity could not be independently ruled out.
- JVM: Oracle JDK 21.0.3, HotSpot 64-Bit Server VM, `21.0.3+7-LTS-152`; selected by the Oracle Java 21 toolchain and confirmed in each JMH JSON/log. Java executable: `/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home/bin/java`.
- Bytecode target: JVM 11. Gradle: 9.3.1 (wrapper). Kotlin compiler/plugin: 2.4.10; core language/API baseline 2.2, stdlib baseline 2.2.0. kotlinx.coroutines: 1.10.2. JMH: 1.37.
- GC/heap: G1, `-Xms1g -Xmx1g -XX:+UseG1GC -Dfile.encoding=UTF-8`. The Gradle task and `BenchmarkMain` both supply these flags, so they appear twice, as in the reference. Inherited locale flags: `-Duser.country=VN -Duser.language=en -Duser.variant`.
- Profile: AverageTime, one harness thread, three forks, five warm-up iterations × 1 s and five measurements × 1 s, `-prof gc -rf json`. Times are ns/op in raw JSON and µs/report in analysis; normalized allocation is B/report.

## Source state and workload

The working tree was clean on entry. These files were added or changed before preflight and measurement and remained unchanged through the final integrity capture:

- Added `benchmarks/src/main/java/dev/kotrace/benchmarks/CrashSinkBenchmark.java`.
- Added `benchmarks/src/main/kotlin/dev/kotrace/benchmarks/CrashSinkSupport.kt`.
- Changed `benchmarks/src/main/kotlin/dev/kotrace/benchmarks/BenchmarkSupport.kt` (shared balanced builder with trial-only registrations and a recovered child fixture).
- Changed `benchmarks/src/test/kotlin/dev/kotrace/benchmarks/BenchmarkPreflightTest.kt` (preflight across all 12 parameter combinations, including repeated reports).
- Changed `docs/benchmark-plan.md` (one Vietnamese workload-table row). The Vietnamese technical-writing validator passed with exit 0, no errors or warnings.

`ReportOutcomeBenchmark.java` was not edited. SHA-1 is `3b50fe080e0bc049e0a1c559a020a9a52c804988` in `git show 48443f9:<path>`, `git show 722b74b:<path>`, the baseline worktree and the candidate working tree, before and after measurement. The outcome fixture still has 128 balanced spans and 128 logs with no exceptions or attachments; builder changes execute outside timing.

The crash workload fixes the tree at BALANCED / 128 spans and uses exactly attached = 0, 1, 10 × status = OK, ERROR × layout = ALONE, MIXED (12 cases). It has 128 logs and one recovered birthplace exception; root status is checked OK at construction. Registrations use public `attachException` on fixture spans under the collector, in `@Setup(Level.Trial)`, and persist across reports. The setup report warms lazy messages.

The crash sink materializes `records.toList()` once, counts/checksums logs first, filters `ExceptionRecord` by ERROR or ATTACHED, then counts/checksums selected records. It keeps scalar diagnostics, not records or lists. ALONE has one crash adapter. MIXED adds three adapters that return without reading on OK and consume/checksum on ERROR. This follows the requested status-first behavior: the original REPORT_SKIP implementation itself skips every status, including ERROR. Analysis labels that distinction.

No file under root `src/main` or `src/test`, no ADR, no older result and no README was edited. No commit, amend, push, reset or checkout was performed in the main worktree. HEAD remains `722b74b95f33ffd47b1bb85857606161f9d3a133`.

## Execution and preflight

| Step | Started UTC | Finished UTC | Elapsed s | Exit | Cases |
|---|---|---|---:|---:|---:|
| baseline-report-outcome | 2026-11-07T15:30:11.311446+00:00 | 2026-11-07T15:39:31.253698+00:00 | 559.940 | 0 | 18 |
| candidate-report-outcome | 2026-11-07T15:39:53.890950+00:00 | 2026-11-07T15:49:21.862208+00:00 | 567.967 | 0 | 18 |
| candidate-crash-sink | 2026-11-07T15:49:45.931463+00:00 | 2026-11-07T15:56:00.508230+00:00 | 374.575 | 0 | 12 |

- `./gradlew :benchmarks:test`: exit 0, BUILD SUCCESSFUL, 8 tests. `preflight.log` preserves output.
- `./gradlew :benchmarks:test --rerun`: exit 0, reran the test task, 8 tests, no failures/errors/skips. `preflight-rerun.log` and `preflight-results.log` preserve output/XML.
- Smoke `-f 1 -wi 0 -i 1 -r 100ms`: exit 0, all 12 cases, finite scores. `smoke.json`, `smoke.log` and `smoke-launcher.log` are retained. Smoke is a harness check, not a performance result.
- Baseline `./gradlew :benchmarks:classes`: exit 0 before standard measurements; `baseline-build.log` is retained. Both source sets were built before measurement so no agent-initiated compilation competed with measurement.
- Gradle was always redirected directly to a log; no Gradle command was piped into head/tail. Each Gradle exit status was checked. Log polling used tail only on existing files.

Preflight checks every layout and registered count on both statuses twice: 128 breadcrumbs; OK selects exactly the registered count, all selected records ATTACHED, no selected birthplace; zero attachments selects nothing; ERROR selects all exceptions, exactly attached + 1 with one birthplace; MIXED consumes no records on OK and its crash adapter still reads; on ERROR each additional adapter reads 129 + attached records. Four report calls per session confirm repeated reads and a registry that does not drain.

## Source fingerprints

SHA-1 captured before the first standard-profile measurement at **2026-11-07T15:29:46.651573+00:00**, then after the last at **2026-11-07T15:56:19.035622+00:00**. Every hash in both source snapshots is unchanged. The first eleven rows match the files requested in `20261102-fixed/metadata.md`; the next two cover ADR-045, followed by added/changed benchmark sources, the fixed outcome class and extra configuration/document checks. `absent` is expected for the two new workload files in the baseline commit; the baseline never tries to compile them.

| File | Baseline SHA-1 | Candidate SHA-1 |
|---|---|---|
| `src/main/kotlin/dev/kotrace/report/Report.kt` | `d35881fe270ef634dc350269c045e4f510f68711` | `8477ff7b28e4b82c9fd300d0f0616fd9eb776f36` |
| `src/main/kotlin/dev/kotrace/report/TraceTreeIndex.kt` | `fe26823a4fd8e43b9f4e8e8d7016b204485ff754` | `fe26823a4fd8e43b9f4e8e8d7016b204485ff754` |
| `src/main/kotlin/dev/kotrace/report/TraceFormat.kt` | `1f624dcbbaeb5321bca7163f331e020974fee50a` | `1f624dcbbaeb5321bca7163f331e020974fee50a` |
| `src/main/kotlin/dev/kotrace/Trace.kt` | `0c4557a1420b51cfcebb257d6388bac617fadf48` | `0c4557a1420b51cfcebb257d6388bac617fadf48` |
| `src/main/kotlin/dev/kotrace/Span.kt` | `a2393bf58dd082850fb2cc380f90969483d875a7` | `996eaceb37942edc6aef134b98345db883b751a2` |
| `src/main/kotlin/dev/kotrace/event/Emit.kt` | `a14a69ffeb11585d972e50c3cf8d98dbcb0171b1` | `a14a69ffeb11585d972e50c3cf8d98dbcb0171b1` |
| `src/main/kotlin/dev/kotrace/internal/FaultIsolation.kt` | `4f73592f4c7bb0a424887a5a9948ea328849c1ef` | `4f73592f4c7bb0a424887a5a9948ea328849c1ef` |
| `benchmarks/src/main/kotlin/dev/kotrace/benchmarks/BenchmarkSupport.kt` | `b97fc485526ce232f1200cfd9c3192ddba5848e8` | `9beb9a854d75aeadbece6e36f04673415a46d387` |
| `benchmarks/src/test/kotlin/dev/kotrace/benchmarks/BenchmarkPreflightTest.kt` | `331368655b9b55b288bcb43ba694afd99830d51a` | `82663782d01d7649dab1aded88769c03bec882de` |
| `src/main/kotlin/dev/kotrace/ThreadMirror.kt` | `387c45c615561f0b8553ca51a618bf6b5037be5b` | `387c45c615561f0b8553ca51a618bf6b5037be5b` |
| `src/main/kotlin/dev/kotrace/event/SpanRecord.kt` | `a5beba2e6ceb2ace335279142bab6a855594970b` | `a5beba2e6ceb2ace335279142bab6a855594970b` |
| `src/main/kotlin/dev/kotrace/SpanCollector.kt` | `a9b82358491a8ac0c10d89b00748289fa579df2e` | `4b2f7f8f8641940bbd1cc4f633d5e14f1cec0bfb` |
| `src/main/kotlin/dev/kotrace/event/TraceException.kt` | `78a8d0a5cdc8befc2601a43e5397ade362aeee45` | `779654df28781edf5f018cfc442a892d3a0e010b` |
| `benchmarks/src/main/java/dev/kotrace/benchmarks/ReportOutcomeBenchmark.java` | `3b50fe080e0bc049e0a1c559a020a9a52c804988` | `3b50fe080e0bc049e0a1c559a020a9a52c804988` |
| `benchmarks/src/main/java/dev/kotrace/benchmarks/CrashSinkBenchmark.java` | `absent` | `8a47c74d360564e13513e0475253e71ce909af1c` |
| `benchmarks/src/main/kotlin/dev/kotrace/benchmarks/CrashSinkSupport.kt` | `absent` | `a6e695e1f880ba2e7236071627e9fafc44ccf084` |
| `benchmarks/src/main/java/dev/kotrace/benchmarks/BenchmarkMain.java` | `0aea65370764cb14280a29eb3bd00ee193c266cd` | `0aea65370764cb14280a29eb3bd00ee193c266cd` |
| `benchmarks/build.gradle.kts` | `a3bfaf0fb105834843052af9bf2e8ff6461ec90a` | `a3bfaf0fb105834843052af9bf2e8ff6461ec90a` |
| `gradle/libs.versions.toml` | `4c4d02e7464f3ce12a7afec58136d08eabfbc671` | `4c4d02e7464f3ce12a7afec58136d08eabfbc671` |
| `docs/benchmark-plan.md` | `3dd3c5661a2956a7bcf976742fbf1ac29bf01ace` | `5a8704042c216756467d7ed9d5cfb72cfe567ee8` |

`integrity-before.json` and `integrity-after.json` preserve all hashes, HEADs, paths and timestamps. In addition to the requested source hashes, they verify all **199 initially tracked files outside results** and all **77 pre-existing result files** unchanged. After writing results, a final focused check reconfirmed the source hashes and main HEAD. Metadata and analysis were written after the measurement snapshots.

## Integrity and interpretation checks

- Exactly 18 baseline outcome + 18 candidate outcome + 12 candidate crash cases, with distinct benchmark/complete-parameter keys. Baseline and candidate outcome keys match exactly.
- Every standard-profile case has one thread, 3 forks, 5 × 1 s warm-ups and 5 × 1 s measurements. Each primary metric and normalized allocation has exactly 3 arrays of 5 raw measurements.
- All 48 primary scores and all **240 primary/secondary scores** are numeric and finite; all available primary/secondary raw measurements are finite. Primary and normalized-allocation errors and confidence endpoints are finite. There are exactly **720 primary raw measurements** (270 + 270 + 180).
- Each standard measurement launcher exited 0. Harness and launcher logs have no failed fork, benchmark failure, uncaught thread exception, timeout or build failure. Each run completed normally and wrote its final JSON.
- The three launch intervals do not overlap. No standard measurement was interrupted, discarded, replaced or repeated. Only cheap existing-log polls ran during each measurement. External application activity remains unobservable because process enumeration was denied.
- `comparison.json` stores unrounded 18-case gate calculations. `saving-estimates.json` stores the ideal-signal comparison and endpoint-difference envelopes; those envelopes are not newly estimated 99.9% difference intervals. `validation.json` stores counts/check results. `analyze.log` contains the reproducible analysis/validation script.
- The specified >5% / disjoint-interval time gate passes all 9 skip cases. Four-adapter OK skip has a +8.42% mean increase with overlapping intervals and remains unresolved. Allocation is inspected independently. Full 18-case and 12-case tables are in analysis.md.

## Worktree creation, removal and environment diagnostics

The requested direct worktree creation was attempted first:

```text
git worktree add --detach /private/tmp/kotrace-baseline-adr045 48443f9
```

It exited 128 because the sandbox exposes the main `.git` read-only: `could not create leading directories ... Operation not permitted`. No measurement started from that failed attempt. To obtain an actual detached Git worktree without writing main Git metadata, the session made a temporary bare clone and created the worktree through that clone:

```text
git clone --bare --no-hardlinks . /private/tmp/kotrace-adr045-mirror.git
git --git-dir=/private/tmp/kotrace-adr045-mirror.git worktree add --detach /private/tmp/kotrace-baseline-adr045 48443f9
```

Both commands exited 0. This copies committed history, so candidate uncommitted sources cannot enter the baseline. ANDROID_HOME was already `/Users/trungdao/Library/Android/sdk`; no local.properties was created or edited. After the final baseline/candidate fingerprint capture, these commands all exited 0:

```text
git --git-dir=/private/tmp/kotrace-adr045-mirror.git worktree remove /private/tmp/kotrace-baseline-adr045
git --git-dir=/private/tmp/kotrace-adr045-mirror.git worktree prune
git --git-dir=/private/tmp/kotrace-adr045-mirror.git worktree list
```

The final list contained only the bare temporary clone. The detached worktree was removed and its metadata pruned; the temporary bare clone is left outside the repository as provenance. The main repository worktree metadata was not modified.

Other diagnostics and recoveries:

- `RTK.md`, referenced by the supplied instructions, is absent, as in the prior run.
- The first validator command used `python`, which is unavailable (exit 127); rerunning with `python3` passed.
- The initial shell background-launch syntax emitted `nice(5) failed: operation not permitted` and never created a status file or started JMH. A Python `subprocess.Popen(..., start_new_session=True)` supervisor then launched the recorded baseline run. No measurement was discarded or overlapped as a result.
- During initial preflight, Kotlin daemon client-marker creation under Library/Application Support was denied. Gradle used its documented fallback compiler and the build/tests exited 0. The log contains that compiler diagnostic stack trace, not a JMH fork error.
- Gradle warned it could not start the FSEvents stream; Android analytics settings were not writable. Neither diagnostic failed tests or measurements.
- `sysctl` and `ps` were denied. OS and arm64 are confirmed; finer hardware/process details remain qualified above.

## Exact commands

All commands ran from the main repository except the baseline build and first measurement, whose cwd was `/private/tmp/kotrace-baseline-adr045`. Standard-profile result paths are absolute. The archive `run-launcher.log` is Python source, not runtime output; it records UTC start/finish, exact argv, cwd, PID, elapsed seconds and exit status in each `*-status.json`, and directs Gradle output to each `*-launcher.log`. The supervisor disconnects stdin and starts a new session.

Preflight and smoke:

```sh
./gradlew :benchmarks:test > benchmarks/results/20261107-adr045/preflight.log 2>&1
./gradlew :benchmarks:test --rerun > benchmarks/results/20261107-adr045/preflight-rerun.log 2>&1
./gradlew :benchmarks:jmh '-PjmhArgs=.*CrashSinkBenchmark.* -f 1 -wi 0 -i 1 -r 100ms -rf json -rff benchmarks/results/20261107-adr045/smoke.json -o benchmarks/results/20261107-adr045/smoke.log' > benchmarks/results/20261107-adr045/smoke-launcher.log 2>&1
```

Baseline preparation (cwd = detached worktree):

```sh
./gradlew :benchmarks:classes > /Users/trungdao/Work/Others/LuxGroup/kotrace/benchmarks/results/20261107-adr045/baseline-build.log 2>&1
```

Measurements, in this order; stdout/stderr redirected by the archived launcher:

```sh
./gradlew :benchmarks:jmh '-PjmhArgs=.*ReportOutcomeBenchmark.* -f 3 -wi 5 -i 5 -w 1s -r 1s -prof gc -rf json -rff /Users/trungdao/Work/Others/LuxGroup/kotrace/benchmarks/results/20261107-adr045/baseline-report-outcome.json -o /Users/trungdao/Work/Others/LuxGroup/kotrace/benchmarks/results/20261107-adr045/baseline-report-outcome.log'
./gradlew :benchmarks:jmh '-PjmhArgs=.*ReportOutcomeBenchmark.* -f 3 -wi 5 -i 5 -w 1s -r 1s -prof gc -rf json -rff /Users/trungdao/Work/Others/LuxGroup/kotrace/benchmarks/results/20261107-adr045/candidate-report-outcome.json -o /Users/trungdao/Work/Others/LuxGroup/kotrace/benchmarks/results/20261107-adr045/candidate-report-outcome.log'
./gradlew :benchmarks:jmh '-PjmhArgs=.*CrashSinkBenchmark.* -f 3 -wi 5 -i 5 -w 1s -r 1s -prof gc -rf json -rff /Users/trungdao/Work/Others/LuxGroup/kotrace/benchmarks/results/20261107-adr045/candidate-crash-sink.json -o /Users/trungdao/Work/Others/LuxGroup/kotrace/benchmarks/results/20261107-adr045/candidate-crash-sink.log'
```

Source capture and analysis:

```sh
python3 benchmarks/results/20261107-adr045/fingerprint.log before
python3 benchmarks/results/20261107-adr045/fingerprint.log after
python3 benchmarks/results/20261107-adr045/analyze.log
```

Raw JSON, harness logs, launcher/supervisor logs, fingerprints, validation, scripts and status records are retained locally in this results directory. Repository ignore rules exclude `*.json` and `*.log`; the reviewable Git additions here are metadata.md and analysis.md. No raw output was deleted.
