package dev.kotrace.benchmarks

import dev.kotrace.TraceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkPreflightTest {
    @Test
    fun crashSinkSelectsAttachmentsIndependentlyOfOutcomeAndDoesNotDrain() {
        listOf(0, 1, 10).forEach { attached ->
            listOf("ALONE", "MIXED").forEach { layout ->
                val fixture = BenchWorkloads.buildCrashSinkFixture(attached)
                assertEquals(128, fixture.spanCount)
                CrashSinkSession(layout).use { session ->
                    session.enter()
                    // Repeated reports share one trial fixture: no registry drain or record retention.
                    repeat(2) {
                        session.report(fixture, TraceStatus.OK)
                        assertEquals(attached, session.crash.selected)
                        assertEquals(attached, session.crash.selectedAttached)
                        assertEquals(0, session.crash.selectedBirthplaces)
                        assertEquals(attached + 1, session.crash.exceptions)
                        assertEquals(128, session.crash.breadcrumbs)
                        assertEquals(0, session.skippingRecordCount())

                        session.report(fixture, TraceStatus.ERROR)
                        assertEquals(attached + 1, session.crash.selected)
                        assertEquals(session.crash.exceptions, session.crash.selected)
                        assertEquals(attached, session.crash.selectedAttached)
                        assertEquals(1, session.crash.selectedBirthplaces)
                        assertEquals(128, session.crash.breadcrumbs)
                        assertEquals(if (layout == "MIXED") 3 * (129 + attached) else 0,
                            session.skippingRecordCount())
                    }
                    assertEquals(4, session.crash.reads)
                }
            }
        }
    }

    @Test
    fun lazyMessageIsResolvedOnlyAfterLivePolicyAccepts() {
        assertEquals(0, BenchWorkloads.lazyMessageCalls("NONE", 20))
        assertEquals(0, BenchWorkloads.lazyMessageCalls("LIVE_REJECT", 20))
        assertEquals(20, BenchWorkloads.lazyMessageCalls("LIVE_ACCEPT", 20))
    }

    @Test
    fun reportFixturesContainTheDeclaredNumberOfSpansAndRecords() {
        listOf("CHAIN", "STAR", "BALANCED").forEach { shape ->
            val fixture = BenchWorkloads.buildReportFixture(shape, 31)
            assertEquals(31, fixture.spanCount)

            BenchSession("REPORT_CONSUME").use { session ->
                session.enter()
                session.report(fixture, TraceStatus.OK)
                assertEquals(31, session.reportRecordCount())
            }
        }
    }

    @Test
    fun exceptionReportFixtureHasOneBirthplaceRecordPerChild() {
        val fixture = BenchWorkloads.buildExceptionReportFixture(31)
        assertEquals(31, fixture.spanCount)

        BenchSession("REPORT_CONSUME").use { session ->
            session.enter()
            session.report(fixture, TraceStatus.ERROR)
            assertEquals(30, session.reportRecordCount())
        }
    }

    @Test
    fun b01RecoverAndRethrowDifferentReportsBothFailures() {
        // ADR-015: the recover-and-rethrow-different flow used to drop the escaping B (IllegalStateException)
        // and report only the recovered A (IllegalArgumentException). With lineage-key dedup both now report,
        // each at its own span, so the escaping failure is no longer hidden.
        val observed = BenchWorkloads.b01ObservedExceptionTypes()
        assertEquals(
            "both the escaping B and the recovered A report, deepest-visited first",
            listOf("IllegalStateException", "IllegalArgumentException"),
            observed,
        )
        assertTrue("escaping B is no longer dropped (B01 fixed)", observed.contains("IllegalStateException"))
    }

    @Test
    fun cappedBatchKeepsTheCapAndCountsEveryEvictedEvent() {
        BenchSession("NONE").use { session ->
            session.enter()
            assertEquals("100 kept plus 1,900 dropped", 2000L, session.captureCappedBatch(2000, 100))
        }
    }

    @Test
    fun sharedSpanEvictsOnlyInTheCappedMode() {
        ConcurrencyHarness("SHARED").use { harness ->
            harness.run(16, 100)
            assertEquals("a cap above the workload evicts nothing", 0, harness.lastDropped)
        }
        ConcurrencyHarness("SHARED_CAPPED").use { harness ->
            harness.run(16, 100)
            assertTrue("1,600 events pass the default cap", harness.lastDropped > 0)
        }
    }

    @Test
    fun concurrentHarnessCompletesAllModes() {
        listOf("BASELINE", "SHARED", "SHARED_CAPPED", "CHILDREN").forEach { mode ->
            ConcurrencyHarness(mode).use { harness ->
                assertTrue("$mode did not produce an observable result", harness.run(4, 20) != 0L)
            }
        }
    }
}
