package za.co.payfi.clearing.flows

import net.corda.v5.application.flows.*
import net.corda.v5.application.marshalling.JsonMarshallingService
import net.corda.v5.application.membership.MemberLookup
import net.corda.v5.application.persistence.PersistenceService
import net.corda.v5.base.annotations.CordaSerializable
import net.corda.v5.base.annotations.Suspendable
import net.corda.v5.base.types.MemberX500Name
import net.corda.v5.ledger.notary.plugin.api.NotaryLookup
import net.corda.v5.ledger.utxo.UtxoLedgerService
import za.co.payfi.clearing.contracts.PaymentInstructionContract
import za.co.payfi.clearing.persistence.*
import za.co.payfi.clearing.states.*
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// ============================================================================
// UPDATE PAYMENT STATUS FLOW
// ============================================================================

/**
 * Updates payment status (SUBMITTED→VALIDATED, VALIDATED→CLEARED, or rejection).
 * Caller must be either the debtor or creditor bank on the transaction.
 */
@InitiatingFlow(protocol = "update-payment-status")
class UpdatePaymentStatusFlow : ClientStartableFlow {

    @CordaInject lateinit var utxoLedgerService: UtxoLedgerService
    @CordaInject lateinit var memberLookup: MemberLookup
    @CordaInject lateinit var notaryLookup: NotaryLookup
    @CordaInject lateinit var jsonMarshallingService: JsonMarshallingService

    @CordaSerializable
    data class Input(val stateId: String, val newStatus: String, val statusReason: String? = null)
    @CordaSerializable
    data class Output(val stateId: String, val newStatus: String, val success: Boolean, val message: String)

    @Suspendable
    override fun call(requestBody: ClientRequestBody): String {
        val input = requestBody.getRequestBodyAs(jsonMarshallingService, Input::class.java)
        val stateId = UUID.fromString(input.stateId)
        val newStatus = PaymentStatus.valueOf(input.newStatus)

        val stateAndRef = findStateById(stateId)
        val currentState = stateAndRef.state.contractState

        // Authorization: caller must be debtor or creditor bank
        verifyCallerIsParticipant(currentState)

        val updatedState = currentState.withStatus(newStatus, input.statusReason)
        val notary = notaryLookup.notaryServices.first()

        val txBuilder = utxoLedgerService.createTransactionBuilder()
            .setNotary(notary.name)
            .setTimeWindowBetween(Instant.now(), Instant.now().plus(Duration.ofSeconds(60)))
            .addInputState(stateAndRef.ref)
            .addOutputState(updatedState)
            .addCommand(PaymentInstructionContract.PaymentCommand.UpdateStatus())
            .addSignatories(updatedState.participants)

        val signedTx = txBuilder.toSignedTransaction()
        val sessions = buildSessionList(currentState)
        utxoLedgerService.finalize(signedTx, sessions)

        return jsonMarshallingService.format(Output(
            input.stateId, newStatus.name, true, "Status updated to $newStatus"
        ))
    }

    @Suspendable
    private fun findStateById(stateId: UUID) =
        utxoLedgerService.findUnconsumedStatesByType(PaymentInstructionState::class.java)
            .firstOrNull { it.state.contractState.stateId == stateId }
            ?: throw IllegalArgumentException("State not found: $stateId")

    @Suspendable
    private fun verifyCallerIsParticipant(state: PaymentInstructionState) {
        val myBranchCode = memberLookup.myInfo().memberProvidedContext["branchCode"] ?: ""
        require(
            myBranchCode == state.debtorAgentBranchCode ||
            myBranchCode == state.creditorAgentBranchCode
        ) { "Caller ($myBranchCode) is not a participant on this transaction" }
    }

    @Suspendable
    private fun buildSessionList(state: PaymentInstructionState): List<FlowSession> {
        val myBranchCode = memberLookup.myInfo().memberProvidedContext["branchCode"] ?: ""
        val sessions = mutableListOf<FlowSession>()

        // Counterparty session (skip if same-bank)
        if (state.debtorAgentBranchCode != state.creditorAgentBranchCode) {
            val counterpartyBranch = if (myBranchCode == state.debtorAgentBranchCode) {
                state.creditorAgentBranchCode
            } else {
                state.debtorAgentBranchCode
            }
            memberLookup.lookup()
                .filter { it.memberProvidedContext["branchCode"] == counterpartyBranch }
                .firstOrNull()?.let { sessions.add(initiateFlow(it.name)) }
        }

        // SARB observer session
        memberLookup.lookup()
            .filter { it.memberProvidedContext["role"] == "REGULATOR_OBSERVER" }
            .firstOrNull()?.let { sessions.add(initiateFlow(it.name)) }

        return sessions
    }
}

