package za.co.payfi.clearing.flows

import net.corda.v5.application.flows.*
import net.corda.v5.application.marshalling.JsonMarshallingService
import net.corda.v5.application.membership.MemberLookup
import net.corda.v5.application.messaging.FlowMessaging
import net.corda.v5.application.messaging.FlowSession
import net.corda.v5.application.persistence.PersistenceService
import net.corda.v5.base.annotations.CordaSerializable
import net.corda.v5.base.annotations.Suspendable
import net.corda.v5.base.exceptions.CordaRuntimeException
import net.corda.v5.base.types.MemberX500Name
import net.corda.v5.ledger.common.NotaryLookup
import net.corda.v5.ledger.utxo.UtxoLedgerService
import org.slf4j.LoggerFactory
import za.co.payfi.clearing.contracts.PaymentInstructionContract
import za.co.payfi.clearing.persistence.*
import za.co.payfi.clearing.states.*
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// ============================================================================
// SHARED HELPERS — branch code / role derived from X500 organisation (demo-only)
// ============================================================================

internal fun getBranchCode(memberName: MemberX500Name): String = when (memberName.organization) {
    "BankAlpha" -> "100001"
    "BankBeta" -> "200002"
    else -> throw CordaRuntimeException("Unknown bank: ${memberName.organization}")
}

internal fun getRole(memberName: MemberX500Name): String = when (memberName.organization) {
    "BankAlpha", "BankBeta" -> "PARTICIPANT"
    "SARB" -> "REGULATOR_OBSERVER"
    else -> "UNKNOWN"
}

internal fun findMemberByBranchCode(
    branchCode: String,
    memberLookup: MemberLookup
): MemberX500Name? {
    val targetOrg = when (branchCode) {
        "100001" -> "BankAlpha"
        "200002" -> "BankBeta"
        else -> return null
    }
    return memberLookup.lookup().firstOrNull { it.name.organization == targetOrg }?.name
}

internal fun findSarbObserver(memberLookup: MemberLookup): MemberX500Name? {
    return memberLookup.lookup().firstOrNull { it.name.organization == "SARB" }?.name
}

/**
 * DTO sent to SARB observer after finality for lifecycle transitions.
 */
@CordaSerializable
data class LifecycleNotificationDto(
    val stateId: String,
    val instructionId: String,
    val newStatus: String,
    val statusReason: String?,
    val updatedAt: String
)

// ============================================================================
// PAYMENT INSTRUCTION DTO — strips PublicKey fields for JSON serialization
// ============================================================================

/**
 * DTO that mirrors PaymentInstructionState but replaces participantKeys
 * (List<PublicKey>) with nothing — Jackson cannot serialize BouncyCastle
 * public key objects. All query flows return this DTO, not raw states.
 */
@CordaSerializable
data class PaymentInstructionDto(
    val stateId: String,
    val instructionId: String,
    val endToEndId: String,
    val transactionId: String,
    val amount: String,
    val currency: String,
    val settlementDate: String,
    val debtorName: String,
    val debtorIdType: String,
    val debtorIdNumber: String,
    val debtorAccount: String,
    val debtorAgentBranchCode: String,
    val creditorName: String,
    val creditorAccount: String,
    val creditorAgentBranchCode: String,
    val remittanceInfo: String? = null,
    val purposeCode: String? = null,
    val status: String,
    val statusReason: String? = null,
    val feeApplicable: Boolean,
    val feeAmount: String,
    val feeTaxAmount: String,
    val feePayerBranchCode: String,
    val createdAt: String? = null
)

