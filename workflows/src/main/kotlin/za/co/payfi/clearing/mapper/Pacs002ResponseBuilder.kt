package za.co.payfi.clearing.mapper

import com.prowidesoftware.swift.model.mx.MxPacs00200110
import com.prowidesoftware.swift.model.mx.dic.*
import net.corda.v5.base.annotations.CordaSerializable
import java.time.Instant
import java.util.UUID
import javax.xml.datatype.DatatypeFactory

/**
 * Builds ISO 20022 pacs.002 (Payment Status Report) XML responses.
 *
 * NOTE: Prowide class names are version-specific. Verify MxPacs00200110,
 * FIToFIPaymentStatusReportV10, GroupHeader91, PaymentTransaction110,
 * ExternalPaymentTransactionStatus1Code against actual JAR.
 */
class Pacs002ResponseBuilder {

    @CordaSerializable
    data class TransactionResult(
        val originalInstructionId: String,
        val originalEndToEndId: String,
        val originalTransactionId: String,
        val accepted: Boolean,
        val rejectionReasonCode: String? = null,
        val rejectionReasonDescription: String? = null
    )

    fun buildResponse(originalMessageId: String, results: List<TransactionResult>): String {
        val mx = MxPacs00200110()
        val statusReport = FIToFIPaymentStatusReportV10()
        mx.fiToFIPmtStsRpt = statusReport

        val grpHdr = GroupHeader91()
        grpHdr.msgId = "PSR-${UUID.randomUUID().toString().substring(0, 8)}"
        val factory = DatatypeFactory.newInstance()
        grpHdr.creDtTm = factory.newXMLGregorianCalendar(
            java.util.GregorianCalendar.getInstance().apply {
                timeInMillis = Instant.now().toEpochMilli()
            }
        )
        statusReport.grpHdr = grpHdr

        val orgnlGrpInf = OriginalGroupHeader17()
        orgnlGrpInf.orgnlMsgId = originalMessageId
        orgnlGrpInf.orgnlMsgNmId = "pacs.008.001.08"
        statusReport.orgnlGrpInfAndSts = orgnlGrpInf

        results.forEach { result ->
            val txInfAndSts = PaymentTransaction110()
            txInfAndSts.orgnlInstrId = result.originalInstructionId
            txInfAndSts.orgnlEndToEndId = result.originalEndToEndId
            txInfAndSts.orgnlTxId = result.originalTransactionId

            if (result.accepted) {
                txInfAndSts.txSts = ExternalPaymentTransactionStatus1Code.ACCP.name
            } else {
                txInfAndSts.txSts = ExternalPaymentTransactionStatus1Code.RJCT.name
                if (result.rejectionReasonCode != null) {
                    val stsRsnInf = StatusReasonInformation12()
                    val rsn = StatusReason6Choice()
                    rsn.cd = result.rejectionReasonCode
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