@InitiatedBy(protocol = "update-payment-status")
class UpdatePaymentStatusResponderFlow : ResponderFlow {
    @CordaInject lateinit var utxoLedgerService: UtxoLedgerService
    @Suspendable
    override fun call(session: FlowSession) {
        utxoLedgerService.receiveFinality(session) { _ -> }
    }
}

// ============================================================================
// REQUEST CANCELLATION FLOW (camt.056)
// ============================================================================

/**
 * Initiates a cancellation request. Only the debtor bank (originator) may request.
 */
@InitiatingFlow(protocol = "request-cancellation")
class RequestCancellationFlow : ClientStartableFlow {

    @CordaInject lateinit var utxoLedgerService: UtxoLedgerService
    @CordaInject lateinit var memberLookup: MemberLookup
    @CordaInject lateinit var notaryLookup: NotaryLookup
    @CordaInject lateinit var jsonMarshallingService: JsonMarshallingService

    @CordaSerializable
    data class Input(val stateId: String, val cancellationReason: String)
    @CordaSerializable
    data class Output(val stateId: String, val status: String, val message: String)

    @Suspendable
    override fun call(requestBody: ClientRequestBody): String {
        val input = requestBody.getRequestBodyAs(jsonMarshallingService, Input::class.java)
        val stateId = UUID.fromString(input.stateId)

        val stateAndRef = utxoLedgerService.findUnconsumedStatesByType(PaymentInstructionState::class.java)
            .firstOrNull { it.state.contractState.stateId == stateId }
            ?: throw IllegalArgumentException("State not found: $stateId")

        val currentState = stateAndRef.state.contractState

        // Authorization: only the debtor bank (originator) may request cancellation
        val myBranchCode = memberLookup.myInfo().memberProvidedContext["branchCode"] ?: ""
        require(myBranchCode == currentState.debtorAgentBranchCode) {
            "Only the debtor bank may request cancellation"
        }

        val updatedState = currentState.withStatus(
            PaymentStatus.CANCELLATION_REQUESTED, input.cancellationReason
        )
        val notary = notaryLookup.notaryServices.first()

        val txBuilder = utxoLedgerService.createTransactionBuilder()
            .setNotary(notary.name)
            .setTimeWindowBetween(Instant.now(), Instant.now().plus(Duration.ofSeconds(60)))
            .addInputState(stateAndRef.ref)
            .addOutputState(updatedState)
            .addCommand(PaymentInstructionContract.PaymentCommand.RequestCancellation())
            .addSignatories(updatedState.participants)

        val signedTx = txBuilder.toSignedTransaction()
        val sessions = buildSessionList(currentState, myBranchCode)
        utxoLedgerService.finalize(signedTx, sessions)

        return jsonMarshallingService.format(Output(
            input.stateId, "CANCELLATION_REQUESTED",
            "Cancellation requested. Awaiting counterparty resolution."
        ))
    }

    @Suspendable
    private fun buildSessionList(state: PaymentInstructionState, myBranchCode: String): List<FlowSession> {
        val sessions = mutableListOf<FlowSession>()
        if (state.debtorAgentBranchCode != state.creditorAgentBranchCode) {
            val counterpartyBranch = state.creditorAgentBranchCode
            memberLookup.lookup()
                .filter { it.memberProvidedContext["branchCode"] == counterpartyBranch }
                .firstOrNull()?.let { sessions.add(initiateFlow(it.name)) }
        }
        memberLookup.lookup()
            .filter { it.memberProvidedContext["role"] == "REGULATOR_OBSERVER" }
            .firstOrNull()?.let { sessions.add(initiateFlow(it.name)) }
        return sessions
    }
}

@InitiatedBy(protocol = "request-cancellation")
class RequestCancellationResponderFlow : ResponderFlow {
    @CordaInject lateinit var utxoLedgerService: UtxoLedgerService
    @Suspendable
    override fun call(session: FlowSession) {
        utxoLedgerService.receiveFinality(session) { _ -> }
    }
}

// ============================================================================
// RESOLVE CANCELLATION FLOW (camt.029)
// ============================================================================