/** Convert a PaymentInstructionState to a DTO safe for JSON serialization */
internal fun PaymentInstructionState.toDto() = PaymentInstructionDto(
    stateId = stateId.toString(),
    instructionId = instructionId,
    endToEndId = endToEndId,
    transactionId = transactionId,
    amount = amount.toPlainString(),
    currency = currency,
    settlementDate = settlementDate.toString(),
    debtorName = debtorName,
    debtorIdType = debtorIdType.name,
    debtorIdNumber = debtorIdNumber,
    debtorAccount = debtorAccount,
    debtorAgentBranchCode = debtorAgentBranchCode,
    creditorName = creditorName,
    creditorAccount = creditorAccount,
    creditorAgentBranchCode = creditorAgentBranchCode,
    remittanceInfo = remittanceInfo,
    purposeCode = purposeCode,
    status = status.name,
    statusReason = statusReason,
    feeApplicable = feeApplicable,
    feeAmount = feeAmount.toPlainString(),
    feeTaxAmount = feeTaxAmount.toPlainString(),
    feePayerBranchCode = feePayerBranchCode,
    createdAt = createdAt.toString()
)

// ============================================================================
// UPDATE PAYMENT STATUS FLOW
// ============================================================================

@InitiatingFlow(protocol = "update-payment-status")
class UpdatePaymentStatusFlow : ClientStartableFlow {

    private companion object {
        val log = LoggerFactory.getLogger(UpdatePaymentStatusFlow::class.java)
    }

    @CordaInject lateinit var ledgerService: UtxoLedgerService
    @CordaInject lateinit var memberLookup: MemberLookup
    @CordaInject lateinit var notaryLookup: NotaryLookup
    @CordaInject lateinit var jsonMarshallingService: JsonMarshallingService
    @CordaInject lateinit var flowEngine: FlowEngine
    @CordaInject lateinit var flowMessaging: FlowMessaging

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

        val myBranchCode = getBranchCode(memberLookup.myInfo().name)
        require(
            myBranchCode == currentState.debtorAgentBranchCode ||
            myBranchCode == currentState.creditorAgentBranchCode
        ) { "Caller ($myBranchCode) is not a participant on this transaction" }

        val updatedState = currentState.withStatus(newStatus, input.statusReason)
        val notary = notaryLookup.notaryServices.single()

        val txBuilder = ledgerService.createTransactionBuilder()
            .setNotary(notary.name)
            .setTimeWindowBetween(
                Instant.now(),
                Instant.now().plusMillis(Duration.ofSeconds(60).toMillis())
            )
            .addInputState(stateAndRef.ref)
            .addOutputState(updatedState)
            .addCommand(PaymentInstructionContract.PaymentCommand.UpdateStatus())
            .addSignatories(updatedState.participants)

        val signedTx = txBuilder.toSignedTransaction()
        val finalitySessions = buildFinalitySessions(currentState, myBranchCode)
        ledgerService.finalize(signedTx, finalitySessions)

        notifySarb(currentState, newStatus, input.statusReason)

        return jsonMarshallingService.format(Output(
            input.stateId, newStatus.name, true, "Status updated to $newStatus"
        ))
    }

    @Suspendable
    private fun findStateById(stateId: UUID) =
        ledgerService.findUnconsumedStatesByExactType(
            PaymentInstructionState::class.java, 100, Instant.now()
        ).results.firstOrNull { it.state.contractState.stateId == stateId }
            ?: throw CordaRuntimeException("State not found: $stateId")

    @Suspendable
    private fun buildFinalitySessions(
        state: PaymentInstructionState,
        myBranchCode: String
    ): List<FlowSession> {
        val sessions = mutableListOf<FlowSession>()
        if (state.debtorAgentBranchCode != state.creditorAgentBranchCode) {
            val counterpartyBranch = if (myBranchCode == state.debtorAgentBranchCode) {
                state.creditorAgentBranchCode
            } else {
                state.debtorAgentBranchCode
            }
            val counterparty = findMemberByBranchCode(counterpartyBranch, memberLookup)
            if (counterparty != null) {
                sessions.add(flowMessaging.initiateFlow(counterparty))
            }
        }
        return sessions
    }

    @Suspendable
    private fun notifySarb(state: PaymentInstructionState, newStatus: PaymentStatus, reason: String?) {
        val sarbX500 = findSarbObserver(memberLookup) ?: return
        val sarbSession = flowMessaging.initiateFlow(sarbX500)
        sarbSession.send(LifecycleNotificationDto(
            stateId = state.stateId.toString(),
            instructionId = state.instructionId,
            newStatus = newStatus.name,
            statusReason = reason,
            updatedAt = Instant.now().toString()
        ))
        sarbSession.close()
    }
}

