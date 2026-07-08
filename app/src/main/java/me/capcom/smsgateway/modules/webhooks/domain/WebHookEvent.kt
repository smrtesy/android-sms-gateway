package me.capcom.smsgateway.modules.webhooks.domain

import com.google.gson.annotations.SerializedName

enum class WebHookEvent(val value: String) {
    @SerializedName("sms:received")
    SmsReceived("sms:received"),

    @SerializedName("sms:sent")
    SmsSent("sms:sent"),

    // Fired by SentSmsContentObserver for a message the user sent manually from
    // the phone's default messaging app (observed in content://sms/sent).
    // Distinct from `sms:sent`, which is a state transition on the app's OWN
    // outbox rows and carries an internal message id.
    @SerializedName("sms:sent-observed")
    SmsSentObserved("sms:sent-observed"),

    // Fired by SentMmsContentObserver for an MMS the user sent manually from the
    // phone's default messaging app (observed in content://mms, msg_box = sent).
    // The stock app only knows MMS it downloaded (incoming); a manual outgoing
    // MMS has no app row and fires no event.
    @SerializedName("mms:sent-observed")
    MmsSentObserved("mms:sent-observed"),

    @SerializedName("sms:delivered")
    SmsDelivered("sms:delivered"),

    @SerializedName("sms:failed")
    SmsFailed("sms:failed"),

    @SerializedName("system:ping")
    SystemPing("system:ping"),

    @SerializedName("sms:data-received")
    SmsDataReceived("sms:data-received"),

    @SerializedName("mms:received")
    MmsReceived("mms:received"),

    @SerializedName("mms:downloaded")
    MmsDownloaded("mms:downloaded"),

    @SerializedName("app:started")
    AppStarted("app:started"),
}
