package za.co.payfi.clearing.mapper

import com.prowidesoftware.swift.model.mx.MxPacs00800108
import com.prowidesoftware.swift.model.mx.dic.*
import za.co.payfi.clearing.states.*
import za.co.payfi.clearing.persistence.PaymentMessageMetadata
import java.math.BigDecimal
import java.security.MessageDigest
import java.security.PublicKey
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Maps between ISO 20022 pacs.008.001.08 XML and PaymentInstructionState.
 *
 * Handles batch messages: a single pacs.008 can contain multiple CdtTrfTxInf
 * blocks. Each block becomes a separate PaymentInstructionState on the ledger.
 *
 * Uses Prowide pw-iso20022 SRU2023-9.4.7 (open source) for XML parsing.
 *
 * IMPORTANT: Prowide dictionary objects use builder-pattern setters that return
 * `this`. In Kotlin, some setters may NOT be recognized as property setters.
 * When `obj.field = value` produces "Variable expected", use the explicit
 * Java setter form `obj.setField(value)` instead.
 */
class Pacs008Mapper {

    fun fromXml(xml: String, participantKeys: List<PublicKey>): List<PaymentInstructionState> {
        val mx = MxPacs00800108.parse(xml)
            ?: throw IllegalArgumentException("Failed to parse pacs.008 XML")

        val fiToFi = mx.fiToFICstmrCdtTrf
            ?: throw IllegalArgumentException("Missing FIToFICstmrCdtTrf element")

        val transactions = fiToFi.cdtTrfTxInf
            ?: throw IllegalArgumentException("Missing CdtTrfTxInf elements")

        if (transactions.isEmpty()) {
            throw IllegalArgumentException("pacs.008 must contain at least one CdtTrfTxInf")
        }

        return transactions.map { txInf -> mapTransaction(txInf, participantKeys) }
    }

    fun extractMetadata(xml: String): PaymentMessageMetadata {
        val mx = MxPacs00800108.parse(xml)
            ?: throw IllegalArgumentException("Failed to parse pacs.008 XML")

        val grpHdr = mx.fiToFICstmrCdtTrf?.grpHdr
            ?: throw IllegalArgumentException("Missing GrpHdr element")

        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(xml.toByteArray(Charsets.UTF_8))
        val hashHex = hashBytes.joinToString("") { "%02x".format(it) }

        return PaymentMessageMetadata(
            originalMessageId = grpHdr.msgId ?: "",
            messageCreationTime = grpHdr.creDtTm?.toGregorianCalendar()?.toInstant() ?: Instant.now(),
            numberOfTransactions = grpHdr.nbOfTxs?.toIntOrNull() ?: 1,
            messageDigest = hashHex,
            receivedAt = Instant.now()
        )
    }

