package com.kerybotu.derpibooru.mirror.network

import kotlinx.coroutines.CompletableDeferred

/** Shares one verification window and remembers which in-flight requests it resolved. */
internal class ChallengeSessionGate {
    private data class Outcome(val version: Long, val success: Boolean)
    private data class Session(
        val id: Long,
        val scope: String,
        val result: CompletableDeferred<Boolean> = CompletableDeferred()
    )

    private val lock = Any()
    private val outcomes = mutableMapOf<String, Outcome>()
    private var pending: Session? = null
    private var nextId = 0L

    fun version(scope: String): Long = synchronized(lock) { outcomes[scope]?.version ?: 0L }

    suspend fun awaitResolved(scope: String, requestVersion: Long, launch: (Long) -> Unit): Boolean {
        while (true) {
            val (session, owner) = synchronized(lock) {
                val outcome = outcomes[scope]
                // A response issued before the last verification must reuse its result,
                // even if it arrived after that verification window already closed.
                if (outcome != null && outcome.version != requestVersion) return outcome.success
                val existing = pending
                if (existing != null) existing to false else {
                    val created = Session(++nextId, scope)
                    pending = created
                    created to true
                }
            }
            if (owner) {
                try {
                    launch(session.id)
                } catch (error: Exception) {
                    complete(session.id, false)
                    throw error
                }
            }
            val success = session.result.await()
            if (session.scope == scope) return success
            // Other origins may need their own verification, but never open two
            // windows at once or reuse another site's clearance result.
        }
    }

    fun complete(sessionId: Long, success: Boolean) {
        val session = synchronized(lock) {
            val current = pending?.takeIf { it.id == sessionId } ?: return
            val version = (outcomes[current.scope]?.version ?: 0L) + 1L
            outcomes[current.scope] = Outcome(version, success)
            pending = null
            current
        }
        session.result.complete(success)
    }
}
