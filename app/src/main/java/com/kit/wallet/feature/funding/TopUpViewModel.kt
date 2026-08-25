package com.kit.wallet.feature.funding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.repository.BankingRepository
import com.kit.wallet.data.repository.BeneficiaryContactDirectory
import com.kit.wallet.data.repository.ContactRepository
import com.kit.wallet.data.repository.FinancialOperationQuote
import com.kit.wallet.data.repository.MobileMoneyRepository
import com.kit.wallet.data.repository.ProfilePhotoDirectory
import com.kit.wallet.data.repository.WalletRepository
import com.kit.wallet.data.repository.WalletSyncRepository
import com.kit.wallet.ui.model.BankCapability
import com.kit.wallet.ui.model.BankInstitution
import com.kit.wallet.ui.model.BankOperationKind
import com.kit.wallet.ui.model.BeneficiaryIdentity
import com.kit.wallet.ui.model.Money
import com.kit.wallet.ui.model.TopUp
import com.kit.wallet.ui.model.TopUpRequirement
import com.kit.wallet.ui.model.TxStatus
import com.kit.wallet.ui.model.eligibleBankBeneficiaries
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Covering a payment the wallet cannot yet afford.
 *
 * Every screen that spends money — an internal transfer, a mobile money payout, a bank withdrawal
 * — can find itself short, and the answer is the same each time: say by how much, move that much in
 * from an account the person already owns, and hand the original payment back its approval step. So
 * this lives once, here, rather than three times in three screens.
 *
 * The one thing it will not do is claim the money arrived. [TopUpStage.Funded] is published only
 * after the wallet balance itself has been re-read from the server and covers the payment.
 */
