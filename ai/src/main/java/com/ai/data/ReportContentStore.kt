package com.ai.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException

/** Keep large immutable strings outside the frequently rewritten parent JSON.
 * The public Report stays fully materialized; exports therefore remain portable.
 * Blobs are written before the parent and retained until report deletion so a
 * failed save or older source revision never points at a removed body. */
internal object ReportContentStore {
    private fun directory(files: File, id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]+")))
        return File(files,"report_content/$id")
    }
    private fun slots(root: JsonObject, visit: (JsonObject,String) -> Unit) {
        listOf("imageBase64","knowledgeContext").forEach { visit(root,it) }
        root.getAsJsonArray("agents")?.forEach { node ->
            val agent=node.asJsonObject
            listOf("responseBody","requestBody","rawUsageJson").forEach { visit(agent,it) }
            agent.get("executionConfig")?.takeIf { it.isJsonObject }?.asJsonObject?.let { visit(it,"prompt") }
            agent.get("answerHistory")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { revision ->
                if (revision.isJsonObject) { visit(revision.asJsonObject,"body"); visit(revision.asJsonObject,"prompt") }
            }
        }
        root.get("conclusion")?.takeIf { it.isJsonObject }?.asJsonObject?.let { visit(it,"body") }
    }
    fun pack(files: File, reportId: String, fullJson: String): String {
        val root=JsonParser.parseString(fullJson).asJsonObject
        val dir=directory(files,reportId)
        slots(root) { obj,key ->
            val value=obj[key]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            if (value != null && value.length >= 4096) {
                val hash=ReportEvidenceStore.digest(value)
                val blob=File(dir,"$hash.txt")
                if (!blob.exists()) {
                    dir.mkdirs()
                    if (!blob.writeTextAtomic(value)) throw IOException("Could not save report content")
                }
                obj.remove(key); obj.addProperty("_$key",hash)
            }
        }
        return root.toString()
    }
    fun unpack(files: File, reportId: String, json: String): String {
        val root=JsonParser.parseString(json).asJsonObject
        slots(root) { obj,key ->
            val hash=obj["_$key"]?.takeIf { it.isJsonPrimitive }?.asString
            if (hash != null) {
                require(hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid report content reference" }
                val blob=File(directory(files,reportId),"$hash.txt")
                if (!blob.exists()) throw IOException("Saved report content is missing: $key")
                val value = blob.readText()
                if (ReportEvidenceStore.digest(value) != hash) throw IOException("Saved report content failed integrity validation: $key")
                obj.addProperty(key,value); obj.remove("_$key")
            }
        }
        return root.toString()
    }
    fun delete(files: File, reportId: String) { directory(files,reportId).deleteRecursively() }
}
