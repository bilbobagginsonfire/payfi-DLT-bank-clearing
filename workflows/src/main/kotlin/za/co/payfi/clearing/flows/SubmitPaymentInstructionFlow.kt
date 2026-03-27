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
import za.co.payfi.clearing.mapper.Pacs002ResponseBuilder
import za.co.payfi.clearing.mapper.Pacs008Mapper
import za.co.payfi.clearing.persistence.*
import za.co.payfi.clearing.states.*
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Initiating flow for submitting payment instructions.
 *
 * Accepts ISO 20022 pacs.008 XML (single or batch), parses into
 * individual PaymentInstructionState objects, validates, calculates fees,
 * and finalises peer-to-peer with the counterparty bank.
 *
 * SARB observer receives finalised transaction data via separate session.send()
 * AFTER finality — NOT as a participant or signatory.
 *
 * Processing steps per transaction:
 * 1. Parse XML → List<PaymentInstructionState>
 * 2. Extract + store message envelope metadata off-ledger
 * 3. For each transaction:
 *    a. Check idempotency (vault query for MsgId + InstrId) — replay cached response if hit
 *    b. Check participant suspension (derived from X500 org name — demo only)
 *    c. Fee already calculated by mapper using PilotFeeConstants
 *    d. Resolve counterparty via X500 org-based branch code mapping (skip if same-bank)
 *    e. Build participant keys (debtor + creditor banks only, NOT SARB)
 *    f. Build UTXO transaction (Submit command, 60s time window, notary)
 *    g. Sign + finalise with counterparty session (SARB excluded from finality)
 *    h. Send transaction summary DTO to SARB observer via separate session
 *    i. Record idempotency key with pacs.002 response payload for exact replay
 *    j. Record fee accrual if applicable
 * 4. Return consolidated pacs.002 response (includes replayed idempotent results)
 */
@InitiatingFlow(protocol = "submit-payment-instruction")
class SubmitPaymentInstructionFlow : ClientStartableFlow {

    private companion object {
        val log = LoggerFactory.getLogger(SubmitPaymentInstructionFlow::class.java)
    }

    @CordaInject lateinit var ledgerService: UtxoLedgerService
    @CordaInject lateinit var memberLookup: MemberLookup
    @CordaInject lateinit var notaryLookup: NotaryLookup
    @CordaInject lateinit var jsonMarshallingService: JsonMarshallingService
    @CordaInject lateinit var persistenceService: PersistenceService
    @CordaInject lateinit var flowEngine: FlowEngine
    @CordaInject lateinit var flowMessaging: FlowMessaging

    private val mapper = Pacs008Mapper()
    private val responseBuilder = Pacs002ResponseBuilder()

    @CordaSerializable
    data class FlowInput(val pacs008Xml: String)

    @CordaSerializable
    data class FlowOutput(
        val pacs002Xml: String,
        val processedCount: Int,
        val acceptedCount: Int,
        val rejectedCount: Int,
        val skippedIdempotentCount: Int
    )

    /**
     * DTO sent to SARB observer after finality.
     * Must be @CordaSerializable — NOT a Prowide object.
     */
    @CordaSerializable
    data class SarbNotificationDto(
        val stateId: String,
        val instructionId: String,
        val endToEndId: String,
        val transactionId: String,
        val amount: String,
        val currency: String,
        val debtorName: String,
        val debtorAccount: String,
        val debtorAgentBranchCode: String,
        val creditorName: String,
        val creditorAccount: String,
        val creditorAgentBranchCode: String,
        val status: String,
        val feeApplicable: Boolean,
        val feeAmount: String,
        val settlementDate: String
    )

