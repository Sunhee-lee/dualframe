package com.dualframe.data

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import org.json.JSONObject

/**
 * Central store for master file recovery state and lifecycle.
 * Uses SharedPreferences (one JSON blob per master path).
 *
 * Master deletion happens only through this store, in three explicit paths:
 *   - markCompletedAndDeleteMaster: after both native/cropped MediaStore saves verified (§11)
 *   - deleteFailedMaster: user's explicit [삭제] on the recovery card (§16)
 *   - discardMaster: user's explicit [다시 촬영]/BackHandler at EXPORT_COMPLETE (§19)
 *
 * There is NO orphan scan, NO time-based auto cleanup, NO folder walk. Files
 * whose Store entry survives a crash are handled by [recoverIncompleteSaves].
 */
object MasterRecoveryStore {

    private const val TAG = "MasterRecoveryStore"
    private const val PREFS = "dualframe_recovery"
    private const val KEY_ENTRIES = "entries"  // JSONObject: path → entry JSON

    enum class State { RECORDING, SAVE_STARTED, FAILED }

    data class Snapshot(
        val isPortrait: Boolean,
        val landscapeCropOffsetY: Float,
        val targetRotation: Int,      // Surface.ROTATION_0 / 90 / 180 / 270
        val isFront: Boolean,
        val beauty: Boolean,
        val mirror: Boolean,          // Explicit; equals isFront in current pipeline
        val masterRotation: Int,      // Master file metadata rotation
        val croppedAspect: Float,     // ExportManager.ASPECT_16x9 or ASPECT_9x16
        val durationSeconds: Long,    // Confirmed at recording end for recovery preflight
    )

    data class SavedUris(val native: Uri?, val cropped: Uri?) {
        companion object { val EMPTY = SavedUris(null, null) }
    }

    data class Entry(
        val path: String,
        val state: State,
        val createdAt: Long,
        val snapshot: Snapshot?,
        val savedUris: SavedUris,
    )

    // ── Public API ────────────────────────────────────────────────────

    fun markRecording(context: Context, masterFile: File) {
        writeEntry(context, Entry(
            path = masterFile.absolutePath,
            state = State.RECORDING,
            createdAt = System.currentTimeMillis(),
            snapshot = null,
            savedUris = SavedUris.EMPTY,
        ))
        Log.i(TAG, "markRecording: ${masterFile.name}")
    }

    fun updateSnapshot(context: Context, masterFile: File, snapshot: Snapshot) {
        val current = readEntry(context, masterFile.absolutePath) ?: Entry(
            path = masterFile.absolutePath,
            state = State.RECORDING,
            createdAt = System.currentTimeMillis(),
            snapshot = null,
            savedUris = SavedUris.EMPTY,
        )
        writeEntry(context, current.copy(snapshot = snapshot))
        Log.i(TAG, "updateSnapshot: ${masterFile.name}")
    }

    /**
     * Transition to SAVE_STARTED. Requires snapshot to be non-null so a later
     * crash can still be recovered via snapshot-driven re-export (§14 case 3).
     */
    fun markSaveStarted(context: Context, masterFile: File) {
        val current = readEntry(context, masterFile.absolutePath)
            ?: throw IllegalStateException("markSaveStarted without existing entry: ${masterFile.name}")
        if (current.snapshot == null) {
            throw IllegalStateException("markSaveStarted requires snapshot: ${masterFile.name}")
        }
        writeEntry(context, current.copy(state = State.SAVE_STARTED))
        Log.i(TAG, "markSaveStarted: ${masterFile.name}")
    }

    fun markFailed(context: Context, masterFile: File) {
        val current = readEntry(context, masterFile.absolutePath) ?: Entry(
            path = masterFile.absolutePath,
            state = State.FAILED,
            createdAt = System.currentTimeMillis(),
            snapshot = null,
            savedUris = SavedUris.EMPTY,
        )
        writeEntry(context, current.copy(state = State.FAILED))
        Log.i(TAG, "markFailed: ${masterFile.name}")
    }

    fun recordNativeUri(context: Context, masterFile: File, uri: Uri) {
        val current = readEntry(context, masterFile.absolutePath) ?: return
        writeEntry(context, current.copy(savedUris = current.savedUris.copy(native = uri)))
    }

    fun recordCroppedUri(context: Context, masterFile: File, uri: Uri) {
        val current = readEntry(context, masterFile.absolutePath) ?: return
        writeEntry(context, current.copy(savedUris = current.savedUris.copy(cropped = uri)))
    }

    /**
     * Success path: delete master file then remove Store entry.
     * NOT atomic across file system + SharedPreferences — a crash between the two
     * steps leaves `file 없음 + Store 남음`, which recoverIncompleteSaves cleans
     * up on next start (file.exists() == false → clearEntry).
     */
    fun markCompletedAndDeleteMaster(context: Context, masterFile: File) {
        val deleted = runCatching { !masterFile.exists() || masterFile.delete() }.getOrDefault(false)
        if (deleted) removeEntry(context, masterFile.absolutePath)
        Log.i(TAG, "markCompletedAndDeleteMaster: ${masterFile.name} deleted=$deleted")
    }

    /** User's explicit [삭제] on a failed master. Same idempotent pattern as complete. */
    fun deleteFailedMaster(context: Context, masterFile: File) {
        val deleted = runCatching { !masterFile.exists() || masterFile.delete() }.getOrDefault(false)
        if (deleted) removeEntry(context, masterFile.absolutePath)
        Log.i(TAG, "deleteFailedMaster: ${masterFile.name} deleted=$deleted")
    }

