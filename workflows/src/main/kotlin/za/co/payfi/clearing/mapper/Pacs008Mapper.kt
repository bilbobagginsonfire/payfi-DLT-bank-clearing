package za.co.payfi.clearing.mapper

import za.co.payfi.clearing.states.*
import za.co.payfi.clearing.persistence.PaymentMessageMetadata
import java.io.StringReader
import java.io.StringWriter
import java.math.BigDecimal
import java.security.MessageDigest
import java.security.PublicKey
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.xml.XMLConstants
import javax.xml.namespace.NamespaceContext
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import org.xml.sax.InputSource

/**
 * Maps between ISO 20022 pacs.008.001.08 XML and PaymentInstructionState.
 *
 * Uses JDK DOM + XPath parsing only — no third-party libraries.
 * This avoids the OSGi sandbox resolution failures caused by Prowide's
 * transitive JAXB/javax.activation dependencies.
 *
 * Handles batch messages: a single pacs.008 can contain multiple CdtTrfTxInf
 * blocks. Each block becomes a separate PaymentInstructionState on the ledger.
 */
class Pacs008Mapper {

    companion object {
        private const val NS_PACS008 = "urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08"
        private const val NS_PREFIX = "p"
    }

    /**
     * Namespace context for pacs.008.001.08 XPath queries.
     */
    private class Pacs008NamespaceContext : NamespaceContext {
        override fun getNamespaceURI(prefix: String): String = when (prefix) {
            NS_PREFIX -> NS_PACS008
            else -> XMLConstants.NULL_NS_URI
        }
        override fun getPrefix(namespaceURI: String): String? = null
        override fun getPrefixes(namespaceURI: String): MutableIterator<String> =
            mutableListOf<String>().iterator()
    }

    // =========================================================================
    // Public interface — MUST be preserved (flows call these)
    // =========================================================================

    fun fromXml(xml: String, participantKeys: List<PublicKey>): List<PaymentInstructionState> {
        val doc = parseXml(xml)
        val xpath = newXPath()

        // Validate root structure
        val fiToFi = xpath.evaluate("//p:FIToFICstmrCdtTrf", doc, XPathConstants.NODE) as? Node
            ?: throw IllegalArgumentException("Missing FIToFICstmrCdtTrf element")

        val txNodes = xpath.evaluate(
            "//p:FIToFICstmrCdtTrf/p:CdtTrfTxInf", doc, XPathConstants.NODESET
        ) as NodeList

        if (txNodes.length == 0) {
            throw IllegalArgumentException("pacs.008 must contain at least one CdtTrfTxInf")
        }

        return (0 until txNodes.length).map { i ->
            mapTransaction(txNodes.item(i) as Element, xpath, participantKeys)
        }
    }

