package za.co.payfi.clearing.flows

import net.corda.v5.application.flows.ClientRequestBody
import net.corda.v5.application.flows.ClientStartableFlow
import net.corda.v5.application.flows.CordaInject
import net.corda.v5.application.marshalling.JsonMarshallingService
import net.corda.v5.application.persistence.PersistenceService
import net.corda.v5.base.annotations.Suspendable
import za.co.payfi.clearing.states.PaymentMessageMetadata

/**
 * QueryPaymentStatesFlow
 *
 * Called by the frontend on each vnode (BankBlue, BankTurquoise, SARB) to retrieve
 * all PaymentMessageMetadata records from that vnode's persistence layer.
 *
 * Returns a JSON array. Each element matches the schema expected by the frontend's
 * mapApiState() function.
 *
 * Flow class name: za.co.payfi.clearing.flows.QueryPaymentStatesFlow
 * Request body: {} (empty — no parameters needed)
 *
 * PREREQUISITE: The PaymentMessageMetadata entity must have a @NamedQuery:
 *
 *   @NamedQuery(
 *       name = "PaymentMessageMetadata.findAll",
 *       query = "SELECT p FROM PaymentMessageMetadata p ORDER BY p.createdAt DESC"
 *   )
 *
 * If your entity doesn't have this yet, add it to the @NamedQueries annotation on
 * the PaymentMessageMetadata class in contracts/src/main/kotlin/.../PaymentMessageMetadata.kt
 */
class QueryPaymentStatesFlow : ClientStartableFlow {

    @CordaInject
    lateinit var persistenceService: PersistenceService

    @CordaInject
    lateinit var jsonMarshallingService: JsonMarshallingService

    @Suspendable
    override fun call(requestBody: ClientRequestBody): String {
        // Query all payment metadata records from this vnode's persistence layer
        val results = persistenceService.query<PaymentMessageMetadata>(
            "PaymentMessageMetadata.findAll",
            PaymentMessageMetadata::class.java
        ).setLimit(500)
         .execute()
         .results

        // Map to frontend-expected JSON format
        val output = results.map { p ->
            mapOf(
                "msgId" to (p.msgId ?: ""),
                "instrId" to (p.instrId ?: ""),
                "endToEndId" to (p.endToEndId ?: ""),
                "txId" to (p.txId ?: ""),
                "stateId" to (p.stateId ?: ""),
                "cordaTxId" to (p.cordaTxId ?: ""),
                "amount" to (p.amount ?: "0.00"),
                "currency" to (p.currency ?: "ZAR"),
                "debtorName" to (p.debtorName ?: ""),
                "debtorId" to (p.debtorId ?: ""),
                "debtorAccount" to (p.debtorAccount ?: ""),
                "debtorBranch" to (p.debtorBranch ?: ""),
                "creditorName" to (p.creditorName ?: ""),
                "creditorAccount" to (p.creditorAccount ?: ""),
                "creditorBranch" to (p.creditorBranch ?: ""),
                "remittanceInfo" to (p.remittanceInfo ?: ""),
                "status" to (p.status ?: "CLEARED"),
                "pacs008Xml" to (p.pacs008Xml ?: ""),
                "pacs002Xml" to (p.pacs002Xml ?: ""),
                "pacs002Code" to (p.pacs002Code ?: ""),
                "feeAmount" to (p.feeAmount ?: "0.00"),
                "feeVat" to (p.feeVat ?: "0.00"),
                "clearingLatencyMs" to (p.clearingLatencyMs ?: 0),
                "sarbCopied" to (p.sarbCopied ?: false),
                "createdAt" to (p.createdAt?.toString() ?: "")
            )
        }

        return jsonMarshallingService.format(output)
    }
}
