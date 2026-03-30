package za.co.payfi.clearing.contracts

import net.corda.v5.base.annotations.CordaSerializable
import net.corda.v5.base.exceptions.CordaRuntimeException
import net.corda.v5.ledger.utxo.Command
import net.corda.v5.ledger.utxo.Contract
import net.corda.v5.ledger.utxo.transaction.UtxoLedgerTransaction
import za.co.payfi.clearing.states.DebtorIdType
import za.co.payfi.clearing.states.PaymentInstructionState
import za.co.payfi.clearing.states.PaymentStatus
import za.co.payfi.clearing.states.PilotFeeConstants
import za.co.payfi.clearing.validation.SouthAfricanIdentityValidator
import java.math.BigDecimal

/**
 * Smart contract enforcing PayFi system rules for payment instructions.
 *
 * Validates ISO 20022 field requirements, FICA sender identification,
 * transaction fee calculations, payment lifecycle transitions, and
 * field immutability across state transitions.
 *
 * Rejection codes align with REJECTION-CODE-MATRIX.md (preliminary,
 * subject to review with participant banks and PASA).
 */
class PaymentInstructionContract : Contract {

    /**
     * Contract commands.
     *
     * NOTE on Corda 5 command extraction: In Corda 5, transaction.commands
     * returns a list whose elements may need .value to access the command data,
     * depending on the exact 5.2 API version. If compilation fails, change
     * the extraction in verify() to: transaction.commands.map { it.value }
     * A developer MUST verify this against the actual Corda 5.2 JAR.
     */
    @CordaSerializable
    sealed class PaymentCommand : Command {
        class Submit : PaymentCommand()
        class UpdateStatus : PaymentCommand()
        class RequestCancellation : PaymentCommand()
        class ResolveCancellation : PaymentCommand()
        class Return : PaymentCommand()
    }

    override fun verify(transaction: UtxoLedgerTransaction) {
        /*
         * CORDA 5.2 API UNCERTAINTY — MUST VERIFY AGAINST ACTUAL JAR
         *
         * In Corda 5, transaction.commands may return wrapped Command objects
         * where the actual command data is accessed via .value property.
         * The .value approach is the safer default (per DeepSeek, Gemini, ChatGPT reviews).
         *
         * If compilation fails with the active line, swap to the fallback.
         */
        // Command extraction (Corda 5.2 — commands are direct, not wrapped)
        val command = transaction.commands.firstOrNull { it is PaymentCommand }
            as? PaymentCommand
        command ?: throw CordaRuntimeException("Transaction must contain a PaymentCommand")

        when (command) {
            is PaymentCommand.Submit -> verifySubmit(transaction)
            is PaymentCommand.UpdateStatus -> verifyUpdateStatus(transaction)
            is PaymentCommand.RequestCancellation -> verifyRequestCancellation(transaction)
            is PaymentCommand.ResolveCancellation -> verifyResolveCancellation(transaction)
            is PaymentCommand.Return -> verifyReturn(transaction)
        }
    }

    // =========================================================================
    // SUBMIT — new payment instruction
    // =========================================================================

    private fun verifySubmit(transaction: UtxoLedgerTransaction) {
        val inputs = transaction.getInputStates(PaymentInstructionState::class.java)
        val outputs = transaction.getOutputStates(PaymentInstructionState::class.java)

        require(inputs.isEmpty()) { "Submit: must have zero input states" }
        require(outputs.isNotEmpty()) { "Submit: must have at least one output state" }

        outputs.forEach { state -> validateSubmittedState(state) }
    }