    /** User's explicit [다시 촬영] / EXPORT_COMPLETE BackHandler. Same idempotent pattern. */
    fun discardMaster(context: Context, masterFile: File) {
        val deleted = runCatching { !masterFile.exists() || masterFile.delete() }.getOrDefault(false)
        if (deleted) removeEntry(context, masterFile.absolutePath)
        Log.i(TAG, "discardMaster: ${masterFile.name} deleted=$deleted")
    }

    /**
     * App-start recovery. RECORDING masters are preserved as-is (finalize state
     * unknown; not exposed in the recovery list this release). SAVE_STARTED
     * masters are promoted to FAILED (save was actually attempted).
     * Stale entries (file no longer exists) are cleaned up.
     * No folder scan — only entries already in the Store are considered.
     */
    fun recoverIncompleteSaves(context: Context) {
        val entries = readAllEntries(context)
        var promoted = 0
        var stale = 0
        for (entry in entries) {
            val file = File(entry.path)
            if (!file.exists()) {
                removeEntry(context, entry.path)
                stale++
                continue
            }
            when (entry.state) {
                State.RECORDING -> {
                    // Preserve: finalize status unknown. Not exposed in this release.
                }
                State.SAVE_STARTED -> {
                    writeEntry(context, entry.copy(state = State.FAILED))
                    promoted++
                }
                State.FAILED -> { /* keep */ }
            }
        }
        if (promoted > 0 || stale > 0) {
            Log.i(TAG, "recoverIncompleteSaves: promoted=$promoted stale=$stale")
        }
    }

    fun getEntry(context: Context, masterFile: File): Entry? =
        readEntry(context, masterFile.absolutePath)

    fun listFailed(context: Context): List<Entry> =
        readAllEntries(context).filter { it.state == State.FAILED }

    fun failedCount(context: Context): Int = listFailed(context).size

    fun failedTotalBytes(context: Context): Long =
        listFailed(context).sumOf { runCatching { File(it.path).length() }.getOrDefault(0L) }

    // ── SharedPreferences serialization ───────────────────────────────

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readEntry(context: Context, path: String): Entry? {
        val root = readRoot(context)
        val obj = root.optJSONObject(path) ?: return null
        return parseEntry(path, obj)
    }

    private fun readAllEntries(context: Context): List<Entry> {
        val root = readRoot(context)
        val out = mutableListOf<Entry>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val path = keys.next()
            val obj = root.optJSONObject(path) ?: continue
            out += parseEntry(path, obj)
        }
        return out
    }

    private fun writeEntry(context: Context, entry: Entry) {
        val root = readRoot(context)
        root.put(entry.path, entryToJson(entry))
        prefs(context).edit().putString(KEY_ENTRIES, root.toString()).apply()
    }

    private fun removeEntry(context: Context, path: String) {
        val root = readRoot(context)
        if (!root.has(path)) return
        root.remove(path)
        prefs(context).edit().putString(KEY_ENTRIES, root.toString()).apply()
    }

    private fun readRoot(context: Context): JSONObject {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return JSONObject()
        return try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
    }

    private fun entryToJson(entry: Entry): JSONObject = JSONObject().apply {
        put("state", entry.state.name)
        put("createdAt", entry.createdAt)
        entry.snapshot?.let { put("snapshot", snapshotToJson(it)) }
        put("savedUris", JSONObject().apply {
            entry.savedUris.native?.let { put("native", it.toString()) }
            entry.savedUris.cropped?.let { put("cropped", it.toString()) }
        })
    }

    private fun parseEntry(path: String, obj: JSONObject): Entry {
        val state = try {
            State.valueOf(obj.optString("state", State.FAILED.name))
        } catch (_: Exception) { State.FAILED }
        val createdAt = obj.optLong("createdAt", System.currentTimeMillis())
        val snapshot = obj.optJSONObject("snapshot")?.let { parseSnapshot(it) }
        val uris = obj.optJSONObject("savedUris") ?: JSONObject()
        val savedUris = SavedUris(
            native = uris.optString("native", null).takeUnless { it.isNullOrEmpty() }?.let { Uri.parse(it) },
            cropped = uris.optString("cropped", null).takeUnless { it.isNullOrEmpty() }?.let { Uri.parse(it) },
        )
        return Entry(path, state, createdAt, snapshot, savedUris)
    }

    private fun snapshotToJson(s: Snapshot): JSONObject = JSONObject().apply {
        put("isPortrait", s.isPortrait)
        put("landscapeCropOffsetY", s.landscapeCropOffsetY.toDouble())
        put("targetRotation", s.targetRotation)
        put("isFront", s.isFront)
        put("beauty", s.beauty)
        put("mirror", s.mirror)
        put("masterRotation", s.masterRotation)
        put("croppedAspect", s.croppedAspect.toDouble())
        put("durationSeconds", s.durationSeconds)
    }

    private fun parseSnapshot(obj: JSONObject): Snapshot = Snapshot(
        isPortrait = obj.optBoolean("isPortrait"),
        landscapeCropOffsetY = obj.optDouble("landscapeCropOffsetY", 0.0).toFloat(),
        targetRotation = obj.optInt("targetRotation"),
        isFront = obj.optBoolean("isFront"),
        beauty = obj.optBoolean("beauty"),
        mirror = obj.optBoolean("mirror"),
        masterRotation = obj.optInt("masterRotation"),
        croppedAspect = obj.optDouble("croppedAspect", 0.0).toFloat(),
        durationSeconds = obj.optLong("durationSeconds"),
    )
}
