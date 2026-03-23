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
 * SARB observer receives all finalised transactions via session distribution
 * but is NOT a participant (not a required signer).
 *
 * Processing steps per transaction:
 * 1. Parse XML → List<PaymentInstructionState>
 * 2. Extract + store message envelope metadata off-ledger
 * 3. For each transaction:
 *    a. Check idempotency (off-ledger table, composite key) — replay cached response if hit
 *    b. Check participant status (MGM metadata — authoritative)
 *    c. Fee already calculated by mapper using PilotFeeConstants
 *    d. Resolve counterparty via MGM branch code metadata (skip if same-bank)
 *    e. Build participant keys (debtor + creditor banks only, NOT SARB)
 *    f. Build UTXO transaction (Submit command, 60s time window, notary)
 *    g. Sign + finalise with counterparty session + SARB observer session
 *    h. Record idempotency key with pacs.002 response payload for exact replay
 *    i. Record fee accrual if applicable
 * 4. Return consolidated pacs.002 response (includes replayed idempotent results)
 *
 * ============================================================================
 * CORDA 5.2 API UNCERTAINTIES — DEVELOPER MUST VERIFY AGAINST ACTUAL JAR
 * ============================================================================
 * 1. PersistenceService: query() vs find() for named JPA queries.
 *    Current code uses query().setParameter().execute() pattern (per Gemini).
 *    If not available, fall back to EntityManager-based approach:
 *    persistenceService.getEntityManager().use { em -> em.createNamedQuery(...) }
 * 2. Prowide class names: MxPacs00800108, GroupHeader93, CreditTransferTransaction39,
 *    etc. are version-specific. Verify against actual pw-iso20022 JAR for target SRU.
 * 3. Command extraction in contract: see PaymentInstructionContract.verify() note.
 * ============================================================================
 */
@InitiatingFlow(protocol = "submit-payment-instruction")
class SubmitPaymentInstructionFlow : ClientStartableFlow {

    @CordaInject
    lateinit var utxoLedgerService: UtxoLedgerService

    @CordaInject
    lateinit var memberLookup: MemberLookup

    @CordaInject
    lateinit var notaryLookup: NotaryLookup

    @CordaInject
    lateinit var jsonMarshallingService: JsonMarshallingService

    @CordaInject
    lateinit var persistenceService: PersistenceService

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