    private fun validateSubmittedState(state: PaymentInstructionState) {
        // --- Payment Identification ---
        require(state.instructionId.isNotBlank()) { "FF01: instructionId must not be blank" }
        require(state.endToEndId.isNotBlank()) { "FF01: endToEndId must not be blank" }
        require(state.transactionId.isNotBlank()) { "FF01: transactionId must not be blank" }

        // --- Amount and Currency ---
        require(state.amount > BigDecimal.ZERO) { "AM01: amount must be positive" }
        require(state.currency == "ZAR") { "AM03: currency must be ZAR (domestic only)" }

        // --- Debtor Identity (FICA / FATF R16) ---
        require(state.debtorName.isNotBlank() && state.debtorName.length >= 2) {
            "CH09: debtorName must not be blank (minimum 2 characters)"
        }
        require(state.debtorIdNumber.isNotBlank()) { "CH09: debtorIdNumber must not be blank" }

        when (state.debtorIdType) {
            DebtorIdType.SA_NATIONAL_ID -> {
                require(SouthAfricanIdentityValidator.isValidSaId(state.debtorIdNumber)) {
                    "BE01: invalid SA ID number (Luhn check failed)"
                }
            }
            DebtorIdType.PASSPORT -> {
                require(SouthAfricanIdentityValidator.isValidPassport(state.debtorIdNumber)) {
                    "CH09: invalid passport number"
                }
                require(state.debtorAddress != null && state.debtorAddress.isPopulated()) {
                    "CH11: debtorAddress is mandatory for PASSPORT identification"
                }
            }
            DebtorIdType.UNIQUE_CUSTOMER_ID -> {
                require(SouthAfricanIdentityValidator.isValidUniqueCustomerId(state.debtorIdNumber)) {
                    "CH09: invalid unique customer ID"
                }
                require(state.debtorAddress != null && state.debtorAddress.isPopulated()) {
                    "CH11: debtorAddress is mandatory for UNIQUE_CUSTOMER_ID identification"
                }
            }
            DebtorIdType.BUSINESS_REGISTRATION_ID -> {
                require(SouthAfricanIdentityValidator.isValidBusinessRegistration(state.debtorIdNumber)) {
                    "CH09: invalid business registration number"
                }
            }
        }

        // --- Debtor Account and Agent ---
        require(state.debtorAccount.isNotBlank()) { "FF01: debtorAccount must not be blank" }
        require(SouthAfricanIdentityValidator.isValidBranchCode(state.debtorAgentBranchCode)) {
            "RC01: debtorAgentBranchCode must be a valid 6-digit code"
        }

        // --- Creditor ---
        require(state.creditorName.isNotBlank()) { "FF01: creditorName must not be blank" }
        require(state.creditorAccount.isNotBlank()) { "FF01: creditorAccount must not be blank" }
        require(SouthAfricanIdentityValidator.isValidBranchCode(state.creditorAgentBranchCode)) {
            "RC01: creditorAgentBranchCode must be a valid 6-digit code"
        }

        // --- Same-bank payments ARE ALLOWED ---
        // Banks may route on-us transactions through the platform for
        // standardised ISO 20022 processing and audit trail.

        // --- Status ---
        require(state.status == PaymentStatus.SUBMITTED) { "Submit: initial status must be SUBMITTED" }

        // --- Fee Validation (hardcoded for pilot determinism) ---
        if (state.amount > PilotFeeConstants.THRESHOLD_AMOUNT) {
            require(state.feeApplicable) { "Fee: feeApplicable must be true when amount > R3,000" }
            require(state.feeAmount.compareTo(PilotFeeConstants.FEE_AMOUNT) == 0) {
                "Fee: feeAmount must be ${PilotFeeConstants.FEE_AMOUNT} when applicable"
            }
            require(state.feeTaxAmount.compareTo(PilotFeeConstants.TAX_AMOUNT) == 0) {
                "Fee: feeTaxAmount must be ${PilotFeeConstants.TAX_AMOUNT} (15% VAT) when applicable"
            }
        } else {
            require(!state.feeApplicable) { "Fee: feeApplicable must be false when amount <= R3,000" }
            require(state.feeAmount.compareTo(BigDecimal.ZERO) == 0) { "Fee: feeAmount must be 0.00" }
            require(state.feeTaxAmount.compareTo(BigDecimal.ZERO) == 0) { "Fee: feeTaxAmount must be 0.00" }
        }
        require(state.feePayerBranchCode == state.creditorAgentBranchCode) {
            "Fee: feePayerBranchCode must equal creditorAgentBranchCode"
        }
    }

    // =========================================================================
    // UPDATE STATUS — SUBMITTED→VALIDATED, VALIDATED→CLEARED, or rejection
    // =========================================================================

