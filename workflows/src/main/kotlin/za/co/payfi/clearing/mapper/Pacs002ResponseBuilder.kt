package za.co.payfi.clearing.mapper

import net.corda.v5.base.annotations.CordaSerializable
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Builds ISO 20022 pacs.002.001.10 (Payment Status Report) XML responses.
 *
 * Uses JDK string building only — no Prowide or JAXB dependencies.
 * This avoids OSGi sandbox resolution failures in Corda 5.2.
 *
 * Output XML conforms to namespace:
 *   urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10
 */
class Pacs002ResponseBuilder {

    companion object {
        private const val NS_PACS002 = "urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10"
        private val ISO_DT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
            .withZone(ZoneOffset.UTC)
    }

    /**
     * ISO 20022 payment transaction status codes as String constants.
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
        val sb = StringBuilder()
        sb.append("""<Document xmlns="$NS_PACS002">""")
        sb.append("<FIToFIPmtStsRpt>")

        // GrpHdr
        sb.append("<GrpHdr>")
        // Deterministic MsgId derived from inbound originalMessageId.
        // On flow replay, this produces the same output MsgId.
        sb.append("<MsgId>").append(xmlEscape("PSR-$originalMessageId")).append("</MsgId>")
        sb.append("<CreDtTm>").append(ISO_DT_FORMAT.format(Instant.now())).append("</CreDtTm>")
        sb.append("</GrpHdr>")

        // OrgnlGrpInfAndSts
        sb.append("<OrgnlGrpInfAndSts>")
        sb.append("<OrgnlMsgId>").append(xmlEscape(originalMessageId)).append("</OrgnlMsgId>")
        sb.append("<OrgnlMsgNmId>pacs.008.001.08</OrgnlMsgNmId>")
        sb.append("</OrgnlGrpInfAndSts>")

        // TxInfAndSts per result
        results.forEach { result ->
            sb.append("<TxInfAndSts>")
            sb.append("<OrgnlInstrId>").append(xmlEscape(result.originalInstructionId)).append("</OrgnlInstrId>")
            sb.append("<OrgnlEndToEndId>").append(xmlEscape(result.originalEndToEndId)).append("</OrgnlEndToEndId>")
            sb.append("<OrgnlTxId>").append(xmlEscape(result.originalTransactionId)).append("</OrgnlTxId>")

            if (result.accepted) {
                sb.append("<TxSts>").append(PaymentStatusCode.ACCEPTED).append("</TxSts>")
            } else {
                sb.append("<TxSts>").append(PaymentStatusCode.REJECTED).append("</TxSts>")
                if (result.rejectionReasonCode != null) {
                    sb.append("<StsRsnInf>")
                    sb.append("<Rsn><Cd>").append(xmlEscape(result.rejectionReasonCode)).append("</Cd></Rsn>")
                    if (result.rejectionReasonDescription != null) {
                        sb.append("<AddtlInf>").append(xmlEscape(result.rejectionReasonDescription)).append("</AddtlInf>")
                    }
                    sb.append("</StsRsnInf>")
                }
            }
            sb.append("</TxInfAndSts>")
        }

        sb.append("</FIToFIPmtStsRpt>")
        sb.append("</Document>")
        return sb.toString()
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

    /**
     * Escape XML special characters in text content.
     */
    private fun xmlEscape(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
