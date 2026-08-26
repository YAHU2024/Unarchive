package com.unarchive.android.sync

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ImaKnowledgeBase(val id: String, val name: String, val description: String = "")
data class ImaFolder(
    val id: String,
    val name: String,
    val depth: Int = 0,
    val displayPath: String = name,
)

internal class ImaFolderTraversalBudget(
    private val maximumDepth: Int = 12,
    private val maximumItems: Int = 500,
) {
    private val visitedIds = mutableSetOf<String>()

    fun canDescend(depth: Int): Boolean = depth in 0 until maximumDepth && visitedIds.size < maximumItems

    fun claim(id: String): Boolean = id.isNotBlank() && visitedIds.size < maximumItems && visitedIds.add(id)
}

class ImaQuotaExceededException(message: String) : IOException(message)
class ImaRateLimitException(message: String) : IOException(message)
class ImaAlreadyAddedException(message: String) : IOException(message)
class ImaCredentialException(message: String) : IOException(message)

/** Minimal ima OpenAPI client. Business errors are classified before HTTP errors. */
interface ImaGateway {
    suspend fun connect()
    suspend fun findNote(videoId: String): String?
    suspend fun importDocument(markdown: String, folderId: String = ""): String
    suspend fun appendDocument(noteId: String, markdown: String)
    suspend fun addToKnowledgeBase(noteId: String, title: String, kbId: String, folderId: String = "")
}

class ImaClient(
    private val clientId: String,
    private val apiKey: String,
    private val baseUrl: String = "https://ima.qq.com",
    private val minIntervalMs: Long = 800L,
) : ImaGateway {
    private var lastCallAt = 0L

    override suspend fun connect() { call("openapi/note/v1/list_notebook", JSONObject().put("cursor", "0").put("limit", 1)) }

    suspend fun listKnowledgeBases(): List<ImaKnowledgeBase> {
        val data = call("openapi/wiki/v1/get_addable_knowledge_base_list", JSONObject().put("cursor", "").put("limit", 50))
        val values = data.optJSONArray("addable_knowledge_base_list") ?: JSONArray()
        return buildList { repeat(values.length()) { val item = values.optJSONObject(it) ?: return@repeat; item.optString("id").takeIf(String::isNotBlank)?.let { id -> add(ImaKnowledgeBase(id, item.optString("name"))) } } }
    }

    suspend fun listKnowledgeBaseFolders(kbId: String): List<ImaFolder> = listFolders(kbId)

    suspend fun listFolders(kbId: String): List<ImaFolder> = buildList {
        collectFolders(
            kbId = kbId,
            parentId = "",
            parentPath = "",
            depth = 0,
            budget = ImaFolderTraversalBudget(),
            output = this,
        )
    }

    private suspend fun collectFolders(
        kbId: String,
        parentId: String,
        parentPath: String,
        depth: Int,
        budget: ImaFolderTraversalBudget,
        output: MutableList<ImaFolder>,
    ) {
        if (!budget.canDescend(depth)) return
        val body = JSONObject().put("knowledge_base_id", kbId).put("cursor", "").put("limit", 50)
        if (parentId.isNotBlank()) body.put("folder_id", parentId)
        val data = call("openapi/wiki/v1/get_knowledge_list", body)
        val values = data.optJSONArray("knowledge_list") ?: JSONArray()
        for (index in 0 until values.length()) {
            if (!budget.canDescend(depth)) break
            val item = values.optJSONObject(index) ?: continue
            val id = item.optString("media_id")
            if (!id.startsWith("folder_") && item.optInt("media_type") != 99) continue
            if (!budget.claim(id)) continue
            val name = item.optString("title").ifBlank { "未命名文件夹" }
            val displayPath = listOf(parentPath, name).filter(String::isNotBlank).joinToString(" / ")
            output += ImaFolder(id = id, name = name, depth = depth, displayPath = displayPath)
            collectFolders(
                kbId = kbId,
                parentId = id,
                parentPath = displayPath,
                depth = depth + 1,
                budget = budget,
                output = output,
            )
        }
    }

    override suspend fun findNote(videoId: String): String? {
        val data = call("openapi/note/v1/search_note", JSONObject()
            .put("search_type", 0).put("query_info", JSONObject().put("title", "[$videoId]"))
            .put("start", 0).put("end", 20))
        val values = data.optJSONArray("search_note_infos") ?: JSONArray()
        repeat(values.length()) { val info = values.optJSONObject(it)?.optJSONObject("note_book_info") ?: return@repeat
            if (info.optString("title").startsWith("[$videoId]")) return info.optString("note_id").takeIf(String::isNotBlank)
        }
        return null
    }

    override suspend fun importDocument(markdown: String, folderId: String): String {
        val body = JSONObject().put("content_format", 1).put("content", markdown)
        if (folderId.isNotBlank()) body.put("folder_id", folderId)
        return call("openapi/note/v1/import_doc", body).optString("note_id").takeIf(String::isNotBlank)
            ?: throw IOException("ima 未返回 note_id")
    }

    override suspend fun appendDocument(noteId: String, markdown: String) {
        call(
            "openapi/note/v1/append_doc",
            JSONObject()
                .put("note_id", noteId)
                .put("content_format", 1)
                .put("content", markdown),
        )
    }

    override suspend fun addToKnowledgeBase(noteId: String, title: String, kbId: String, folderId: String) {
        val body = JSONObject().put("media_type", 11).put("note_info", JSONObject().put("content_id", noteId))
            .put("title", title).put("knowledge_base_id", kbId)
        if (folderId.isNotBlank()) body.put("folder_id", folderId)
        call("openapi/wiki/v1/add_knowledge", body)
    }

    private suspend fun call(path: String, body: JSONObject, attempt: Int = 0): JSONObject = withContext(Dispatchers.IO) {
        val gap = minIntervalMs - (System.currentTimeMillis() - lastCallAt)
        if (gap > 0) delay(gap)
        lastCallAt = System.currentTimeMillis()
        val connection = URL("$baseUrl/$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.connectTimeout = 30_000; connection.readTimeout = 30_000; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("ima-openapi-clientid", clientId)
            connection.setRequestProperty("ima-openapi-apikey", apiKey)
            connection.setRequestProperty("ima-openapi-ctx", "skill_version=unknown")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val stream = if (connection.responseCode >= 400) connection.errorStream else connection.inputStream
            val response = stream?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
            val root = runCatching { JSONObject(response) }.getOrElse { throw IOException("ima 响应非 JSON：HTTP ${connection.responseCode}") }
            val code = root.optInt("code", 0); val message = root.optString("msg")
            when (code) {
                0 -> Unit
                200001 -> if (attempt < MAX_RATE_RETRIES) {
                    delay((2_000L shl attempt).coerceAtMost(30_000L))
                    return@withContext call(path, body, attempt + 1)
                } else throw ImaRateLimitException(message.ifBlank { "请求频率超限，重试次数已用尽" })
                200005 -> throw ImaQuotaExceededException(message.ifBlank { "配额耗尽" })
                200002 -> throw ImaCredentialException(message.ifBlank { "凭据或权限无效" })
                220001 -> throw ImaAlreadyAddedException(message.ifBlank { "已关联" })
                else -> throw IOException("ima API 错误：$message (code=$code)")
            }
            root.optJSONObject("data") ?: JSONObject()
        } finally { connection.disconnect() }
    }

    companion object { private const val MAX_RATE_RETRIES = 5 }
}