@InitiatedBy(protocol = "update-payment-status")
class UpdatePaymentStatusResponderFlow : ResponderFlow {
    @CordaInject lateinit var ledgerService: UtxoLedgerService
    @CordaInject lateinit var memberLookup: MemberLookup

    @Suspendable
    override fun call(session: FlowSession) {
        val myOrg = memberLookup.myInfo().name.organization
        if (myOrg == "SARB") {
            val dto = session.receive(LifecycleNotificationDto::class.java)
            return
        }
        ledgerService.receiveFinality(session) { _ -> }
    }
}

// ============================================================================
// REQUEST CANCELLATION FLOW (camt.056)
// ============================================================================

@InitiatingFlow(protocol = "request-cancellation")
class RequestCancellationFlow : ClientStartableFlow {

    @CordaInject lateinit var ledgerService: UtxoLedgerService
    @CordaInject lateinit var memberLookup: MemberLookup
    @CordaInject lateinit var notaryLookup: NotaryLookup
    @CordaInject lateinit var jsonMarshallingService: JsonMarshallingService
    @CordaInject lateinit var flowEngine: FlowEngine
    @CordaInject lateinit var flowMessaging: FlowMessaging

    @CordaSerializable
    data class Input(val stateId: String, val cancellationReason: String)
    @CordaSerializable
    data class Output(val stateId: String, val status: String, val message: String)

    @Suspendable
    override fun call(requestBody: ClientRequestBody): String {
        val input = requestBody.getRequestBodyAs(jsonMarshallingService, Input::class.java)
        val stateId = UUID.fromString(input.stateId)

        val stateAndRef = ledgerService.findUnconsumedStatesByExactType(
            PaymentInstructionState::class.java, 100, Instant.now()
        ).results.firstOrNull { it.state.contractState.stateId == stateId }
            ?: throw CordaRuntimeException("State not found: $stateId")

        val currentState = stateAndRef.state.contractState
        val myBranchCode = getBranchCode(memberLookup.myInfo().name)
        require(myBranchCode == currentState.debtorAgentBranchCode) {
            "Only the debtor bank may request cancellation"
        }

        val updatedState = currentState.withStatus(
            PaymentStatus.CANCELLATION_REQUESTED, input.cancellationReason
        )
        val notary = notaryLookup.notaryServices.single()

        val txBuilder = ledgerService.createTransactionBuilder()
            .setNotary(notary.name)
            .setTimeWindowBetween(Instant.now(), Instant.now().plusMillis(Duration.ofSeconds(60).toMillis()))
            .addInputState(stateAndRef.ref)
            .addOutputState(updatedState)
            .addCommand(PaymentInstructionContract.PaymentCommand.RequestCancellation())
            .addSignatories(updatedState.participants)

        val signedTx = txBuilder.toSignedTransaction()

        val finalitySessions = mutableListOf<FlowSession>()
        if (currentState.debtorAgentBranchCode != currentState.creditorAgentBranchCode) {
            val counterparty = findMemberByBranchCode(currentState.creditorAgentBranchCode, memberLookup)
            if (counterparty != null) {
                finalitySessions.add(flowMessaging.initiateFlow(counterparty))
            }
        }
        ledgerService.finalize(signedTx, finalitySessions)

        val sarbX500 = findSarbObserver(memberLookup)
        if (sarbX500 != null) {
            val sarbSession = flowMessaging.initiateFlow(sarbX500)
            sarbSession.send(LifecycleNotificationDto(
                stateId = currentState.stateId.toString(),
                instructionId = currentState.instructionId,
                newStatus = PaymentStatus.CANCELLATION_REQUESTED.name,
                statusReason = input.cancellationReason,
                updatedAt = Instant.now().toString()
            ))
            sarbSession.close()
        }

        return jsonMarshallingService.format(Output(
            input.stateId, "CANCELLATION_REQUESTED",
            "Cancellation requested. Awaiting counterparty resolution."
        ))
    }
}

