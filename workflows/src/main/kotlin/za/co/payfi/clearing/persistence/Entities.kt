package za.co.payfi.clearing.persistence

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.persistence.*

@Entity
@Table(name = "payment_message_metadata")
data class PaymentMessageMetadata(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(nullable = false) val originalMessageId: String,
    @Column(nullable = false) val messageCreationTime: Instant,
    @Column(nullable = false) val numberOfTransactions: Int,
    @Column(nullable = false, length = 64) val messageDigest: String,
    @Column(nullable = false) val receivedAt: Instant = Instant.now(),
    @ElementCollection
    @CollectionTable(name = "metadata_linked_states", joinColumns = [JoinColumn(name = "metadata_id")])
    @Column(name = "state_id")
    val linkedStateIds: MutableList<UUID> = mutableListOf()
)

@Entity
@Table(name = "idempotency_keys",
    uniqueConstraints = [UniqueConstraint(columnNames = ["messageId", "instructionId"])])
@NamedQuery(
    name = "IdempotencyKey.findByCompositeKey",
    query = "SELECT k FROM IdempotencyKey k WHERE k.messageId = :messageId AND k.instructionId = :instructionId"
)
data class IdempotencyKey(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(nullable = false) val messageId: String,
    @Column(nullable = false) val instructionId: String,
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
    @Column(nullable = false, columnDefinition = "TEXT") val responsePayload: String,
    @Column(nullable = false) val stateId: UUID
)

/**
 * Fee accrual record. Reversals use negative amounts.
 * CAVEAT: VAT rate (15%) is a pilot assumption.
 */
@Entity
@Table(name = "fee_accruals")
data class FeeAccrual(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(nullable = false) val stateId: UUID,
    @Column(nullable = false, length = 6) val creditorBankBranchCode: String,
    @Column(nullable = false, precision = 10, scale = 2) val feeAmount: BigDecimal,
    @Column(nullable = false, precision = 10, scale = 2) val taxAmount: BigDecimal,
    @Column(nullable = false, precision = 10, scale = 2) val totalAmount: BigDecimal,
    @Column(nullable = false, precision = 19, scale = 2) val transactionAmount: BigDecimal,
    @Column(nullable = false) val accrualDate: LocalDate,
    @Column(nullable = false) val invoiced: Boolean = false,
    @Column(nullable = true) val invoiceReference: String? = null,
    @Column(nullable = false) val reversed: Boolean = false,
    @Column(nullable = true) val reversalNote: String? = null
)

@Entity
@Table(name = "fee_rules")
data class FeeRule(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(nullable = false, precision = 19, scale = 2) val thresholdAmount: BigDecimal,
    @Column(nullable = false, precision = 10, scale = 2) val feeAmount: BigDecimal,
    @Column(nullable = false, length = 3) val currency: String = "ZAR",
    @Column(nullable = false) val feePayer: String = "CREDITOR_BANK",
    @Column(nullable = false) val effectiveFrom: Instant
)

@Entity
@Table(name = "settlement_reports")
data class SettlementReport(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(nullable = false, length = 6) val bankBranchCode: String,
    @Column(nullable = false) val windowStart: Instant,
    @Column(nullable = false) val windowEnd: Instant,
    @Column(nullable = false, precision = 19, scale = 2) val netAmount: BigDecimal,
    @Column(nullable = false, precision = 19, scale = 2) val grossPayable: BigDecimal,
    @Column(nullable = false, precision = 19, scale = 2) val grossReceivable: BigDecimal,
    @Column(nullable = false) val transactionCount: Int,
    @Column(nullable = false) val generatedAt: Instant = Instant.now()
)

/**
 * Audit trail only — MGM metadata is authoritative for enforcement.
 */
@Entity
@Table(name = "participant_status")
data class ParticipantStatusRecord(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(nullable = false, length = 6) val branchCode: String,
    @Column(nullable = false) val status: String,
    @Column(nullable = false) val effectiveFrom: Instant,
    @Column(nullable = false) val reason: String,
    @Column(nullable = false) val initiatedBy: String,
    @Column(nullable = true) val reinstatedAt: Instant? = null
)
