package za.co.payfi.clearing.persistence

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.persistence.*
import net.corda.v5.base.annotations.CordaSerializable

/**
 * Off-ledger persistence entities for PayFi.
 *
 * NOTE: In the Corda 5.2 template, JPA entities are used with the
 * PersistenceService API. The persist() method requires a deterministic
 * deduplication ID as its first argument:
 *   persistenceService.persist("dedup-id-string", entity)
 *
 * All callers of persistenceService.persist() must provide a business-key-based
 * dedup ID (e.g., "persist-${msgId}-${instrId}"), NEVER UUID.randomUUID().
 *
 * Every field has an explicit @Column(name = "snake_case") to guarantee
 * PostgreSQL column names match Liquibase definitions exactly.
 */

@CordaSerializable
@Entity
@Table(name = "payment_message_metadata")
data class PaymentMessageMetadata(
    @Id
    @Column(name = "id")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "original_message_id", nullable = false)
    val originalMessageId: String,

    @Column(name = "message_creation_time", nullable = false)
    val messageCreationTime: Instant,

    @Column(name = "number_of_transactions", nullable = false)
    val numberOfTransactions: Int,

    @Column(name = "message_digest", nullable = false, length = 64)
    val messageDigest: String,

    @Column(name = "received_at", nullable = false)
    val receivedAt: Instant = Instant.now(),

    @ElementCollection
    @CollectionTable(
        name = "metadata_linked_states",
        joinColumns = [JoinColumn(name = "metadata_id")]
    )
    @Column(name = "state_id")
    val linkedStateIds: MutableList<UUID> = mutableListOf()
)

@CordaSerializable
@Entity
@Table(
    name = "idempotency_keys",
    uniqueConstraints = [UniqueConstraint(columnNames = ["message_id", "instruction_id"])]
)
@NamedQuery(
    name = "IdempotencyKey.findByCompositeKey",
    query = "SELECT k FROM IdempotencyKey k WHERE k.messageId = :messageId AND k.instructionId = :instructionId"
)
data class IdempotencyKey(
    @Id
    @Column(name = "id")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "message_id", nullable = false)
    val messageId: String,

    @Column(name = "instruction_id", nullable = false)
    val instructionId: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "response_payload", nullable = false, columnDefinition = "TEXT")
    val responsePayload: String,

    @Column(name = "state_id", nullable = false)
    val stateId: UUID
)

/**
 * Fee accrual record. Reversals use negative amounts.
 * CAVEAT: VAT rate (15%) is a pilot assumption.
 */
@CordaSerializable
@Entity
@Table(name = "fee_accruals")
data class FeeAccrual(
    @Id
    @Column(name = "id")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "state_id", nullable = false)
    val stateId: UUID,

    @Column(name = "creditor_bank_branch_code", nullable = false, length = 6)
    val creditorBankBranchCode: String,

    @Column(name = "fee_amount", nullable = false, precision = 10, scale = 2)
    val feeAmount: BigDecimal,

    @Column(name = "tax_amount", nullable = false, precision = 10, scale = 2)
    val taxAmount: BigDecimal,

    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    val totalAmount: BigDecimal,

    @Column(name = "transaction_amount", nullable = false, precision = 19, scale = 2)
    val transactionAmount: BigDecimal,

    @Column(name = "accrual_date", nullable = false)
    val accrualDate: LocalDate,

    @Column(name = "invoiced", nullable = false)
    val invoiced: Boolean = false,

    @Column(name = "invoice_reference", nullable = true)
    val invoiceReference: String? = null,

    @Column(name = "reversed", nullable = false)
    val reversed: Boolean = false,

    @Column(name = "reversal_note", nullable = true)
    val reversalNote: String? = null
)

@CordaSerializable
@Entity
@Table(name = "fee_rules")
data class FeeRule(
    @Id
    @Column(name = "id")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "threshold_amount", nullable = false, precision = 19, scale = 2)
    val thresholdAmount: BigDecimal,

    @Column(name = "fee_amount", nullable = false, precision = 10, scale = 2)
    val feeAmount: BigDecimal,

    @Column(name = "currency", nullable = false, length = 3)
    val currency: String = "ZAR",

    @Column(name = "fee_payer", nullable = false)
    val feePayer: String = "CREDITOR_BANK",

    @Column(name = "effective_from", nullable = false)
    val effectiveFrom: Instant
)

@CordaSerializable
@Entity
@Table(name = "settlement_reports")
data class SettlementReport(
    @Id
    @Column(name = "id")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "bank_branch_code", nullable = false, length = 6)
    val bankBranchCode: String,

    @Column(name = "window_start", nullable = false)
    val windowStart: Instant,

    @Column(name = "window_end", nullable = false)
    val windowEnd: Instant,

    @Column(name = "net_amount", nullable = false, precision = 19, scale = 2)
    val netAmount: BigDecimal,

    @Column(name = "gross_payable", nullable = false, precision = 19, scale = 2)
    val grossPayable: BigDecimal,

    @Column(name = "gross_receivable", nullable = false, precision = 19, scale = 2)
    val grossReceivable: BigDecimal,

    @Column(name = "transaction_count", nullable = false)
    val transactionCount: Int,

    @Column(name = "generated_at", nullable = false)
    val generatedAt: Instant = Instant.now()
)

/**
 * Audit trail only — MGM metadata is authoritative for enforcement.
 */
@CordaSerializable
@Entity
@Table(name = "participant_status")
data class ParticipantStatusRecord(
    @Id
    @Column(name = "id")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "branch_code", nullable = false, length = 6)
    val branchCode: String,

    @Column(name = "status", nullable = false)
    val status: String,

    @Column(name = "effective_from", nullable = false)
    val effectiveFrom: Instant,

    @Column(name = "reason", nullable = false)
    val reason: String,

    @Column(name = "initiated_by", nullable = false)
    val initiatedBy: String,

    @Column(name = "reinstated_at", nullable = true)
    val reinstatedAt: Instant? = null
)