@InitiatedBy(protocol = "request-cancellation")
class RequestCancellationResponderFlow : ResponderFlow {
    @CordaInject lateinit var ledgerService: UtxoLedgerService
    @CordaInject lateinit var memberLookup: MemberLookup

    @Suspendable
    override fun call(session: FlowSession) {
        val myOrg = memberLookup.myInfo().name.organization
        if (myOrg == "SARB") {
            session.receive(LifecycleNotificationDto::class.java)
            return
        }
        ledgerService.receiveFinality(session) { _ -> }
    }
}

// ============================================================================
// RESOLVE CANCELLATION FLOW (camt.029)
// ============================================================================

@InitiatingFlow(protocol = "resolve-cancellation")
class ResolveCancellationFlow : ClientStartableFlow {

    @CordaInject lateinit var ledgerService: UtxoLedgerService
    @CordaInject lateinit var memberLookup: MemberLookup
    @CordaInject lateinit var notaryLookup: NotaryLookup
    @CordaInject lateinit var jsonMarshallingService: JsonMarshallingService
    @CordaInject lateinit var persistenceService: PersistenceService
    @CordaInject lateinit var flowEngine: FlowEngine
    @CordaInject lateinit var flowMessaging: FlowMessaging

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

        val stateAndRef = ledgerService.findUnconsumedStatesByExactType(
            PaymentInstructionState::class.java, 100, Instant.now()
        ).results.firstOrNull { it.state.contractState.stateId == stateId }
            ?: throw CordaRuntimeException("State not found: $stateId")

        val currentState = stateAndRef.state.contractState
        val myBranchCode = getBranchCode(memberLookup.myInfo().name)
        require(myBranchCode == currentState.creditorAgentBranchCode) {
            "Only the creditor bank may resolve cancellation"
        }

        val updatedState = currentState.withStatus(resolution, input.reason)
        val notary = notaryLookup.notaryServices.single()

        val txBuilder = ledgerService.createTransactionBuilder()
            .setNotary(notary.name)
            .setTimeWindowBetween(Instant.now(), Instant.now().plusMillis(Duration.ofSeconds(60).toMillis()))
            .addInputState(stateAndRef.ref)
            .addOutputState(updatedState)
            .addCommand(PaymentInstructionContract.PaymentCommand.ResolveCancellation())
            .addSignatories(updatedState.participants)

        val signedTx = txBuilder.toSignedTransaction()

        val finalitySessions = mutableListOf<FlowSession>()
        if (currentState.debtorAgentBranchCode != currentState.creditorAgentBranchCode) {
            val counterparty = findMemberByBranchCode(currentState.debtorAgentBranchCode, memberLookup)
            if (counterparty != null) {
                finalitySessions.add(flowMessaging.initiateFlow(counterparty))
            }
        }
        ledgerService.finalize(signedTx, finalitySessions)

        val sarbX500 = findSarbObserver(memberLookup)
        if (sarbX500 != null) {
            val sarbSession = flowMessaging.initiateFlow(sarbX500)
            sarbSession.send(LifecycleNotificationDto(
                stateId = currentState.stateId.toString(),
                instructionId = currentState.instructionId,
                newStatus = resolution.name,
                statusReason = input.reason,
                updatedAt = Instant.now().toString()
            ))
            sarbSession.close()
        }

        var feeReversed = false
        if (resolution == PaymentStatus.CANCELLATION_ACCEPTED && currentState.feeApplicable) {
            persistenceService.persist(
                "persist-fee-reversal-cancel-${currentState.stateId}",
                FeeAccrual(
                    stateId = currentState.stateId,
                    creditorBankBranchCode = currentState.feePayerBranchCode,
                    feeAmount = currentState.feeAmount.negate(),
                    taxAmount = currentState.feeTaxAmount.negate(),
                    totalAmount = currentState.feeAmount.add(currentState.feeTaxAmount).negate(),
                    transactionAmount = currentState.amount,
                    accrualDate = LocalDate.now(),
                    reversed = true,
                    reversalNote = "CANCELLATION_ACCEPTED — fee reversed"
                )
            )
            feeReversed = true
        }