    fun toXml(state: PaymentInstructionState): String {
        val mx = MxPacs00800108()
        val fiToFi = FIToFICustomerCreditTransferV08()
        mx.fiToFICstmrCdtTrf = fiToFi

        // Group Header — including mandatory CreDtTm
        val grpHdr = GroupHeader93()
        // Use explicit Java setter in case builder pattern causes "Variable expected"
        grpHdr.setMsgId("MSG-${state.stateId}")
        grpHdr.setNbOfTxs("1")

        // CreDtTm is MANDATORY per ISO 20022 schema
        val factory = javax.xml.datatype.DatatypeFactory.newInstance()
        grpHdr.creDtTm = factory.newXMLGregorianCalendar(
            java.util.GregorianCalendar.getInstance().apply {
                timeInMillis = Instant.now().toEpochMilli()
            }
        )

        fiToFi.grpHdr = grpHdr

        // Settlement Information
        val sttlmInf = SettlementInstruction7()
        sttlmInf.sttlmMtd = SettlementMethod1Code.CLRG
        grpHdr.sttlmInf = sttlmInf

        // Credit Transfer Transaction
        val txInf = CreditTransferTransaction39()
        fiToFi.addCdtTrfTxInf(txInf)

        // Payment Identification
        val pmtId = PaymentIdentification7()
        pmtId.setInstrId(state.instructionId)
        pmtId.setEndToEndId(state.endToEndId)
        pmtId.setTxId(state.transactionId)
        txInf.pmtId = pmtId

        // Amount
        val amt = ActiveCurrencyAndAmount()
        amt.value = state.amount
        amt.setCcy(state.currency)
        txInf.intrBkSttlmAmt = amt

        // Settlement Date
        txInf.intrBkSttlmDt = factory.newXMLGregorianCalendar(state.settlementDate.toString())

        // Debtor
        val dbtr = PartyIdentification135()
        dbtr.setNm(state.debtorName)
        txInf.dbtr = dbtr
        setDebtorId(dbtr, state.debtorIdType, state.debtorIdNumber)

        // Debtor Address — use explicit local variable to avoid smart cast across modules
        val addr = state.debtorAddress
        if (addr != null) {
            val pstlAdr = PostalAddress24()
            pstlAdr.setStrtNm(addr.streetName)
            pstlAdr.setBldgNb(addr.buildingNumber)
            pstlAdr.setPstCd(addr.postCode)
            pstlAdr.setTwnNm(addr.townName)
            pstlAdr.setCtry(addr.country)
            dbtr.pstlAdr = pstlAdr
        }

        // Debtor Account
        val dbtrAcct = CashAccount38()
        val dbtrAcctId = AccountIdentification4Choice()
        val dbtrOthr = GenericAccountIdentification1()
        dbtrOthr.setId(state.debtorAccount)
        dbtrAcctId.othr = dbtrOthr
        dbtrAcct.id = dbtrAcctId
        txInf.dbtrAcct = dbtrAcct

        // Debtor Agent
        val dbtrAgt = BranchAndFinancialInstitutionIdentification6()
        val dbtrFinInstn = FinancialInstitutionIdentification18()
        val dbtrClrSys = ClearingSystemMemberIdentification2()
        dbtrClrSys.setMmbId(state.debtorAgentBranchCode)
        dbtrFinInstn.clrSysMmbId = dbtrClrSys
        dbtrAgt.finInstnId = dbtrFinInstn
        txInf.dbtrAgt = dbtrAgt

        // Creditor
        val cdtr = PartyIdentification135()
        cdtr.setNm(state.creditorName)
        txInf.cdtr = cdtr

        // Creditor Account
        val cdtrAcct = CashAccount38()
        val cdtrAcctId = AccountIdentification4Choice()
        val cdtrOthr = GenericAccountIdentification1()
        cdtrOthr.setId(state.creditorAccount)
        cdtrAcctId.othr = cdtrOthr
        cdtrAcct.id = cdtrAcctId
        txInf.cdtrAcct = cdtrAcct

        // Creditor Agent
        val cdtrAgt = BranchAndFinancialInstitutionIdentification6()
        val cdtrFinInstn = FinancialInstitutionIdentification18()
        val cdtrClrSys = ClearingSystemMemberIdentification2()
        cdtrClrSys.setMmbId(state.creditorAgentBranchCode)
        cdtrFinInstn.clrSysMmbId = cdtrClrSys
        cdtrAgt.finInstnId = cdtrFinInstn
        txInf.cdtrAgt = cdtrAgt

        // Remittance
        if (!state.remittanceInfo.isNullOrBlank()) {
            val rmtInf = RemittanceInformation16()
            rmtInf.addUstrd(state.remittanceInfo)
            txInf.rmtInf = rmtInf
        }

        return mx.message()
    }

    // --- Private ---

