package com.claudeauto.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.claudeauto.BuildConfig
import com.claudeauto.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Reports a response from Claude, as Google Play's AI-generated content policy requires: pick a
 * reason in the app, then the report goes by email to the address on the Play listing.
 */
object Reporter {

    private val reasons = intArrayOf(
        R.string.report_offensive,
        R.string.report_sexual,
        R.string.report_dangerous,
        R.string.report_wrong,
        R.string.report_other,
    )

    fun show(context: Context, response: String) {
        var picked = 0
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.report_title)
            .setSingleChoiceItems(reasons.map(context::getString).toTypedArray(), picked) { _, which -> picked = which }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.report_send) { _, _ -> send(context, context.getString(reasons[picked]), response) }
            .show()
    }

    private fun send(context: Context, reason: String, response: String) {
        val email = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(BuildConfig.SUPPORT_EMAIL))
            .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.report_subject))
            .putExtra(Intent.EXTRA_TEXT, context.getString(R.string.report_body_intro, reason, response))
        try {
            if (BuildConfig.SUPPORT_EMAIL.isBlank()) throw ActivityNotFoundException()
            context.startActivity(email)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.report_no_email, Toast.LENGTH_LONG).show()
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.ISSUES_URL)))
        }
    }
}
