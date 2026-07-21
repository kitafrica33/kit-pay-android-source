package com.kit.wallet.data.demo

import com.kit.wallet.ui.model.Beneficiary
import com.kit.wallet.ui.model.BillProvider
import com.kit.wallet.ui.model.CallDirection
import com.kit.wallet.ui.model.CallEntry
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.DeliveryState
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageKind
import com.kit.wallet.ui.model.Transaction
import com.kit.wallet.ui.model.TxStatus
import com.kit.wallet.ui.model.TxType

/**
 * Fictional preview-only data. Production repositories use the dedicated
 * /api/kit-wallet/v1 API; this object is never bound into the production graph.
 * Amounts are UGX in minor units (cents).
 */
object DemoData {

    const val USER_NAME = "Amina Yusuf"
    const val USER_PHONE = "+256 772 345 678"
    const val WALLET_BALANCE_MINOR = 128_450_000L // UGX 1,284,500

    val contacts = listOf(
        Contact("c1", "Brian Okello", "+256 701 234 567", favorite = true),
        Contact("c2", "Grace Nakato", "+256 772 987 654", favorite = true, status = "Living the dream ✨"),
        Contact("c3", "Deng Majok", "+256 753 456 789", favorite = true, status = "Available"),
        Contact("c4", "Fatuma Ali", "+256 745 111 222", status = "At work 💼"),
        Contact("c5", "Peter Ssemwanga", "+256 710 333 444", status = "Available"),
        Contact("c6", "Lydia Achen", "+256 782 555 666", status = "Weekend mode 🏔️"),
        Contact("c7", "Samuel Mugisha", "+256 799 777 888", isKitUser = false),
        Contact("c8", "Halima Noor", "+256 706 999 000"),
        Contact("c9", "John Wasswa", "+256 774 121 314", isKitUser = false),
    )

    val transactions = listOf(
        Transaction("t1", "Grace Nakato", "Lunch split 🍜", 2_500_000, "2:14 PM", "Today", TxType.RECEIVE, TxStatus.COMPLETED, "KIT-9F27A1"),
        Transaction("t2", "Kit Pay Power", "Meter 04512278", -8_500_000, "11:02 AM", "Today", TxType.BILL, TxStatus.COMPLETED, "KIT-8E11B4"),
        Transaction("t3", "Brian Okello", "Rent contribution", -120_000_000, "9:45 AM", "Today", TxType.SEND, TxStatus.COMPLETED, "KIT-7D93C2"),
        Transaction("t4", "Airtime top-up", "+256 772 345 678", -500_000, "8:30 AM", "Today", TxType.AIRTIME, TxStatus.COMPLETED, "KIT-6C55D9"),
        Transaction("t5", "Javabean Café", "Card • Acacia Mall", -1_850_000, "6:12 PM", "Yesterday", TxType.MERCHANT, TxStatus.COMPLETED, "KIT-5B37E6"),
        Transaction("t6", "Stanbic Bank ••42", "Withdrawal", -50_000_000, "3:40 PM", "Yesterday", TxType.BANK_OUT, TxStatus.PENDING, "KIT-4A19F3"),
        Transaction("t7", "Deng Majok", "Payment request", 25_000_000, "1:05 PM", "Yesterday", TxType.REQUEST, TxStatus.COMPLETED, "KIT-3F82G7"),
        Transaction("t8", "Fatuma Ali", "Thanks for the ride!", 1_200_000, "10:22 AM", "Mon, 13 Jul", TxType.RECEIVE, TxStatus.COMPLETED, "KIT-2E64H1"),
        Transaction("t9", "CityFiber Internet", "Acct 88231", -18_000_000, "9:00 AM", "Mon, 13 Jul", TxType.BILL, TxStatus.FAILED, "KIT-1D46J8"),
        Transaction("t10", "Centenary Bank ••18", "Deposit", 200_000_000, "8:15 AM", "Mon, 13 Jul", TxType.BANK_IN, TxStatus.COMPLETED, "KIT-0C28K5"),
    )

