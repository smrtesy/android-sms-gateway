package me.capcom.smsgateway.modules.webhooks.payload

import java.util.Date

sealed class SmsEventPayload(
    messageId: String,
    sender: String?,
    recipient: String?,
    simNumber: Int?,

    phoneNumber: String,
) : MessageEventPayload(messageId, sender, recipient, simNumber, phoneNumber) {

    class SmsSent(
        messageId: String,
        sender: String?,
        recipient: String,
        simNumber: Int?,
        val partsCount: Int,
        val sentAt: Date,
    ) : SmsEventPayload(messageId, sender, recipient, simNumber, recipient)

    class SmsDelivered(
        messageId: String,
        sender: String?,
        recipient: String,
        simNumber: Int?,
        val deliveredAt: Date,
    ) : SmsEventPayload(messageId, sender, recipient, simNumber, recipient)

    class SmsFailed(
        messageId: String,
        sender: String?,
        recipient: String,
        simNumber: Int?,
        val failedAt: Date,
        val reason: String,
    ) : SmsEventPayload(messageId, sender, recipient, simNumber, recipient)

    class SmsReceived(
        messageId: String,
        sender: String,
        recipient: String?,
        simNumber: Int?,
        val message: String,
        val receivedAt: Date,
    ) : SmsEventPayload(messageId, sender, recipient, simNumber, sender)

    class SmsDataReceived(
        messageId: String,
        sender: String,
        recipient: String?,
        simNumber: Int?,
        val data: String,
        val receivedAt: Date,
    ) : SmsEventPayload(messageId, sender, recipient, simNumber, sender)

    /**
     * A message the user sent manually from the phone's messaging app, picked up
     * by SentSmsContentObserver from content://sms/sent. Carries the body (unlike
     * [SmsSent]) so the consumer can ingest the outgoing message content.
     */
    class SmsSentObserved(
        messageId: String,
        recipient: String,
        simNumber: Int?,
        val message: String,
        val sentAt: Date,
    ) : SmsEventPayload(messageId, null, recipient, simNumber, recipient)
}