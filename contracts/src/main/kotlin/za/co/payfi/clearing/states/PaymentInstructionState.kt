package za.co.payfi.clearing.states

import net.corda.v5.base.annotations.CordaSerializable
import net.corda.v5.ledger.utxo.BelongsToContract
import net.corda.v5.ledger.utxo.ContractState
import za.co.payfi.clearing.contracts.PaymentInstructionContract
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.PublicKey
import java.time.LocalDate
import java.util.UUID

/**
 * Core on-ledger state representing a single payment instruction.
 * Maps to a single CdtTrfTxInf element from an ISO 20022 pacs.008 message.
 *
 * Fee fields are system-calculated and visible to all participants (both banks).
 * Corda has no field-level privacy. The fee is contractually the creditor bank's liability.
 *
 * participantKeys includes debtor bank and creditor bank only.
 * SARB observer receives transactions via session distribution but is NOT a
 * participant (not a required signer). This preserves the observer-only model.
 */
@BelongsToContract(PaymentInstructionContract::class)
@CordaSerializable
data class PaymentInstructionState(

    // --- State Identity ---
    val stateId: UUID,

    // --- Payment Identification (PmtId) ---
    /** PmtId/InstrId — unique instruction identifier */
    val instructionId: String,
    /** PmtId/EndToEndId — payer's end-to-end reference */
    val endToEndId: String,
    /** PmtId/TxId — clearing system transaction reference */
    val transactionId: String,

    // --- Settlement (IntrBkSttlm) ---
    /** IntrBkSttlmAmt — interbank settlement amount */
    val amount: BigDecimal,
    /** IntrBkSttlmAmt/@Ccy — always "ZAR" for domestic */
    val currency: String = "ZAR",
    /** IntrBkSttlmDt — interbank settlement date */
    val settlementDate: LocalDate,

    // --- Debtor / Sender (Dbtr) ---
    /** Dbtr/Nm — debtor name (MANDATORY per platform rule, FICA) */
    val debtorName: String,
    /** Derived from Dbtr/Id/PrvtId or OrgId scheme codes */
    val debtorIdType: DebtorIdType,
    /** Dbtr/Id/PrvtId/Othr/Id or Dbtr/Id/OrgId/Othr/Id */
    val debtorIdNumber: String,
    /** Dbtr/PstlAdr — structured address, CONDITIONAL: mandatory for PASSPORT and UNIQUE_CUSTOMER_ID */
    val debtorAddress: StructuredAddress? = null,
    /** DbtrAcct/Id/Othr/Id — debtor account number */
    val debtorAccount: String,
    /** DbtrAgt/FinInstnId/ClrSysMmbId/MmbId — 6-digit SA branch code */
    val debtorAgentBranchCode: String,

    // --- Creditor / Receiver (Cdtr) ---
    /** Cdtr/Nm — creditor name */
    val creditorName: String,
    /** CdtrAcct/Id/Othr/Id — creditor account number */
    val creditorAccount: String,
    /** CdtrAgt/FinInstnId/ClrSysMmbId/MmbId — 6-digit SA branch code */
    val creditorAgentBranchCode: String,

    // --- Remittance ---
    /** RmtInf/Ustrd — unstructured remittance information */
    val remittanceInfo: String? = null,
    /** PmtTpInf/CtgyPurp/Cd — category purpose code */
    val purposeCode: String? = null,

    // --- Status Lifecycle ---
    val status: PaymentStatus,
    val statusReason: String? = null,

    // --- Transaction Fee (system-calculated, NOT from ISO 20022) ---
    /**
     * PILOT NOTE: Fee amounts are hardcoded in the contract for determinism.
     * Changing these values requires a contract CPK upgrade.
     * For production, implement Corda 5 Reference States.
     */
    /** True if amount > R3,000.00 */
    val feeApplicable: Boolean,
    /** ZAR 0.20 if applicable, else 0.00 (exclusive of VAT) */
    val feeAmount: BigDecimal,
    /** ZAR 0.03 if applicable, else 0.00 (15% VAT) */
    val feeTaxAmount: BigDecimal,
    /** Always equals creditorAgentBranchCode — creditor bank pays */
    val feePayerBranchCode: String,

    // --- Corda Participants (public for copy() support) ---
    /** Debtor bank + creditor bank keys. SARB is NOT included. */
    val participantKeys: List<PublicKey>

) : ContractState {

    override fun getParticipants(): List<PublicKey> = participantKeys

    /**
     * Convenience: returns a copy with only status and statusReason changed.
     * Lifecycle flows should use this to ensure immutability of business fields.
     */
    fun withStatus(newStatus: PaymentStatus, reason: String? = null): PaymentInstructionState =
        copy(status = newStatus, statusReason = reason)
}

@CordaSerializable
enum class DebtorIdType {
    SA_NATIONAL_ID,
    PASSPORT,
    UNIQUE_CUSTOMER_ID,
    BUSINESS_REGISTRATION_ID
}

@CordaSerializable
enum class PaymentStatus {
    SUBMITTED, VALIDATED, CLEARED, REJECTED,
    CANCELLATION_REQUESTED, CANCELLATION_ACCEPTED, CANCELLATION_REJECTED,
    RETURNED
    // NOTE: CANCELLED was removed — the lifecycle uses CANCELLATION_ACCEPTED instead.
    // If a generic "cancelled" status is needed in future, add it back with a
    // corresponding contract command and transition rule.
}

/**
 * Structured postal address. Maps to Dbtr/PstlAdr elements.
 * For FICA compliance when required (PASSPORT / UNIQUE_CUSTOMER_ID),
 * address must include street/building AND town AND country.
 */
@CordaSerializable
data class StructuredAddress(
    val streetName: String? = null,
    val buildingNumber: String? = null,
    val postCode: String? = null,
    val townName: String? = null,
    val country: String? = null
) {
    fun isPopulated(): Boolean {
        val hasStreetOrBuilding = !streetName.isNullOrBlank() || !buildingNumber.isNullOrBlank()
        val hasTown = !townName.isNullOrBlank()
        val hasCountry = !country.isNullOrBlank()
        return hasStreetOrBuilding && hasTown && hasCountry
    }
}

/**
 * Fee constants for the pilot. Hardcoded to match contract validation.
 * CAVEAT: VAT rate (15%) is a pilot assumption — must be tax-reviewed.
 * For production, implement Corda 5 Reference States.
 */
object PilotFeeConstants {
    val THRESHOLD_AMOUNT: BigDecimal = BigDecimal("3000.00")
    val FEE_AMOUNT: BigDecimal = BigDecimal("0.20")
    val VAT_RATE: BigDecimal = BigDecimal("0.15")
    val TAX_AMOUNT: BigDecimal = FEE_AMOUNT.multiply(VAT_RATE).setScale(2, RoundingMode.HALF_UP)
    val TOTAL_AMOUNT: BigDecimal = FEE_AMOUNT.add(TAX_AMOUNT)
    const val CURRENCY: String = "ZAR"
}

@CordaSerializable
enum class FeePayerType {
    CREDITOR_BANK
}

