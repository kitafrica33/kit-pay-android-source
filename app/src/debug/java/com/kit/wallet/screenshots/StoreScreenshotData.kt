package com.kit.wallet.screenshots

import com.kit.wallet.data.messaging.KitPaymentAction
import com.kit.wallet.data.messaging.KitPaymentMessage
import com.kit.wallet.ui.model.CallDirection
import com.kit.wallet.ui.model.CallEntry
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.DeliveryState
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageKind
import com.kit.wallet.ui.model.PaymentEventKind
import com.kit.wallet.ui.model.Transaction
import com.kit.wallet.ui.model.TransferClaim
import com.kit.wallet.ui.model.TransferClaimActor
import com.kit.wallet.ui.model.TransferClaimStatus
import com.kit.wallet.ui.model.TxStatus
import com.kit.wallet.ui.model.TxType
import com.kit.wallet.ui.model.UserProfile

/**
 * Fictional conversation and wallet data used only to render the Play Store listing screenshots.
 * It lives in the debug source set, so it is never compiled into a distributable build. Every
 * name, number and amount here is invented; no real account or person is depicted.
 */
internal object StoreScreenshotData {

    const val ME = "acct-amina"
    const val PEER_GRACE = "acct-grace"
    const val PEER_BRIAN = "acct-brian"
    const val PEER_DENG = "acct-deng"

    private const val HELD_CLAIM_ID = "6f1c9d20-3a44-4c6b-9f52-2c7e18b0aa31"
    private const val REVERSED_CLAIM_ID = "b23a7e58-9c11-4d0a-8fe6-51d4a6c73b90"

    val profile = UserProfile(
        name = "Amina Yusuf",
        phone = "+256 772 345 678",
        tag = "@amina",
        kycLabel = "Verified",
    )

    val balanceMinor = 128_450_000L

    val favorites = listOf(
        Contact("c1", "Brian Okello", "+256 701 234 567", favorite = true, receivingWalletId = "w-brian"),
        Contact("c2", "Grace Nakato", "+256 772 987 654", favorite = true, receivingWalletId = "w-grace"),
        Contact("c3", "Deng Majok", "+256 753 456 789", favorite = true, receivingWalletId = "w-deng"),
        Contact("c4", "Fatuma Ali", "+256 745 111 222", favorite = true, receivingWalletId = "w-fatuma"),
    )

    val transactions = listOf(
        Transaction("t1", "Grace Nakato", "Lunch split", 2_500_000, "2:14 PM", "Today", TxType.RECEIVE, TxStatus.COMPLETED, "KIT-9F27A1"),
        Transaction("t2", "Kit Pay Power", "Meter 04512278", -8_500_000, "11:02 AM", "Today", TxType.BILL, TxStatus.COMPLETED, "KIT-8E11B4"),
        Transaction("t3", "Brian Okello", "Rent contribution", -120_000_000, "9:45 AM", "Today", TxType.SEND, TxStatus.COMPLETED, "KIT-7D93C2"),
        Transaction("t4", "Airtime top-up", "+256 772 345 678", -500_000, "8:30 AM", "Today", TxType.AIRTIME, TxStatus.COMPLETED, "KIT-6C55D9"),
        Transaction("t5", "Javabean Cafe", "Card - Acacia Mall", -1_850_000, "6:12 PM", "Yesterday", TxType.MERCHANT, TxStatus.COMPLETED, "KIT-5B37E6"),
        Transaction("t6", "Stanbic Bank ..42", "Withdrawal", -50_000_000, "3:40 PM", "Yesterday", TxType.BANK_OUT, TxStatus.PENDING, "KIT-4A19F3"),
        Transaction("t7", "Deng Majok", "Payment request", 25_000_000, "1:05 PM", "Yesterday", TxType.REQUEST, TxStatus.COMPLETED, "KIT-3F82G7"),
        Transaction("t8", "Fatuma Ali", "Thanks for the ride", 1_200_000, "10:22 AM", "Mon, 18 Aug", TxType.RECEIVE, TxStatus.COMPLETED, "KIT-2E64H1"),
        Transaction("t9", "CityFiber Internet", "Acct 88231", -18_000_000, "9:00 AM", "Mon, 18 Aug", TxType.BILL, TxStatus.COMPLETED, "KIT-1D46J8"),
        Transaction("t10", "Centenary Bank ..18", "Deposit", 200_000_000, "8:15 AM", "Mon, 18 Aug", TxType.BANK_IN, TxStatus.COMPLETED, "KIT-0C28K5"),
    )

