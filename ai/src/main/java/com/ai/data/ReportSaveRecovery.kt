package com.ai.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Stop the requesting operation without crashing a Compose/ViewModel scope.
 * The recovery queue, rather than a provider-error handler, owns the user action. */
class ReportSaveException(val reportId: String, message: String) : kotlinx.coroutines.CancellationException(message)

data class UnsavedReportChange(val id: String, val reportId: String, val message: String, val text: String)

/** Retains failed writes for a network-free retry. Only the persistence owner
 * performs the retry under its normal lock. A three-way merge refuses conflicting
 * newer edits instead of overwriting them with an old whole-report snapshot. */
object ReportSaveRecovery {
    private val pending = MutableStateFlow<List<UnsavedReportChange>>(emptyList())
    val changes = pending.asStateFlow()
    private val retries = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

    fun write(file: File, text: String, reportId: String, retryLocked: ((() -> Unit) -> Unit),
              stillValid: () -> Boolean = { true }, recoveryText: String = text, onSaved: () -> Unit) {
        val baseRead = runCatching { file.takeIf { it.exists() }?.readText() }
        val base = baseRead.getOrNull()
        if (baseRead.isSuccess && file.writeTextAtomic(text)) { onSaved(); return }
        val id = ReportEvidenceStore.digest(file.absolutePath + text)
        if (retries.containsKey(id)) throw ReportSaveException(reportId, "This change is already awaiting a save retry.")
        val change = UnsavedReportChange(id, reportId,
            "Changes could not be saved. Free storage, then retry saving without another AI call. Unsaved changes remain available while the app is open.", recoveryText)
        retries[id] = {
            retryLocked {
                if (!stillValid()) throw IOException("This report was removed. Copy the unsaved text to keep it.")
                val current = file.takeIf { it.exists() }?.readText()
                if (baseRead.isFailure) throw IOException("The previous file could not be read when this save failed. Copy the unsaved changes and review the saved item before replacing it.")
                val merged = merge(base?.let(JsonParser::parseString), JsonParser.parseString(text), current?.let(JsonParser::parseString))
                    ?: throw IOException("The saved item was removed. Copy the unsaved text to keep it.")
                if (!file.writeTextAtomic(merged.toString())) throw IOException("Storage is still unavailable; the changes have not been saved.")
                onSaved()
            }
        }
        pending.update { it + change }
        throw ReportSaveException(reportId, change.message)
    }

    fun retry(id: String) {
        try { retries[id]?.invoke() ?: return; dismiss(id) }
        catch (e: Exception) { pending.update { rows -> rows.map { if (it.id == id) it.copy(message = e.message ?: "Save failed") else it } } }
    }
    fun dismiss(id: String) { retries.remove(id); pending.update { it.filterNot { c -> c.id == id } } }

    private fun merge(base: JsonElement?, desired: JsonElement?, current: JsonElement?): JsonElement? {
        if (desired == base) return current
        if (current == base || desired == current) return desired
        if (base?.isJsonObject == true && desired?.isJsonObject == true && current?.isJsonObject == true) {
            val b = base.asJsonObject; val d = desired.asJsonObject; val c = current.asJsonObject
            return JsonObject().apply { (b.keySet() + d.keySet() + c.keySet()).forEach { k ->
                merge(b[k], d[k], c[k])?.let { add(k, it) }
            } }
        }
        if (base?.isJsonArray == true && desired?.isJsonArray == true && current?.isJsonArray == true) {
            val all = base.asJsonArray.toList() + desired.asJsonArray.toList() + current.asJsonArray.toList()
            val key = listOf("id", "agentId").firstOrNull { k -> all.isNotEmpty() && all.all { it.isJsonObject && it.asJsonObject[k]?.isJsonPrimitive == true } }
            if (key != null) {
                fun keyed(v: JsonElement) = v.asJsonArray.associateBy { it.asJsonObject[key].asString }
                val b=keyed(base); val d=keyed(desired); val c=keyed(current)
                return com.google.gson.JsonArray().apply { (c.keys + d.keys + b.keys).forEach { k -> merge(b[k],d[k],c[k])?.let(::add) } }
            }
        }
        throw IOException("This item changed after the failed save. Copy the unsaved text and review the newer version; it will not be overwritten.")
    }
}
