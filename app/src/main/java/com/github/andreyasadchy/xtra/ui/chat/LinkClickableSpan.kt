package com.github.andreyasadchy.xtra.ui.chat

import android.content.Intent
import android.net.Uri
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import androidx.core.text.getSpans
import androidx.fragment.app.Fragment
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs

class LinkClickableSpan(
    private val url: String,
    private val onUrlClick: (String) -> Unit,
) : ClickableSpan() {

    override fun onClick(widget: android.view.View) {
        onUrlClick(url)
    }

    override fun updateDrawState(ds: TextPaint) {
        ds.isUnderlineText = true
    }
}

fun Fragment.openLink(url: String) {
    val context = requireContext()
    val uri = Uri.parse(url)
    val host = uri.host?.lowercase()
    val isTwitchUrl = host == "twitch.tv" || host?.endsWith(".twitch.tv") == true
    if (isTwitchUrl && context.prefs().getBoolean(C.OPEN_TWITCH_LINKS_IN_XTRA, true)) {
        context.startActivity(Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = uri
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
    } else {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }
}

fun android.text.SpannableStringBuilder.interceptUrlSpans(onUrlClick: (String) -> Unit) {
    getSpans(0, length, URLSpan::class.java).forEach { span ->
        val start = getSpanStart(span)
        val end = getSpanEnd(span)
        val url = span.url
        removeSpan(span)
        setSpan(LinkClickableSpan(url, onUrlClick), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}