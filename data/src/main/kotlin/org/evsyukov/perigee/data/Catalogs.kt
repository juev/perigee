package org.evsyukov.perigee.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** The OPDS catalogs the reader added, with the passwords to them. */
class Catalogs internal constructor(database: ReadingDatabase, private val now: () -> Long) {
    private val dao = database.dao()

    fun all(): Flow<List<Catalog>> = dao.catalogs()

    /** Adds a catalog; one that is there already, by its address, takes the new title, user and password. */
    suspend fun add(url: String, title: String, user: String, password: String): Catalog {
        val secret = withContext(Dispatchers.Default) { Secrets.seal(password) }
        dao.insert(Catalog(url = url, title = title, user = user, secret = secret, addedAt = now()))
        dao.updateCatalog(url, title, user, secret)
        return checkNotNull(dao.catalog(url))
    }

    suspend fun remove(id: Long) = dao.deleteCatalog(id)

    /** The password of a catalog; null when it cannot be read any more and has to be entered again. */
    suspend fun password(catalog: Catalog): String? = withContext(Dispatchers.Default) { Secrets.open(catalog.secret) }

    companion object {
        @Volatile
        private var instance: Catalogs? = null

        fun get(context: Context): Catalogs = instance ?: synchronized(this) {
            instance ?: Catalogs(ReadingDatabase.get(context), System::currentTimeMillis).also { instance = it }
        }

        /** Catalogs that are forgotten when the process ends, for tests. */
        fun inMemory(context: Context): Catalogs = Catalogs(ReadingDatabase.inMemory(context), System::currentTimeMillis)
    }
}
