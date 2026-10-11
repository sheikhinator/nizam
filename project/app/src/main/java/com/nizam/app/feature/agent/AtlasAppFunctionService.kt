package com.nizam.app.feature.agent

import android.content.Context
import androidx.appfunctions.AppFunctionContext
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.service.AppFunction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** The only status exposed to Android system agents: how many Atlas approvals are waiting. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class AtlasPendingApprovalCount(
    /** Number of Atlas actions awaiting the user's decision. */
    val count: Int,
)

/** A note draft submitted by an Android system agent for user approval. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class AtlasNoteApprovalRequest(
    /** Short title for the note. */
    val title: String,
    /** Note body; may be empty. */
    val body: String,
)

/** An expense submitted by an Android system agent for user approval. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class AtlasExpenseApprovalRequest(
    /** Expense amount in Pakistani rupees (PKR). */
    val amountPkr: Double,
    /** Expense category. */
    val category: String,
    /** Optional note describing the expense; use an empty string when not needed. */
    val note: String,
)

/** Confirms that a request is waiting for approval, not that it has been saved. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class AtlasApprovalReceipt(
    /** Identifier of the request in Atlas's approval inbox. */
    val approvalId: String,
    /** Current request state. */
    val state: String,
    /** Explains that Atlas has not applied the write yet. */
    val message: String,
)

/**
 * Android system-agent bridge for Atlas.
 *
 * Reads are limited to the count of pending approvals. Note and expense writes are always staged
 * in Atlas's approval inbox and never executed from here. The library's own service hosts these
 * functions; this class only declares them.
 */
class AtlasAppFunctions {

    /** Returns only the number of Atlas actions awaiting user approval. */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun getPendingApprovalCount(appFunctionContext: AppFunctionContext): AtlasPendingApprovalCount =
        withContext(Dispatchers.IO) {
            AtlasPendingApprovalCount(ApprovalInbox.pending(appFunctionContext.context).size)
        }

    /**
     * Stages a note request in Atlas's approval inbox. The note is not saved until the user approves it.
     *
     * @param request Note title and body to stage.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun requestNoteSave(appFunctionContext: AppFunctionContext, request: AtlasNoteApprovalRequest): AtlasApprovalReceipt =
        withContext(Dispatchers.IO) {
            val title = request.title.trim()
            if (title.isEmpty()) {
                throw AppFunctionInvalidArgumentException("A note title is required.")
            }
            if (title.length > 120) {
                throw AppFunctionInvalidArgumentException("The note title must be 120 characters or fewer.")
            }
            if (request.body.length > 8000) {
                throw AppFunctionInvalidArgumentException("The note body must be 8000 characters or fewer.")
            }

            val args = JSONObject()
                .put("title", title)
                .put("body", request.body)
            stageForApproval(
                c = appFunctionContext.context,
                action = "add_note",
                args = args,
                risk = "low",
                purpose = "Save the note requested by an Android system agent.",
            )
        }

    /**
     * Stages a PKR expense request in Atlas's approval inbox. The expense is not logged until the user approves it.
     *
     * @param request PKR amount, category, and optional note to stage.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun requestExpenseLog(appFunctionContext: AppFunctionContext, request: AtlasExpenseApprovalRequest): AtlasApprovalReceipt =
        withContext(Dispatchers.IO) {
            if (!request.amountPkr.isFinite() || request.amountPkr <= 0.0) {
                throw AppFunctionInvalidArgumentException("The PKR amount must be a finite number greater than zero.")
            }
            val category = request.category.trim()
            if (category.isEmpty() || category.length > 64) {
                throw AppFunctionInvalidArgumentException("The category must contain 1 to 64 characters.")
            }
            if (request.note.length > 500) {
                throw AppFunctionInvalidArgumentException("The expense note must be 500 characters or fewer.")
            }

            val args = JSONObject()
                .put("amount", request.amountPkr)
                .put("category", category)
                .put("note", request.note)
            stageForApproval(
                c = appFunctionContext.context,
                action = "log_expense",
                args = args,
                risk = "high",
                purpose = "Log the PKR expense requested by an Android system agent.",
            )
        }

    private fun stageForApproval(
        c: Context,
        action: String,
        args: JSONObject,
        risk: String,
        purpose: String,
    ): AtlasApprovalReceipt {
        val entry = ApprovalInbox.add(
            c = c,
            source = "Android system agent",
            action = action,
            args = args,
            verdict = Reviewer.Verdict(
                risk = risk,
                decision = "ask",
                why = "System-agent writes always wait for your approval in Atlas.",
            ),
            purpose = purpose,
        )
        return AtlasApprovalReceipt(
            approvalId = entry.optString("id"),
            state = "awaiting_user_approval",
            message = "Nothing has been saved yet. Review this request in Atlas's Approvals inbox.",
        )
    }
}