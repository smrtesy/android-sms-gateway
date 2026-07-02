package me.capcom.smsgateway.modules.receiver

import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.Telephony
import androidx.core.content.ContextCompat
import me.capcom.smsgateway.helpers.SubscriptionsHelper
import me.capcom.smsgateway.modules.logs.LogsService
import me.capcom.smsgateway.modules.logs.db.LogEntry
import me.capcom.smsgateway.modules.webhooks.WebHooksService
import me.capcom.smsgateway.modules.webhooks.domain.WebHookEvent
import me.capcom.smsgateway.modules.webhooks.payload.SmsEventPayload
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.Date

/**
 * Captures OUTGOING SMS the user sends from the phone's own messaging app
 * (e.g. Samsung Messages). The stock SMS Gateway only knows about messages it
 * sent itself via its API — a manual send lands in `content://sms/sent` with no
 * corresponding app row and fires no event. This observer watches the sent box,
 * picks up rows with `_id` above a persisted high-water mark, and emits a
 * dedicated `sms:sent-observed` webhook for each one.
 *
 * Mirrors [SmsContentObserver] (the inbox fallback). It does NOT write to the
 * incoming-messages store — that store is for genuinely received messages, and
 * the persisted high-water mark alone provides dedup across restarts. Sent rows
 * carry `type = 2` while inbox rows carry `type = 1`, so this observer and the
 * inbox observer never pick up each other's rows despite sharing the `_id`
 * sequence.
 */
class SentSmsContentObserver : KoinComponent {
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
                "SMS sent observer not started because READ_SMS is not granted",
            )
            return
        }

        // Initialize high-water mark to current max ID so existing rows in the
        // sent box are not re-processed (and re-forwarded) on first start.
        if (storage.smsSentLastProcessedID == 0L) {
            storage.smsSentLastProcessedID = queryMaxSentSmsId()
        }

        val thread = HandlerThread("SentSmsContentObserver").apply { start() }
        handlerThread = thread
        val handler = Handler(thread.looper)

        val obs = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                processNewMessages()
            }
        }
        observer = obs

        // Observe the parent sms:// URI with notifyForDescendants=true so we
        // catch inserts into the sent box regardless of which internal URI the
        // system provider notifies under.
        context.contentResolver.registerContentObserver(
            Uri.parse("content://sms"),
            true,
            obs,
        )

        // Catch up rows that were sent while the app process was stopped.
        // ContentObserver callbacks are edge-triggered, so already-inserted sent
        // rows would otherwise remain pending forever.
        handler.post { processNewMessages() }
    }

    fun stop() {
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
        handlerThread?.quitSafely()
        handlerThread = null
    }

    private fun queryMaxSentSmsId(): Long {
        if (!canReadSms()) return 0

        val cursor = try {
            context.contentResolver.query(
                Telephony.Sms.Sent.CONTENT_URI,
                arrayOf(Telephony.Sms._ID),
                null, null,
                Telephony.Sms._ID + " DESC LIMIT 1",
            )
        } catch (e: SecurityException) {
            logsService.insert(
                LogEntry.Priority.WARN,
                MODULE_NAME,
                "Unable to initialize SMS sent high-water mark because provider access was denied",
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
                "Skipping SMS sent processing because READ_SMS is not granted",
            )
            return
        }

        val mark = storage.smsSentLastProcessedID

        val projection = mutableListOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.DATE,
            Telephony.Sms.BODY,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            projection += Telephony.Sms.SUBSCRIPTION_ID
        }

        val cursor = try {
            context.contentResolver.query(
                Telephony.Sms.Sent.CONTENT_URI,
                projection.toTypedArray(),
                Telephony.Sms._ID + " > ?",
                arrayOf(mark.toString()),
                Telephony.Sms._ID + " ASC",
            )
        } catch (e: SecurityException) {
            logsService.insert(
                LogEntry.Priority.WARN,
                MODULE_NAME,
                "Skipping SMS sent processing because provider access was denied",
                mapOf("error" to (e.message ?: e.toString())),
            )
            return
        } ?: return

        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val address = c.getString(1) ?: ""
                val date = Date(c.getLong(2))
                val body = c.getString(3) ?: ""
                val subId = if (projection.size > 4) {
                    c.getInt(4).takeIf { it >= 0 }
                } else {
                    null
                }

                try {
                    emitSentObserved(id, address, body, date, subId)
                } catch (e: Exception) {
                    logsService.insert(
                        LogEntry.Priority.ERROR,
                        MODULE_NAME,
                        "Failed processing sent SMS (id=$id)",
                        mapOf("smsId" to id, "error" to (e.message ?: e.toString())),
                    )
                }
                storage.smsSentLastProcessedID = id
            }
        }
    }

    private fun emitSentObserved(
        id: Long,
        address: String,
        body: String,
        date: Date,
        subscriptionId: Int?,
    ) {
        val simSlotIndex = subscriptionId?.let {
            SubscriptionsHelper.getSimSlotIndex(context, it)
        }
        val simNumber = simSlotIndex?.let { it + 1 }

        webHooksService.emit(
            context,
            WebHookEvent.SmsSentObserved,
            SmsEventPayload.SmsSentObserved(
                messageId = id.toString(),
                recipient = address,
                simNumber = simNumber,
                message = body,
                sentAt = date,
            ),
        )
    }

    private fun canReadSms(): Boolean = ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.READ_SMS,
    ) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "SentSmsContentObserver"
    }
}