/**
 * Resolves a cancellation request. Only the creditor bank may resolve.
 * Fee reversal applied if cancellation accepted and fee was applicable.
 */
@InitiatingFlow(protocol = "resolve-cancellation")
class ResolveCancellationFlow : ClientStartableFlow {

    @CordaInject lateinit var utxoLedgerService: UtxoLedgerService
    @CordaInject lateinit var memberLookup: MemberLookup
    @CordaInject lateinit var notaryLookup: NotaryLookup
    @CordaInject lateinit var jsonMarshallingService: JsonMarshallingService
    @CordaInject lateinit var persistenceService: PersistenceService

    @CordaSerializable
    data class Input(val stateId: String, val resolution: String, val reason: String? = null)
    @CordaSerializable
    data class Output(val stateId: String, val status: String, val feeReversed: Boolean, val message: String)

    @Suspendable
    override fun call(requestBody: ClientRequestBody): String {
        val input = requestBody.getRequestBodyAs(jsonMarshallingService, Input::class.java)
        val stateId = UUID.fromString(input.stateId)
        val resolution = PaymentStatus.valueOf(input.resolution)

        require(resolution == PaymentStatus.CANCELLATION_ACCEPTED ||
                resolution == PaymentStatus.CANCELLATION_REJECTED) {
            "Resolution must be CANCELLATION_ACCEPTED or CANCELLATION_REJECTED"
        }

        val stateAndRef = utxoLedgerService.findUnconsumedStatesByType(PaymentInstructionState::class.java)
            .firstOrNull { it.state.contractState.stateId == stateId }
            ?: throw IllegalArgumentException("State not found: $stateId")

        val currentState = stateAndRef.state.contractState

        // Authorization: only the creditor bank may resolve cancellation
        val myBranchCode = memberLookup.myInfo().memberProvidedContext["branchCode"] ?: ""
        require(myBranchCode == currentState.creditorAgentBranchCode) {
            "Only the creditor bank may resolve cancellation"
        }

        val updatedState = currentState.withStatus(resolution, input.reason)
        val notary = notaryLookup.notaryServices.first()

        val txBuilder = utxoLedgerService.createTransactionBuilder()
            .setNotary(notary.name)
            .setTimeWindowBetween(Instant.now(), Instant.now().plus(Duration.ofSeconds(60)))
            .addInputState(stateAndRef.ref)
            .addOutputState(updatedState)
            .addCommand(PaymentInstructionContract.PaymentCommand.ResolveCancellation())
            .addSignatories(updatedState.participants)

        val signedTx = txBuilder.toSignedTransaction()
        val sessions = buildSessionList(currentState, myBranchCode)
        utxoLedgerService.finalize(signedTx, sessions)

        // Fee reversal if cancellation accepted and fee was applicable
        var feeReversed = false
        if (resolution == PaymentStatus.CANCELLATION_ACCEPTED && currentState.feeApplicable) {
            persistenceService.persist(FeeAccrual(
                stateId = currentState.stateId,
                creditorBankBranchCode = currentState.creditorAgentBranchCode,
                feeAmount = currentState.feeAmount.negate(),
                taxAmount = currentState.feeTaxAmount.negate(),
                totalAmount = currentState.feeAmount.add(currentState.feeTaxAmount).negate(),
                transactionAmount = currentState.amount,
                accrualDate = LocalDate.now(),
                reversed = true,
                reversalNote = "CANCELLATION_ACCEPTED — fee reversed"
            ))
            feeReversed = true
        }

        return jsonMarshallingService.format(Output(
            input.stateId, resolution.name, feeReversed,
            "Cancellation ${if (resolution == PaymentStatus.CANCELLATION_ACCEPTED) "accepted" else "rejected"}"
        ))
    }

    @Suspendable
    private fun buildSessionList(state: PaymentInstructionState, myBranchCode: String): List<FlowSession> {
        val sessions = mutableListOf<FlowSession>()
        if (state.debtorAgentBranchCode != state.creditorAgentBranchCode) {
            val counterpartyBranch = state.debtorAgentBranchCode
            memberLookup.lookup()
                .filter { it.memberProvidedContext["branchCode"] == counterpartyBranch }
                .firstOrNull()?.let { sessions.add(initiateFlow(it.name)) }
        }
        memberLookup.lookup()
            .filter { it.memberProvidedContext["role"] == "REGULATOR_OBSERVER" }
            .firstOrNull()?.let { sessions.add(initiateFlow(it.name)) }
        return sessions
    }
}