    val chats = listOf(
        ChatPreview("ch1", "Grace Nakato", "typing…", "2:14 PM", unread = 2, online = true, typing = true, pinned = true),
        ChatPreview("ch2", "Apartment 4B 🏠", "Deng: I've sent my share", "1:48 PM", unread = 5, isGroup = true, pinned = true),
        ChatPreview("ch3", "Brian Okello", "Received, webale! 🙏", "12:30 PM", lastFromMe = true, lastState = DeliveryState.READ, online = true),
        ChatPreview("ch4", "Fatuma Ali", "Voice note • 0:42", "11:05 AM", unread = 1),
        ChatPreview("ch5", "Weekend Hikers", "Lydia: Poll — Sipi or Mabira?", "Yesterday", isGroup = true, muted = true),
        ChatPreview("ch6", "Peter Ssemwanga", "You: Photo", "Yesterday", lastFromMe = true, lastState = DeliveryState.DELIVERED),
        ChatPreview("ch7", "Halima Noor", "Kale, see you at 6", "Sunday"),
        ChatPreview("ch8", "Kit Pay Updates 📣", "New: pay school fees in-app", "Sunday", muted = true),
    )

    val conversation = listOf(
        Message("m1", "Reached home yet?", "1:58 PM", fromMe = false),
        Message("m2", "Yes, just got in. Traffic was crazy on Jinja Road 😅", "2:00 PM", fromMe = true, state = DeliveryState.READ),
        Message("m3", "Btw lunch was 50k, split is 25k 🍜", "2:02 PM", fromMe = false, reactions = listOf("👍")),
        Message("m4", "Sending now", "2:03 PM", fromMe = true, state = DeliveryState.READ, replyToText = "Btw lunch was 50k, split is 25k 🍜"),
        Message("m5", "Payment", "2:04 PM", fromMe = true, kind = MessageKind.PAYMENT, amountMinor = -2_500_000, state = DeliveryState.READ),
        Message("m6", "Received! Webale nyo 🎉", "2:05 PM", fromMe = false, reactions = listOf("❤️", "🎉")),
        Message("m7", "Voice note", "2:08 PM", fromMe = false, kind = MessageKind.VOICE_NOTE, durationSec = 42),
        Message("m8", "Anytime! Same time next week?", "2:14 PM", fromMe = true, state = DeliveryState.DELIVERED),
    )

    val calls = listOf(
        CallEntry("cl1", "Grace Nakato", "Today, 1:20 PM", CallDirection.OUTGOING, video = true),
        CallEntry("cl2", "Brian Okello", "Today, 11:47 AM", CallDirection.INCOMING),
        CallEntry("cl3", "Deng Majok", "Today, 9:15 AM", CallDirection.MISSED),
        CallEntry("cl4", "Apartment 4B 🏠", "Yesterday, 8:02 PM", CallDirection.OUTGOING, video = true),
        CallEntry("cl5", "Fatuma Ali", "Yesterday, 4:31 PM", CallDirection.INCOMING, video = false),
        CallEntry("cl6", "Halima Noor", "Sunday, 7:44 PM", CallDirection.MISSED, video = true),
        CallEntry("cl7", "Peter Ssemwanga", "Sunday, 10:12 AM", CallDirection.OUTGOING),
    )

    val billProviders = listOf(
        BillProvider("b1", "Kit Pay Power", "Electricity", "Meter number"),
        BillProvider("b2", "City Water", "Water", "Account number"),
        BillProvider("b3", "CityFiber", "Internet", "Account number"),
        BillProvider("b4", "SkyView TV", "TV", "Smartcard number"),
        BillProvider("b5", "Greenhill Academy", "School fees", "Student ID"),
        BillProvider("b6", "URA / Government", "Government", "Invoice number"),
    )

    val beneficiaries = listOf(
        Beneficiary("bn1", "Amina Yusuf", "Stanbic Bank", "••••  4212"),
        Beneficiary("bn2", "Amina Yusuf", "Centenary Bank", "••••  0918"),
        Beneficiary("bn3", "Mama Halima Shop", "dfcu Bank", "••••  7733", verified = false),
    )
}
