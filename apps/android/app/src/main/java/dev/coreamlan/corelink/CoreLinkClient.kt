package dev.coreamlan.corelink

import android.content.Context
import org.json.JSONObject

/** Fail closed until the authenticated TLS companion transport is available. */
class CoreLinkClient(@Suppress("UNUSED_PARAMETER") host: String) {
    fun pair(@Suppress("UNUSED_PARAMETER") code: String, @Suppress("UNUSED_PARAMETER") name: String): Result<Unit> =
        Result.failure(IllegalStateException("Secure pairing is not available yet"))

    fun input(@Suppress("UNUSED_PARAMETER") body: JSONObject): Result<Unit> =
        Result.failure(IllegalStateException("Secure pairing is not available yet"))

    fun disconnect() = Unit

    companion object {
        fun initialize(@Suppress("UNUSED_PARAMETER") context: Context) = Unit
    }
}