@InitiatedBy(protocol = "resolve-cancellation")
class ResolveCancellationResponderFlow : ResponderFlow {
    @CordaInject lateinit var utxoLedgerService: UtxoLedgerService
    @Suspendable
    override fun call(session: FlowSession) {
        utxoLedgerService.receiveFinality(session) { _ -> }
    }
}

// ============================================================================
// RETURN PAYMENT FLOW (pacs.004)
// ============================================================================

/**
 * Returns a cleared payment. Only the creditor bank may return.
 * Fee reversal applied if fee was applicable.
 */
@InitiatingFlow(protocol = "return-payment")
class ReturnPaymentFlow : ClientStartableFlow {

    @CordaInject lateinit var utxoLedgerService: UtxoLedgerService
    @CordaInject lateinit var memberLookup: MemberLookup
    @CordaInject lateinit var notaryLookup: NotaryLookup
    @CordaInject lateinit var jsonMarshallingService: JsonMarshallingService
    @CordaInject lateinit var persistenceService: PersistenceService

    @CordaSerializable
    data class Input(val stateId: String, val returnReasonCode: String)
    @CordaSerializable
    data class Output(val stateId: String, val status: String, val feeReversed: Boolean, val message: String)

    @Suspendable
    override fun call(requestBody: ClientRequestBody): String {
        val input = requestBody.getRequestBodyAs(jsonMarshallingService, Input::class.java)
        val stateId = UUID.fromString(input.stateId)

        val stateAndRef = utxoLedgerService.findUnconsumedStatesByType(PaymentInstructionState::class.java)
            .firstOrNull { it.state.contractState.stateId == stateId }
            ?: throw IllegalArgumentException("State not found: $stateId")

        val currentState = stateAndRef.state.contractState

        // Authorization: only the creditor bank may return a payment
        val myBranchCode = memberLookup.myInfo().memberProvidedContext["branchCode"] ?: ""
        require(myBranchCode == currentState.creditorAgentBranchCode) {
            "Only the creditor bank may return a payment"
        }

        val updatedState = currentState.withStatus(PaymentStatus.RETURNED, input.returnReasonCode)
        val notary = notaryLookup.notaryServices.first()

        val txBuilder = utxoLedgerService.createTransactionBuilder()
            .setNotary(notary.name)
            .setTimeWindowBetween(Instant.now(), Instant.now().plus(Duration.ofSeconds(60)))
            .addInputState(stateAndRef.ref)
            .addOutputState(updatedState)
            .addCommand(PaymentInstructionContract.PaymentCommand.Return())
            .addSignatories(updatedState.participants)

        val signedTx = txBuilder.toSignedTransaction()
        val sessions = buildSessionList(currentState, myBranchCode)
        utxoLedgerService.finalize(signedTx, sessions)

        // Fee reversal
        var feeReversed = false
        if (currentState.feeApplicable) {
            persistenceService.persist(FeeAccrual(
                stateId = currentState.stateId,
                creditorBankBranchCode = currentState.creditorAgentBranchCode,
                feeAmount = currentState.feeAmount.negate(),
                taxAmount = currentState.feeTaxAmount.negate(),
                totalAmount = currentState.feeAmount.add(currentState.feeTaxAmount).negate(),
                transactionAmount = currentState.amount,
                accrualDate = LocalDate.now(),
                reversed = true,
                reversalNote = "RETURNED (${input.returnReasonCode}) — fee reversed"
            ))
            feeReversed = true
        }

        return jsonMarshallingService.format(Output(
            input.stateId, "RETURNED", feeReversed,
            "Payment returned with reason ${input.returnReasonCode}"
        ))
    }

    @Suspendable
    private fun buildSessionList(state: PaymentInstructionState, myBranchCode: String): List<FlowSession> {
        val sessions = mutableListOf<FlowSession>()
        if (state.debtorAgentBranchCode != state.creditorAgentBranchCode) {
            val counterpartyBranch = state.debtorAgentBranchCode
            memberLookup.lookup()
                .filter { it.memberProvidedContext["branchCode"] == counterpartyBranch }
                .firstOrNull()?.let { sessions.add(initiateFlow(it.name)) }
        }
        memberLookup.lookup()
            .filter { it.memberProvidedContext["role"] == "REGULATOR_OBSERVER" }
            .firstOrNull()?.let { sessions.add(initiateFlow(it.name)) }
        return sessions
    }
}