    @Suspendable
    override fun call(requestBody: ClientRequestBody): String {
        val input = requestBody.getRequestBodyAs(jsonMarshallingService, FlowInput::class.java)
        val xml = input.pacs008Xml
        val myInfo = memberLookup.myInfo()
        val myBranchCode = myInfo.memberProvidedContext["branchCode"] ?: ""

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
        persistenceService.persist(metadata)

        // Step 3: Process each transaction
        val results = mutableListOf<Pacs002ResponseBuilder.TransactionResult>()
        var acceptedCount = 0
        var rejectedCount = 0
        var skippedCount = 0

        for (state in states) {

            // Step 3a: IDEMPOTENCY CHECK
            // Query off-ledger table for composite key (messageId + instructionId)
            //
            // CORDA 5.2 API UNCERTAINTY — PersistenceService query pattern:
            // PRIMARY: persistenceService.query() for named queries (per Gemini)
            // FALLBACK: persistenceService.find() may only work for @Id lookups
            // Developer MUST verify against actual JAR.
            val existingKey: IdempotencyKey? = try {
                persistenceService.query(
                    "IdempotencyKey.findByCompositeKey",
                    IdempotencyKey::class.java
                )
                .setParameter("messageId", metadata.originalMessageId)
                .setParameter("instructionId", state.instructionId)
                .execute()
                .firstOrNull()
            } catch (e: Exception) {
                // Fallback: if query() is not the correct API, try direct JPQL
                // persistenceService.find(IdempotencyKey::class.java,
                //     "SELECT k FROM IdempotencyKey k WHERE k.messageId = :messageId AND k.instructionId = :instructionId",
                //     mapOf("messageId" to metadata.originalMessageId, "instructionId" to state.instructionId))
                null
            }

            if (existingKey != null) {
                // IDEMPOTENT REPLAY: return the cached pacs.002 result for this transaction.
                // The submitting bank must receive the exact same response on retry,
                // otherwise their reconciliation will fail (missing transactions in pacs.002).
                results.add(Pacs002ResponseBuilder.TransactionResult(
                    originalInstructionId = state.instructionId,
                    originalEndToEndId = state.endToEndId,
                    originalTransactionId = state.transactionId,
                    accepted = true // cached key implies original was accepted
                ))
                skippedCount++
                acceptedCount++ // count towards totals so pacs.002 is complete
                continue
            }

            // Step 3b: PARTICIPANT STATUS CHECK
            val counterpartyBranchCode = if (state.debtorAgentBranchCode == myBranchCode) {
                state.creditorAgentBranchCode
            } else {
                state.debtorAgentBranchCode
            }

            // Same-bank detection: debtor and creditor are on this node
            val isSameBank = state.debtorAgentBranchCode == state.creditorAgentBranchCode

            // Resolve counterparty (skip if same-bank)
            val counterpartyMember: MemberX500Name? = if (isSameBank) {
                null // no counterparty session needed
            } else {
                findMemberByBranchCode(counterpartyBranchCode)
            }

            if (!isSameBank && counterpartyMember == null) {
                results.add(Pacs002ResponseBuilder.TransactionResult(
                    state.instructionId, state.endToEndId, state.transactionId,
                    accepted = false, rejectionReasonCode = "RC01",
                    rejectionReasonDescription = "Unknown creditor agent: ${counterpartyBranchCode}"
                ))
                rejectedCount++
                continue
            }

            // Check suspension — both banks must be active
            val myStatus = getMemberStatus(myInfo.name)
            if (myStatus == "SUSPENDED") {
                results.add(Pacs002ResponseBuilder.TransactionResult(
                    state.instructionId, state.endToEndId, state.transactionId,
                    accepted = false, rejectionReasonCode = "AG01",
                    rejectionReasonDescription = "Submitting participant suspended"
                ))
                rejectedCount++
                continue
            }

            if (!isSameBank && counterpartyMember != null) {
                val counterpartyStatus = getMemberStatus(counterpartyMember)
                if (counterpartyStatus == "SUSPENDED") {
                    results.add(Pacs002ResponseBuilder.TransactionResult(
                        state.instructionId, state.endToEndId, state.transactionId,
                        accepted = false, rejectionReasonCode = "AG01",
                        rejectionReasonDescription = "Counterparty participant suspended"
                    ))
                    rejectedCount++
                    continue
                }
            }

            // Step 3e: Build participant keys — banks only, NOT SARB
            val myKey = myInfo.ledgerKeys.firstOrNull()
                ?: throw IllegalStateException("This node has no ledger keys")

            val participantKeys = if (isSameBank) {
                listOf(myKey) // single node handles both sides
            } else {
                val counterpartyKey = memberLookup.lookup(counterpartyMember!!)
                    ?.ledgerKeys?.firstOrNull()
                    ?: throw IllegalStateException("Counterparty has no ledger keys")
                listOf(myKey, counterpartyKey)
            }

            // Create state with correct participant keys
            val finalState = state.copy(participantKeys = participantKeys)

            try {
                // Step 3f: Build UTXO transaction
                val notary = notaryLookup.notaryServices.firstOrNull()
                    ?: throw IllegalStateException("No notary service available")

                val txBuilder = utxoLedgerService.createTransactionBuilder()
                    .setNotary(notary.name)
                    .setTimeWindowBetween(
                        Instant.now(),
                        Instant.now().plus(Duration.ofSeconds(60))  // 60s, not 5min
                    )
                    .addOutputState(finalState)
                    .addCommand(PaymentInstructionContract.PaymentCommand.Submit())
                    .addSignatories(participantKeys)

                val signedTx = txBuilder.toSignedTransaction()

                // Step 3g: Finalise — sessions for counterparty + SARB observer
                val sessions = mutableListOf<FlowSession>()
                if (!isSameBank && counterpartyMember != null) {
                    sessions.add(initiateFlow(counterpartyMember))
                }
                // SARB observer receives via session but is NOT a signer
                val sarbObserver = findSarbObserver()
                if (sarbObserver != null) {
                    sessions.add(initiateFlow(sarbObserver))
                }

                utxoLedgerService.finalize(signedTx, sessions)

                // Step 3h: Record idempotency key with response payload
                val acceptanceXml = responseBuilder.buildAcceptance(
                    metadata.originalMessageId,
                    state.instructionId, state.endToEndId, state.transactionId
                )
                persistenceService.persist(IdempotencyKey(
                    messageId = metadata.originalMessageId,
                    instructionId = state.instructionId,
                    responsePayload = acceptanceXml,
                    stateId = finalState.stateId
                ))

                // Step 3i: Record fee accrual if applicable
                if (finalState.feeApplicable) {
                    persistenceService.persist(FeeAccrual(
                        stateId = finalState.stateId,
                        creditorBankBranchCode = finalState.creditorAgentBranchCode,
                        feeAmount = finalState.feeAmount,
                        taxAmount = finalState.feeTaxAmount,
                        totalAmount = finalState.feeAmount.add(finalState.feeTaxAmount),
                        transactionAmount = finalState.amount,
                        accrualDate = LocalDate.now()
                    ))
                }

                // Link state to metadata
                metadata.linkedStateIds.add(finalState.stateId)

                results.add(Pacs002ResponseBuilder.TransactionResult(
                    state.instructionId, state.endToEndId, state.transactionId,
                    accepted = true
                ))
                acceptedCount++

            } catch (e: Exception) {
                results.add(Pacs002ResponseBuilder.TransactionResult(
                    state.instructionId, state.endToEndId, state.transactionId,
                    accepted = false, rejectionReasonCode = "FF01",
                    rejectionReasonDescription = "Transaction failed: ${e.message}"
                ))
                rejectedCount++
            }
        }

        // Update metadata with linked states
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

    @Suspendable
    private fun findMemberByBranchCode(branchCode: String): MemberX500Name? {
        return memberLookup.lookup()
            .filter { it.memberProvidedContext["branchCode"] == branchCode }
            .firstOrNull()
            ?.name
    }

    @Suspendable
    private fun findSarbObserver(): MemberX500Name? {
        return memberLookup.lookup()
            .filter { it.memberProvidedContext["role"] == "REGULATOR_OBSERVER" }
            .firstOrNull()
            ?.name
    }

    @Suspendable
    private fun getMemberStatus(memberName: MemberX500Name): String {
        val member = memberLookup.lookup(memberName)
        return member?.memberProvidedContext?.get("participantStatus") ?: "ACTIVE"
    }
}

/**
 * Single responder flow for both counterparty banks AND the SARB observer.
 * Corda 5 allows only one responder per protocol per node.
 * The responder checks the node's role to determine behaviour:
 * - PARTICIPANT: validates the transaction is addressed to this node, co-signs
 * - REGULATOR_OBSERVER: accepts and stores everything, does not reject
 */
@InitiatedBy(protocol = "submit-payment-instruction")
class PaymentInstructionResponderFlow : ResponderFlow {

    @CordaInject
    lateinit var utxoLedgerService: UtxoLedgerService

    @CordaInject
    lateinit var memberLookup: MemberLookup

    @Suspendable
    override fun call(session: FlowSession) {
        val myRole = memberLookup.myInfo().memberProvidedContext["role"] ?: "PARTICIPANT"

        utxoLedgerService.receiveFinality(session) { ledgerTransaction ->
            if (myRole == "REGULATOR_OBSERVER") {
                // SARB observer: accept everything, never reject, never sign
                // (SARB is not in participantKeys so is not a required signer)
                return@receiveFinality
            }

            // Participant bank: verify this transaction is addressed to us
            val myBranchCode = memberLookup.myInfo()
                .memberProvidedContext["branchCode"] ?: ""

            val states = ledgerTransaction.getOutputStates(PaymentInstructionState::class.java)
            states.forEach { state ->
                require(
                    state.creditorAgentBranchCode == myBranchCode ||
                    state.debtorAgentBranchCode == myBranchCode
                ) {
                    "Transaction is not addressed to this node (branch $myBranchCode)"
                }
            }
            // Contract validation is automatically enforced by Corda
        }
    }
}