        return jsonMarshallingService.format(Output(
            input.stateId, resolution.name, feeReversed,
            "Cancellation ${if (resolution == PaymentStatus.CANCELLATION_ACCEPTED) "accepted" else "rejected"}"
        ))
    }
}

@InitiatedBy(protocol = "resolve-cancellation")
class ResolveCancellationResponderFlow : ResponderFlow {
    @CordaInject lateinit var ledgerService: UtxoLedgerService
    @CordaInject lateinit var memberLookup: MemberLookup

    @Suspendable
    override fun call(session: FlowSession) {
        val myOrg = memberLookup.myInfo().name.organization
        if (myOrg == "SARB") {
            session.receive(LifecycleNotificationDto::class.java)
            return
        }
        ledgerService.receiveFinality(session) { _ -> }
    }
}

// ============================================================================
// RETURN PAYMENT FLOW (pacs.004)
// ============================================================================

@InitiatingFlow(protocol = "return-payment")
class ReturnPaymentFlow : ClientStartableFlow {

    @CordaInject lateinit var ledgerService: UtxoLedgerService
    @CordaInject lateinit var memberLookup: MemberLookup
    @CordaInject lateinit var notaryLookup: NotaryLookup
    @CordaInject lateinit var jsonMarshallingService: JsonMarshallingService
    @CordaInject lateinit var persistenceService: PersistenceService
    @CordaInject lateinit var flowEngine: FlowEngine
    @CordaInject lateinit var flowMessaging: FlowMessaging

    @CordaSerializable
    data class Input(val stateId: String, val returnReasonCode: String)
    @CordaSerializable
    data class Output(val stateId: String, val status: String, val feeReversed: Boolean, val message: String)

    @Suspendable
    override fun call(requestBody: ClientRequestBody): String {
        val input = requestBody.getRequestBodyAs(jsonMarshallingService, Input::class.java)
        val stateId = UUID.fromString(input.stateId)

        val stateAndRef = ledgerService.findUnconsumedStatesByExactType(
            PaymentInstructionState::class.java, 100, Instant.now()
        ).results.firstOrNull { it.state.contractState.stateId == stateId }
            ?: throw CordaRuntimeException("State not found: $stateId")

        val currentState = stateAndRef.state.contractState
        val myBranchCode = getBranchCode(memberLookup.myInfo().name)
        require(myBranchCode == currentState.creditorAgentBranchCode) {
            "Only the creditor bank may return a payment"
        }

        val updatedState = currentState.withStatus(PaymentStatus.RETURNED, input.returnReasonCode)
        val notary = notaryLookup.notaryServices.single()

        val txBuilder = ledgerService.createTransactionBuilder()
            .setNotary(notary.name)
            .setTimeWindowBetween(Instant.now(), Instant.now().plusMillis(Duration.ofSeconds(60).toMillis()))
            .addInputState(stateAndRef.ref)
            .addOutputState(updatedState)
            .addCommand(PaymentInstructionContract.PaymentCommand.Return())
            .addSignatories(updatedState.participants)

        val signedTx = txBuilder.toSignedTransaction()

        val finalitySessions = mutableListOf<FlowSession>()
        if (currentState.debtorAgentBranchCode != currentState.creditorAgentBranchCode) {
            val counterparty = findMemberByBranchCode(currentState.debtorAgentBranchCode, memberLookup)
            if (counterparty != null) {
                finalitySessions.add(flowMessaging.initiateFlow(counterparty))
            }
        }
        ledgerService.finalize(signedTx, finalitySessions)

        val sarbX500 = findSarbObserver(memberLookup)
        if (sarbX500 != null) {
            val sarbSession = flowMessaging.initiateFlow(sarbX500)
            sarbSession.send(LifecycleNotificationDto(
                stateId = currentState.stateId.toString(),
                instructionId = currentState.instructionId,
                newStatus = PaymentStatus.RETURNED.name,
                statusReason = input.returnReasonCode,
                updatedAt = Instant.now().toString()
            ))
            sarbSession.close()
        }

        var feeReversed = false
        if (currentState.feeApplicable) {
            persistenceService.persist(
                "persist-fee-reversal-return-${currentState.stateId}",
                FeeAccrual(
                    stateId = currentState.stateId,
                    creditorBankBranchCode = currentState.feePayerBranchCode,
                    feeAmount = currentState.feeAmount.negate(),
                    taxAmount = currentState.feeTaxAmount.negate(),
                    totalAmount = currentState.feeAmount.add(currentState.feeTaxAmount).negate(),
                    transactionAmount = currentState.amount,
                    accrualDate = LocalDate.now(),
                    reversed = true,
                    reversalNote = "RETURNED (${input.returnReasonCode}) — fee reversed"
                )
            )
            feeReversed = true
        }

        return jsonMarshallingService.format(Output(
            input.stateId, "RETURNED", feeReversed,
            "Payment returned with reason ${input.returnReasonCode}"
        ))
    }
}