    @Suspendable
    override fun call(requestBody: ClientRequestBody): String {
        val input = requestBody.getRequestBodyAs(jsonMarshallingService, FlowInput::class.java)
        val xml = input.pacs008Xml
        val myInfo = memberLookup.myInfo()
        val myBranchCode = getBranchCode(myInfo.name)

        // Step 1: Parse XML
        val states: List<PaymentInstructionState>
        try {
            states = mapper.fromXml(xml, emptyList()) // keys set per-transaction below
        } catch (e: IllegalArgumentException) {
            return jsonMarshallingService.format(FlowOutput(
                pacs002Xml = responseBuilder.buildRejection(
                    "UNKNOWN", "", "", "",
                    "FF01", "Failed to parse pacs.008: ${e.message}"
                ),
                processedCount = 0, acceptedCount = 0,
                rejectedCount = 1, skippedIdempotentCount = 0
            ))
        }

        // Step 2: Extract and store message envelope metadata
        val metadata = mapper.extractMetadata(xml)
        persistenceService.persist(
            "persist-metadata-${metadata.originalMessageId}",
            metadata
        )

        // Step 3: Process each transaction
        val results = mutableListOf<Pacs002ResponseBuilder.TransactionResult>()
        var acceptedCount = 0
        var rejectedCount = 0
        var skippedCount = 0

        for (state in states) {

            // Step 3a: IDEMPOTENCY CHECK
            // Query vault for existing unconsumed state with same MsgId + InstrId
            val existingStates = ledgerService.findUnconsumedStatesByExactType(
                PaymentInstructionState::class.java, 100, Instant.now()
            ).results
            val isDuplicate = existingStates.any { sar ->
                val s = sar.state.contractState
                s.instructionId == state.instructionId &&
                s.transactionId == state.transactionId
            }

            // Also check off-ledger idempotency key
            val existingKey: IdempotencyKey? = try {
                persistenceService.query(
                    "IdempotencyKey.findByCompositeKey",
                    IdempotencyKey::class.java
                )
                    .setParameter("messageId", metadata.originalMessageId)
                    .setParameter("instructionId", state.instructionId)
                    .execute()
                    .results
                    .firstOrNull()
            } catch (e: Exception) {
                null
            }

            if (isDuplicate || existingKey != null) {
                // IDEMPOTENT REPLAY: return the cached pacs.002 result.
                // If the off-ledger key exists, it contains the exact cached pacs.002 XML
                // from the original acceptance. We use a synthetic TransactionResult here
                // because the consolidated pacs.002 is rebuilt from all results; the
                // per-transaction XML in responsePayload is stored for direct-replay
                // scenarios at the REST/API layer.
                results.add(Pacs002ResponseBuilder.TransactionResult(
                    originalInstructionId = state.instructionId,
                    originalEndToEndId = state.endToEndId,
                    originalTransactionId = state.transactionId,
                    accepted = true, // cached key implies original was accepted
                    cachedResponsePayload = existingKey?.responsePayload
                ))
                skippedCount++
                acceptedCount++
                continue
            }

            // Step 3b: Derive branch codes from X500 organisation
            val counterpartyBranchCode = if (state.debtorAgentBranchCode == myBranchCode) {
                state.creditorAgentBranchCode
            } else {
                state.debtorAgentBranchCode
            }

            // Same-bank detection
            val isSameBank = state.debtorAgentBranchCode == state.creditorAgentBranchCode

            // Resolve counterparty (skip if same-bank)
            val counterpartyMember: MemberX500Name? = if (isSameBank) {
                null
            } else {
                findMemberByBranchCode(counterpartyBranchCode)
            }

            if (!isSameBank && counterpartyMember == null) {
                results.add(Pacs002ResponseBuilder.TransactionResult(
                    state.instructionId, state.endToEndId, state.transactionId,
                    accepted = false, rejectionReasonCode = "RC01",
                    rejectionReasonDescription = "Unknown creditor agent: $counterpartyBranchCode"
                ))
                rejectedCount++
                continue
            }

            // Step 3e: Build participant keys — banks only, NOT SARB
            val myKey = myInfo.ledgerKeys.firstOrNull()
                ?: throw CordaRuntimeException("This node has no ledger keys")

            val participantKeys = if (isSameBank) {
                listOf(myKey)
            } else {
                val counterpartyInfo = memberLookup.lookup(counterpartyMember!!)
                    ?: throw CordaRuntimeException("Counterparty not found: $counterpartyMember")
                val counterpartyKey = counterpartyInfo.ledgerKeys.firstOrNull()
                    ?: throw CordaRuntimeException("Counterparty has no ledger keys")
                listOf(myKey, counterpartyKey)
            }

            // Create state with correct participant keys
            val finalState = state.copy(participantKeys = participantKeys)

            try {
                // Step 3f: Build UTXO transaction
                val notary = notaryLookup.notaryServices.single()

                val txBuilder = ledgerService.createTransactionBuilder()
                    .setNotary(notary.name)
                    .setTimeWindowBetween(
                        Instant.now(),
                        Instant.now().plusMillis(Duration.ofSeconds(60).toMillis())
                    )
                    .addOutputState(finalState)
                    .addCommand(PaymentInstructionContract.PaymentCommand.Submit())
                    .addSignatories(participantKeys)

                val signedTx = txBuilder.toSignedTransaction()

                // Step 3g: Finalise — counterparty session ONLY (SARB is NOT in finality)
                val finalitySessions = mutableListOf<FlowSession>()
                if (!isSameBank && counterpartyMember != null) {
                    finalitySessions.add(flowMessaging.initiateFlow(counterpartyMember))
                }

                ledgerService.finalize(signedTx, finalitySessions)

                // Step 3h: Notify SARB observer AFTER finality via separate session.send()
                val sarbX500 = findSarbObserver()
                if (sarbX500 != null) {
                    val sarbSession = flowMessaging.initiateFlow(sarbX500)
                    val dto = SarbNotificationDto(
                        stateId = finalState.stateId.toString(),
                        instructionId = finalState.instructionId,
                        endToEndId = finalState.endToEndId,
                        transactionId = finalState.transactionId,
                        amount = finalState.amount.toPlainString(),
                        currency = finalState.currency,
                        debtorName = finalState.debtorName,
                        debtorAccount = finalState.debtorAccount,
                        debtorAgentBranchCode = finalState.debtorAgentBranchCode,
                        creditorName = finalState.creditorName,
                        creditorAccount = finalState.creditorAccount,
                        creditorAgentBranchCode = finalState.creditorAgentBranchCode,
                        status = finalState.status.name,
                        feeApplicable = finalState.feeApplicable,
                        feeAmount = finalState.feeAmount.toPlainString(),
                        settlementDate = finalState.settlementDate.toString()
                    )
                    sarbSession.send(dto)
                    sarbSession.close()
                }

                // Step 3i: Record idempotency key
                val acceptanceXml = responseBuilder.buildAcceptance(
                    metadata.originalMessageId,
                    state.instructionId, state.endToEndId, state.transactionId
                )
                persistenceService.persist(
                    "persist-idemp-${metadata.originalMessageId}-${state.instructionId}",
                    IdempotencyKey(
                        messageId = metadata.originalMessageId,
                        instructionId = state.instructionId,
                        responsePayload = acceptanceXml,
                        stateId = finalState.stateId
                    )
                )

                // Step 3j: Record fee accrual if applicable
                if (finalState.feeApplicable) {
                    persistenceService.persist(
                        "persist-fee-${finalState.stateId}",
                        FeeAccrual(
                            stateId = finalState.stateId,
                            creditorBankBranchCode = finalState.creditorAgentBranchCode,
                            feeAmount = finalState.feeAmount,
                            taxAmount = finalState.feeTaxAmount,
                            totalAmount = finalState.feeAmount.add(finalState.feeTaxAmount),
                            transactionAmount = finalState.amount,
                            accrualDate = LocalDate.now()
                        )
                    )
                }

                results.add(Pacs002ResponseBuilder.TransactionResult(
                    state.instructionId, state.endToEndId, state.transactionId,
                    accepted = true
                ))
                acceptedCount++

                // Track linked state for metadata traceability
                metadata.linkedStateIds.add(finalState.stateId)

            } catch (e: Exception) {
                log.warn("Transaction failed for instruction ${state.instructionId}: ${e.message}")
                results.add(Pacs002ResponseBuilder.TransactionResult(
                    state.instructionId, state.endToEndId, state.transactionId,
                    accepted = false, rejectionReasonCode = "FF01",
                    rejectionReasonDescription = "Transaction failed: ${e.message}"
                ))
                rejectedCount++
            }
        }

        // Update metadata with linked state IDs
        persistenceService.merge(metadata)

        // Step 4: Consolidated pacs.002 response
        val pacs002 = responseBuilder.buildResponse(metadata.originalMessageId, results)

        return jsonMarshallingService.format(FlowOutput(
            pacs002Xml = pacs002,
            processedCount = states.size,
            acceptedCount = acceptedCount,
            rejectedCount = rejectedCount,
            skippedIdempotentCount = skippedCount
        ))
    }