    fun extractMetadata(xml: String): PaymentMessageMetadata {
        val doc = parseXml(xml)
        val xpath = newXPath()

        val msgId = xpath.evaluate("//p:GrpHdr/p:MsgId/text()", doc) ?: ""
        val creDtTm = xpath.evaluate("//p:GrpHdr/p:CreDtTm/text()", doc) ?: ""
        val nbOfTxs = xpath.evaluate("//p:GrpHdr/p:NbOfTxs/text()", doc) ?: "1"

        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(xml.toByteArray(Charsets.UTF_8))
        val hashHex = hashBytes.joinToString("") { "%02x".format(it) }

        val creationTime = try {
            ZonedDateTime.parse(creDtTm, DateTimeFormatter.ISO_DATE_TIME).toInstant()
        } catch (e: Exception) {
            try {
                // Try parsing without timezone (common in pacs.008: "2026-03-22T10:00:00")
                java.time.LocalDateTime.parse(creDtTm, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .toInstant(java.time.ZoneOffset.UTC)
            } catch (e2: Exception) {
                Instant.now()
            }
        }

        return PaymentMessageMetadata(
            originalMessageId = msgId,
            messageCreationTime = creationTime,
            numberOfTransactions = nbOfTxs.toIntOrNull() ?: 1,
            messageDigest = hashHex,
            receivedAt = Instant.now()
        )
    }

    fun toXml(state: PaymentInstructionState): String {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        // XXE prevention — required for Corda 5.2 security compliance
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        val builder = factory.newDocumentBuilder()
        val doc = builder.newDocument()

        // Root: Document
        val docElem = doc.createElementNS(NS_PACS008, "Document")
        docElem.setAttribute("xmlns", NS_PACS008)
        doc.appendChild(docElem)

        // FIToFICstmrCdtTrf
        val fiToFi = appendElement(doc, docElem, "FIToFICstmrCdtTrf")

        // GrpHdr
        val grpHdr = appendElement(doc, fiToFi, "GrpHdr")
        appendTextElement(doc, grpHdr, "MsgId", "MSG-${state.stateId}")
        appendTextElement(doc, grpHdr, "CreDtTm", Instant.now().toString())
        appendTextElement(doc, grpHdr, "NbOfTxs", "1")
        val sttlmInf = appendElement(doc, grpHdr, "SttlmInf")
        appendTextElement(doc, sttlmInf, "SttlmMtd", "CLRG")

        // CdtTrfTxInf
        val txInf = appendElement(doc, fiToFi, "CdtTrfTxInf")

        // PmtId
        val pmtId = appendElement(doc, txInf, "PmtId")
        appendTextElement(doc, pmtId, "InstrId", state.instructionId)
        appendTextElement(doc, pmtId, "EndToEndId", state.endToEndId)
        appendTextElement(doc, pmtId, "TxId", state.transactionId)

        // IntrBkSttlmAmt
        val amt = appendTextElement(doc, txInf, "IntrBkSttlmAmt", state.amount.toPlainString())
        amt.setAttribute("Ccy", state.currency)

        // IntrBkSttlmDt
        appendTextElement(doc, txInf, "IntrBkSttlmDt", state.settlementDate.toString())

        // Dbtr
        val dbtr = appendElement(doc, txInf, "Dbtr")
        appendTextElement(doc, dbtr, "Nm", state.debtorName)

        // Debtor Address
        val addr = state.debtorAddress
        if (addr != null) {
            val pstlAdr = appendElement(doc, dbtr, "PstlAdr")
            if (!addr.streetName.isNullOrBlank()) appendTextElement(doc, pstlAdr, "StrtNm", addr.streetName ?: "")
            if (!addr.buildingNumber.isNullOrBlank()) appendTextElement(doc, pstlAdr, "BldgNb", addr.buildingNumber ?: "")
            if (!addr.postCode.isNullOrBlank()) appendTextElement(doc, pstlAdr, "PstCd", addr.postCode ?: "")
            if (!addr.townName.isNullOrBlank()) appendTextElement(doc, pstlAdr, "TwnNm", addr.townName ?: "")
            if (!addr.country.isNullOrBlank()) appendTextElement(doc, pstlAdr, "Ctry", addr.country ?: "")
        }

        // Debtor Id
        val dbtrIdElem = appendElement(doc, dbtr, "Id")
        when (state.debtorIdType) {
            DebtorIdType.SA_NATIONAL_ID, DebtorIdType.PASSPORT, DebtorIdType.UNIQUE_CUSTOMER_ID -> {
                val prvtId = appendElement(doc, dbtrIdElem, "PrvtId")
                val othr = appendElement(doc, prvtId, "Othr")
                appendTextElement(doc, othr, "Id", state.debtorIdNumber)
                val schmeNm = appendElement(doc, othr, "SchmeNm")
                appendTextElement(doc, schmeNm, "Cd", when (state.debtorIdType) {
                    DebtorIdType.SA_NATIONAL_ID -> "NIDN"
                    DebtorIdType.PASSPORT -> "CCPT"
                    DebtorIdType.UNIQUE_CUSTOMER_ID -> "CUST"
                    else -> "NIDN"
                })
            }
            DebtorIdType.BUSINESS_REGISTRATION_ID -> {
                val orgId = appendElement(doc, dbtrIdElem, "OrgId")
                val othr = appendElement(doc, orgId, "Othr")
                appendTextElement(doc, othr, "Id", state.debtorIdNumber)
            }
        }

        // DbtrAcct
        val dbtrAcct = appendElement(doc, txInf, "DbtrAcct")
        val dbtrAcctId = appendElement(doc, dbtrAcct, "Id")
        val dbtrOthr = appendElement(doc, dbtrAcctId, "Othr")
        appendTextElement(doc, dbtrOthr, "Id", state.debtorAccount)

        // DbtrAgt
        val dbtrAgt = appendElement(doc, txInf, "DbtrAgt")
        val dbtrFinInstn = appendElement(doc, dbtrAgt, "FinInstnId")
        val dbtrClrSys = appendElement(doc, dbtrFinInstn, "ClrSysMmbId")
        appendTextElement(doc, dbtrClrSys, "MmbId", state.debtorAgentBranchCode)

        // Cdtr
        val cdtr = appendElement(doc, txInf, "Cdtr")
        appendTextElement(doc, cdtr, "Nm", state.creditorName)

        // CdtrAcct
        val cdtrAcct = appendElement(doc, txInf, "CdtrAcct")
        val cdtrAcctId = appendElement(doc, cdtrAcct, "Id")
        val cdtrOthr = appendElement(doc, cdtrAcctId, "Othr")
        appendTextElement(doc, cdtrOthr, "Id", state.creditorAccount)

        // CdtrAgt
        val cdtrAgt = appendElement(doc, txInf, "CdtrAgt")
        val cdtrFinInstn = appendElement(doc, cdtrAgt, "FinInstnId")
        val cdtrClrSys = appendElement(doc, cdtrFinInstn, "ClrSysMmbId")
        appendTextElement(doc, cdtrClrSys, "MmbId", state.creditorAgentBranchCode)

        // RmtInf
        if (!state.remittanceInfo.isNullOrBlank()) { val rmtInfo = state.remittanceInfo ?: ""
            val rmtInf = appendElement(doc, txInf, "RmtInf")
            appendTextElement(doc, rmtInf, "Ustrd", rmtInfo)
        }

        return serializeDocument(doc)
    }

    // =========================================================================
    // Private — XML parsing helpers
    // =========================================================================

    private fun parseXml(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        // XXE prevention — required for Corda 5.2 security compliance
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        val builder = factory.newDocumentBuilder()
        return builder.parse(InputSource(StringReader(xml)))
    }

    private fun newXPath(): javax.xml.xpath.XPath {
        val xpath = XPathFactory.newInstance().newXPath()
        xpath.namespaceContext = Pacs008NamespaceContext()
        return xpath
    }

    private fun mapTransaction(
        txNode: Element,
        xpath: javax.xml.xpath.XPath,
        participantKeys: List<PublicKey>
    ): PaymentInstructionState {

        // Payment Identification
        val instrId = xpathText(xpath, "p:PmtId/p:InstrId", txNode)
        val endToEndId = xpathText(xpath, "p:PmtId/p:EndToEndId", txNode)
        val txId = xpathText(xpath, "p:PmtId/p:TxId", txNode)

        if (instrId.isBlank()) throw IllegalArgumentException("Missing PmtId/InstrId")

        // Amount and Currency
        val amtNode = xpath.evaluate("p:IntrBkSttlmAmt", txNode, XPathConstants.NODE) as? Element
            ?: throw IllegalArgumentException("Missing IntrBkSttlmAmt")
        val currency = amtNode.getAttribute("Ccy") ?: ""
        if (currency != "ZAR") throw IllegalArgumentException("AM03: Currency must be ZAR, got $currency")
        val amount = try {
            BigDecimal(amtNode.textContent.trim())
        } catch (e: Exception) {
            BigDecimal.ZERO
        }

        // Settlement Date
        val sttlmDtStr = xpathText(xpath, "p:IntrBkSttlmDt", txNode)
        val settlementDate = if (sttlmDtStr.isNotBlank()) {
            try { LocalDate.parse(sttlmDtStr.substring(0, 10)) } catch (e: Exception) { LocalDate.now() }
        } else {
            LocalDate.now()
        }

        // Debtor
        val debtorName = xpathText(xpath, "p:Dbtr/p:Nm", txNode)
        val (debtorIdType, debtorIdNumber) = extractDebtorId(xpath, txNode)

        // Debtor Address
        val debtorAddress = extractAddress(xpath, "p:Dbtr/p:PstlAdr", txNode)

        // Debtor Account and Agent
        val debtorAccount = xpathText(xpath, "p:DbtrAcct/p:Id/p:Othr/p:Id", txNode)
        val debtorAgentBranchCode = xpathText(xpath, "p:DbtrAgt/p:FinInstnId/p:ClrSysMmbId/p:MmbId", txNode)

        // Creditor
        val creditorName = xpathText(xpath, "p:Cdtr/p:Nm", txNode)
        val creditorAccount = xpathText(xpath, "p:CdtrAcct/p:Id/p:Othr/p:Id", txNode)
        val creditorAgentBranchCode = xpathText(xpath, "p:CdtrAgt/p:FinInstnId/p:ClrSysMmbId/p:MmbId", txNode)

        // Remittance and Purpose
        val remittanceInfo = xpathText(xpath, "p:RmtInf/p:Ustrd", txNode).ifBlank { null }
        val purposeCode = xpathText(xpath, "p:Purp/p:Cd", txNode).ifBlank { null }

        // Fee calculation using pilot constants
        val feeApplicable = amount > PilotFeeConstants.THRESHOLD_AMOUNT
        val feeAmount = if (feeApplicable) PilotFeeConstants.FEE_AMOUNT else BigDecimal.ZERO
        val feeTaxAmount = if (feeApplicable) PilotFeeConstants.TAX_AMOUNT else BigDecimal.ZERO

        return PaymentInstructionState(
            // Deterministic stateId derived from unique business keys.
            // On flow retry, the mapper regenerates the same stateId, ensuring
            // downstream persist() dedup IDs remain stable.
            stateId = UUID.nameUUIDFromBytes(
                "${instrId}-${txId}".toByteArray(Charsets.UTF_8)
            ),
            instructionId = instrId,
            endToEndId = endToEndId,
            transactionId = txId,
            amount = amount,
            currency = currency,
            settlementDate = settlementDate,
            debtorName = debtorName,
            debtorIdType = debtorIdType,
            debtorIdNumber = debtorIdNumber,
            debtorAddress = debtorAddress,
            debtorAccount = debtorAccount,
            debtorAgentBranchCode = debtorAgentBranchCode,
            creditorName = creditorName,
            creditorAccount = creditorAccount,
            creditorAgentBranchCode = creditorAgentBranchCode,
            remittanceInfo = remittanceInfo,
            purposeCode = purposeCode,
            status = PaymentStatus.SUBMITTED,
            feeApplicable = feeApplicable,
            feeAmount = feeAmount,
            feeTaxAmount = feeTaxAmount,
            feePayerBranchCode = creditorAgentBranchCode,
            participantKeys = participantKeys
        )
    }

    private fun extractDebtorId(
        xpath: javax.xml.xpath.XPath,
        txNode: Element
    ): Pair<DebtorIdType, String> {
        // Try PrvtId/Othr first (personal identification)
        val prvtIdNumber = xpathText(xpath, "p:Dbtr/p:Id/p:PrvtId/p:Othr/p:Id", txNode)
        if (prvtIdNumber.isNotBlank()) {
            val schemeCd = xpathText(xpath, "p:Dbtr/p:Id/p:PrvtId/p:Othr/p:SchmeNm/p:Cd", txNode)
            return when (schemeCd) {
                "NIDN" -> Pair(DebtorIdType.SA_NATIONAL_ID, prvtIdNumber)
                "CCPT" -> Pair(DebtorIdType.PASSPORT, prvtIdNumber)
                "CUST" -> Pair(DebtorIdType.UNIQUE_CUSTOMER_ID, prvtIdNumber)
                else -> Pair(DebtorIdType.SA_NATIONAL_ID, prvtIdNumber)
            }
        }

        // Try OrgId/Othr (organisation identification)
        val orgIdNumber = xpathText(xpath, "p:Dbtr/p:Id/p:OrgId/p:Othr/p:Id", txNode)
        if (orgIdNumber.isNotBlank()) {
            return Pair(DebtorIdType.BUSINESS_REGISTRATION_ID, orgIdNumber)
        }

        return Pair(DebtorIdType.SA_NATIONAL_ID, "")
    }

    private fun extractAddress(
        xpath: javax.xml.xpath.XPath,
        basePath: String,
        context: Element
    ): StructuredAddress? {
        val addrNode = xpath.evaluate(basePath, context, XPathConstants.NODE) as? Element
            ?: return null

        val streetName = xpathText(xpath, "p:StrtNm", addrNode).ifBlank { null }
        val buildingNumber = xpathText(xpath, "p:BldgNb", addrNode).ifBlank { null }
        val postCode = xpathText(xpath, "p:PstCd", addrNode).ifBlank { null }
        val townName = xpathText(xpath, "p:TwnNm", addrNode).ifBlank { null }
        val country = xpathText(xpath, "p:Ctry", addrNode).ifBlank { null }

        return if (streetName != null || buildingNumber != null || townName != null || country != null) {
            StructuredAddress(
                streetName = streetName,
                buildingNumber = buildingNumber,
                postCode = postCode,
                townName = townName,
                country = country
            )
        } else {
            null
        }
    }

    /**
     * Evaluate XPath returning text content, or empty string if not found.
     */
    private fun xpathText(
        xpath: javax.xml.xpath.XPath,
        expression: String,
        context: Any
    ): String {
        return try {
            xpath.evaluate("$expression/text()", context)?.trim() ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    // =========================================================================
    // Private — XML building helpers
    // =========================================================================

    private fun appendElement(doc: Document, parent: Element, localName: String): Element {
        val elem = doc.createElementNS(NS_PACS008, localName)
        parent.appendChild(elem)
        return elem
    }

    private fun appendTextElement(doc: Document, parent: Element, localName: String, text: String): Element {
        val elem = doc.createElementNS(NS_PACS008, localName)
        elem.textContent = text
        parent.appendChild(elem)
        return elem
    }

    private fun serializeDocument(doc: Document): String {
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        transformer.setOutputProperty(OutputKeys.INDENT, "no")
        val writer = StringWriter()
        transformer.transform(DOMSource(doc), StreamResult(writer))
        return writer.toString()
    }
}