@InitiatedBy(protocol = "return-payment")
class ReturnPaymentResponderFlow : ResponderFlow {
    @CordaInject lateinit var ledgerService: UtxoLedgerService
    @CordaInject lateinit var memberLookup: MemberLookup

    @Suspendable
    override fun call(session: FlowSession) {
        val myOrg = memberLookup.myInfo().name.organization
        if (myOrg == "SARB") {
            session.receive(LifecycleNotificationDto::class.java)
            return
        }
        ledgerService.receiveFinality(session) { _ -> }
    }
}

// ============================================================================
// QUERY PAYMENT INSTRUCTIONS (local, read-only) — uses DTO for serialization
// ============================================================================

class QueryPaymentInstructionsFlow : ClientStartableFlow {

    @CordaInject lateinit var ledgerService: UtxoLedgerService
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

        var results = ledgerService.findUnconsumedStatesByExactType(
            PaymentInstructionState::class.java, 500, Instant.now()
        ).results.map { it.state.contractState }

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

        // Convert to DTOs to avoid BouncyCastle PublicKey serialization errors
        val dtos = results.map { it.toDto() }
        return jsonMarshallingService.format(dtos)
    }
}

// ============================================================================
// QUERY SARB TRANSACTIONS (SARB vnode only, reads off-ledger table)
// ============================================================================

class QuerySarbTransactionsFlow : ClientStartableFlow {

    private companion object {
        val log = LoggerFactory.getLogger(QuerySarbTransactionsFlow::class.java)
    }

    @CordaInject lateinit var jsonMarshallingService: JsonMarshallingService
    @CordaInject lateinit var persistenceService: PersistenceService

    @Suspendable
    override fun call(requestBody: ClientRequestBody): String {
        val results = persistenceService.findAll(SarbTransactionRecord::class.java)
            .execute()
            .results
            .filter { !it.settled }

        // Map to PaymentInstructionDto for frontend compatibility
        val dtos = results.map { r ->
            PaymentInstructionDto(
                stateId = r.stateId,
                instructionId = r.instructionId,
                endToEndId = r.endToEndId,
                transactionId = r.transactionId,
                amount = r.amount,
                currency = r.currency,
                settlementDate = r.settlementDate,
                debtorName = r.debtorName,
                debtorIdType = r.debtorIdType ?: "",
                debtorIdNumber = r.debtorIdNumber ?: "",
                debtorAccount = r.debtorAccount,
                debtorAgentBranchCode = r.debtorAgentBranchCode,
                creditorName = r.creditorName,
                creditorAccount = r.creditorAccount,
                creditorAgentBranchCode = r.creditorAgentBranchCode,
                remittanceInfo = r.remittanceInfo,
                status = r.status,
                feeApplicable = r.feeApplicable,
                feeAmount = r.feeAmount,
                feeTaxAmount = r.feeTaxAmount ?: "0.00",
                feePayerBranchCode = r.debtorAgentBranchCode,
                createdAt = r.receivedAt.toString()
            )
        }
        return jsonMarshallingService.format(dtos)
    }
}

// ============================================================================
// SETTLE TRANSACTIONS FLOW — consumes UTXO states post-settlement
// ============================================================================

@InitiatingFlow(protocol = "settle-transactions")
class SettleTransactionsFlow : ClientStartableFlow {