    private fun mapTransaction(
        txInf: CreditTransferTransaction39,
        participantKeys: List<PublicKey>
    ): PaymentInstructionState {

        val pmtId = txInf.pmtId ?: throw IllegalArgumentException("Missing PmtId")

        val sttlmAmt = txInf.intrBkSttlmAmt ?: throw IllegalArgumentException("Missing IntrBkSttlmAmt")
        val currency = sttlmAmt.ccy ?: ""
        if (currency != "ZAR") throw IllegalArgumentException("AM03: Currency must be ZAR, got $currency")
        val amount = sttlmAmt.value ?: BigDecimal.ZERO

        val settlementDate = txInf.intrBkSttlmDt?.let {
            LocalDate.parse(it.toString().substring(0, 10))
        } ?: LocalDate.now()

        val dbtr = txInf.dbtr
        val (debtorIdType, debtorIdNumber) = extractDebtorId(dbtr)

        // Use local variable for debtorAddress to avoid smart cast across modules (Issue 11)
        val debtorPstlAdr = dbtr?.pstlAdr
        val debtorAddress = if (debtorPstlAdr != null) {
            StructuredAddress(
                streetName = debtorPstlAdr.strtNm,
                buildingNumber = debtorPstlAdr.bldgNb,
                postCode = debtorPstlAdr.pstCd,
                townName = debtorPstlAdr.twnNm,
                country = debtorPstlAdr.ctry
            )
        } else {
            null
        }

        val creditorAgentBranchCode = txInf.cdtrAgt?.finInstnId?.clrSysMmbId?.mmbId ?: ""

        // Fee calculation using pilot constants
        val feeApplicable = amount > PilotFeeConstants.THRESHOLD_AMOUNT
        val feeAmount = if (feeApplicable) PilotFeeConstants.FEE_AMOUNT else BigDecimal.ZERO
        val feeTaxAmount = if (feeApplicable) PilotFeeConstants.TAX_AMOUNT else BigDecimal.ZERO

        return PaymentInstructionState(
            // Deterministic stateId derived from unique business keys.
            // On flow retry, the mapper regenerates the same stateId, ensuring
            // downstream persist() dedup IDs remain stable.
            stateId = UUID.nameUUIDFromBytes(
                "${pmtId.instrId}-${pmtId.txId}".toByteArray(Charsets.UTF_8)
            ),
            instructionId = pmtId.instrId ?: "",
            endToEndId = pmtId.endToEndId ?: "",
            transactionId = pmtId.txId ?: "",
            amount = amount,
            currency = currency,
            settlementDate = settlementDate,
            debtorName = dbtr?.nm ?: "",
            debtorIdType = debtorIdType,
            debtorIdNumber = debtorIdNumber,
            debtorAddress = debtorAddress,
            debtorAccount = txInf.dbtrAcct?.id?.othr?.id ?: "",
            debtorAgentBranchCode = txInf.dbtrAgt?.finInstnId?.clrSysMmbId?.mmbId ?: "",
            creditorName = txInf.cdtr?.nm ?: "",
            creditorAccount = txInf.cdtrAcct?.id?.othr?.id ?: "",
            creditorAgentBranchCode = creditorAgentBranchCode,
            remittanceInfo = txInf.rmtInf?.ustrd?.firstOrNull(),
            purposeCode = txInf.purp?.cd,
            status = PaymentStatus.SUBMITTED,
            feeApplicable = feeApplicable,
            feeAmount = feeAmount,
            feeTaxAmount = feeTaxAmount,
            feePayerBranchCode = creditorAgentBranchCode,
            participantKeys = participantKeys
        )
    }

    private fun extractDebtorId(dbtr: PartyIdentification135?): Pair<DebtorIdType, String> {
        val id = dbtr?.id ?: return Pair(DebtorIdType.SA_NATIONAL_ID, "")

        val prvtId = id.prvtId
        if (prvtId != null) {
            val othr = prvtId.othr?.firstOrNull()
            if (othr != null) {
                val idNumber = othr.id ?: ""
                return when (othr.schmeNm?.cd ?: "") {
                    "NIDN" -> Pair(DebtorIdType.SA_NATIONAL_ID, idNumber)
                    "CCPT" -> Pair(DebtorIdType.PASSPORT, idNumber)
                    "CUST" -> Pair(DebtorIdType.UNIQUE_CUSTOMER_ID, idNumber)
                    else -> Pair(DebtorIdType.SA_NATIONAL_ID, idNumber)
                }
            }
        }

        val orgId = id.orgId
        if (orgId != null) {
            val othr = orgId.othr?.firstOrNull()
            if (othr != null) return Pair(DebtorIdType.BUSINESS_REGISTRATION_ID, othr.id ?: "")
        }

        return Pair(DebtorIdType.SA_NATIONAL_ID, "")
    }

    private fun setDebtorId(dbtr: PartyIdentification135, idType: DebtorIdType, idNumber: String) {
        val partyId = Party38Choice()
        dbtr.id = partyId

        when (idType) {
            DebtorIdType.SA_NATIONAL_ID, DebtorIdType.PASSPORT, DebtorIdType.UNIQUE_CUSTOMER_ID -> {
                val prvtId = PersonIdentification13()
                val othr = GenericPersonIdentification1()
                othr.setId(idNumber)
                val schmeNm = PersonIdentificationSchemeName1Choice()
                schmeNm.setCd(when (idType) {
                    DebtorIdType.SA_NATIONAL_ID -> "NIDN"
                    DebtorIdType.PASSPORT -> "CCPT"
                    DebtorIdType.UNIQUE_CUSTOMER_ID -> "CUST"
                    else -> "NIDN"
                })
                othr.schmeNm = schmeNm
                prvtId.addOthr(othr)
                partyId.prvtId = prvtId
            }
            DebtorIdType.BUSINESS_REGISTRATION_ID -> {
                val orgId = OrganisationIdentification29()
                val othr = GenericOrganisationIdentification1()
                othr.setId(idNumber)
                orgId.addOthr(othr)
                partyId.orgId = orgId
            }
        }
    }
}
