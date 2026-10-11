package com.nizam.app.feature.autofill

import android.app.assist.AssistStructure
import android.content.Context
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews

/** Your details for forms, kept only on this phone. */
object Profile {
    val FIELDS = linkedMapOf(
        "name" to "Full name", "email" to "Email", "phone" to "Phone", "cnic" to "CNIC",
        "address" to "Street address", "city" to "City", "postcode" to "Postal code", "dob" to "Date of birth"
    )
    private fun p(c: Context) = c.getSharedPreferences("autofill_profile", 0)
    fun get(c: Context, k: String) = p(c).getString(k, "").orEmpty()
    fun set(c: Context, k: String, v: String) = p(c).edit().putString(k, v.trim()).apply()
    fun all(c: Context) = FIELDS.keys.associateWith { get(c, it) }.filterValues { it.isNotBlank() }
}

/**
 * Atlas as an Android autofill provider: in any app's form it offers "Fill with Atlas", matching
 * fields by the app's own hints first, then by what the field is called.
 */
class AtlasAutofill : AutofillService() {
    private fun kind(n: AssistStructure.ViewNode): String? {
        n.autofillHints?.forEach { h ->
            val x = h.lowercase()
            when {
                x.contains("email") -> return "email"
                x.contains("phone") || x.contains("tel") -> return "phone"
                x.contains("postal") && x.contains("code") -> return "postcode"
                x.contains("address") || x.contains("street") -> return "address"
                x.contains("locality") || x.contains("city") -> return "city"
                x.contains("birth") -> return "dob"
                x.contains("name") && !x.contains("user") -> return "name"
            }
        }
        val t = listOfNotNull(n.hint, n.idEntry, n.text?.toString(), n.contentDescription?.toString()).joinToString(" ").lowercase()
        return when {
            t.contains("cnic") || t.contains("national id") || t.contains("nic ") || t.contains("shanakht") -> "cnic"
            t.contains("email") || t.contains("e-mail") -> "email"
            t.contains("phone") || t.contains("mobile") || t.contains("contact no") -> "phone"
            t.contains("postal") || t.contains("zip") || t.contains("postcode") -> "postcode"
            t.contains("city") || t.contains("town") -> "city"
            t.contains("address") || t.contains("street") -> "address"
            t.contains("birth") || t.contains("dob") -> "dob"
            t.contains("full name") || t.contains("your name") || t == "name" || t.contains(" name") -> "name"
            else -> null
        }
    }

    private fun walk(n: AssistStructure.ViewNode, out: MutableList<Pair<AutofillId, String>>) {
        val id = n.autofillId
        if (id != null && n.autofillType == View.AUTOFILL_TYPE_TEXT && n.inputType and android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD == 0)
            kind(n)?.let { out += id to it }
        for (i in 0 until n.childCount) walk(n.getChildAt(i), out)
    }

    override fun onFillRequest(request: FillRequest, cancel: CancellationSignal, callback: FillCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure ?: return callback.onSuccess(null)
        if (structure.activityComponent?.packageName == packageName) return callback.onSuccess(null)
        val found = mutableListOf<Pair<AutofillId, String>>()
        for (i in 0 until structure.windowNodeCount) walk(structure.getWindowNodeAt(i).rootViewNode, found)
        val data = Profile.all(this)
        val usable = found.filter { data.containsKey(it.second) }.distinctBy { it.first }
        if (usable.isEmpty()) return callback.onSuccess(null)
        @Suppress("DEPRECATION")
        val ds = Dataset.Builder()
        usable.forEach { (id, k) ->
            val view = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
                setTextViewText(android.R.id.text1, "Fill with Atlas · ${Profile.FIELDS[k]}")
            }
            @Suppress("DEPRECATION")
            ds.setValue(id, AutofillValue.forText(data[k]), view)
        }
        callback.onSuccess(FillResponse.Builder().addDataset(ds.build()).build())
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) = callback.onSuccess()
}