    private companion object {
        val log = LoggerFactory.getLogger(SettleTransactionsFlow::class.java)
    }

    @CordaInject lateinit var ledgerService: UtxoLedgerService
    @CordaInject lateinit var memberLookup: MemberLookup
    @CordaInject lateinit var notaryLookup: NotaryLookup
    @CordaInject lateinit var jsonMarshallingService: JsonMarshallingService
    @CordaInject lateinit var flowMessaging: FlowMessaging

    @CordaSerializable
    data class Output(val settled: Int, val message: String)

    @Suspendable
    override fun call(requestBody: ClientRequestBody): String {
        val unconsumed = ledgerService.findUnconsumedStatesByExactType(
            PaymentInstructionState::class.java, 500, Instant.now()
        ).results

        if (unconsumed.isEmpty()) {
            return jsonMarshallingService.format(Output(0, "No transactions to settle"))
        }

        val notary = notaryLookup.notaryServices.single()
        var settledCount = 0

        for (stateAndRef in unconsumed) {
            val state = stateAndRef.state.contractState
            try {
                val txBuilder = ledgerService.createTransactionBuilder()
                    .setNotary(notary.name)
                    .setTimeWindowBetween(Instant.now(), Instant.now().plusMillis(Duration.ofSeconds(60).toMillis()))
                    .addInputState(stateAndRef.ref)
                    .addCommand(PaymentInstructionContract.PaymentCommand.Settle())
                    .addSignatories(state.participants)

                val signedTx = txBuilder.toSignedTransaction()

                // Finality sessions — counterparty banks only (not SARB)
                val finalitySessions = mutableListOf<FlowSession>()
                if (state.debtorAgentBranchCode != state.creditorAgentBranchCode) {
                    val myBranchCode = getBranchCode(memberLookup.myInfo().name)
                    val counterpartyBranch = if (myBranchCode == state.debtorAgentBranchCode) {
                        state.creditorAgentBranchCode
                    } else {
                        state.debtorAgentBranchCode
                    }
                    val counterparty = findMemberByBranchCode(counterpartyBranch, memberLookup)
                    if (counterparty != null) {
                        finalitySessions.add(flowMessaging.initiateFlow(counterparty))
                    }
                }

                ledgerService.finalize(signedTx, finalitySessions)
                settledCount++
            } catch (e: Exception) {
                log.warn("Failed to settle state ${state.stateId}: ${e.message}")
            }
        }

        return jsonMarshallingService.format(Output(
            settledCount, "Settlement complete — $settledCount transactions archived"
        ))
    }
}

@InitiatedBy(protocol = "settle-transactions")
class SettleTransactionsResponderFlow : ResponderFlow {
    @CordaInject lateinit var ledgerService: UtxoLedgerService
    @CordaInject lateinit var memberLookup: MemberLookup

    @Suspendable
    override fun call(session: FlowSession) {
        val myOrg = memberLookup.myInfo().name.organization
        if (myOrg == "SARB") {
            // SARB is not a participant in settlement finality — return immediately
            return
        }
        ledgerService.receiveFinality(session) { _ -> }
    }
}

// ============================================================================
// MARK SARB SETTLED — marks off-ledger SARB records as settled
// ============================================================================

class MarkSarbSettledFlow : ClientStartableFlow {

    private companion object {
        val log = LoggerFactory.getLogger(MarkSarbSettledFlow::class.java)
    }

    @CordaInject lateinit var jsonMarshallingService: JsonMarshallingService
    @CordaInject lateinit var persistenceService: PersistenceService

    @CordaSerializable
    data class Output(val settled: Int)

    @Suspendable
    override fun call(requestBody: ClientRequestBody): String {
        val unsettled = persistenceService.findAll(SarbTransactionRecord::class.java)
            .execute()
            .results
            .filter { !it.settled }

        var count = 0
        for (record in unsettled) {
            try {
                val updated = record.copy(settled = true)
                persistenceService.merge(updated)
                count++
            } catch (e: Exception) {
                log.warn("Failed to mark settled: ${record.stateId}: ${e.message}")
            }
        }

        return jsonMarshallingService.format(Output(count))
    }
}

