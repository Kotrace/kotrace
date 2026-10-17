package dev.kotrace.room

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The README's Room snippet, compiled against this module. The `README snippet` region is the README block
 * verbatim (imports aside); the core `ReadmeSnippetsTest` fails if the two drift. Never executed.
 */
@Suppress("unused")
internal object ReadmeSnippets {

    abstract class AppDatabase : RoomDatabase()

    fun setup(context: Context) {
        // README snippet: room-setup
        Room.databaseBuilder(context, AppDatabase::class.java, "app.db")
            .tracing(mapOf("level" to "DEBUG"))   // attributes stamped on each SQL log; kotrace holds no level taxonomy
            .build()
        // end README snippet
    }
}