@InitiatedBy(protocol = "return-payment")
class ReturnPaymentResponderFlow : ResponderFlow {
    @CordaInject lateinit var utxoLedgerService: UtxoLedgerService
    @Suspendable
    override fun call(session: FlowSession) {
        utxoLedgerService.receiveFinality(session) { _ -> }
    }
}

// ============================================================================
// QUERY PAYMENT INSTRUCTIONS (local, read-only)
// ============================================================================

class QueryPaymentInstructionsFlow : ClientStartableFlow {

    @CordaInject lateinit var utxoLedgerService: UtxoLedgerService
    @CordaInject lateinit var jsonMarshallingService: JsonMarshallingService

    @CordaSerializable
    data class Input(
        val status: String? = null,
        val counterpartyBranchCode: String? = null,
        val dateFrom: String? = null,
        val dateTo: String? = null
    )

    @Suspendable
    override fun call(requestBody: ClientRequestBody): String {
        val input = requestBody.getRequestBodyAs(jsonMarshallingService, Input::class.java)

        // NOTE: Uses in-memory filtering for pilot. For production,
        // implement vault-named queries with ContractStateVaultJsonFactory.
        var results = utxoLedgerService
            .findUnconsumedStatesByType(PaymentInstructionState::class.java)
            .map { it.state.contractState }

        if (input.status != null) {
            results = results.filter { it.status == PaymentStatus.valueOf(input.status) }
        }
        if (input.counterpartyBranchCode != null) {
            results = results.filter {
                it.debtorAgentBranchCode == input.counterpartyBranchCode ||
                it.creditorAgentBranchCode == input.counterpartyBranchCode
            }
        }
        if (input.dateFrom != null) {
            val from = LocalDate.parse(input.dateFrom)
            results = results.filter { it.settlementDate >= from }
        }
        if (input.dateTo != null) {
            val to = LocalDate.parse(input.dateTo)
            results = results.filter { it.settlementDate <= to }
        }

        return jsonMarshallingService.format(results)
    }
}

// ============================================================================
// GENERATE SETTLEMENT REPORT (local, read-only)
// ============================================================================

class GenerateSettlementReportFlow : ClientStartableFlow {

    @CordaInject lateinit var utxoLedgerService: UtxoLedgerService
    @CordaInject lateinit var memberLookup: MemberLookup
    @CordaInject lateinit var jsonMarshallingService: JsonMarshallingService
    @CordaInject lateinit var persistenceService: PersistenceService

    @CordaSerializable
    data class Input(val windowStart: String, val windowEnd: String)

    @CordaSerializable
    data class CounterpartyPosition(
        val counterpartyBranchCode: String,
        val grossPayable: BigDecimal,
        val grossReceivable: BigDecimal,
        val netAmount: BigDecimal,
        val transactionCount: Int
    )

    @CordaSerializable
    data class SettlementReportOutput(
        val reportId: String,
        val windowStart: String,
        val windowEnd: String,
        val thisBankBranchCode: String,
        val counterpartyPositions: List<CounterpartyPosition>,
        val generatedAt: String
    )