// ============================================================================
// GENERATE SETTLEMENT REPORT (local, read-only)
// ============================================================================

class GenerateSettlementReportFlow : ClientStartableFlow {

    @CordaInject lateinit var ledgerService: UtxoLedgerService
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
        val myBranchCode = getBranchCode(memberLookup.myInfo().name)

        val clearedStates = ledgerService.findUnconsumedStatesByExactType(
            PaymentInstructionState::class.java, 500, Instant.now()
        ).results.map { it.state.contractState }
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

        val reportId = UUID.nameUUIDFromBytes(
            "${myBranchCode}-${windowStart}-${windowEnd}".toByteArray(Charsets.UTF_8)
        ).toString()

        val report = SettlementReportOutput(
            reportId = reportId,
            windowStart = windowStart.toString(),
            windowEnd = windowEnd.toString(),
            thisBankBranchCode = myBranchCode,
            counterpartyPositions = positions,
            generatedAt = Instant.now().toString()
        )

        persistenceService.persist(
            "persist-settlement-report-$reportId",
            SettlementReport(
                bankBranchCode = myBranchCode,
                windowStart = windowStart.atStartOfDay().toInstant(java.time.ZoneOffset.UTC),
                windowEnd = windowEnd.atStartOfDay().toInstant(java.time.ZoneOffset.UTC),
                netAmount = positions.sumOf { it.netAmount },
                grossPayable = positions.sumOf { it.grossPayable },
                grossReceivable = positions.sumOf { it.grossReceivable },
                transactionCount = clearedStates.size
            )
        )

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
        val myBranchCode = getBranchCode(memberLookup.myInfo().name)
        val periodStart = LocalDate.parse(input.periodStart)
        val periodEnd = LocalDate.parse(input.periodEnd)

        val accruals = persistenceService.findAll(FeeAccrual::class.java)
            .execute()
            .results
            .filter { it.creditorBankBranchCode == myBranchCode }
            .filter { it.accrualDate in periodStart..periodEnd }

        val totalFees = accruals.sumOf { it.feeAmount }
        val totalTax = accruals.sumOf { it.taxAmount }
        val totalAmount = accruals.sumOf { it.totalAmount }
        val eligibleCount = accruals.count { !it.reversed }

        return jsonMarshallingService.format(FeeReportOutput(
            reportId = UUID.nameUUIDFromBytes(
                "${myBranchCode}-${input.periodStart}-${input.periodEnd}".toByteArray(Charsets.UTF_8)
            ).toString(),
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
// PARTICIPANT SUSPENSION — callable from any bank vnode for demo
// ============================================================================

/**
 * Suspends a participant by recording a SUSPENDED status in the off-ledger
 * participant_status table. SubmitPaymentInstructionFlow checks this table
 * before processing payments.
 *
 * NOTE: In the pilot, there is no System Operator vnode. Any bank can
 * trigger suspension (for demo/testing). In production with MGM, the
 * System Operator would also update MGM metadata.
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

        persistenceService.persist(
            "persist-suspend-${input.branchCode}-${Instant.now().toEpochMilli()}",
            ParticipantStatusRecord(
                branchCode = input.branchCode,
                status = "SUSPENDED",
                effectiveFrom = Instant.now(),
                reason = input.reason,
                initiatedBy = memberLookup.myInfo().name.toString()
            )
        )

        return jsonMarshallingService.format(Output(
            input.branchCode, "SUSPENDED",
            "Participant ${input.branchCode} suspended. Reason: ${input.reason}"
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

        persistenceService.persist(
            "persist-reinstate-${input.branchCode}-${Instant.now().toEpochMilli()}",
            ParticipantStatusRecord(
                branchCode = input.branchCode,
                status = "ACTIVE",
                effectiveFrom = Instant.now(),
                reason = "Reinstated",
                initiatedBy = memberLookup.myInfo().name.toString()
            )
        )

        return jsonMarshallingService.format(Output(
            input.branchCode, "ACTIVE",
            "Participant ${input.branchCode} reinstated."
        ))
    }
}
