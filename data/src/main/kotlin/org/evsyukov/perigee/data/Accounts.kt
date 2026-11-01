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

    /** The tokens of [account] as it was read, rather than of the account signed in when they are asked for. */
    suspend fun tokensOf(account: Account): Tokens? = withContext(Dispatchers.Default) {
        val access = Secrets.open(account.accessSecret) ?: return@withContext null
        val refresh = Secrets.open(account.refreshSecret) ?: return@withContext null
        Tokens(access, account.accessExpiresAt, refresh, account.refreshExpiresAt)
    }

    /**
     * Keeps [tokens]; with [ofDevice], only while the account signed in is still the one that calls itself so
     * to the server (see [Account.deviceId], made anew at each sign-in), as for tokens renewed for a reader who
     * was replaced meanwhile.
     * @return whether they were kept
     */
    suspend fun saveTokens(tokens: Tokens, ofDevice: String? = null): Boolean {
        val sealed = seal(tokens)
        if (ofDevice == null) {
            dao.updateTokens(sealed.first, tokens.accessExpiresAt, sealed.second, tokens.refreshExpiresAt)
            return true
        }
        return dao.updateTokensOf(ofDevice, sealed.first, tokens.accessExpiresAt, sealed.second, tokens.refreshExpiresAt) > 0
    }

    /**
     * Remembers the user name and key of the channel reading devices sync through; with [ofDevice], only for the
     * account of that [Account.deviceId], as [saveTokens] does.
     * @return whether they were remembered
     */
    suspend fun saveDevice(user: String, key: String, ofDevice: String? = null): Boolean {
        val secret = withContext(Dispatchers.Default) { Secrets.seal(key) }
        if (ofDevice == null) {
            dao.updateDevice(user, secret)
            return true
        }
        return dao.updateDeviceOf(ofDevice, user, secret) > 0
    }

    /** The key of the devices' channel; null when it is not set up, or cannot be read any more. */
    suspend fun deviceKey(): String? {
        val account = dao.account() ?: return null
        return deviceKeyOf(account)
    }

    /** As [deviceKey], of [account] as it was read. */
    suspend fun deviceKeyOf(account: Account): String? =
        withContext(Dispatchers.Default) { Secrets.open(account.deviceSecret) }?.ifEmpty { null }

    suspend fun saveServerVersion(version: String) = dao.updateServerVersion(version)

    /** Remembers the library of the server, and the folder in it, the reader sends books to; forgotten with the server. */
    suspend fun saveUploadPlace(libraryId: Long?, folderId: Long?) = dao.updateUploadPlace(libraryId, folderId)

    /** A round of syncing went through. */
    suspend fun roundDone() = dao.roundDone(now())

    /** A round of syncing could not reach the server; the first of such rounds in a row is remembered. */
    suspend fun roundUnreachable() = dao.roundUnreachable(now())

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