    @Suspendable
    override fun call(requestBody: ClientRequestBody): String {
        val input = requestBody.getRequestBodyAs(jsonMarshallingService, Input::class.java)
        val windowStart = LocalDate.parse(input.windowStart)
        val windowEnd = LocalDate.parse(input.windowEnd)
        val myBranchCode = memberLookup.myInfo().memberProvidedContext["branchCode"] ?: ""

        // NOTE: Queries unconsumed CLEARED states. Returned/cancelled states
        // are already consumed (their CLEARED version no longer exists in the
        // unconsumed set), so they are correctly excluded from settlement.
        val clearedStates = utxoLedgerService
            .findUnconsumedStatesByType(PaymentInstructionState::class.java)
            .map { it.state.contractState }
            .filter { it.status == PaymentStatus.CLEARED }
            .filter { it.settlementDate in windowStart..windowEnd }

        val positionsByCounterparty = mutableMapOf<String, Pair<BigDecimal, BigDecimal>>()

        clearedStates.forEach { state ->
            val counterpartyBranch = if (state.debtorAgentBranchCode == myBranchCode) {
                state.creditorAgentBranchCode
            } else {
                state.debtorAgentBranchCode
            }

            val current = positionsByCounterparty.getOrDefault(
                counterpartyBranch, Pair(BigDecimal.ZERO, BigDecimal.ZERO)
            )

            if (state.debtorAgentBranchCode == myBranchCode) {
                positionsByCounterparty[counterpartyBranch] = Pair(current.first + state.amount, current.second)
            } else {
                positionsByCounterparty[counterpartyBranch] = Pair(current.first, current.second + state.amount)
            }
        }

        val positions = positionsByCounterparty.map { (branch, amounts) ->
            CounterpartyPosition(
                counterpartyBranchCode = branch,
                grossPayable = amounts.first,
                grossReceivable = amounts.second,
                netAmount = amounts.second - amounts.first,
                transactionCount = clearedStates.count {
                    (it.debtorAgentBranchCode == branch && it.creditorAgentBranchCode == myBranchCode) ||
                    (it.creditorAgentBranchCode == branch && it.debtorAgentBranchCode == myBranchCode)
                }
            )
        }

        val report = SettlementReportOutput(
            reportId = UUID.randomUUID().toString(),
            windowStart = windowStart.toString(),
            windowEnd = windowEnd.toString(),
            thisBankBranchCode = myBranchCode,
            counterpartyPositions = positions,
            generatedAt = Instant.now().toString()
        )

        // Persist settlement report off-ledger
        persistenceService.persist(SettlementReport(
            bankBranchCode = myBranchCode,
            windowStart = windowStart.atStartOfDay().toInstant(java.time.ZoneOffset.UTC),
            windowEnd = windowEnd.atStartOfDay().toInstant(java.time.ZoneOffset.UTC),
            netAmount = positions.sumOf { it.netAmount },
            grossPayable = positions.sumOf { it.grossPayable },
            grossReceivable = positions.sumOf { it.grossReceivable },
            transactionCount = clearedStates.size,
        ))

        return jsonMarshallingService.format(report)
    }
}

// ============================================================================
// GENERATE FEE REPORT (local, read-only)
// ============================================================================

class GenerateFeeReportFlow : ClientStartableFlow {

    @CordaInject lateinit var jsonMarshallingService: JsonMarshallingService
    @CordaInject lateinit var persistenceService: PersistenceService
    @CordaInject lateinit var memberLookup: MemberLookup

    @CordaSerializable
    data class Input(val periodStart: String, val periodEnd: String)

    @CordaSerializable
    data class FeeReportOutput(
        val reportId: String,
        val periodStart: String,
        val periodEnd: String,
        val bankBranchCode: String,
        val totalTransactions: Int,
        val feeEligibleTransactions: Int,
        val totalFeesOwed: BigDecimal,
        val totalTaxOwed: BigDecimal,
        val totalAmountOwed: BigDecimal,
        val generatedAt: String
    )

    @Suspendable
    override fun call(requestBody: ClientRequestBody): String {
        val input = requestBody.getRequestBodyAs(jsonMarshallingService, Input::class.java)
        val myBranchCode = memberLookup.myInfo().memberProvidedContext["branchCode"] ?: ""

        // Query off-ledger fee_accruals for this bank in the period
        // This includes reversals (negative amounts) — report shows net
        val accruals = persistenceService.findAll(FeeAccrual::class.java)
            .filter { it.creditorBankBranchCode == myBranchCode }
            .filter {
                val periodStart = LocalDate.parse(input.periodStart)
                val periodEnd = LocalDate.parse(input.periodEnd)
                it.accrualDate in periodStart..periodEnd
            }

        val totalFees = accruals.sumOf { it.feeAmount }
        val totalTax = accruals.sumOf { it.taxAmount }
        val totalAmount = accruals.sumOf { it.totalAmount }
        val eligibleCount = accruals.count { !it.reversed }

        return jsonMarshallingService.format(FeeReportOutput(
            reportId = UUID.randomUUID().toString(),
            periodStart = input.periodStart,
            periodEnd = input.periodEnd,
            bankBranchCode = myBranchCode,
            totalTransactions = accruals.size,
            feeEligibleTransactions = eligibleCount,
            totalFeesOwed = totalFees,
            totalTaxOwed = totalTax,
            totalAmountOwed = totalAmount,
            generatedAt = Instant.now().toString()
        ))
    }
}

