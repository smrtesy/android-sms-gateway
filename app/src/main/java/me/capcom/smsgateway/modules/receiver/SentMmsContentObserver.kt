package me.capcom.smsgateway.modules.receiver

import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import me.capcom.smsgateway.helpers.SubscriptionsHelper
import me.capcom.smsgateway.modules.logs.LogsService
import me.capcom.smsgateway.modules.logs.db.LogEntry
import me.capcom.smsgateway.modules.webhooks.WebHooksService
import me.capcom.smsgateway.modules.webhooks.domain.WebHookEvent
import me.capcom.smsgateway.modules.webhooks.payload.SmsEventPayload

import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Captures OUTGOING MMS the user sends from the phone's own messaging app.
 * US carriers frequently send group / long / media texts as MMS, so a
 * sent-SMS observer alone would miss them. The stock gateway only knows MMS it
 * downloaded (incoming); a manual outgoing MMS lands in content://mms with
 * msg_box = 2 (sent) and fires no event.
 *
 * Mirrors [SentSmsContentObserver] and [MmsContentObserver]: registers on
 * content://mms, picks up sent-box rows with `_id` above a persisted
 * high-water mark, reuses [MmsContentReader] for the body/subject/date, reads
 * the TO address (type 151) itself (the reader resolves the FROM address, which
 * for an outgoing message is the user's own line), and emits an
 * `mms:sent-observed` webhook.
 *
 * MMS is written to the provider in stages: the message row can appear (and
 * flip to msg_box = 2) a beat before its `addr` (recipient) and `part` (body)
 * child rows are committed. Reading on that first onChange yields a null
 * recipient/body. So a row that isn't fully readable yet is LEFT for the next
 * onChange (the mark is not advanced past it) rather than skipped-and-lost;
 * a row still unreadable after [GIVE_UP_MS] is abandoned so a permanently
 * malformed row can't wedge the queue. Re-emitting a later row while waiting is
 * harmless — the consumer upserts on messageId.
 */
class SentMmsContentObserver : KoinComponent {
    private val context: Context by inject()
    private val storage: StateStorage by inject()
    private val webHooksService: WebHooksService by inject()
    private val logsService: LogsService by inject()

    private var handlerThread: HandlerThread? = null
    private var observer: ContentObserver? = null

    fun start() {
        if (observer != null) {
            return
        }

        if (!canReadSms()) {
            logsService.insert(
                LogEntry.Priority.WARN,
                MODULE_NAME,
                "MMS sent observer not started because READ_SMS is not granted",
            )
            return
        }

        // Initialize high-water mark to current max ID so existing sent MMS are
        // not re-processed (and re-forwarded) on first start.
        if (storage.mmsSentLastProcessedID == 0L) {
            storage.mmsSentLastProcessedID = queryMaxSentMmsId()
        }

        val thread = HandlerThread("SentMmsContentObserver").apply { start() }
        handlerThread = thread
        val handler = Handler(thread.looper)

        val obs = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                processNewMessages()
            }
        }
        observer = obs

        context.contentResolver.registerContentObserver(
            Uri.parse("content://mms"),
            true,
            obs,
        )

        logsService.insert(
            LogEntry.Priority.INFO,
            MODULE_NAME,
            "MMS sent observer started",
            mapOf("mark" to storage.mmsSentLastProcessedID),
        )

        // Catch up rows sent while the app process was stopped (edge-triggered).
        handler.post { processNewMessages() }
    }

    fun stop() {
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
        handlerThread?.quitSafely()
        handlerThread = null
    }

    private fun queryMaxSentMmsId(): Long {
        val cursor = try {
            context.contentResolver.query(
                Uri.parse("content://mms"),
                arrayOf("_id"),
                "msg_box = 2",
                null,
                "_id DESC LIMIT 1",
            )
        } catch (e: SecurityException) {
            logsService.insert(
                LogEntry.Priority.WARN,
                MODULE_NAME,
                "Unable to initialize MMS sent high-water mark because provider access was denied",
                mapOf("error" to (e.message ?: e.toString())),
            )
            return 0
        } ?: return 0

        return cursor.use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0
        }
    }

    private fun processNewMessages() {
        if (!canReadSms()) {
            logsService.insert(
                LogEntry.Priority.WARN,
                MODULE_NAME,
                "Skipping MMS sent processing because READ_SMS is not granted",
            )
            return
        }

        val mark = storage.mmsSentLastProcessedID
        // msg_box 2 = sent. `date` (seconds) drives the give-up window for rows
        // whose child tables never finish populating.
        val cursor = try {
            context.contentResolver.query(
                Uri.parse("content://mms"),
                arrayOf("_id", "date"),
                "_id > ? AND msg_box = 2",
                arrayOf(mark.toString()),
                "_id ASC",
            )
        } catch (e: SecurityException) {
            logsService.insert(
                LogEntry.Priority.WARN,
                MODULE_NAME,
                "Skipping MMS sent processing because provider access was denied",
                mapOf("error" to (e.message ?: e.toString())),
            )
            return
        } ?: return

        // Collect first so the cursor is closed before we do per-row provider
        // reads (each of which opens more cursors).
        val rows = cursor.use { c ->
            val out = mutableListOf<Pair<Long, Long>>()
            while (c.moveToNext()) out.add(c.getLong(0) to c.getLong(1))
            out
        }

        if (rows.isEmpty()) return
        logsService.insert(
            LogEntry.Priority.INFO,
            MODULE_NAME,
            "Found ${rows.size} new sent-MMS row(s) to process",
            mapOf("mark" to mark, "ids" to rows.map { it.first }),
        )

        var newMark = mark
        for ((mmsId, dateSeconds) in rows) {
            val emitted = try {
                emitSentObserved(mmsId)
            } catch (e: Exception) {
                logsService.insert(
                    LogEntry.Priority.ERROR,
                    MODULE_NAME,
                    "Failed processing sent MMS (id=$mmsId)",
                    mapOf("mmsId" to mmsId, "error" to (e.message ?: e.toString())),
                )
                // Treat as processed so one bad row can't wedge the queue.
                true
            }

            if (emitted) {
                newMark = mmsId
                continue
            }

            // Not readable yet. Give the provider time to finish writing the
            // addr/part rows: leave the mark before this id so the next onChange
            // retries. Abandon only once the row is older than the grace window.
            val ageMs = nowMs() - dateSeconds * 1000
            if (ageMs > GIVE_UP_MS) {
                logsService.insert(
                    LogEntry.Priority.WARN,
                    MODULE_NAME,
                    "Abandoning unreadable sent MMS (id=$mmsId) after ${ageMs}ms",
                    mapOf("mmsId" to mmsId),
                )
                newMark = mmsId
                continue
            }

            logsService.insert(
                LogEntry.Priority.INFO,
                MODULE_NAME,
                "Sent MMS (id=$mmsId) not fully written yet; will retry on next change",
                mapOf("mmsId" to mmsId, "ageMs" to ageMs),
            )
            break
        }

        if (newMark != mark) {
            storage.mmsSentLastProcessedID = newMark
        }
    }

    /** @return true if the MMS was read and a webhook emitted; false if the row
     *  is not yet fully written (recipient/body missing) and should be retried. */
    private fun emitSentObserved(mmsId: Long): Boolean {
        val message = MmsContentReader.read(context, mmsId)
        val recipient = readRecipient(mmsId)
        val body = message?.body ?: message?.subject
        // Recipient is required to route the thread; body may legitimately be
        // empty for a pure-attachment MMS, so only the recipient gates readiness.
        if (message == null || recipient.isNullOrBlank()) {
            return false
        }

        val simSlotIndex = message.subscriptionId?.let {
            SubscriptionsHelper.getSimSlotIndex(context, it)
        }
        val simNumber = simSlotIndex?.let { it + 1 }

        webHooksService.emit(
            context,
            WebHookEvent.MmsSentObserved,
            // Reuses the sms:sent-observed payload shape { messageId, recipient,
            // message, sentAt }; the consumer distinguishes SMS vs MMS by event.
            SmsEventPayload.SmsSentObserved(
                messageId = mmsId.toString(),
                recipient = recipient,
                simNumber = simNumber,
                message = body ?: "",
                sentAt = message.date,
            ),
        )
        logsService.insert(
            LogEntry.Priority.INFO,
            MODULE_NAME,
            "Emitted mms:sent-observed (id=$mmsId)",
            mapOf("mmsId" to mmsId, "recipient" to recipient, "hasBody" to (body != null)),
        )
        return true
    }

    /** The TO address (type 151) of an outgoing MMS; first non-blank recipient. */
    private fun readRecipient(mmsId: Long): String? {
        return context.contentResolver.query(
            Uri.parse("content://mms/$mmsId/addr"),
            arrayOf("address"),
            "type = 151",
            null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val addr = c.getString(0)?.takeIf { it.isNotBlank() }
                if (addr != null) return addr
            }
            null
        }
    }

    private fun canReadSms(): Boolean = ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.READ_SMS,
    ) == PackageManager.PERMISSION_GRANTED

    // System.currentTimeMillis via a helper so the intent is explicit.
    private fun nowMs(): Long = System.currentTimeMillis()

    companion object {
        private const val TAG = "SentMmsContentObserver"

        // How long to keep retrying an MMS whose addr/part rows never finish
        // committing before abandoning it (so a malformed row can't wedge the
        // queue). Generous — a normal MMS is fully written within seconds.
        private const val GIVE_UP_MS = 5 * 60 * 1000L
    }
}