@HiltViewModel
class TopUpViewModel @Inject constructor(
    private val mobileMoney: MobileMoneyRepository,
    private val banking: BankingRepository,
    private val wallet: WalletRepository,
    private val walletSync: WalletSyncRepository,
    contacts: ContactRepository,
    beneficiaryContacts: BeneficiaryContactDirectory,
    profilePhotos: ProfilePhotoDirectory,
) : ViewModel() {

    private val mutableRequirement = MutableStateFlow<TopUpRequirement?>(null)

    /** The shortfall being covered, or null when no top-up is on screen. */
    val requirement: StateFlow<TopUpRequirement?> = mutableRequirement.asStateFlow()

    private val mutableStage = MutableStateFlow<TopUpStage>(TopUpStage.ChooseSource)
    val stage: StateFlow<TopUpStage> = mutableStage.asStateFlow()

    private val mutableSelectedSourceId = MutableStateFlow<String?>(null)
    val selectedSourceId: StateFlow<String?> = mutableSelectedSourceId.asStateFlow()

    private val mutableQuote = MutableStateFlow<FinancialOperationQuote?>(null)
    val quote: StateFlow<FinancialOperationQuote?> = mutableQuote.asStateFlow()

    private val mutableBusy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()

    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()

    /** Which kind of account the "add an account" form on screen is for, if any. */
    private val mutableAddingSource = MutableStateFlow<TopUpChannel?>(null)
    val addingSource: StateFlow<TopUpChannel?> = mutableAddingSource.asStateFlow()

    /**
     * Identity of the currently visible top-up and of its current command.
     *
     * Repository calls can finish after cancellation (for example, while an HTTP response is
     * already being decoded). Both numbers therefore fence every post-suspension publication: a
     * dismissed or replaced top-up can never turn the next one into Funded, StillMoving, or an
     * error, and its finally block cannot clear the next one's busy indicator.
     */
    private var sessionSequence = 0L
    private var commandSequence = 0L
    private var activeCommand: Job? = null

    /** The submitted operation remains authoritative across a StillMoving -> keep waiting retry. */
    private var waitingOperationId: String? = null
    private var waitingChannel: TopUpChannel? = null

    val networks = mobileMoney.networks
    val verification = mobileMoney.verification

    /**
     * The accounts money can be pulled *from*.
     *
     * Filtered the way the server filters: a mobile money cash-in needs an active account of your
     * own in the wallet's currency, and a bank deposit needs a verified own account at a bank that
     * supports deposits. Showing a row the server is certain to refuse would only turn a shortfall
     * into two errors instead of one.
     */
    private val mobileMoneySources: Flow<List<TopUpSource>> = combine(
        mobileMoney.accounts,
        contacts.contacts,
        beneficiaryContacts.snapshots,
        profilePhotos.snapshots,
    ) { saved, addressBook, linkSnapshot, photoSnapshot ->
        val links = beneficiaryContacts.currentLinks(linkSnapshot)
        val knownPhotos = profilePhotos.currentPhotos(photoSnapshot)
        saved
            .filter { it.isOwnAccount && it.status == "active" }
            .map { account ->
                TopUpSource(
                    id = account.id,
                    channel = TopUpChannel.MOBILE_MONEY,
                    title = account.label,
                    detail = "${account.networkName} · ${account.phoneNumberMasked}",
                    currencyCode = account.currencyCode,
                    currencyScale = account.currencyScale,
                    avatarUrl = BeneficiaryIdentity.avatarUrlFor(
                        kitUserId = account.kitUserId,
                        serverAvatarUrl = account.avatarUrl,
                        savedPhoneIdentity = links[account.id],
                        contacts = addressBook,
                        knownPhotos = knownPhotos,
                        phoneIdentityOf = beneficiaryContacts::identityForPhone,
                    ),
                )
            }
    }

    private val bankSources: Flow<List<TopUpSource>> = combine(
        banking.beneficiaries,
        banking.banks,
        profilePhotos.snapshots,
        mutableRequirement,
    ) { saved, banks, photoSnapshot, need ->
        val knownPhotos = profilePhotos.currentPhotos(photoSnapshot)
        val banksById = banks.associateBy(BankInstitution::id)
        eligibleBankBeneficiaries(BankOperationKind.DEPOSIT, banks, saved).map { beneficiary ->
            val bank = beneficiary.bankId?.let(banksById::get)
            TopUpSource(
                id = beneficiary.id,
                channel = TopUpChannel.BANK,
                title = beneficiary.name,
                detail = "${beneficiary.bank} · ${beneficiary.accountMasked}",
                // A bank account carries no scale of its own; it is only ever offered for a wallet
                // in its own currency, so the wallet's scale is the account's scale.
                currencyCode = bank?.currency ?: "",
                currencyScale = need?.currencyScale ?: Money.SCALE,
                avatarUrl = BeneficiaryIdentity.avatarUrlFor(
                    kitUserId = beneficiary.kitUserId,
                    serverAvatarUrl = beneficiary.avatarUrl,
                    knownPhotos = knownPhotos,
                ),
            )
        }
    }

    private val availableSources: Flow<List<TopUpSource>> =
        combine(mobileMoneySources, bankSources, mutableRequirement) { fromPhone, fromBank, need ->
            val currency = need?.currencyCode ?: return@combine emptyList()
            (fromPhone + fromBank).filter { it.currencyCode.equals(currency, ignoreCase = true) }
        }

    val sources: StateFlow<List<TopUpSource>> = availableSources
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The banks a new deposit account can be added at, in the wallet's currency. */
    val depositBanks: StateFlow<List<BankInstitution>> =
        combine(banking.banks, mutableRequirement) { banks, need ->
            val currency = need?.currencyCode ?: return@combine emptyList()
            banks.filter {
                it.currency.equals(currency, ignoreCase = true) &&
                    it.supports(BankCapability.DEPOSITS) &&
                    it.supports(BankCapability.ACCOUNT_VERIFICATION)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Opens the top-up for a payment that came up short.
     *
     * The caller passes what the payment needs and what the wallet holds; the arithmetic — and the
     * rounding up to a whole unit, which the collection endpoints require anyway — is done once, in
     * [TopUp].
     */
    fun start(requirement: TopUpRequirement) {
        invalidateActiveCommand()
        mutableRequirement.value = requirement
        mutableStage.value = TopUpStage.ChooseSource
        mutableSelectedSourceId.value = null
        mutableQuote.value = null
        mutableError.value = null
        mutableAddingSource.value = null
        waitingOperationId = null
        waitingChannel = null
        viewModelScope.launch {
            runCatching { mobileMoney.refresh() }
            runCatching { banking.refresh() }
        }
    }

    /** Closes the top-up. Money already submitted keeps moving; only this screen goes away. */
    fun dismiss() {
        invalidateActiveCommand()
        mutableRequirement.value = null
        mutableStage.value = TopUpStage.ChooseSource
        mutableSelectedSourceId.value = null
        mutableQuote.value = null
        mutableError.value = null
        mutableAddingSource.value = null
        waitingOperationId = null
        waitingChannel = null
    }

    fun select(sourceId: String) {
        mutableSelectedSourceId.value = sourceId
        mutableError.value = null
    }

    fun addSource(channel: TopUpChannel?) {
        mutableAddingSource.value = channel
        mutableError.value = null
    }

    fun clearError() {
        mutableError.value = null
    }

    /** Steps back from a quote to the list, so a different account can be chosen. */
    fun back() {
        if (mutableStage.value != TopUpStage.Review) return
        mutableQuote.value = null
        mutableStage.value = TopUpStage.ChooseSource
    }

    /** Prices the top-up, ready for approval. */
    fun review() {
        runCommand { fence ->
            val source = selectedSource() ?: return@runCommand
            // Re-derived against the balance as it stands now rather than as it stood when the
            // payment was refused: money may have arrived in between, in which case there is
            // nothing to top up, and if more has gone out the quote should cover the larger gap.
            val current = currentRequirement() ?: run {
                ensureCurrent(fence)
                mutableStage.value = TopUpStage.Funded
                return@runCommand
            }
            val quote = when (source.channel) {
                // `gross_up` puts the fees on top, so the wallet is credited the amount asked for
                // exactly. `inclusive` would take the provider's cut out of the credit and land
                // short of the shortfall — the one thing this flow must not do.
                TopUpChannel.MOBILE_MONEY -> mobileMoney.previewOperation(
                    action = "collection",
                    accountId = source.id,
                    amountMinor = current.topUpMinor,
                    feeMode = "gross_up",
                )
                TopUpChannel.BANK -> banking.previewOperation(
                    type = BankOperationKind.DEPOSIT.apiType,
                    beneficiaryId = source.id,
                    amountMinor = current.topUpMinor,
                )
            }
            ensureCurrent(fence)
            check(current.balanceMinor + quote.recipientAmountMinor >= current.requiredMinor) {
                "This top-up would not cover the payment. Choose a different account."
            }
            mutableQuote.value = quote
            mutableStage.value = TopUpStage.Review
        }
    }

    /**
     * Approves the quote and waits for the money.
     *
     * [paymentPin] is passed straight through and may be empty: an empty PIN is what asks the
     * payment authorizer for the biometric path, exactly as the mobile money and bank screens do.
     */
    fun confirm(paymentPin: String) {
        val quote = mutableQuote.value ?: return
        val requirement = mutableRequirement.value ?: return
        runCommand { fence ->
            val source = selectedSource() ?: return@runCommand
            val operationId = when (source.channel) {
                TopUpChannel.MOBILE_MONEY -> mobileMoney.submitOperation(quote, paymentPin)
                TopUpChannel.BANK -> banking.submitOperation(quote, paymentPin)
            }
            ensureCurrent(fence)
            mutableQuote.value = null
            waitingOperationId = operationId
            waitingChannel = source.channel
            awaitCredit(requirement, source.channel, operationId, fence)
        }
    }

    /** Keeps waiting after a wait that ran out of patience rather than out of money. */
    fun keepWaiting() {
        if (mutableStage.value != TopUpStage.StillMoving) return
        val requirement = mutableRequirement.value ?: return
        val operationId = waitingOperationId ?: return
        val channel = waitingChannel ?: return
        runCommand { fence -> awaitCredit(requirement, channel, operationId, fence) }
    }

    /**
     * Watches the wallet until it covers the payment.
     *
     * Watching the *balance* rather than the operation's status is deliberate: the balance is the
     * only thing that decides whether the blocked payment can now go through, and it is the same
     * figure the server will check when it is retried. A completed operation whose credit has not
     * landed yet is not good enough to send somebody back to a payment that would fail again.
     *
     * [operationId] is the operation this wait belongs to, so that its failure ends the wait while
     * anybody else's does not. It is retained when the wait times out, because a later failure of
     * this exact operation must still end a `keepWaiting` attempt.
     */
    private suspend fun awaitCredit(
        requirement: TopUpRequirement,
        channel: TopUpChannel,
        operationId: String,
        fence: CommandFence,
    ) {
        ensureCurrent(fence)
        mutableStage.value = TopUpStage.Waiting
        var failure: String? = null
        val funded = withTimeoutOrNull(WAIT_TIMEOUT_MILLIS) {
            // Establish an authoritative balance before making any success claim. A repository
            // submit may have updated another local projection, but only the server-backed wallet
            // refresh answers whether the original payment can now be retried.
            var observedBalance = ignoreRefreshFailure { walletSync.refresh() }
                ?.selectedAvailableBalanceMinor
                ?: wallet.balanceMinor.value
            ensureCurrent(fence)
            while (!requirement.coveredBy(observedBalance)) {
                failure = failureFor(channel, operationId)
                if (failure != null) return@withTimeoutOrNull false
                delay(POLL_INTERVAL_MILLIS)
                // Neither refresh is allowed to end the wait: a dropped connection mid-way through
                // a top-up is a reason to look again in a moment, not to declare anything about
                // where the money is.
                observedBalance = ignoreRefreshFailure { walletSync.refresh() }
                    ?.selectedAvailableBalanceMinor
                    ?: wallet.balanceMinor.value
                ensureCurrent(fence)
                ignoreRefreshFailure {
                    when (channel) {
                        TopUpChannel.MOBILE_MONEY -> mobileMoney.refresh()
                        TopUpChannel.BANK -> banking.refresh()
                    }
                }
                ensureCurrent(fence)
            }
            true
        }
        ensureCurrent(fence)
        when {
            funded == true -> {
                waitingOperationId = null
                waitingChannel = null
                mutableStage.value = TopUpStage.Funded
            }
            failure != null -> {
                waitingOperationId = null
                waitingChannel = null
                mutableError.value = failure
                mutableStage.value = TopUpStage.ChooseSource
            }
            else -> mutableStage.value = TopUpStage.StillMoving
        }
    }

    /** The reason this top-up ended badly, or null while it is still on its way. */
    private fun failureFor(channel: TopUpChannel, operationId: String): String? = when (channel) {
        TopUpChannel.MOBILE_MONEY -> mobileMoney.operations.value
            .firstOrNull { it.id == operationId }
            ?.takeIf { it.status.lowercase() in FAILED_OPERATION_STATUSES }
            ?.let {
                it.failureMessage?.takeIf(String::isNotBlank)
                    ?: "The mobile money top-up did not go through."
            }
        TopUpChannel.BANK -> banking.operations.value
            .firstOrNull { it.id == operationId }
            ?.takeIf { it.status == TxStatus.FAILED }
            ?.let { "The bank deposit did not go through." }
    }

    fun addMobileMoneySource(networkCode: String, phoneNumber: String, label: String) {
        runCommand { fence ->
            val known = availableSources.first()
                .filter { it.channel == TopUpChannel.MOBILE_MONEY }
                .map(TopUpSource::id)
                .toSet()
            mobileMoney.verifyAndSaveAccount(networkCode, phoneNumber, label, kind = "own")
            ensureCurrent(fence)
            mutableAddingSource.value = null
            selectNewlyAdded(TopUpChannel.MOBILE_MONEY, known)
        }
    }

    fun addBankSource(bankId: String, accountNumber: String, label: String) {
        runCommand { fence ->
            val known = availableSources.first()
                .filter { it.channel == TopUpChannel.BANK }
                .map(TopUpSource::id)
                .toSet()
            banking.addBeneficiary(bankId, accountNumber, label, kind = "own")
            ensureCurrent(fence)
            mutableAddingSource.value = null
            selectNewlyAdded(TopUpChannel.BANK, known)
        }
    }

    /**
     * Selects the account that was just added, so the person does not have to find it themselves.
     *
     * By elimination against the ids that were there beforehand, because neither add call reports
     * what it created. If two arrived at once, nothing is selected rather than the wrong one.
     */
    private suspend fun selectNewlyAdded(channel: TopUpChannel, knownBefore: Set<String>) {
        // `sources` is shared WhileSubscribed and can legitimately lag when the add sheet is the
        // only observer. Reading one fresh combined emission avoids missing the row just saved.
        val added = availableSources.first()
            .filter { it.channel == channel && it.id !in knownBefore }
            .singleOrNull()
            ?: return
        mutableSelectedSourceId.value = added.id
    }

    private suspend fun selectedSource(): TopUpSource? {
        val id = mutableSelectedSourceId.value ?: return null
        return availableSources.first().firstOrNull { it.id == id }
    }

    /** The shortfall as it stands right now, or null if the wallet has since caught up. */
    private fun currentRequirement(): TopUpRequirement? {
        val requirement = mutableRequirement.value ?: return null
        val refreshed = TopUp.requirementFor(
            requiredMinor = requirement.requiredMinor,
            balanceMinor = wallet.balanceMinor.value,
            currencyCode = requirement.currencyCode,
            currencyScale = requirement.currencyScale,
        )
        mutableRequirement.value = refreshed ?: requirement
        return refreshed
    }

    private fun runCommand(command: suspend (CommandFence) -> Unit) {
        if (mutableBusy.value) return
        val fence = CommandFence(sessionSequence, ++commandSequence)
        mutableBusy.value = true
        mutableError.value = null
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                command(fence)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (isCurrent(fence)) {
                    mutableError.value = error.message
                        ?.takeIf(String::isNotBlank)
                        ?: "The top-up could not be completed"
                }
            } finally {
                if (isCurrent(fence)) {
                    mutableBusy.value = false
                    activeCommand = null
                }
            }
        }
        activeCommand = job
        job.start()
    }

    private fun invalidateActiveCommand() {
        sessionSequence++
        commandSequence++
        activeCommand?.cancel()
        activeCommand = null
        mutableBusy.value = false
    }

    private fun isCurrent(fence: CommandFence): Boolean =
        fence.session == sessionSequence && fence.command == commandSequence

    private fun ensureCurrent(fence: CommandFence) {
        if (!isCurrent(fence)) throw CancellationException("Top-up session changed")
    }

    private suspend fun <T> ignoreRefreshFailure(refresh: suspend () -> T): T? =
        try {
            refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A transient refresh failure is not evidence that an in-flight top-up failed. The
            // polling window retains the operation id and tries both projections again.
            null
        }

    private data class CommandFence(val session: Long, val command: Long)

    private companion object {
        /**
         * How long to watch the wallet before offering to stop watching.
         *
         * Long enough for a mobile money prompt to be answered on the handset — which is a person
         * finding their phone and typing a PIN, not a machine — and short enough that nobody is
         * left staring at a spinner with no way out.
         */
        const val WAIT_TIMEOUT_MILLIS = 150_000L
        const val POLL_INTERVAL_MILLIS = 3_000L
        val FAILED_OPERATION_STATUSES = setOf("failed", "reversed", "cancelled", "canceled")
    }
}