    private fun verifyUpdateStatus(transaction: UtxoLedgerTransaction) {
        val (input, output) = extractSingleInputOutput(transaction, "UpdateStatus")

        // Only status and statusReason may change
        verifyImmutableFieldsUnchanged(input, output, "UpdateStatus")

        val validTransitions = mapOf(
            PaymentStatus.SUBMITTED to setOf(PaymentStatus.VALIDATED, PaymentStatus.REJECTED),
            PaymentStatus.VALIDATED to setOf(PaymentStatus.CLEARED, PaymentStatus.REJECTED)
        )

        val allowedNext = validTransitions[input.status]
            ?: throw CordaRuntimeException("UpdateStatus: no transitions allowed from ${input.status}")

        require(output.status in allowedNext) {
            "UpdateStatus: invalid transition ${input.status} → ${output.status}"
        }

        if (output.status == PaymentStatus.REJECTED) {
            require(!output.statusReason.isNullOrBlank()) {
                "UpdateStatus: statusReason is mandatory for REJECTED"
            }
        }
    }

    // =========================================================================
    // REQUEST CANCELLATION — camt.056
    // =========================================================================

    private fun verifyRequestCancellation(transaction: UtxoLedgerTransaction) {
        val (input, output) = extractSingleInputOutput(transaction, "RequestCancellation")

        verifyImmutableFieldsUnchanged(input, output, "RequestCancellation")

        require(input.status == PaymentStatus.SUBMITTED || input.status == PaymentStatus.VALIDATED) {
            "RequestCancellation: input status must be SUBMITTED or VALIDATED"
        }
        require(output.status == PaymentStatus.CANCELLATION_REQUESTED) {
            "RequestCancellation: output status must be CANCELLATION_REQUESTED"
        }
    }

    // =========================================================================
    // RESOLVE CANCELLATION — camt.029
    // =========================================================================

    private fun verifyResolveCancellation(transaction: UtxoLedgerTransaction) {
        val (input, output) = extractSingleInputOutput(transaction, "ResolveCancellation")

        verifyImmutableFieldsUnchanged(input, output, "ResolveCancellation")

        require(input.status == PaymentStatus.CANCELLATION_REQUESTED) {
            "ResolveCancellation: input status must be CANCELLATION_REQUESTED"
        }
        require(output.status == PaymentStatus.CANCELLATION_ACCEPTED ||
                output.status == PaymentStatus.CANCELLATION_REJECTED) {
            "ResolveCancellation: output status must be CANCELLATION_ACCEPTED or CANCELLATION_REJECTED"
        }

        if (output.status == PaymentStatus.CANCELLATION_REJECTED) {
            require(!output.statusReason.isNullOrBlank()) {
                "ResolveCancellation: statusReason is mandatory for CANCELLATION_REJECTED"
            }
        }
    }

    // =========================================================================
    // RETURN — pacs.004
    // =========================================================================

    private fun verifyReturn(transaction: UtxoLedgerTransaction) {
        val (input, output) = extractSingleInputOutput(transaction, "Return")

        verifyImmutableFieldsUnchanged(input, output, "Return")

        require(input.status == PaymentStatus.CLEARED) { "Return: input status must be CLEARED" }
        require(output.status == PaymentStatus.RETURNED) { "Return: output status must be RETURNED" }
        require(!output.statusReason.isNullOrBlank()) {
            "Return: statusReason (return reason code) is mandatory"
        }
    }

    // =========================================================================
    // SHARED HELPERS
    // =========================================================================

    /**
     * Extract exactly one input and one output state, verifying counts and stateId match.
     */
    private fun extractSingleInputOutput(
        transaction: UtxoLedgerTransaction,
        commandName: String
    ): Pair<PaymentInstructionState, PaymentInstructionState> {
        val inputs = transaction.getInputStates(PaymentInstructionState::class.java)
        val outputs = transaction.getOutputStates(PaymentInstructionState::class.java)

        require(inputs.size == 1) { "$commandName: must have exactly one input" }
        require(outputs.size == 1) { "$commandName: must have exactly one output" }

        val input = inputs.single()
        val output = outputs.single()

        require(input.stateId == output.stateId) { "$commandName: stateId must match" }

        return Pair(input, output)
    }

    /**
     * Verifies that ALL fields except status and statusReason are unchanged.
     * This prevents any business field from being mutated during lifecycle transitions.
     *
     * ChatGPT correctly identified that the original code only enforced this for
     * UpdateStatus but not for Cancel/Resolve/Return — a serious contract integrity flaw.
     */
    private fun verifyImmutableFieldsUnchanged(
        input: PaymentInstructionState,
        output: PaymentInstructionState,
        commandName: String
    ) {
        require(
            input.copy(status = output.status, statusReason = output.statusReason) == output
        ) {
            "$commandName: only status and statusReason may change — all other fields must be immutable"
        }
    }
}