    val chats = listOf(
        ChatPreview("ch1", "Grace Nakato", "Sent you UGX 25,000", "2:14 PM", peerUserId = PEER_GRACE, unread = 2, online = true, pinned = true),
        ChatPreview("ch2", "Apartment 4B", "Deng: I've sent my share", "1:48 PM", unread = 5, isGroup = true, pinned = true),
        ChatPreview("ch3", "Brian Okello", "Received, webale!", "12:30 PM", peerUserId = PEER_BRIAN, lastFromMe = true, lastState = DeliveryState.READ, online = true),
        ChatPreview("ch4", "Fatuma Ali", "Voice note - 0:42", "11:05 AM", unread = 1),
        ChatPreview("ch5", "Weekend Hikers", "Lydia: Sipi or Mabira?", "Yesterday", isGroup = true, muted = true),
        ChatPreview("ch6", "Peter Ssemwanga", "You: Photo", "Yesterday", lastFromMe = true, lastState = DeliveryState.DELIVERED),
        ChatPreview("ch7", "Halima Noor", "Kale, see you at 6", "Sunday"),
        ChatPreview("ch8", "Deng Majok", "You: Requested UGX 250,000", "Sunday", peerUserId = PEER_DENG, lastFromMe = true, lastState = DeliveryState.DELIVERED),
    )

    val calls = listOf(
        CallEntry("cl1", "Grace Nakato", "Today, 1:20 PM", CallDirection.OUTGOING, video = true),
        CallEntry("cl2", "Brian Okello", "Today, 11:47 AM", CallDirection.INCOMING),
        CallEntry("cl3", "Deng Majok", "Today, 9:15 AM", CallDirection.MISSED),
        CallEntry("cl4", "Apartment 4B", "Yesterday, 8:02 PM", CallDirection.OUTGOING, video = true),
        CallEntry("cl5", "Fatuma Ali", "Yesterday, 4:31 PM", CallDirection.INCOMING),
        CallEntry("cl6", "Halima Noor", "Sunday, 7:44 PM", CallDirection.MISSED, video = true),
        CallEntry("cl7", "Peter Ssemwanga", "Sunday, 10:12 AM", CallDirection.OUTGOING),
    )

    /** Grace's thread: an ordinary chat that a held transfer arrives into. */
    val graceChat = chats.first()

    private val heldDescriptor = KitPaymentMessage(
        action = KitPaymentAction.TRANSFER,
        referenceId = HELD_CLAIM_ID,
        amountMinor = 2_500_000,
        currencyCode = "UGX",
        currencyScale = 2,
        note = "Lunch split",
    ).encode()

    val heldClaim = TransferClaim(
        id = HELD_CLAIM_ID,
        transactionId = "9a4f2c31-77bd-4e18-9a03-6d5b21c8ef47",
        status = TransferClaimStatus.PENDING,
        amountMinor = 2_500_000,
        currencyCode = "UGX",
        currencyScale = 2,
        note = "Lunch split",
        senderUserId = PEER_GRACE,
        recipientUserId = ME,
        senderName = "Grace Nakato",
        recipientName = "Amina Yusuf",
        canAccept = true,
        canReject = true,
    )

    /** Screenshot 2: money arriving inside the conversation, waiting to be accepted. */
    val incomingTransferConversation = listOf(
        Message("m1", "Reached home yet?", "1:58 PM", fromMe = false),
        Message("m2", "Yes, just got in. Traffic was mad on Jinja Road", "2:00 PM", fromMe = true, state = DeliveryState.READ),
        Message("m3", "Lunch came to 50k, so your half is 25k", "2:02 PM", fromMe = false, reactions = listOf("👍")),
        Message("m4", "Sending it over now", "2:03 PM", fromMe = false),
        Message(
            id = "m5",
            text = heldDescriptor,
            time = "2:04 PM",
            fromMe = false,
            kind = MessageKind.PAYMENT_TRANSFER,
            mediaDescriptor = heldDescriptor,
            amountMinor = 2_500_000,
            paymentReferenceId = HELD_CLAIM_ID,
            paymentEvent = PaymentEventKind.TRANSFER,
            paymentNote = "Lunch split",
            paymentCurrencyCode = "UGX",
            paymentCurrencyScale = 2,
        ),
        Message("m6", "It's waiting for you whenever you're ready", "2:05 PM", fromMe = false),
    )

    private val reversedDescriptor = KitPaymentMessage(
        action = KitPaymentAction.TRANSFER,
        referenceId = REVERSED_CLAIM_ID,
        amountMinor = 12_000_000,
        currencyCode = "UGX",
        currencyScale = 2,
        note = "Deposit",
    ).encode()

    val reversedClaim = TransferClaim(
        id = REVERSED_CLAIM_ID,
        transactionId = "1e7c04b9-52a8-4f3d-b6ac-8d0917e5c264",
        status = TransferClaimStatus.REVERSED,
        amountMinor = 12_000_000,
        currencyCode = "UGX",
        currencyScale = 2,
        note = "Deposit",
        reason = "Wrong Brian in my contacts",
        resolvedBy = TransferClaimActor.SENDER,
        senderUserId = ME,
        recipientUserId = PEER_BRIAN,
        senderName = "Amina Yusuf",
        recipientName = "Brian Okello",
    )

