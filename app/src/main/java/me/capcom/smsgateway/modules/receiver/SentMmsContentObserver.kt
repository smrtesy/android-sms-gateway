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
 * high-water mark, and emits an `mms:sent-observed` webhook for each. Reuses
 * [MmsContentReader] for the body/subject/date and reads the TO address
 * (type 151) itself, since the reader resolves the FROM address (sender), which
 * for an outgoing message is the user's own line.
 *
 * Sent rows (msg_box = 2) never collide with the incoming MMS observer's rows
 * (msg_box = 1) despite sharing the `_id` sequence; each keeps its own mark.
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
        // msg_box 2 = sent
        val cursor = try {
            context.contentResolver.query(
                Uri.parse("content://mms"),
                arrayOf("_id"),
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

        cursor.use { c ->
            while (c.moveToNext()) {
                val mmsId = c.getLong(0)
                try {
                    emitSentObserved(mmsId)
                } catch (e: Exception) {
                    logsService.insert(
                        LogEntry.Priority.ERROR,
                        MODULE_NAME,
                        "Failed processing sent MMS (id=$mmsId)",
                        mapOf("mmsId" to mmsId, "error" to (e.message ?: e.toString())),
                    )
                }
                storage.mmsSentLastProcessedID = mmsId
            }
        }
    }

    private fun emitSentObserved(mmsId: Long) {
        val message = MmsContentReader.read(context, mmsId) ?: return
        val recipient = readRecipient(mmsId) ?: return

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
                message = message.body ?: message.subject ?: "",
                sentAt = message.date,
            ),
        )
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

    companion object {
        private const val TAG = "SentMmsContentObserver"
    }
}
