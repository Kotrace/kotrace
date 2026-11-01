// Tests inspect the raw span timeline (ADR-028); nothing here reaches a sink.
@file:OptIn(dev.kotrace.UnredactedTraceRead::class)

package dev.kotrace.room

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.kotrace.SpanCollector
import dev.kotrace.TraceConfig
import dev.kotrace.event.LogEvent
import dev.kotrace.span
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Collections

@Entity(tableName = "account")
data class Account(@PrimaryKey val id: Long, val mail: String)

@Dao
interface AccountDao {
    @Insert
    suspend fun insert(account: Account): Long

    @Query("SELECT * FROM account WHERE mail = :mail")
    suspend fun byMail(mail: String): Account?
}

@Database(entities = [Account::class], version = 1, exportSchema = false)
abstract class AccountDatabase : RoomDatabase() {
    abstract fun accounts(): AccountDao
}

/**
 * Runs [tracing] on a real in-memory Room database, where [TracingQueryCallbackTest] mocks the builder: Room itself
 * runs a suspend query on its own executor thread, the caller's span reaches that thread, and Room calls the
 * callback inline there, so each statement is logged onto the span the query ran under. A suspend read is the case
 * the query executor exists for: Room 2.6 launches it outside the caller's coroutine.
 */
@RunWith(RobolectricTestRunner::class)
class TracingRoomDatabaseTest {

    /** The thread each query callback ran on, recorded by the app's own callback. */
    private val queryThreads: MutableList<Thread> = Collections.synchronizedList(mutableListOf())

    private val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AccountDatabase::class.java)
        .tracing(mapOf("level" to "DEBUG"), delegate = { _, _ -> queryThreads += Thread.currentThread() })
        .build()

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun `a suspend query logs its SQL onto the caller's span from Room's thread, never the bound values`() = runTest {
        val collector = SpanCollector()
        withContext(collector + TraceConfig(emptyList())) {
            span("account.save") {
                database.accounts().insert(Account(id = 1, mail = "user@example.com"))
                database.accounts().byMail("user@example.com")
            }
        }

        val logs = collector.spans.single().events.filterIsInstance<LogEvent>().map { it.message }
        assertTrue("the insert is logged: $logs", logs.any { it.startsWith("SQL INSERT") && "account" in it })
        assertTrue("the select is logged: $logs", logs.any { it == "SQL SELECT * FROM account WHERE mail = ?" })
        assertFalse("no bound value is logged: $logs", logs.any { "user@example.com" in it })
        assertTrue("Room ran the callback", queryThreads.isNotEmpty())
        queryThreads.forEach { assertNotSame("Room ran the query on its own thread", Thread.currentThread(), it) }
    }

    @Test
    fun `concurrent reads each log onto their own span`() = runTest {
        val collector = SpanCollector()
        withContext(collector + TraceConfig(emptyList())) {
            coroutineScope {
                launch { span("first") { database.accounts().byMail("first@example.com") } }
                launch { span("second") { database.accounts().byMail("second@example.com") } }
            }
        }

        assertEquals(listOf("first", "second"), collector.spans.map { it.name }.sorted())
        collector.spans.forEach { span ->
            val logs = span.events.filterIsInstance<LogEvent>().map { it.message }
            assertEquals("${span.name} holds its own read only", listOf("SQL SELECT * FROM account WHERE mail = ?"), logs)
        }
    }

    @Test
    fun `a query under no span logs nothing and still reaches the app's callback`() = runTest {
        val collector = SpanCollector()
        withContext(collector + TraceConfig(emptyList())) {
            database.accounts().byMail("user@example.com")
        }

        assertEquals("no span opened, so nothing collected", emptyList<Any>(), collector.spans)
        assertTrue("the app's callback received the query", queryThreads.isNotEmpty())
    }
}
