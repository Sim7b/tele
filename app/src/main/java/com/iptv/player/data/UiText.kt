package com.iptv.player.data

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import com.iptv.player.R
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * User-facing text produced outside the UI (data layer, player): a string resource with its arguments,
 * or a raw text such as a server message. Resolved where it is displayed, in the current locale.
 */
sealed interface UiText {
    data class Raw(val value: String) : UiText

    /** [args] may themselves be [UiText], resolved first. */
    class Res(@param:StringRes val id: Int, vararg val args: Any) : UiText {
        override fun toString() = "UiText.Res($id, ${args.contentToString()})"
    }

    /** Quantity string; [args] may themselves be [UiText], resolved first. */
    class Plural(@param:PluralsRes val id: Int, val quantity: Int, vararg val args: Any) : UiText {
        override fun toString() = "UiText.Plural($id, $quantity, ${args.contentToString()})"
    }

    fun resolve(resources: Resources): String = when (this) {
        is Raw -> value
        is Res -> resources.getString(id, *resolveArgs(resources, args))
        is Plural -> resources.getQuantityString(id, quantity, *resolveArgs(resources, args))
    }

    private fun resolveArgs(resources: Resources, args: Array<out Any>): Array<Any> =
        args.map { if (it is UiText) it.resolve(resources) else it }.toTypedArray()
}

@Composable
fun UiText.resolve(): String = resolve(LocalResources.current)

/** Error carrying a user-facing message. */
class UserFacingException(val text: UiText, cause: Throwable? = null) : IOException(text.toString(), cause)

/** User-facing message of an error: the text of a [UserFacingException], a known network error, else the raw message. */
fun Throwable.toUiText(): UiText = when (this) {
    is UserFacingException -> text
    is UnknownHostException -> UiText.Res(R.string.error_unknown_host)
    is ConnectException -> UiText.Res(R.string.error_connection_refused)
    is SocketTimeoutException -> UiText.Res(R.string.error_timeout)
    is IllegalArgumentException -> UiText.Res(R.string.error_invalid_address)
    else -> localizedMessage?.let { UiText.Raw(it) } ?: UiText.Res(R.string.error_network_unknown)
}

/** Shortcut for a failed [Result] carrying a string resource. */
fun <T> failure(@StringRes id: Int, vararg args: Any): Result<T> =
    Result.failure(UserFacingException(UiText.Res(id, *args)))