    val brianChat = chats[2]

    /** Screenshot 3: a transfer taken back before it was accepted, with the reason on the record. */
    val reversedConversation = listOf(
        Message("r1", "Sending the deposit for the flat now", "12:18 PM", fromMe = true, state = DeliveryState.READ),
        Message(
            id = "r2",
            text = reversedDescriptor,
            time = "12:19 PM",
            fromMe = true,
            state = DeliveryState.READ,
            kind = MessageKind.PAYMENT_TRANSFER,
            mediaDescriptor = reversedDescriptor,
            amountMinor = -12_000_000,
            paymentReferenceId = REVERSED_CLAIM_ID,
            paymentEvent = PaymentEventKind.TRANSFER,
            paymentNote = "Deposit",
            paymentCurrencyCode = "UGX",
            paymentCurrencyScale = 2,
        ),
        Message("r3", "Hold on, I think that went to the wrong Brian", "12:24 PM", fromMe = true, state = DeliveryState.READ),
        Message(
            id = "r4",
            text = "",
            time = "12:25 PM",
            fromMe = true,
            kind = MessageKind.PAYMENT_EVENT,
            amountMinor = -12_000_000,
            paymentReferenceId = REVERSED_CLAIM_ID,
            paymentEvent = PaymentEventKind.REVERSED,
            paymentReason = "Wrong Brian in my contacts",
            paymentCurrencyCode = "UGX",
            paymentCurrencyScale = 2,
        ),
        Message("r5", "No stress, nothing landed on my side", "12:27 PM", fromMe = false),
        Message("r6", "Resending to the right one now", "12:28 PM", fromMe = true, state = DeliveryState.DELIVERED),
    )

    /** Screenshot: a completed payment sitting in the thread it was agreed in. */
    val paidConversation = listOf(
        Message("p1", "Are you still able to cover the driver?", "4:31 PM", fromMe = false),
        Message("p2", "Yes, sending it across now", "4:32 PM", fromMe = true, state = DeliveryState.READ),
        Message(
            id = "p3",
            text = "Payment",
            time = "4:33 PM",
            fromMe = true,
            state = DeliveryState.READ,
            kind = MessageKind.PAYMENT,
            amountMinor = -4_500_000,
            paymentNote = "Driver for Saturday",
            paymentCurrencyCode = "UGX",
            paymentCurrencyScale = 2,
        ),
        Message("p4", "Landed, thank you", "4:34 PM", fromMe = false, reactions = listOf("🙏")),
        Message("p5", "I'll book him for 7am then", "4:35 PM", fromMe = false),
        Message("p6", "Perfect", "4:36 PM", fromMe = true, state = DeliveryState.DELIVERED),
    )

    private const val REQUEST_ID = "4c8e5b1a-6d92-4a70-84f1-b3e279d05c68"

    private val requestDescriptor = KitPaymentMessage(
        action = KitPaymentAction.REQUEST,
        referenceId = REQUEST_ID,
        amountMinor = 25_000_000,
        currencyCode = "UGX",
        currencyScale = 2,
        note = "Share of the water bill",
    ).encode()

    /** Screenshot 4: a payment request answered without leaving the thread. */
    val requestConversation = listOf(
        Message("q1", "Water bill came in at 500k for the quarter", "10:02 AM", fromMe = false),
        Message("q2", "Send me a request and I'll clear my half", "10:04 AM", fromMe = true, state = DeliveryState.READ),
        Message(
            id = "q3",
            text = requestDescriptor,
            time = "10:05 AM",
            fromMe = false,
            kind = MessageKind.PAYMENT_REQUEST,
            mediaDescriptor = requestDescriptor,
            amountMinor = 25_000_000,
            paymentReferenceId = REQUEST_ID,
            paymentEvent = PaymentEventKind.REQUESTED,
            paymentNote = "Share of the water bill",
            paymentCurrencyCode = "UGX",
            paymentCurrencyScale = 2,
        ),
        Message(
            id = "q4",
            text = "",
            time = "10:06 AM",
            fromMe = true,
            kind = MessageKind.PAYMENT_EVENT,
            amountMinor = -25_000_000,
            paymentReferenceId = REQUEST_ID,
            paymentEvent = PaymentEventKind.PAID,
            paymentCurrencyCode = "UGX",
            paymentCurrencyScale = 2,
        ),
        Message("q5", "Got it, thank you", "10:07 AM", fromMe = false, reactions = listOf("🎉")),
        Message("q6", "Voice note", "10:12 AM", fromMe = false, kind = MessageKind.VOICE_NOTE, durationSec = 42),
    )
}