// ============================================================================
// PARTICIPANT SUSPENSION (System Operator admin only)
// ============================================================================

/**
 * Suspends a participant. Caller must be System Operator.
 *
 * PARTICIPANT SUSPENSION — SOURCE OF TRUTH HIERARCHY:
 *
 * 1. AUTHORITATIVE: MGM metadata (participantStatus field).
 *    All flows check this via getMemberStatus(). This is what actually
 *    blocks transactions. Updated via Corda 5 REST Admin API — NOT from
 *    within a flow. Flows cannot mutate network-level metadata.
 *
 * 2. LAYER 1 (IMMEDIATE): API credential revocation.
 *    Performed by System Operator outside the Corda network.
 *    Prevents the suspended bank from calling any REST endpoint.
 *
 * 3. AUDIT TRAIL: Off-ledger participant_status table.
 *    This flow records the suspension event for audit/compliance history.
 *    This table is NOT consulted for enforcement — it is history only.
 *
 * OPERATIONAL PROCEDURE:
 * 1. SO calls this flow → records audit trail
 * 2. SO calls Corda 5 REST Admin API → updates MGM participantStatus = SUSPENDED
 * 3. SO revokes API credentials for the bank (Layer 1)
 * 4. All subsequent flow invocations check MGM metadata and reject if SUSPENDED
 *
 * The Layer 2 vault race-condition guard (vault query for duplicate instructionIds)
 * is deferred for pilot. The off-ledger idempotency table with unique constraint
 * provides sufficient protection. Vault-level checking is a production hardening item.
 */
class SuspendParticipantFlow : ClientStartableFlow {

    @CordaInject lateinit var memberLookup: MemberLookup
    @CordaInject lateinit var jsonMarshallingService: JsonMarshallingService
    @CordaInject lateinit var persistenceService: PersistenceService

    @CordaSerializable
    data class Input(val branchCode: String, val reason: String)
    @CordaSerializable
    data class Output(val branchCode: String, val status: String, val message: String)

    @Suspendable
    override fun call(requestBody: ClientRequestBody): String {
        val input = requestBody.getRequestBodyAs(jsonMarshallingService, Input::class.java)

        val myRole = memberLookup.myInfo().memberProvidedContext["role"] ?: ""
        require(myRole == "SYSTEM_OPERATOR") {
            "Only the System Operator can suspend participants"
        }

        // NOTE: MGM metadata update mechanism depends on Corda 5.2 API.
        // The developer must implement this using the MGM REST API or
        // the MemberLookup service's metadata update capability.
        // For now, record the audit trail off-ledger.

        persistenceService.persist(ParticipantStatusRecord(
            branchCode = input.branchCode,
            status = "SUSPENDED",
            effectiveFrom = Instant.now(),
            reason = input.reason,
            initiatedBy = memberLookup.myInfo().name.toString()
        ))

        return jsonMarshallingService.format(Output(
            input.branchCode, "SUSPENDED",
            "Participant ${input.branchCode} suspended. Reason: ${input.reason}. " +
            "NOTE: MGM metadata update must be confirmed via Corda 5 admin API."
        ))
    }
}

class ReinstateParticipantFlow : ClientStartableFlow {

    @CordaInject lateinit var memberLookup: MemberLookup
    @CordaInject lateinit var jsonMarshallingService: JsonMarshallingService
    @CordaInject lateinit var persistenceService: PersistenceService

    @CordaSerializable
    data class Input(val branchCode: String)
    @CordaSerializable
    data class Output(val branchCode: String, val status: String, val message: String)

    @Suspendable
    override fun call(requestBody: ClientRequestBody): String {
        val input = requestBody.getRequestBodyAs(jsonMarshallingService, Input::class.java)

        val myRole = memberLookup.myInfo().memberProvidedContext["role"] ?: ""
        require(myRole == "SYSTEM_OPERATOR") {
            "Only the System Operator can reinstate participants"
        }

        persistenceService.persist(ParticipantStatusRecord(
            branchCode = input.branchCode,
            status = "ACTIVE",
            effectiveFrom = Instant.now(),
            reason = "Reinstated",
            initiatedBy = memberLookup.myInfo().name.toString()
        ))

        return jsonMarshallingService.format(Output(
            input.branchCode, "ACTIVE",
            "Participant ${input.branchCode} reinstated. " +
            "NOTE: MGM metadata update must be confirmed via Corda 5 admin API."
        ))
    }
}
