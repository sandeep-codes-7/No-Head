package com.nohead.mesh.domain.service

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import com.nohead.mesh.domain.crypto.CryptoEngine
import com.nohead.mesh.domain.model.AttachmentMetadata
import com.nohead.mesh.domain.model.ProtocolConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages safe media & file attachments, chunked BLE/Wi-Fi Aware transfer,
 * voice note recording & playback, and live Push-to-Talk (PTT) audio streaming.
 *
 * Security Rules:
 * - Strict MIME validation and executable extension blocking (`.apk`, `.sh`, `.dex`, `.so`, `.exe`, `.bat` rejected)
 * - Filename sanitization (strips path traversal `../`, control chars)
 * - Explicit user acceptance required before saving received external files
 * - Never auto-opens or executes received files
 * - Enforces storage quota (`MAX_LOCAL_ATTACHMENT_STORAGE_BYTES = 32 MB`)
 */
@Singleton
class AttachmentAndVoiceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cryptoEngine: CryptoEngine
) {
    companion object {
        const val MAX_LOCAL_ATTACHMENT_STORAGE_BYTES = 32L * 1024L * 1024L // 32 MB quota
        const val VOICE_NOTE_MAX_DURATION_SEC = 60
        private val BLOCKED_EXTENSIONS = setOf(
            "apk", "dex", "so", "sh", "exe", "bat", "cmd", "msi", "jar", "js", "vbs", "elf"
        )
        private val ALLOWED_MIME_PREFIXES = listOf(
            "image/", "audio/", "text/plain", "application/pdf", "application/octet-stream"
        )
    }

    @Serializable
    data class AttachmentChunkWire(
        val attachmentId: String,
        val fileName: String,
        val mimeType: String,
        val totalSizeBytes: Long,
        val sha256Hex: String,
        val chunkIndex: Int,
        val totalChunks: Int,
        val chunkDataBase64: String,
        val durationSeconds: Int? = null
    )

    @Serializable
    data class LivePttFrameWire(
        val streamId: String,
        val sequenceNumber: Int,
        val sampleRateHz: Int = 8000,
        val pcm8BitBase64: String
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val attachmentsDir: File by lazy {
        File(context.filesDir, "attachments").apply { mkdirs() }
    }

    private val incomingChunks = ConcurrentHashMap<String, ConcurrentHashMap<Int, ByteArray>>()
    private val incomingManifests = ConcurrentHashMap<String, AttachmentMetadata>()

    private val _isRecordingVoice = MutableStateFlow(false)
    val isRecordingVoice: StateFlow<Boolean> = _isRecordingVoice.asStateFlow()

    private val _recordingDurationSec = MutableStateFlow(0)
    val recordingDurationSec: StateFlow<Int> = _recordingDurationSec.asStateFlow()

    private val _isPlayingVoiceId = MutableStateFlow<String?>(null)
    val isPlayingVoiceId: StateFlow<String?> = _isPlayingVoiceId.asStateFlow()

    private val _isLivePttStreaming = MutableStateFlow(false)
    val isLivePttStreaming: StateFlow<Boolean> = _isLivePttStreaming.asStateFlow()

    private var mediaRecorder: MediaRecorder? = null
    private var currentVoiceFile: File? = null
    private var recordingStartMs: Long = 0L
    private var mediaPlayer: MediaPlayer? = null
    private var pttJob: Job? = null

    fun sanitizeFileName(rawName: String): String {
        val strippedPath = rawName.substringAfterLast('/').substringAfterLast('\\')
        val clean = strippedPath.replace(Regex("[^a-zA-Z0-9._\\-]"), "_").take(64)
        val ext = clean.substringAfterLast('.', "").lowercase()
        if (ext in BLOCKED_EXTENSIONS) {
            return "${clean.substringBeforeLast('.')}.blocked_bin"
        }
        return clean.ifBlank { "attachment_${System.currentTimeMillis()}.bin" }
    }

    fun isSafeMimeAndSize(fileName: String, mimeType: String, sizeBytes: Long, isBleOnly: Boolean): Result<Unit> = runCatching {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        require(ext !in BLOCKED_EXTENSIONS) { "Executable file type '.$ext' is blocked by security policy." }
        require(ALLOWED_MIME_PREFIXES.any { mimeType.lowercase().startsWith(it) }) {
            "Unsupported or unsafe MIME type: $mimeType"
        }
        val maxCap = if (isBleOnly) ProtocolConstants.MAX_BLE_MEDIA_BYTES.toLong() else ProtocolConstants.MAX_WIFI_AWARE_MEDIA_BYTES.toLong()
        require(sizeBytes in 1..maxCap) {
            "Attachment size ($sizeBytes B) exceeds transport cap ($maxCap B)."
        }
    }

    fun createAttachmentChunks(
        rawBytes: ByteArray,
        rawFileName: String,
        mimeType: String,
        durationSeconds: Int? = null,
        chunkSizeBytes: Int = 2048
    ): Pair<AttachmentMetadata, List<AttachmentChunkWire>> {
        val safeName = sanitizeFileName(rawFileName)
        val sha256 = cryptoEngine.sha256Hex(rawBytes)
        val attachmentId = "att_${UUID.randomUUID().toString().take(12)}"
        val totalChunks = ((rawBytes.size + chunkSizeBytes - 1) / chunkSizeBytes).coerceAtLeast(1)

        enforceQuotaBeforeWrite(rawBytes.size.toLong())
        val localFile = File(attachmentsDir, "${attachmentId}_$safeName")
        localFile.writeBytes(rawBytes)

        val metadata = AttachmentMetadata(
            attachmentId = attachmentId,
            fileName = safeName,
            mimeType = mimeType,
            sizeBytes = rawBytes.size.toLong(),
            sha256Hex = sha256,
            totalChunks = totalChunks,
            receivedChunks = totalChunks,
            localFilePath = localFile.absolutePath,
            isAcceptedByUser = true,
            durationSeconds = durationSeconds,
            expiresAtEpochMs = System.currentTimeMillis() + 86_400_000L * 7
        )

        val chunks = ArrayList<AttachmentChunkWire>(totalChunks)
        for (i in 0 until totalChunks) {
            val start = i * chunkSizeBytes
            val end = minOf(start + chunkSizeBytes, rawBytes.size)
            val slice = rawBytes.copyOfRange(start, end)
            chunks.add(
                AttachmentChunkWire(
                    attachmentId = attachmentId,
                    fileName = safeName,
                    mimeType = mimeType,
                    totalSizeBytes = rawBytes.size.toLong(),
                    sha256Hex = sha256,
                    chunkIndex = i,
                    totalChunks = totalChunks,
                    chunkDataBase64 = CryptoEngine.encodeBase64(slice),
                    durationSeconds = durationSeconds
                )
            )
        }
        return metadata to chunks
    }

    fun onAttachmentChunkReceived(wire: AttachmentChunkWire): AttachmentMetadata? {
        val safeName = sanitizeFileName(wire.fileName)
        if (isSafeMimeAndSize(safeName, wire.mimeType, wire.totalSizeBytes, isBleOnly = false).isFailure) {
            return null
        }

        val chunkBytes = runCatching { CryptoEngine.decodeBase64(wire.chunkDataBase64) }.getOrNull() ?: return null
        val chunkMap = incomingChunks.computeIfAbsent(wire.attachmentId) { ConcurrentHashMap() }
        chunkMap[wire.chunkIndex] = chunkBytes

        val currentMeta = incomingManifests.compute(wire.attachmentId) { _, existing ->
            (existing ?: AttachmentMetadata(
                attachmentId = wire.attachmentId,
                fileName = safeName,
                mimeType = wire.mimeType,
                sizeBytes = wire.totalSizeBytes,
                sha256Hex = wire.sha256Hex,
                totalChunks = wire.totalChunks,
                durationSeconds = wire.durationSeconds
            )).copy(receivedChunks = chunkMap.size)
        } ?: return null

        if (chunkMap.size == wire.totalChunks) {
            val combined = ByteArray(wire.totalSizeBytes.toInt())
            var offset = 0
            for (i in 0 until wire.totalChunks) {
                val part = chunkMap[i] ?: return currentMeta
                if (offset + part.size <= combined.size) {
                    System.arraycopy(part, 0, combined, offset, part.size)
                    offset += part.size
                }
            }
            incomingChunks.remove(wire.attachmentId)

            val actualSha = cryptoEngine.sha256Hex(combined)
            if (actualSha != wire.sha256Hex) {
                incomingManifests.remove(wire.attachmentId)
                return null // Tampered or corrupted attachment rejected
            }

            enforceQuotaBeforeWrite(combined.size.toLong())
            val savedFile = File(attachmentsDir, "${wire.attachmentId}_$safeName")
            savedFile.writeBytes(combined)

            val completed = currentMeta.copy(
                receivedChunks = wire.totalChunks,
                localFilePath = savedFile.absolutePath
            )
            incomingManifests[wire.attachmentId] = completed
            return completed
        }
        return currentMeta
    }

    /**
     * Starts recording an AAC-LC compressed voice note (`.m4a`).
     */
    fun startVoiceNoteRecording(): Result<Unit> = runCatching {
        if (_isRecordingVoice.value) return@runCatching
        val outFile = File(attachmentsDir, "voice_${System.currentTimeMillis()}.m4a")
        currentVoiceFile = outFile
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        recorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16000)
            setAudioEncodingBitRate(24000) // Compact ~3 KB/sec for BLE mesh transfer
            setMaxDuration(VOICE_NOTE_MAX_DURATION_SEC * 1000)
            setOutputFile(outFile.absolutePath)
            prepare()
            start()
        }
        mediaRecorder = recorder
        recordingStartMs = System.currentTimeMillis()
        _recordingDurationSec.value = 0
        _isRecordingVoice.value = true
    }

    /**
     * Stops voice note recording and returns the raw bytes and duration in seconds.
     */
    fun stopVoiceNoteRecording(cancel: Boolean = false): Pair<ByteArray, Int>? {
        val recorder = mediaRecorder ?: return null
        val file = currentVoiceFile
        _isRecordingVoice.value = false
        try {
            recorder.stop()
        } catch (_: Exception) {
        } finally {
            try {
                recorder.release()
            } catch (_: Exception) {
            }
            mediaRecorder = null
        }

        if (cancel || file == null || !file.exists()) {
            file?.delete()
            return null
        }

        val durationSec = ((System.currentTimeMillis() - recordingStartMs) / 1000L).toInt().coerceAtLeast(1)
        val bytes = file.readBytes()
        return if (bytes.isNotEmpty()) bytes to durationSec else null
    }

    fun playVoiceNote(attachmentId: String, filePath: String) {
        stopVoicePlayback()
        val file = File(filePath)
        if (!file.exists()) return
        try {
            val player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    _isPlayingVoiceId.value = null
                    it.release()
                }
                prepare()
                start()
            }
            mediaPlayer = player
            _isPlayingVoiceId.value = attachmentId
        } catch (_: Exception) {
            _isPlayingVoiceId.value = null
        }
    }

    fun stopVoicePlayback() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
        _isPlayingVoiceId.value = null
    }

    /**
     * Starts live Push-to-Talk (PTT) low-latency 8kHz mono audio frame capture.
     */
    @SuppressLint("MissingPermission")
    fun startLivePttCapture(onFrameReady: (LivePttFrameWire) -> Unit) {
        if (_isLivePttStreaming.value) return
        _isLivePttStreaming.value = true
        val streamId = "ptt_${System.currentTimeMillis()}"

        pttJob = scope.launch {
            val sampleRate = 8000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(1600)

            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf
                )
            } catch (_: Exception) {
                _isLivePttStreaming.value = false
                return@launch
            }

            val shortBuf = ShortArray(800) // 100ms frame at 8kHz
            var seq = 0
            try {
                record.startRecording()
                while (isActive && _isLivePttStreaming.value) {
                    val read = record.read(shortBuf, 0, shortBuf.size)
                    if (read > 0) {
                        // Compress 16-bit PCM to 8-bit mu-law/linear byte representation for mesh efficiency
                        val compact = ByteArray(read) { idx -> (shortBuf[idx].toInt() shr 8).toByte() }
                        onFrameReady(
                            LivePttFrameWire(
                                streamId = streamId,
                                sequenceNumber = seq++,
                                sampleRateHz = sampleRate,
                                pcm8BitBase64 = CryptoEngine.encodeBase64(compact)
                            )
                        )
                    }
                }
            } catch (_: Exception) {
            } finally {
                try {
                    record.stop()
                    record.release()
                } catch (_: Exception) {
                }
                _isLivePttStreaming.value = false
            }
        }
    }

    fun stopLivePttCapture() {
        _isLivePttStreaming.value = false
        pttJob?.cancel()
        pttJob = null
    }

    fun playIncomingLivePttFrame(frame: LivePttFrameWire) {
        scope.launch {
            runCatching {
                val compact = CryptoEngine.decodeBase64(frame.pcm8BitBase64)
                val pcm16 = ShortArray(compact.size) { idx -> (compact[idx].toInt() shl 8).toShort() }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(frame.sampleRateHz)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(pcm16.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(pcm16, 0, pcm16.size)
                track.play()
            }
        }
    }

    private fun enforceQuotaBeforeWrite(requiredBytes: Long) {
        val files = attachmentsDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total + requiredBytes <= MAX_LOCAL_ATTACHMENT_STORAGE_BYTES) break
            val len = file.length()
            if (file.delete()) {
                total -= len
            }
        }
    }

    fun deleteAllAttachmentsForWipe() {
        stopVoicePlayback()
        stopLivePttCapture()
        attachmentsDir.listFiles()?.forEach { file ->
            try {
                // Overwrite file bytes with zeros before deletion (best-effort within flash wear-leveling limits)
                if (file.isFile && file.length() in 1..(4 * 1024 * 1024)) {
                    file.writeBytes(ByteArray(file.length().toInt()))
                }
                file.delete()
            } catch (_: Exception) {
            }
        }
        incomingChunks.clear()
        incomingManifests.clear()
    }
}

