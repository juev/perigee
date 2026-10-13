package org.evsyukov.perigee.data

import android.content.Context
import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** The server the reader signed in to, with the secrets of the session. */
class Accounts internal constructor(private val database: ReadingDatabase, private val now: () -> Long) {
    private val dao = database.dao()

    /** Null while the reader is signed in nowhere. */
    fun account(): Flow<Account?> = dao.accountFlow()

    suspend fun current(): Account? = dao.account()

    /**
     * Remembers a server the reader signed in to, in place of the one before.
     * What was known of the other server's books is forgotten.
     */
    suspend fun signIn(url: String, user: String, name: String, tokens: Tokens, serverVersion: String): Account {
        val sealed = seal(tokens)
        return database.withTransaction {
            dao.forgetServer()
            dao.deleteRemotePositions()
            dao.unsendQuotes()
            dao.unsendBookmarks()
            dao.insert(
                Account(
                    url = url, user = user, name = name,
                    accessSecret = sealed.first, accessExpiresAt = tokens.accessExpiresAt,
                    refreshSecret = sealed.second, refreshExpiresAt = tokens.refreshExpiresAt,
                    deviceId = "perigee-${UUID.randomUUID()}", deviceUser = "", deviceSecret = "", serverVersion = serverVersion, addedAt = now(),
                ),
            )
            checkNotNull(dao.account())
        }
    }

    /** The tokens of the session; null when there is none, or they cannot be read any more. */
    suspend fun tokens(): Tokens? {
        val account = dao.account() ?: return null
        return withContext(Dispatchers.Default) {
            val access = Secrets.open(account.accessSecret) ?: return@withContext null
            val refresh = Secrets.open(account.refreshSecret) ?: return@withContext null
            Tokens(access, account.accessExpiresAt, refresh, account.refreshExpiresAt)
        }
    }

    suspend fun saveTokens(tokens: Tokens) {
        val sealed = seal(tokens)
        dao.updateTokens(sealed.first, tokens.accessExpiresAt, sealed.second, tokens.refreshExpiresAt)
    }

    /** Remembers the user name and key of the channel reading devices sync through. */
    suspend fun saveDevice(user: String, key: String) {
        val secret = withContext(Dispatchers.Default) { Secrets.seal(key) }
        dao.updateDevice(user, secret)
    }

    /** The key of the devices' channel; null when it is not set up, or cannot be read any more. */
    suspend fun deviceKey(): String? {
        val account = dao.account() ?: return null
        return withContext(Dispatchers.Default) { Secrets.open(account.deviceSecret) }?.ifEmpty { null }
    }

    suspend fun saveServerVersion(version: String) = dao.updateServerVersion(version)

    /** Remembers the library of the server, and the folder in it, the reader sends books to; forgotten with the server. */
    suspend fun saveUploadPlace(libraryId: Long?, folderId: Long?) = dao.updateUploadPlace(libraryId, folderId)

    /** Forgets the server and what was known of its books. The books themselves stay. */
    suspend fun signOut() = database.withTransaction {
        dao.deleteAccount()
        dao.forgetServer()
        dao.deleteRemotePositions()
        dao.unsendQuotes()
        dao.unsendBookmarks()
    }

    private suspend fun seal(tokens: Tokens): Pair<String, String> =
        withContext(Dispatchers.Default) { Secrets.seal(tokens.access) to Secrets.seal(tokens.refresh) }

    companion object {
        @Volatile
        private var instance: Accounts? = null

        fun get(context: Context): Accounts = instance ?: synchronized(this) {
            instance ?: Accounts(ReadingDatabase.get(context), System::currentTimeMillis).also { instance = it }
        }

        /** An account kept where [library] keeps its books, for tests. */
        fun of(library: Library, now: () -> Long = System::currentTimeMillis): Accounts = Accounts(library.database, now)
    }
}