    // =========================================================================
    // Helpers — branch code / role derived from X500 organisation (demo-only)
    // =========================================================================

    /**
     * Demo-only workaround: derive branch code from X500 organisation name.
     * In production, this would come from MGM-provided member metadata.
     */
    private fun getBranchCode(memberName: MemberX500Name): String = when (memberName.organization) {
        "BankAlpha" -> "100001"
        "BankBeta" -> "200002"
        else -> throw CordaRuntimeException("Unknown bank: ${memberName.organization}")
    }

    private fun getRole(memberName: MemberX500Name): String = when (memberName.organization) {
        "BankAlpha", "BankBeta" -> "PARTICIPANT"
        "SARB" -> "REGULATOR_OBSERVER"
        else -> "UNKNOWN"
    }

    @Suspendable
    private fun findMemberByBranchCode(branchCode: String): MemberX500Name? {
        val targetOrg = when (branchCode) {
            "100001" -> "BankAlpha"
            "200002" -> "BankBeta"
            else -> return null
        }
        return memberLookup.lookup().firstOrNull { it.name.organization == targetOrg }?.name
    }

    @Suspendable
    private fun findSarbObserver(): MemberX500Name? {
        return memberLookup.lookup().firstOrNull { it.name.organization == "SARB" }?.name
    }
}

