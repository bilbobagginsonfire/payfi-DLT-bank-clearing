package za.co.payfi.clearing.mapper

import com.prowidesoftware.swift.model.mx.MxPacs00200110
import com.prowidesoftware.swift.model.mx.dic.*
import net.corda.v5.base.annotations.CordaSerializable
import java.time.Instant
import java.util.UUID
import javax.xml.datatype.DatatypeFactory

/**
 * Builds ISO 20022 pacs.002.001.10 (Payment Status Report) XML responses.
 *
 * Uses Prowide pw-iso20022 SRU2023-9.4.7 classes:
 * - MxPacs00200110
 * - FIToFIPaymentStatusReportV10
 * - GroupHeader91
 * - OriginalGroupHeader17
 * - PaymentTransaction110
 * - StatusReasonInformation12
 * - StatusReason6Choice
 *
 * NOTE: ExternalPaymentTransactionStatus1Code does NOT exist in Prowide
 * SRU2023-9.4.7. The transaction status field on PaymentTransaction110
 * is a plain String. We use String constants defined in PaymentStatusCode.
 */
class Pacs002ResponseBuilder {

    /**
     * ISO 20022 payment transaction status codes as String constants.
     * ExternalPaymentTransactionStatus1Code does not exist in the
     * Prowide SRU2023-9.4.7 JAR.
     */
    object PaymentStatusCode {
        const val ACCEPTED = "ACCP"            // Accepted Customer Profile
        const val ACCEPTED_SETTLEMENT = "ACSP" // Accepted Settlement in Process
        const val REJECTED = "RJCT"            // Rejected
        const val PENDING = "PDNG"             // Pending
    }

    @CordaSerializable
    data class TransactionResult(
        val originalInstructionId: String,
        val originalEndToEndId: String,
        val originalTransactionId: String,
        val accepted: Boolean,
        val rejectionReasonCode: String? = null,
        val rejectionReasonDescription: String? = null,
        /** Cached pacs.002 XML from idempotency replay, if available */
        val cachedResponsePayload: String? = null
    )

    fun buildResponse(originalMessageId: String, results: List<TransactionResult>): String {
        val mx = MxPacs00200110()
        val statusReport = FIToFIPaymentStatusReportV10()
        mx.fiToFIPmtStsRpt = statusReport

        val grpHdr = GroupHeader91()
        // Deterministic MsgId derived from inbound originalMessageId.
        // On flow replay, this produces the same output MsgId, preserving
        // checkpoint consistency and downstream signature verification.
        grpHdr.setMsgId("PSR-${originalMessageId}")
        val factory = DatatypeFactory.newInstance()
        grpHdr.creDtTm = factory.newXMLGregorianCalendar(
            java.util.GregorianCalendar.getInstance().apply {
                timeInMillis = Instant.now().toEpochMilli()
            }
        )
        statusReport.grpHdr = grpHdr

        val orgnlGrpInf = OriginalGroupHeader17()
        orgnlGrpInf.setOrgnlMsgId(originalMessageId)
        orgnlGrpInf.setOrgnlMsgNmId("pacs.008.001.08")
        statusReport.orgnlGrpInfAndSts = orgnlGrpInf

        results.forEach { result ->
            val txInfAndSts = PaymentTransaction110()
            txInfAndSts.setOrgnlInstrId(result.originalInstructionId)
            txInfAndSts.setOrgnlEndToEndId(result.originalEndToEndId)
            txInfAndSts.setOrgnlTxId(result.originalTransactionId)

            if (result.accepted) {
                // Use String constant — ExternalPaymentTransactionStatus1Code does not exist
                txInfAndSts.setTxSts(PaymentStatusCode.ACCEPTED)
            } else {
                txInfAndSts.setTxSts(PaymentStatusCode.REJECTED)
                if (result.rejectionReasonCode != null) {
                    val stsRsnInf = StatusReasonInformation12()
                    val rsn = StatusReason6Choice()
                    rsn.setCd(result.rejectionReasonCode)
                    stsRsnInf.rsn = rsn
                    if (result.rejectionReasonDescription != null) {
                        stsRsnInf.addAddtlInf(result.rejectionReasonDescription)
                    }
                    txInfAndSts.addStsRsnInf(stsRsnInf)
                }
            }
            statusReport.addTxInfAndSts(txInfAndSts)
        }

        return mx.message()
    }

    fun buildAcceptance(
        originalMessageId: String, instructionId: String,
        endToEndId: String, transactionId: String
    ): String = buildResponse(originalMessageId, listOf(
        TransactionResult(instructionId, endToEndId, transactionId, accepted = true)
    ))

    fun buildRejection(
        originalMessageId: String, instructionId: String,
        endToEndId: String, transactionId: String,
        reasonCode: String, reasonDescription: String
    ): String = buildResponse(originalMessageId, listOf(
        TransactionResult(instructionId, endToEndId, transactionId,
            accepted = false, rejectionReasonCode = reasonCode,
            rejectionReasonDescription = reasonDescription)
    ))
}