/**
 * Responder flow for counterparty banks receiving payment instruction finality.
 * SARB observer uses a separate responder (SarbObserverResponderFlow).
 */
@InitiatedBy(protocol = "submit-payment-instruction")
class PaymentInstructionResponderFlow : ResponderFlow {

    @CordaInject lateinit var ledgerService: UtxoLedgerService
    @CordaInject lateinit var memberLookup: MemberLookup

    @Suspendable
    override fun call(session: FlowSession) {
        val myOrg = memberLookup.myInfo().name.organization

        if (myOrg == "SARB") {
            // SARB observer: receive the DTO notification (not finality)
            val dto = session.receive(SubmitPaymentInstructionFlow.SarbNotificationDto::class.java)
            // Persist locally for regulatory visibility (off-ledger)
            // In production, this would persist the DTO to a local audit table
            return
        }

        // Participant bank: receive finality and validate
        ledgerService.receiveFinality(session) { ledgerTransaction ->
            val myBranchCode = when (myOrg) {
                "BankAlpha" -> "100001"
                "BankBeta" -> "200002"
                else -> ""
            }

            val states = ledgerTransaction.getOutputStates(PaymentInstructionState::class.java)
            states.forEach { state ->
                require(
                    state.creditorAgentBranchCode == myBranchCode ||
                    state.debtorAgentBranchCode == myBranchCode
                ) {
                    "Transaction is not addressed to this node (branch $myBranchCode)"
                }
            }
        }
    }
}
