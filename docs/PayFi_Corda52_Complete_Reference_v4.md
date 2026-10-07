# PayFi Corda 5.2 — Complete Deployment Reference

## Document Version: 4.0
## Date: 31 March 2026
## Platform: PayFi Interbank ZAR Clearing (ISO 20022) on Corda 5.2
## GitHub: [bilbobagginsonfire/payfi-DLT-bank-clearing](https://github.com/bilbobagginsonfire/payfi-DLT-bank-clearing) (branch: main)

---

## Changelog

**v4.1 (October 2026)** — Luhn fix:
- **Section 10/11:** `SouthAfricanIdentityValidator` now applies standard Luhn over all 13 digits. Previous demo and test IDs only passed the old, incorrect check and were replaced with synthetic IDs from `specs/generate_sa_ids.py`

**v4.0 (31 March 2026)** — Settlement, SARB backend, createdAt timestamp, full frontend integration:
- **Section 6:** Added `createdAt: Instant` field to `PaymentInstructionState` and `PaymentInstructionDto`; added `SarbTransactionRecord` JPA entity with `settled` flag
- **Section 8:** Added `sarb_transaction_records` table with `settled` column to SQL creation scripts
- **Section 9:** Added `@InitiatingFlow`/`@InitiatedBy` requirement for settlement flows to prerequisites
- **Section 10:** Complete frontend integration: real timestamps in timeline, pacs.008/pacs.002 XML reconstruction, 15s poll with panel-open detection, field mapping fixes, Luhn-corrected demo IDs, "Payment Reference" rename, Net Settlement button
- **Section 11:** Full settlement cycle confirmed: submit → settle → SARB mark settled → clean slate
- **Section 12:** Added Issues 17–18 (@InitiatingFlow missing, Luhn-invalid demo ID)
- **Section 14:** Updated — Option B (architecturally correct) is now IMPLEMENTED
- **Section 15 (NEW):** Settlement Architecture — Settle command, SettleTransactionsFlow, MarkSarbSettledFlow
- **Section 16 (NEW):** SARB Observer Backend — SarbTransactionRecord entity, responder persistence, QuerySarbTransactionsFlow
- **Appendix A:** Updated file layout with new flows and entities
- **Appendix C:** Updated current vnode hashes and v4 findings

**v3.0 (31 March 2026)** — DTO fix, frontend connection, SARB vault finding:
- **Section 6:** Added `PaymentInstructionDto` requirement — vault queries fail with BouncyCastle serialization errors without it
- **Section 8:** Improved automated grant script using `pg_user` lookup (eliminates manual username discovery)
- **Section 9:** Added `deployCpis` limitation — does NOT update existing vnodes for ANY code changes (always requires full cycle)
- **Section 10:** Added frontend field mapping fixes (`mapApiState` DTO field names), query flow class name fix
- **Section 11:** Confirmed test-033 end-to-end with a Luhn-valid SA ID (superseded in v4.1: now `8506150123089`); documented SARB vault query returns empty
- **Section 12:** Added Issues 13–16 (QueryPaymentInstructionsFlow serialization, SARB vault empty, connection pool exhaustion from polling, deployCpis vnode binding)
- **Section 14 (NEW):** SARB Observer Vault — architectural finding and resolution options
- **Appendix A:** Added `PaymentInstructionDto` to file layout

**v2.0 (30 March 2026)** — Post git-sync recovery session:
- **Section 3:** Added `cordapp-configuration` plugin requirement (MUST be applied, not `apply false`), `cordapp-cpb2` plugin in workflows, `platformVersion=50200` property, template PEM files list, `cpiUploadTimeout` increase, `flow-management-tool` removal from compose
- **Section 6:** Added critical warning about duplicate Entities.kt across modules
- **Section 8:** Updated DB connection user from `postgres` to `user`, added mandatory Step 3 (GRANT permissions to vnode DML/DDL users), added automated grant script
- **Section 9:** Updated full deploy workflow with grants step, added `cordapp-configuration` to prerequisites checklist
- **Section 11:** Fixed test SA ID number (superseded in v4.1, see Section 11)
- **Section 12:** Added Issues 7–12 (cordapp-configuration, duplicate entities, CPB2 plugin, PEM files, compose FlowManagementUI, table permissions)
- **Section 13:** Updated quick reference commands with correct DB user and grant commands
- **Appendix A:** Updated file layout to include config PEM files and compose

**v1.0 (27 March 2026)** — Initial deployment reference.

---

# Table of Contents

1. [Platform Overview](#1-platform-overview)
2. [Infrastructure & Environment](#2-infrastructure--environment)
3. [Template & Build System](#3-template--build-system)
4. [Compilation Fixes](#4-compilation-fixes)
5. [Prowide OSGi Removal](#5-prowide-osgi-removal)
6. [JPA Entity Configuration](#6-jpa-entity-configuration)
7. [Liquibase Migration Discovery](#7-liquibase-migration-discovery)
8. [Manual Database Table Creation](#8-manual-database-table-creation)
9. [Deployment Workflow](#9-deployment-workflow)
10. [Frontend & Nginx](#10-frontend--nginx)
11. [Testing](#11-testing)
12. [Known Issues & Workarounds](#12-known-issues--workarounds)
13. [Quick Reference Commands](#13-quick-reference-commands)
14. [SARB Observer Vault Architecture](#14-sarb-observer-vault-architecture)
15. [Settlement Architecture](#15-settlement-architecture)
16. [SARB Observer Backend](#16-sarb-observer-backend)

---

# 1. Platform Overview

PayFi is a System Operator platform under SARB Directive 2 of 2007 for South African interbank ZAR clearing using ISO 20022 messaging (pacs.008.001.08 inbound, pacs.002.001.10 response). The pilot network includes:

- **BankAlpha** — Participant bank (Johannesburg)
- **BankBeta** — Participant bank (Cape Town)
- **SARBObserver** — SARB regulatory observer node (Pretoria)
- **NotaryRep1** — Notary service (London)

The CorDapp processes ISO 20022 pacs.008 payment instructions, validates them against South African regulatory rules, records them on the Corda ledger, and returns pacs.002 status reports.

---

# 2. Infrastructure & Environment

## Server
- **Provider:** <HOSTING_PROVIDER>
- **OS:** Ubuntu 24
- **IP:** <SERVER_IP> (IPv4), <SERVER_IPV6> (IPv6)
- **k3s/Traefik:** Running on the server, intercepts ports 80/443 via iptables DNAT

## Software Versions
| Component | Version | Notes |
|-----------|---------|-------|
| Corda | 5.2.0.0 | Combined worker, Docker |
| Corda API | 5.2.0.52 | |
| Kotlin | 1.7.21 | |
| Java | 17 | eclipse-temurin:17-jdk |
| PostgreSQL | 14.10 | Docker container |
| Kafka | 7.6.0 (Confluent) | Docker container |
| Gradle | 8.2.1 | Wrapper in repo |
| CPK/CPB plugins | 7.0.4 | |
| Runtime Gradle plugin | 5.2.0.0 | net.corda.gradle.plugin |

## Critical Version Notes
- **Docker image `openjdk:17-jdk`** was deleted from Docker Hub. Use `eclipse-temurin:17-jdk` instead.
- **Corda API versions:** Use `5.2.0.52` for API, `5.2.0.0` for runtime plugin and notary plugins. Do NOT use `5.2.2.54` — those are from a different branch.
- **Prowide `pw-iso20022:SRU2023-9.4.7`** — version 9.4.8 does NOT exist on Maven Central.

---

# 3. Template & Build System

## Correct Template
Use `cordapp-template-kotlin` repo branch `release-V5.2` (NOT CSDE). All plugins resolve from public Maven repos — NO Artifactory credentials needed.

```bash
git clone -b release-V5.2 https://github.com/corda/cordapp-template-kotlin.git payfi-cordapp
```

## Git Remote Management
The server tracks two remotes independently. After cloning the R3 template, add it as a named remote for recovering template files:
```bash
git remote add r3template https://github.com/corda/cordapp-template-kotlin.git
git fetch r3template release-V5.2
```
This allows restoring template files lost during git operations:
```bash
git checkout r3template/release-V5.2 -- config/gradle-plugin-default-key.pem config/r3-ca-key.pem config/combined-worker-compose.yaml
```

## Gradle Properties (gradle.properties)
```properties
cordaApiVersion=5.2.0.52
cordaNotaryPluginsVersion=5.2.0.0
cordaPluginsVersion=7.0.4
cordaGradlePluginVersion=5.2.0.0
kotlinVersion=1.7.21
platformVersion=50200
org.gradle.jvmargs=-Xmx4g
```

**CRITICAL: `platformVersion=50200`** — This property controls `Min-Platform-Version` and `Target-Platform-Version` in the CPK MANIFEST.MF. If missing or set to `999` (the template default), the `cordapp-configuration` plugin cannot set it correctly, and Corda will refuse to register custom flows — only built-in flows like `NotarizedTransactionRepairFlow` will appear in the startable flows list.

The `-Xmx4g` is required if embedding large libraries (e.g., Prowide) — the default heap causes OOM during OSGi analysis.

## Root build.gradle — Critical Plugin Configuration

The `cordapp-configuration` plugin **MUST be applied** (not `apply false`) in the root `build.gradle`:

```groovy
plugins {
    id 'org.jetbrains.kotlin.jvm' apply false
    id 'org.jetbrains.kotlin.plugin.jpa' apply false
    id 'org.jetbrains.kotlin.plugin.allopen' apply false
    id 'net.corda.plugins.cordapp-cpk2' apply false
    id 'net.corda.plugins.cordapp-cpb2' apply false
    id 'net.corda.cordapp.cordapp-configuration'      // MUST be applied — NOT 'apply false'
    id 'net.corda.gradle.plugin'
}
```

**Why this matters:** The `cordapp-configuration` plugin reads `platformVersion` from `gradle.properties` and injects it into the CPK manifest as `Min-Platform-Version` and `Target-Platform-Version`. Without it:
- The build emits: `CORDAPP PLUGIN NOT CONFIGURED! Please apply 'net.corda.cordapp.cordapp-configuration' plugin to root project.`
- The manifest gets `Min-Platform-Version: 999` and `Target-Platform-Version: 999`
- Corda refuses to load custom flows from the CPK — only notary plugin flows are visible

**Verification after build:**
```bash
unzip -p workflows/build/libs/workflows-1.0-SNAPSHOT.jar META-INF/MANIFEST.MF | grep -i "platform"
# Must show: Min-Platform-Version: 50200 / Target-Platform-Version: 50200
# If it shows 999, the cordapp-configuration plugin is not applied
```

## Runtime Plugin Configuration (build.gradle)

```groovy
cordaRuntimeGradlePlugin {
    notaryVersion = cordaNotaryPluginsVersion
    notaryCpiName = "NotaryServer"
    corDappCpiName = "PayFiClearing"
    cpiUploadTimeout = "120000"            // 120 seconds — default 30000 causes timeouts
    vnodeRegistrationTimeout = "60000"
    cordaProcessorTimeout = "300000"
    workflowsModuleName = "workflows"
    cordaClusterURL = "https://localhost:8888"
    cordaRestUser = "admin"
    cordaRestPasswd = "admin"
    composeFilePath = "config/combined-worker-compose.yaml"
    networkConfigFile = "config/static-network-config.json"
    r3RootCertFile = "config/r3-ca-key.pem"
    skipTestsDuringBuildCpis = "false"
    cordaRuntimePluginWorkspaceDir = "workspace"
    cordaBinDir = "${System.getProperty("user.home")}/.corda/corda5"
    cordaCliBinDir = "${System.getProperty("user.home")}/.corda/cli"
}
```

**CRITICAL: `cpiUploadTimeout = "120000"`** — The default `30000` (30 seconds) causes `SocketTimeoutException: Read timed out` during CPI upload, especially on the first deploy after a clean workspace.

## Workflows build.gradle — CPB2 Plugin Required

The workflows module MUST have both `cordapp-cpk2` AND `cordapp-cpb2` plugins:

```groovy
plugins {
    id 'org.jetbrains.kotlin.jvm'
    id 'org.jetbrains.kotlin.plugin.jpa'
    id 'org.jetbrains.kotlin.plugin.allopen'
    id 'net.corda.plugins.cordapp-cpk2'
    id 'net.corda.plugins.cordapp-cpb2'     // REQUIRED — generates the .cpb file
}
```

Without `cordapp-cpb2`, the build produces individual `.cpk` JARs but no `.cpb` bundle, and `deployCpis` has nothing to deploy.

## Template Files Required in config/

The following files from the R3 template MUST be present in `config/`. If lost during git operations, restore from the template:

| File | Purpose | Recovery Command |
|------|---------|-----------------|
| `gradle-plugin-default-key.pem` | CPI signing — default Gradle certificate | `git checkout r3template/release-V5.2 -- config/gradle-plugin-default-key.pem` |
| `r3-ca-key.pem` | CPI signing — R3 root CA certificate | `git checkout r3template/release-V5.2 -- config/r3-ca-key.pem` |
| `combined-worker-compose.yaml` | Docker Compose for Corda cluster | `git checkout r3template/release-V5.2 -- config/combined-worker-compose.yaml` |
| `static-network-config.json` | Static network member definitions | PayFi-specific — do not restore from template |

## Docker Compose Fixes

After restoring `combined-worker-compose.yaml` from the R3 template, apply these fixes:

1. **Replace dead Docker image:**
```bash
sed -i 's|openjdk:17-jdk|eclipse-temurin:17-jdk|' config/combined-worker-compose.yaml
```

2. **Remove `flow-management-tool` service** — it references a `FlowManagementUI/` directory that doesn't exist:
```bash
sed -i '/flow-management-tool:/,$ d' config/combined-worker-compose.yaml
```

The compose file should have exactly 4 services: `postgresql`, `kafka`, `kafka-create-topics`, and `corda`.

## Static Network Config
File: `config/static-network-config.json`
```json
[
  {"x500Name":"CN=BankAlpha, OU=PayFi, O=BankAlpha, L=Johannesburg, C=ZA", "cpi":"PayFiClearing"},
  {"x500Name":"CN=BankBeta, OU=PayFi, O=BankBeta, L=Cape Town, C=ZA", "cpi":"PayFiClearing"},
  {"x500Name":"CN=SARBObserver, OU=PayFi, O=SARB, L=Pretoria, C=ZA", "cpi":"PayFiClearing"},
  {"x500Name":"CN=NotaryRep1, OU=PayFi, O=R3, L=London, C=GB", "cpi":"NotaryServer", "serviceX500Name":"CN=NotaryService, OU=PayFi, O=R3, L=London, C=GB"}
]
```

---

# 4. Compilation Fixes

## 4.1 MemberX500Name — American Spelling
Corda 5.2 uses **American spelling**: `.organization` not `.organisation`.

```kotlin
// WRONG:
memberName.organisation

// CORRECT:
memberName.organization
```

Fix: `sed -i 's/\.organisation/.organization/g' <file>`

Affected files: `LifecycleFlows.kt`, `SubmitPaymentInstructionFlow.kt`

## 4.2 Contract Command Extraction
Corda 5.2 returns commands directly, not wrapped. The `.value` pattern does not work.

```kotlin
// WRONG (Corda 4 pattern):
val command = transaction.commands
    .mapNotNull { it.value as? PaymentCommand }
    .firstOrNull()

// CORRECT (Corda 5.2):
val command = transaction.commands.firstOrNull { it is PaymentCommand }
    as? PaymentCommand
command ?: throw CordaRuntimeException("Transaction must contain a PaymentCommand")
```

The `as? PaymentCommand` returns nullable, so the `when` block needs the null guard.

## 4.3 XMLGregorianCalendar Setter
Prowide/JAXB setters use builder pattern. Use explicit setter form:

```kotlin
// WRONG:
grpHdr.creDtTm = factory.newXMLGregorianCalendar(...)

// CORRECT:
grpHdr.setCreDtTm(factory.newXMLGregorianCalendar(java.util.GregorianCalendar()))
```

## 4.4 Prowide List Fields
Some Prowide fields are Lists, not single objects:

```kotlin
// WRONG:
statusReport.orgnlGrpInfAndSts = orgnlGrpInf

// CORRECT:
statusReport.orgnlGrpInfAndSts.add(orgnlGrpInf)
```

## 4.5 Smart Cast Across Modules
Kotlin cannot smart-cast properties from a different module. Use explicit null-coalescing:

```kotlin
// WRONG (smart cast impossible across modules):
if (!addr.streetName.isNullOrBlank()) appendTextElement(doc, pstlAdr, "StrtNm", addr.streetName)

// CORRECT:
if (!addr.streetName.isNullOrBlank()) appendTextElement(doc, pstlAdr, "StrtNm", addr.streetName ?: "")
```

Affected fields: `streetName`, `buildingNumber`, `postCode`, `townName`, `country`, `remittanceInfo`

## 4.6 NotaryLookup Import
```kotlin
import net.corda.v5.ledger.common.NotaryLookup
```
NOT from `net.corda.v5.ledger.utxo`.

## 4.7 FlowMessaging Injection
`FlowMessaging` must be injected for all `initiateFlow()` calls:
```kotlin
@CordaInject lateinit var flowMessaging: FlowMessaging
```

---

# 5. Prowide OSGi Removal

## The Problem
Prowide `pw-iso20022:SRU2023-9.4.7` is NOT OSGi-compatible. When embedded in a Corda CPK:
1. `cordaEmbedded` configuration causes `verifyBundle` to fail (missing JAXB, activation, validation packages)
2. Adding JAXB runtime as `cordaEmbedded` causes cascading OSGi failures (`org.glassfish.hk2.osgiresourcelocator`, `org.osgi.framework`, `com.sun.activation.registries`)
3. This is an endless dependency rabbit hole with no clean resolution

## The Fix
Replace Prowide with JDK-native DOM/XPath XML parsing. Remove ALL Prowide dependencies from `workflows/build.gradle`.

### Mapper Rewrite
Two files were rewritten:
- `Pacs008Mapper.kt` — Uses `javax.xml.parsers.DocumentBuilder` + `javax.xml.xpath.XPath` to parse pacs.008 XML
- `Pacs002ResponseBuilder.kt` — Uses DOM + `javax.xml.transform.Transformer` to build pacs.002 XML

### XML Namespace Handling
pacs.008 uses namespace `urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08`. XPath queries must use a `NamespaceContext`:

```kotlin
class Pacs008XmlNamespaceContext : NamespaceContext {
    override fun getNamespaceURI(prefix: String): String = when (prefix) {
        "p" -> "urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08"
        else -> javax.xml.XMLConstants.NULL_NS_URI
    }
    // ...
}
```

### XXE Prevention
Always disable external entities when parsing XML:
```kotlin
val factory = DocumentBuilderFactory.newInstance()
factory.isNamespaceAware = true
factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
```

### Build.gradle After Prowide Removal
The workflows `build.gradle` should have NO `cordaEmbedded` dependencies and NO Prowide references. Only:
```groovy
dependencies {
    compileOnly "javax.persistence:javax.persistence-api:2.2"
}
```

### Verification
After removing Prowide, `./gradlew clean build` should pass with NO `-x verifyBundle` or `-x verifyLibraries` flags.

---

# 6. JPA Entity Configuration

## Entity Location — CRITICAL: Single Source of Truth
Entities MUST exist in the **contracts** module ONLY:
```
contracts/src/main/kotlin/za/co/payfi/clearing/persistence/Entities.kt
```

**NEVER have Entities.kt in both contracts AND workflows modules.** If a duplicate exists in workflows, the workflows copy will be loaded at runtime and will likely be missing `@CordaSerializable` annotations (since `sed` fixes target the contracts copy). This causes:
```
Class "class za.co.payfi.clearing.persistence.PaymentMessageMetadata" is not annotated with @CordaSerializable.
```

**Verification after any git operation:**
```bash
# Must return exactly ONE result (contracts only)
find . -path "*/persistence/Entities.kt" -not -path "./build/*"

# If workflows copy exists, delete it:
rm -f workflows/src/main/kotlin/za/co/payfi/clearing/persistence/Entities.kt
```

## Required Annotations
Every entity class needs ALL of these:
```kotlin
@CordaSerializable  // Required for Corda serialization
@Entity             // JPA entity marker
@Table(name = "snake_case_table_name")  // Explicit table name
data class MyEntity(
    @Id
    @Column(name = "id")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "snake_case_field", nullable = false)
    val camelCaseField: String,
    // ...
)
```

### Critical: @CordaSerializable
If missing, the flow fails with: `Class "class ...MyEntity" is not annotated with @CordaSerializable.`

This annotation gets lost when pulling code from GitHub if the repo doesn't have it. Always verify after git operations:
```bash
grep -c "@CordaSerializable" contracts/src/main/kotlin/za/co/payfi/clearing/persistence/Entities.kt
# Should return 7 (one per entity) or 8 (including import)
```

Fix if missing:
```bash
sed -i 's/^@Entity/@CordaSerializable\n@Entity/' contracts/src/main/kotlin/za/co/payfi/clearing/persistence/Entities.kt
grep -q "import net.corda.v5.base.annotations.CordaSerializable" <file> || \
sed -i '/^import javax.persistence/a import net.corda.v5.base.annotations.CordaSerializable' <file>
```

**WARNING:** If `@CordaSerializable` already exists, the `sed` command will create duplicates causing `This annotation is not repeatable` compilation errors. Always check the count BEFORE running the fix. To remove duplicates:
```bash
sed -i '/^@CordaSerializable/{n;/^@CordaSerializable/d;}' contracts/src/main/kotlin/za/co/payfi/clearing/persistence/Entities.kt
```

### Critical: @Column(name = "snake_case")
PostgreSQL folds unquoted identifiers to lowercase. Without explicit `@Column(name = "...")`, Hibernate generates camelCase names which PostgreSQL silently lowercases, causing mismatches.

```kotlin
// WRONG — will cause column mismatch:
@Column(nullable = false) val originalMessageId: String

// CORRECT:
@Column(name = "original_message_id", nullable = false) val originalMessageId: String
```

### Critical: JPQL Uses Kotlin Property Names
The `@NamedQuery` references Kotlin property names, NOT column names:
```kotlin
// This query uses "k.messageId" (Kotlin property), not "k.message_id" (column name)
@NamedQuery(
    name = "IdempotencyKey.findByCompositeKey",
    query = "SELECT k FROM IdempotencyKey k WHERE k.messageId = :messageId AND k.instructionId = :instructionId"
)
```
Do NOT rename Kotlin properties to snake_case — only add `@Column(name = "...")`.

## Kotlin JPA Plugin
The `kotlin.plugin.jpa` must be in the module containing `@Entity` classes. It generates no-arg constructors required by Hibernate.

```groovy
// In contracts/build.gradle plugins block:
plugins {
    id 'org.jetbrains.kotlin.jvm'
    id 'org.jetbrains.kotlin.plugin.jpa'  // REQUIRED
    // ...
}
```

Without this, flows fail with: `No default constructor for entity: za.co.payfi.clearing.persistence.PaymentMessageMetadata`

## javax.persistence Dependency
The contracts module needs:
```groovy
dependencies {
    cordaProvided 'javax.persistence:javax.persistence-api:2.2'
}
```

## PaymentInstructionDto — Query Result Serialization (v3.0)

The `PaymentInstructionState` contains a `participantKeys: List<PublicKey>` field. When `QueryPaymentInstructionsFlow` returns raw states, Jackson cannot serialize the BouncyCastle `PublicKey` objects:
```
No serializer found for class org.bouncycastle.math.ec.WNafL2RMultiplier
  (through reference chain: ...PaymentInstructionState["participantKeys"]...BCECPublicKey["q"]...SecP256R1Curve["multiplier"])
```

**Fix:** Add a `PaymentInstructionDto` data class in `LifecycleFlows.kt` that mirrors all state fields EXCEPT `participantKeys`, and map results through it before serializing:

```kotlin
@CordaSerializable
data class PaymentInstructionDto(
    val stateId: UUID,
    val instructionId: String,
    val endToEndId: String,
    val transactionId: String,
    val amount: BigDecimal,
    val currency: String,
    val settlementDate: String,       // String, not LocalDate (serialization safety)
    val debtorName: String,
    val debtorIdType: String,         // String, not enum (serialization safety)
    val debtorIdNumber: String,
    val debtorAccount: String,
    val debtorAgentBranchCode: String,
    val creditorName: String,
    val creditorAccount: String,
    val creditorAgentBranchCode: String,
    val remittanceInfo: String? = null,
    val purposeCode: String? = null,
    val status: String,               // String, not enum (serialization safety)
    val statusReason: String? = null,
    val feeApplicable: Boolean,
    val feeAmount: BigDecimal,
    val feeTaxAmount: BigDecimal,
    val feePayerBranchCode: String
)
```

In `QueryPaymentInstructionsFlow.call()`, replace:
```kotlin
// WRONG — causes BouncyCastle serialization error:
return jsonMarshallingService.format(results)

// CORRECT — strip PublicKey fields via DTO:
val dtos = results.map { s ->
    PaymentInstructionDto(
        stateId = s.stateId, instructionId = s.instructionId,
        endToEndId = s.endToEndId, transactionId = s.transactionId,
        amount = s.amount, currency = s.currency,
        settlementDate = s.settlementDate.toString(),
        debtorName = s.debtorName, debtorIdType = s.debtorIdType.name,
        debtorIdNumber = s.debtorIdNumber, debtorAccount = s.debtorAccount,
        debtorAgentBranchCode = s.debtorAgentBranchCode,
        creditorName = s.creditorName, creditorAccount = s.creditorAccount,
        creditorAgentBranchCode = s.creditorAgentBranchCode,
        remittanceInfo = s.remittanceInfo, purposeCode = s.purposeCode,
        status = s.status.name, statusReason = s.statusReason,
        feeApplicable = s.feeApplicable, feeAmount = s.feeAmount,
        feeTaxAmount = s.feeTaxAmount, feePayerBranchCode = s.feePayerBranchCode
    )
}
return jsonMarshallingService.format(dtos)
```

**IMPORTANT:** This is a flow-level change, but `deployCpis` alone does NOT update existing vnodes. A full `stopCordaAndCleanWorkspace` cycle is required after adding or modifying the DTO.

## createdAt Timestamp (v4.0)

The `PaymentInstructionState` now includes a `createdAt: Instant` field that records the exact time the state was created on the ledger:

```kotlin
val createdAt: java.time.Instant = java.time.Instant.now(),
```

This field sits between `feePayerBranchCode` and `participantKeys` in the state constructor. It has a default value so the mapper does NOT need to explicitly set it — `Instant.now()` is called automatically when the state is constructed.

The `PaymentInstructionDto` includes `createdAt` as a `String?`:
```kotlin
val createdAt: String? = null
```

In the DTO mapping: `createdAt = s.createdAt.toString()`

This enables the frontend to display real ledger timestamps in the transaction timeline and detail view.

## SarbTransactionRecord Entity (v4.0)

A dedicated JPA entity for SARB's off-ledger transaction storage. The SARB responder flow persists data here when it receives DTOs via `session.send()`:

```kotlin
@CordaSerializable
@Entity
@Table(name = "sarb_transaction_records")
data class SarbTransactionRecord(
    @Id @Column(name = "id") val id: UUID = UUID.randomUUID(),
    @Column(name = "state_id", nullable = false) val stateId: String,
    @Column(name = "instruction_id", nullable = false) val instructionId: String,
    @Column(name = "end_to_end_id", nullable = false) val endToEndId: String,
    @Column(name = "transaction_id", nullable = false) val transactionId: String,
    @Column(name = "amount", nullable = false) val amount: String,
    @Column(name = "currency", nullable = false) val currency: String,
    @Column(name = "debtor_name", nullable = false) val debtorName: String,
    @Column(name = "debtor_id_number") val debtorIdNumber: String? = null,
    @Column(name = "debtor_id_type") val debtorIdType: String? = null,
    @Column(name = "debtor_account", nullable = false) val debtorAccount: String,
    @Column(name = "debtor_agent_branch_code", nullable = false) val debtorAgentBranchCode: String,
    @Column(name = "creditor_name", nullable = false) val creditorName: String,
    @Column(name = "creditor_account", nullable = false) val creditorAccount: String,
    @Column(name = "creditor_agent_branch_code", nullable = false) val creditorAgentBranchCode: String,
    @Column(name = "status", nullable = false) val status: String,
    @Column(name = "fee_applicable", nullable = false) val feeApplicable: Boolean,
    @Column(name = "fee_amount", nullable = false) val feeAmount: String,
    @Column(name = "fee_tax_amount") val feeTaxAmount: String? = null,
    @Column(name = "settlement_date", nullable = false) val settlementDate: String,
    @Column(name = "received_at", nullable = false) val receivedAt: Instant = Instant.now(),
    @Column(name = "remittance_info") val remittanceInfo: String? = null,
    @Column(name = "settled", nullable = false) val settled: Boolean = false
)
```

The `settled` flag is used by `MarkSarbSettledFlow` to archive records after net settlement. `QuerySarbTransactionsFlow` filters by `!settled` to show only active transactions.

---

# 7. Liquibase Migration Discovery

## The Core Problem
**Corda 5.2 does NOT auto-discover custom entity Liquibase changelogs from CorDapp CPKs.** It only runs migrations for its own internal `net.corda.db.schema` bundles (e.g., `vnode-uniqueness`).

Evidence from Corda logs during vnode creation:
```
Creating composite master changelog XML file with:
[classloader://net.corda.db.schema/net/corda/db/schema/vnode-uniqueness/db.changelog-master.xml]
```

Corda's `StreamResourceAccessor` scans specific known bundles by symbolic name — not all CPKs generically.

## What Was Tried (ALL FAILED)
1. `workflows/src/main/resources/db/changelog/` — Corda never scanned this path
2. `workflows/src/main/resources/migration/` — Corda never scanned this path
3. `contracts/src/main/resources/db/changelog/` — Corda never scanned this path
4. `contracts/src/main/resources/migration/` — Corda never scanned this path (even with XML 1.1, `dbchangelog-4.3.xsd`, unique changeset IDs)
5. Both `db/changelog/` and `migration/` in both modules simultaneously — still not scanned
6. Verified files were present in CPK via `jar tf` — confirmed at both `db/changelog/` and `migration/` paths

## Migration Files (Kept for Future)
The Liquibase files are maintained in `contracts/src/main/resources/migration/` in case R3 documents the correct mechanism or a future Corda version supports auto-discovery. The files are:
- `db.changelog-master.xml` (XML 1.1, includes `migration/payfi-001-initial-tables.xml`)
- `payfi-001-initial-tables.xml` (7 changesets for all tables)

## The Working Solution
**Manual PostgreSQL table creation in `vnode_vault_*` schemas with explicit GRANT to vnode DML users.** See Section 8.

---

# 8. Manual Database Table Creation

Since Corda 5.2 does not auto-run custom Liquibase migrations, tables must be created manually in each vnode's vault schema after vnode creation, and permissions must be granted to the vnode-specific database users.

## Step 1: Get Vault Schemas
```bash
docker exec corda-cluster-postgresql-1 psql -U user -d cordacluster -t -c \
  "SELECT schema_name FROM information_schema.schemata WHERE schema_name LIKE 'vnode_vault_%';"
```

**Note:** The database connection user is `user` (not `postgres`). The compose file configures `-ddatabase.user=user` and `-ddatabase.pass=password`.

## Step 2: Create Tables in All Schemas (Automated)

Use a PL/pgSQL block to create tables across all 4 schemas in a single command:

```bash
docker exec -i corda-cluster-postgresql-1 psql -U user -d cordacluster << 'SQL'
DO $$
DECLARE
  s TEXT;
  schemas TEXT[] := ARRAY['vnode_vault_<hash1>','vnode_vault_<hash2>','vnode_vault_<hash3>','vnode_vault_<hash4>'];
BEGIN
  FOREACH s IN ARRAY schemas LOOP
    EXECUTE format('SET search_path TO %I', s);
    EXECUTE 'CREATE TABLE IF NOT EXISTS payment_message_metadata (id uuid PRIMARY KEY, original_message_id varchar(255) NOT NULL, message_creation_time timestamp NOT NULL, number_of_transactions int NOT NULL, message_digest varchar(64) NOT NULL, received_at timestamp NOT NULL)';
    EXECUTE 'CREATE TABLE IF NOT EXISTS metadata_linked_states (metadata_id uuid NOT NULL REFERENCES payment_message_metadata(id), state_id uuid NOT NULL)';
    EXECUTE 'CREATE TABLE IF NOT EXISTS idempotency_keys (id uuid PRIMARY KEY, message_id varchar(255) NOT NULL, instruction_id varchar(255) NOT NULL, created_at timestamp NOT NULL, response_payload text NOT NULL, state_id uuid NOT NULL, UNIQUE(message_id, instruction_id))';
    EXECUTE 'CREATE TABLE IF NOT EXISTS fee_accruals (id uuid PRIMARY KEY, state_id uuid NOT NULL, creditor_bank_branch_code varchar(6) NOT NULL, fee_amount decimal(10,2) NOT NULL, tax_amount decimal(10,2) NOT NULL, total_amount decimal(10,2) NOT NULL, transaction_amount decimal(19,2) NOT NULL, accrual_date date NOT NULL, invoiced boolean DEFAULT false, invoice_reference varchar(255), reversed boolean DEFAULT false, reversal_note varchar(500))';
    EXECUTE 'CREATE TABLE IF NOT EXISTS fee_rules (id uuid PRIMARY KEY, threshold_amount decimal(19,2) NOT NULL, fee_amount decimal(10,2) NOT NULL, currency varchar(3) DEFAULT ''ZAR'', fee_payer varchar(50) DEFAULT ''CREDITOR_BANK'', effective_from timestamp NOT NULL)';
    EXECUTE 'CREATE TABLE IF NOT EXISTS settlement_reports (id uuid PRIMARY KEY, bank_branch_code varchar(6) NOT NULL, window_start timestamp NOT NULL, window_end timestamp NOT NULL, net_amount decimal(19,2) NOT NULL, gross_payable decimal(19,2) NOT NULL, gross_receivable decimal(19,2) NOT NULL, transaction_count int NOT NULL, generated_at timestamp NOT NULL)';
    EXECUTE 'CREATE TABLE IF NOT EXISTS participant_status (id uuid PRIMARY KEY, branch_code varchar(6) NOT NULL, status varchar(20) NOT NULL, effective_from timestamp NOT NULL, reason varchar(500) NOT NULL, initiated_by varchar(255) NOT NULL, reinstated_at timestamp)';
    EXECUTE 'CREATE TABLE IF NOT EXISTS sarb_transaction_records (id uuid PRIMARY KEY, state_id varchar(255) NOT NULL, instruction_id varchar(255) NOT NULL, end_to_end_id varchar(255) NOT NULL, transaction_id varchar(255) NOT NULL, amount varchar(255) NOT NULL, currency varchar(3) NOT NULL, debtor_name varchar(255) NOT NULL, debtor_id_number varchar(255), debtor_id_type varchar(50), debtor_account varchar(255) NOT NULL, debtor_agent_branch_code varchar(6) NOT NULL, creditor_name varchar(255) NOT NULL, creditor_account varchar(255) NOT NULL, creditor_agent_branch_code varchar(6) NOT NULL, status varchar(20) NOT NULL, fee_applicable boolean NOT NULL, fee_amount varchar(255) NOT NULL, fee_tax_amount varchar(255), settlement_date varchar(20) NOT NULL, received_at timestamp NOT NULL, remittance_info text, settled boolean NOT NULL DEFAULT false)';
    RAISE NOTICE 'Tables created in %', s;
  END LOOP;
END $$;
SQL
```

Replace `<hash1>` through `<hash4>` with the actual lowercased vnode short hashes.

## Step 3: GRANT Permissions to Vnode DML Users (MANDATORY)

**This step was not in v1.0 and is the reason flows failed with `permission denied for table payment_message_metadata`.**

Corda creates dedicated DDL and DML database users per vnode vault schema. Tables created manually as the `user` connection user are NOT accessible by these vnode-specific users. Without explicit GRANTs, flows fail silently (stuck in `START_REQUESTED`) or with `permission denied` errors in the Corda logs.

### Finding the DML Users
```bash
docker exec corda-cluster-postgresql-1 psql -U user -d cordacluster -t -c \
  "SELECT usename FROM pg_user WHERE usename LIKE 'vnode_vault_%' ORDER BY usename;"
```

Each schema has two users: `vnode_vault_<hash>_<timestamp>_ddl` and `vnode_vault_<hash>_<timestamp>_dml`.

### Granting Permissions
For each schema, grant SELECT/INSERT/UPDATE/DELETE to both DDL and DML users:

```bash
docker exec -i corda-cluster-postgresql-1 psql -U user -d cordacluster << 'SQL'
-- BankAlpha (replace with actual user names from the query above)
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA vnode_vault_<hash> TO vnode_vault_<hash>_<timestamp>_ddl;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA vnode_vault_<hash> TO vnode_vault_<hash>_<timestamp>_dml;
-- Repeat for BankBeta, SARBObserver, Notary
SQL
```

### Why This Wasn't Needed Before
In the original deployment (v1.0, test-019), tables were created connecting as `postgres` (the superuser). The R3 template compose file uses `postgres`/`password` as the PostgreSQL superuser, but Corda's combined worker connects as `user`/`password` (configured via `-ddatabase.user=user`). Tables created by `postgres` were accessible because PostgreSQL superuser bypasses permission checks. Tables created by `user` are owned by `user` and require explicit GRANTs to the vnode-specific DML/DDL users.

### Verification
After granting, verify the vnode DML user can access the table:
```bash
docker exec corda-cluster-postgresql-1 psql -U user -d cordacluster -t -c \
  "SELECT grantee FROM information_schema.role_table_grants WHERE table_schema = 'vnode_vault_<hash>' AND table_name = 'payment_message_metadata' AND grantee LIKE 'vnode_vault_%';"
```

Should return both DDL and DML user names.

## Important Notes
- Tables MUST be in `vnode_vault_<hash>` schemas, NOT in `public`
- Tables MUST be created AFTER `vNodesSetup` (schemas don't exist until vnodes are created)
- GRANTs MUST be applied AFTER table creation (Step 3 after Step 2)
- If you run `stopCordaAndCleanWorkspace`, the database is wiped — tables AND grants must be recreated
- Column names must be snake_case matching the `@Column(name = "...")` annotations exactly
- Do NOT use `gen_random_uuid()` defaults — Kotlin generates UUIDs
- Run the SQL as a single `docker exec` command per heredoc to avoid exhausting PostgreSQL connections

---

# 9. Deployment Workflow

## Prerequisites Checklist (verify before any deploy)
Before running a deploy cycle, verify these are in place:
- [ ] `gradle.properties` has `platformVersion=50200`
- [ ] Root `build.gradle` has `id 'net.corda.cordapp.cordapp-configuration'` (NOT `apply false`)
- [ ] Root `build.gradle` has `cpiUploadTimeout = "120000"`
- [ ] `workflows/build.gradle` has both `cordapp-cpk2` AND `cordapp-cpb2` plugins
- [ ] `config/gradle-plugin-default-key.pem` exists
- [ ] `config/r3-ca-key.pem` exists
- [ ] `config/combined-worker-compose.yaml` exists (with `flow-management-tool` removed and `eclipse-temurin:17-jdk` image)
- [ ] Entities exist ONLY in `contracts/src/main/kotlin/za/co/payfi/clearing/persistence/Entities.kt` (NOT in workflows)
- [ ] Entities have `@CordaSerializable` annotation (count should be 7 — including SarbTransactionRecord)
- [ ] All flows using `flowMessaging.initiateFlow()` have `@InitiatingFlow(protocol = "...")` annotation
- [ ] Corresponding responder flows have `@InitiatedBy(protocol = "...")` annotation

## Full Clean Deploy (from scratch)

```bash
cd ~/payfi-cordapp

# 1. Build
./gradlew clean build

# 2. Start Corda (wipes everything)
./gradlew stopCordaAndCleanWorkspace
./gradlew startCorda
# Hangs at 50% — this is normal (Known Issue #2)
# Check API in another terminal: curl -sk -u admin:admin https://localhost:8888/api/v5_2/cpi
# Once API responds, Ctrl+C the Gradle task

# 3. Deploy CPI and create vnodes
./gradlew vNodesSetup -x startCorda

# 4. Create database tables (MUST be after vNodesSetup)
# Get vault schemas, then run SQL per schema (see Section 8, Step 2)

# 5. Grant permissions to vnode DML users (MUST be after table creation)
# See Section 8, Step 3

# 6. Test
curl -sk -u admin:admin -X POST "https://localhost:8888/api/v5_2/flow/<ALPHA_HASH>" ...
```

## Code-Only Update (no schema changes)
```bash
./gradlew clean build
./gradlew deployCpis -x startCorda
# Tables and grants persist — no need to recreate
# Note: vnodes are bound to CPIs — flow-only changes via deployCpis work,
# but entity/annotation changes require full stopCordaAndCleanWorkspace cycle
```

## When `stopCordaAndCleanWorkspace` is REQUIRED
- After changing entity classes
- After changing CPI structure (adding/removing CPKs)
- After the first CPI upload to a fresh cluster
- After changing `cordapp-configuration` plugin or `platformVersion`
- `deployCpis` alone does NOT update existing vnodes with new migrations

## When `deployCpis` is SUFFICIENT
- **v3.0 UPDATE:** In practice, `deployCpis` has been unreliable for ANY code changes. Even flow-only changes that successfully uploaded a new CPI still ran the old version on existing vnodes. **Recommendation: Always use full `stopCordaAndCleanWorkspace` cycle for all changes.** The `deployCpis` shortcut is theoretically supported but has not worked reliably in this deployment.

## Checking API Health
```bash
curl -sk -u admin:admin https://localhost:8888/api/v5_2/cpi && echo " API UP"
```

## Checking Vnode Hashes
```bash
./gradlew listVNodes 2>&1 | grep -E "BankAlpha|BankBeta|SARB|Notary"
```

---

# 10. Frontend & Nginx

## The Traefik Problem
The server runs k3s with Traefik ingress, which captures ports 80 and 443 via iptables DNAT rules. Nginx on port 80 appears to work locally but external traffic is intercepted by Traefik.

Evidence:
```bash
iptables -t nat -L -n | grep ":80 "
# DNAT tcp dpt:80 to:<K3S_POD_IP>:80
```

## Solution: Nginx on Port 8080
```nginx
server {
    listen 8080 default_server;
    server_name _;

    root /var/www/payfi;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }

    location /api/ {
        proxy_pass https://localhost:8888/api/;
        proxy_ssl_verify off;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header Authorization "Basic <BASE64_CREDENTIALS>";
        proxy_read_timeout 120s;
    }
}
```

## Firewall
Port 8080 must be opened:
```bash
ufw allow 8080/tcp
iptables -I INPUT -p tcp --dport 8080 -j ACCEPT
```

## URLs
- Frontend: http://<SERVER_IP>:8080/
- API proxy: http://<SERVER_IP>:8080/api/

## Nginx Heredoc Warning
When using `cat > ... << EOF` with nginx configs, use `<< 'EOF'` (single-quoted) to prevent shell variable expansion of `$uri`, `$host`, etc. Without quotes, these are interpreted as shell variables and become empty strings.

## Frontend JavaScript Configuration (v3.0)

### Vnode Hashes
The frontend `index.html` has hardcoded vnode hashes that must be updated after every `stopCordaAndCleanWorkspace` + `vNodesSetup` cycle:

```javascript
const BANKS={
  blue:{name:'Bank Blue',branch:'100001',hash:'<ALPHA_HASH>',...},
  turq:{name:'Bank Turquoise',branch:'200002',hash:'<BETA_HASH>',...}
};
const C={apiBase:'/api/v5_2',sarbHash:'<SARB_HASH>'};
```

Update with `sed` after each deploy:
```bash
cd ~/payfi-cordapp/frontend
sed -i "s/OLD_ALPHA_HASH/NEW_ALPHA_HASH/g" index.html
sed -i "s/OLD_BETA_HASH/NEW_BETA_HASH/g" index.html
sed -i "s/OLD_SARB_HASH/NEW_SARB_HASH/g" index.html
cp index.html /var/www/payfi/index.html
```

### Query Flow Class Name
The flow class is `QueryPaymentInstructionsFlow` (NOT `QueryPaymentStatesFlow`):
```javascript
const QUERY_FLOW='za.co.payfi.clearing.flows.QueryPaymentInstructionsFlow';
```

### Field Mapping (mapApiState)
The `PaymentInstructionDto` returns field names that differ from the frontend's original expectations. The `mapApiState` function must include DTO field names as primary fallbacks:

```javascript
// Branch codes — DTO uses debtorAgentBranchCode / creditorAgentBranchCode
const db=s.debtorAgentBranchCode||s.debtorBranch||s.dbtrBranch||s.senderBranch||'';
const cb=s.creditorAgentBranchCode||s.creditorBranch||s.cdtrBranch||s.receiverBranch||'';

// Debtor ID — DTO uses debtorIdNumber
di: s.debtorIdNumber||s.debtorId||s.dbtrId||s.debtorIdentification||'',

// Fee tax — DTO uses feeTaxAmount
const feeVat=s.feeTaxAmount||s.feeVat||s.feeTax||'0.00';
```

### Deploying Frontend Changes
```bash
cp ~/payfi-cordapp/frontend/index.html /var/www/payfi/index.html
```

### Additional Frontend Fixes (v4.0)

**Poll interval and panel protection:** Changed from 5s to 15s, and polls are skipped when a detail panel is open (prevents auto-closing while reading):
```javascript
pollTimer=setInterval(()=>{if(!document.querySelector(".tc-det.open"))refreshAll();},15000);
```

**ISO XML reconstruction:** The DTO doesn't include raw XML. The frontend reconstructs pacs.008 and pacs.002 from DTO fields:
```javascript
pacs008: s.pacs008Xml||s.pacs008||(s.instructionId?'<Document xmlns="...">...'+s.instructionId+'...':''),
pacs002: s.pacs002Xml||s.pacs002||(s.instructionId?'<Document xmlns="...">...'+(s.status==='REJECTED'?'RJCT':'ACCP')+'...':''),
```

**Real timestamps:** Timeline uses `createdAt` from the DTO (actual Corda ledger time):
```javascript
const t=s.createdAt||new Date().toISOString();
```

**pacs002 status code:** Treats `SUBMITTED` as `ACCP` (the flow returns SUBMITTED status with ACCP acceptance):
```javascript
pacs002Code:s.pacs002Code||s.txStatus||(s.status==='SUBMITTED'?'ACCP':...)
```

**Timestamp display:** Added "Timestamp" row to transaction detail view showing real `createdAt`.

**Payment Reference:** Renamed from "Remittance" to "Payment Reference" in the detail view.

**Demo SA IDs:** Blue demo `9001015009086`, Turquoise demo `8506150123089` (both standard-Luhn valid, synthetic; see `specs/verified-sa-ids.txt`).

**Fee timeline:** Fee line shows "Fee calculated" without amount detail (fee amounts visible in the detail panel, per creditor-bank-pays architecture).

### SARB Query Flow (v4.0)
The frontend uses `QuerySarbTransactionsFlow` for the SARB panel (not `QueryPaymentInstructionsFlow`):
```javascript
const SARB_QUERY_FLOW='za.co.payfi.clearing.flows.QuerySarbTransactionsFlow';
const flow=key==='sarb'?SARB_QUERY_FLOW:QUERY_FLOW;
```

### Net Settlement Button (v4.0)
Replaced "Clear All" with "⚖ Net Settlement" which executes real on-ledger settlement:
```javascript
const SETTLE_FLOW='za.co.payfi.clearing.flows.SettleTransactionsFlow';
const SARB_SETTLE_FLOW='za.co.payfi.clearing.flows.MarkSarbSettledFlow';
```
The button calls `SettleTransactionsFlow` on BankAlpha (consumes UTXO states), then `MarkSarbSettledFlow` on the SARB vnode (marks off-ledger records as settled). Both bank and SARB panels clear to show a fresh post-settlement slate.

---

# 11. Testing

## Test SA ID Numbers
The SA ID `8506150123085` **FAILS** the Luhn check. The correct check digit for `850615012308` is `9`.

**Valid test ID: `8506150123089`** — passes Luhn validation, format YYMMDD SSSS C A Z.

All SA ID examples used in test fixtures must be computationally verified using the standard Luhn algorithm (all 13 digits, sum mod 10 == 0) before use. Regenerate synthetic IDs with `python specs/generate_sa_ids.py`.

> **Note (v4.1):** Versions up to v4.0 of the validator doubled the wrong digits, so earlier demo IDs (`7801015012082`, `8501015800089`, `8505025098085`, …) were accepted incorrectly. They are rejected by the corrected validator and have been replaced.

## Test Payment (Rejected — No Debtor ID)
```bash
ALPHA_HASH=<current_hash>
curl -sk -u admin:admin -X POST "https://localhost:8888/api/v5_2/flow/$ALPHA_HASH" \
  -H "Content-Type: application/json" \
  -d '{"clientRequestId":"test-001","flowClassName":"za.co.payfi.clearing.flows.SubmitPaymentInstructionFlow","requestBody":{"pacs008Xml":"<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08\"><FIToFICstmrCdtTrf><GrpHdr><MsgId>MSG001</MsgId><CreDtTm>2026-03-27T12:00:00</CreDtTm><NbOfTxs>1</NbOfTxs><SttlmInf><SttlmMtd>CLRG</SttlmMtd></SttlmInf></GrpHdr><CdtTrfTxInf><PmtId><InstrId>INSTR001</InstrId><EndToEndId>E2E001</EndToEndId><TxId>TX001</TxId></PmtId><IntrBkSttlmAmt Ccy=\"ZAR\">5000.00</IntrBkSttlmAmt><Dbtr><Nm>John Doe</Nm></Dbtr><DbtrAcct><Id><Othr><Id>1234567890</Id></Othr></Id></DbtrAcct><DbtrAgt><FinInstnId><ClrSysMmbId><MmbId>100001</MmbId></ClrSysMmbId></FinInstnId></DbtrAgt><Cdtr><Nm>Jane Smith</Nm></Cdtr><CdtrAcct><Id><Othr><Id>0987654321</Id></Othr></Id></CdtrAcct><CdtrAgt><FinInstnId><ClrSysMmbId><MmbId>200002</MmbId></ClrSysMmbId></FinInstnId></CdtrAgt></CdtTrfTxInf></FIToFICstmrCdtTrf></Document>"}}'
```

Expected: `RJCT` with `CH09: debtorIdNumber must not be blank`

## Test Payment (Rejected — Invalid Luhn)
Add `<Id><PrvtId><Othr><Id>8506150123085</Id><SchmeNm><Cd>NIDN</Cd></SchmeNm></Othr></PrvtId></Id>` inside `<Dbtr>` after `<Nm>`.

Expected: `RJCT` with `BE01: invalid SA ID number (Luhn check failed)`

## Test Payment (Accepted — Valid SA ID)
Use `8506150123089` as the debtor ID (passes Luhn check).

Expected: `ACCP` — full end-to-end flow: parsed, validated, ledger-recorded across BankAlpha + BankBeta + SARB observer, notarised, pacs.002 success response.

**Confirmed working:** test-029 (30 March), test-031, test-032, test-033 (31 March) all returned `COMPLETED` with `TxSts: ACCP`.

## Vault Query (v3.0)
```bash
curl -sk -u admin:admin -X POST "https://localhost:8888/api/v5_2/flow/$ALPHA_HASH" \
  -H "Content-Type: application/json" \
  -d '{"clientRequestId":"query-NNN","flowClassName":"za.co.payfi.clearing.flows.QueryPaymentInstructionsFlow","requestBody":{}}'

sleep 10
curl -sk -u admin:admin "https://localhost:8888/api/v5_2/flow/$ALPHA_HASH/query-NNN" | python3 -m json.tool
```

**Expected:** JSON array of `PaymentInstructionDto` objects with all payment fields. No `participantKeys`. Each query needs a unique `clientRequestId`.

**SARB vault query returns `[]`** — this is expected for UTXO queries. SARB data comes from `QuerySarbTransactionsFlow` which reads `sarb_transaction_records`. See Section 16.

## Settlement Cycle Test (v4.0)
Full settlement lifecycle confirmed working (test-041, settle-003, sarb-settle-001):

```bash
# 1. Submit payment
curl -sk -u admin:admin -X POST "https://localhost:8888/api/v5_2/flow/$ALPHA_HASH" -H "Content-Type: application/json" -d '{"clientRequestId":"test-NNN","flowClassName":"za.co.payfi.clearing.flows.SubmitPaymentInstructionFlow","requestBody":{"pacs008Xml":"..."}}'

# 2. Settle (consumes UTXO states on bank vaults)
curl -sk -u admin:admin -X POST "https://localhost:8888/api/v5_2/flow/$ALPHA_HASH" -H "Content-Type: application/json" -d '{"clientRequestId":"settle-NNN","flowClassName":"za.co.payfi.clearing.flows.SettleTransactionsFlow","requestBody":{}}'
# Expected: {"settled":1,"message":"Settlement complete — 1 transactions archived"}

# 3. Mark SARB records as settled
curl -sk -u admin:admin -X POST "https://localhost:8888/api/v5_2/flow/$SARB_HASH" -H "Content-Type: application/json" -d '{"clientRequestId":"sarb-settle-NNN","flowClassName":"za.co.payfi.clearing.flows.MarkSarbSettledFlow","requestBody":{}}'
# Expected: {"settled":1}

# 4. Verify clean slate — bank vault and SARB both return empty
# Bank: QueryPaymentInstructionsFlow → []
# SARB: QuerySarbTransactionsFlow → []
```

## Checking Flow Status
```bash
curl -sk -u admin:admin "https://localhost:8888/api/v5_2/flow/$ALPHA_HASH/$CLIENT_REQUEST_ID" | python3 -m json.tool
```

## Each Test Needs a Unique clientRequestId and MsgId
Corda deduplicates by `clientRequestId`. Always increment: `test-001`, `test-002`, etc.
The flow also checks MsgId for idempotency, so use unique message IDs too.

---

# 12. Known Issues & Workarounds

## Issue 1: Liquibase Auto-Discovery
**Status:** UNRESOLVED — Corda 5.2 does not auto-discover custom changelogs from CorDapp CPKs.
**Workaround:** Manual table creation in vault schemas with GRANT to DML users (see Section 8).
**Impact:** Every `stopCordaAndCleanWorkspace` requires table recreation AND permission grants.

## Issue 2: `startCorda` Gradle Task Hangs
**Symptom:** `startCorda` sits at 50% even though all Docker containers are healthy and the API responds.
**Workaround:** Check API health in a second terminal. Once API responds, Ctrl+C the Gradle task and proceed with `vNodesSetup -x startCorda`.

## Issue 3: PostgreSQL Connection Exhaustion
**Symptom:** `FATAL: sorry, too many clients already` or `Unable to acquire JDBC Connection`
**Cause:** Failed flows create retrying persistence requests that hold connections.
**Workaround:** `./gradlew stopCordaAndCleanWorkspace` and start fresh. Do NOT manually restart PostgreSQL — this can cause auth failures (see Issue 10).

## Issue 4: GitHub Pulls Overwrite @CordaSerializable
**Symptom:** After `git checkout payfi/main -- ...`, entity annotations may be missing.
**Workaround:** Always verify and re-add after git operations (see Section 6). Check for duplicates in workflows module.

## Issue 5: Vnode Hashes Change on Every Redeploy
**Symptom:** Every `stopCordaAndCleanWorkspace` + `vNodesSetup` generates new vnode short hashes.
**Impact:** Frontend API calls, test scripts, and manual curl commands need updated hashes.
**Workaround:** Always run `./gradlew listVNodes` after deployment and update references.

## Issue 6: `deployCpis` vs Full Redeploy
**Symptom:** `deployCpis` uploads a new CPI but vnodes may use the old one (cached sandbox).
**Workaround:** For entity/annotation changes, always do full `stopCordaAndCleanWorkspace` cycle. For flow-only changes, `deployCpis` is usually sufficient.

## Issue 7: `cordapp-configuration` Plugin Not Applied (v2.0)
**Symptom:** Build emits `CORDAPP PLUGIN NOT CONFIGURED!`, flows not in startable flows list, only `NotarizedTransactionRepairFlow` visible. CPK manifest shows `Min-Platform-Version: 999`.
**Root Cause:** `net.corda.cordapp.cordapp-configuration` declared as `apply false` in root `build.gradle`.
**Fix:** Change to `id 'net.corda.cordapp.cordapp-configuration'` (without `apply false`). Requires full `stopCordaAndCleanWorkspace` cycle after fix.

## Issue 8: Duplicate Entities.kt Across Modules (v2.0)
**Symptom:** `@CordaSerializable` error persists even after verifying annotations in contracts module.
**Root Cause:** `Entities.kt` exists in BOTH `contracts/src/main/kotlin/.../persistence/` and `workflows/src/main/kotlin/.../persistence/`. The workflows copy lacks `@CordaSerializable` and takes precedence at runtime.
**Fix:** Delete `workflows/src/main/kotlin/za/co/payfi/clearing/persistence/Entities.kt`. Entities must only exist in contracts.

## Issue 9: Missing `cordapp-cpb2` Plugin in Workflows (v2.0)
**Symptom:** Build succeeds but no `.cpb` file is generated. `deployCpis` has nothing to deploy.
**Root Cause:** `workflows/build.gradle` only has `cordapp-cpk2` but not `cordapp-cpb2`.
**Fix:** Add `id 'net.corda.plugins.cordapp-cpb2'` to workflows plugins block.

## Issue 10: Manual Docker Restart Breaks PostgreSQL Auth (v2.0)
**Symptom:** After `docker restart corda-cluster-postgresql-1` + `docker restart corda-cluster-corda-1`, the Corda worker fails with `FATAL: password authentication failed for user "postgres"` and exits.
**Root Cause:** The Corda worker's `superUserConnection` check tries to connect as `postgres` during startup. Manual container restarts can disrupt the auth state.
**Workaround:** Never manually restart Docker containers. Always use `./gradlew stopCordaAndCleanWorkspace` + `startCorda` for a clean lifecycle. If already in this state, `stopCordaAndCleanWorkspace` will fix it.

## Issue 11: Missing Template Config Files After Git Sync (v2.0)
**Symptom:** `deployCpis` fails with `FileNotFoundException: config/gradle-plugin-default-key.pem` or `startCorda` fails with `Unable to locate compose file`.
**Root Cause:** Template files lost during git operations. Both `origin` and `payfi` remotes point to the PayFi repo, not the R3 template.
**Fix:** Add the R3 template as a separate remote and restore files (see Section 3, Git Remote Management).

## Issue 12: Manual Table Permission Denied (v2.0)
**Symptom:** Flow stuck in `START_REQUESTED`, Corda logs show `ERROR: permission denied for table payment_message_metadata` repeatedly.
**Root Cause:** Tables created as `user` (the DB connection user) but Corda's flow engine uses vnode-specific DML users (`vnode_vault_<hash>_<timestamp>_dml`) which have no grants on manually created tables.
**Fix:** GRANT SELECT, INSERT, UPDATE, DELETE to both DDL and DML users for each schema (see Section 8, Step 3).

## Issue 13: QueryPaymentInstructionsFlow BouncyCastle Serialization (v3.0)
**Symptom:** `QueryPaymentInstructionsFlow` returns `FAILED` with `No serializer found for class org.bouncycastle.math.ec.WNafL2RMultiplier`.
**Root Cause:** `jsonMarshallingService.format(results)` serializes raw `PaymentInstructionState` including `participantKeys: List<PublicKey>` — Jackson cannot serialize BouncyCastle public key objects.
**Fix:** Add `PaymentInstructionDto` data class that mirrors all state fields except `participantKeys`, and map results through it before serializing (see Section 6).
**Important:** Requires full `stopCordaAndCleanWorkspace` cycle — `deployCpis` does NOT update existing vnodes even for this flow-only change.

## Issue 14: SARB Vault Returns Empty (v3.0)
**Symptom:** `QueryPaymentInstructionsFlow` on the SARB vnode returns `[]` even after accepted payments.
**Root Cause:** SARB is NOT included in `participantKeys` on `PaymentInstructionState`. Per the architecture, SARB receives a `@CordaSerializable` DTO via `session.send()` after finality — this is a notification, not a vault-recorded UTXO state. `QueryPaymentInstructionsFlow` queries the UTXO vault via `findUnconsumedStatesByExactType()`, which only returns states where the vnode is a participant.
**Status:** Architectural — see Section 14 for resolution options.

## Issue 15: Connection Pool Exhaustion from Frontend Polling (v3.0)
**Symptom:** API returns `500` with `HikariPool-2 - Connection is not available, request timed out after 30000ms (total=0, active=0, idle=0, waiting=8)`.
**Root Cause:** The frontend polls all 3 vnodes every 5 seconds via `QueryPaymentInstructionsFlow`. When the query flow fails (e.g., BouncyCastle serialization error), the failed flows create retrying persistence requests that hold connections, eventually exhausting the pool.
**Prevention:** Fix the underlying query error (DTO fix) before connecting the frontend. If pool is already exhausted, only `stopCordaAndCleanWorkspace` recovers it. NEVER use `docker restart` — see Issue 10.

## Issue 16: `deployCpis` Does Not Update Existing Vnodes (v3.0)
**Symptom:** After `deployCpis`, new CPI is uploaded (confirmed by new CPI hash in output), but flows still exhibit old behavior (e.g., BouncyCastle error persists even after DTO fix was compiled into the new CPI).
**Root Cause:** Existing vnodes remain bound to the CPI they were created with. `deployCpis` uploads a new CPI to the cluster but does not re-bind vnodes to it. Only `vNodesSetup` (which creates new vnodes) binds to the latest CPI.
**Fix:** Always use full `stopCordaAndCleanWorkspace` + `startCorda` + `vNodesSetup` cycle for all code changes.
**Impact:** The "code-only deploy" workflow (`deployCpis -x startCorda`) documented in v1.0 and v2.0 does not work reliably. The v3.0 recommendation is to always do a full cycle.

## Issue 17: @InitiatingFlow Missing on Settlement Flow (v4.0)
**Symptom:** `SettleTransactionsFlow` returns `FAILED` with `Cannot initiate flow inside of ... as it is not annotated with @InitiatingFlow`.
**Root Cause:** Any flow that calls `flowMessaging.initiateFlow()` must have `@InitiatingFlow(protocol = "...")` annotation, and the corresponding responder must have `@InitiatedBy(protocol = "...")`.
**Fix:**
```kotlin
@InitiatingFlow(protocol = "settle-transactions")
class SettleTransactionsFlow : ClientStartableFlow { ... }

@InitiatedBy(protocol = "settle-transactions")
class SettleTransactionsResponderFlow : ResponderFlow { ... }
```

## Issue 18: Luhn-Invalid Demo SA ID in Frontend (v4.0)
**Symptom:** Turquoise demo payment rejected with `BE01: invalid SA ID number (Luhn check failed)`.
**Root Cause:** Demo SA ID `8505025098087` has incorrect check digit. Correct digit is `5`.
**Fix:** `sed -i "s/8505025098087/8505025098085/" frontend/index.html`

---

# 13. Quick Reference Commands

## Build
```bash
cd ~/payfi-cordapp
./gradlew clean build
```

## Full Deploy
```bash
./gradlew stopCordaAndCleanWorkspace
./gradlew startCorda
# Check: curl -sk -u admin:admin https://localhost:8888/api/v5_2/cpi
# Ctrl+C at 50%
./gradlew vNodesSetup -x startCorda
# Then: create tables (Section 8 Step 2) + grant permissions (Section 8 Step 3)
```

## Code-Only Deploy (UNRELIABLE — see Issue 16)
```bash
# WARNING: deployCpis does NOT rebind existing vnodes. Use full deploy instead.
./gradlew clean build
./gradlew deployCpis -x startCorda
```

## List Vnodes
```bash
./gradlew listVNodes 2>&1 | grep -E "BankAlpha|BankBeta|SARB|Notary"
```

## Check Corda Health
```bash
curl -sk -u admin:admin https://localhost:8888/api/v5_2/cpi
docker ps --format "{{.Names}} {{.Status}}"
```

## Check Corda Logs
```bash
docker logs corda-cluster-corda-1 2>&1 | grep -i "error\|fail" | tail -20
docker logs corda-cluster-corda-1 2>&1 | grep "permission denied" | tail -5
docker logs corda-cluster-corda-1 2>&1 | grep "relation.*does not exist" | tail -5
docker logs corda-cluster-corda-1 2>&1 | grep "missing requirement" | tail -5
```

## Check Database Tables
```bash
docker exec corda-cluster-postgresql-1 psql -U user -d cordacluster -c \
  "SELECT tablename FROM pg_tables WHERE schemaname LIKE 'vnode_vault_%' AND tablename='payment_message_metadata';"
```

## Check Table Permissions
```bash
docker exec corda-cluster-postgresql-1 psql -U user -d cordacluster -t -c \
  "SELECT table_schema, table_name, grantee FROM information_schema.role_table_grants WHERE table_schema LIKE 'vnode_vault_%' AND table_name = 'payment_message_metadata' AND grantee LIKE 'vnode_vault_%';"
```

## Check Vault DML Users
```bash
docker exec corda-cluster-postgresql-1 psql -U user -d cordacluster -t -c \
  "SELECT usename FROM pg_user WHERE usename LIKE 'vnode_vault_%_dml' ORDER BY usename;"
```

## Verify CPK Manifest
```bash
unzip -p workflows/build/libs/workflows-1.0-SNAPSHOT.jar META-INF/MANIFEST.MF | grep -i "platform"
```

## Verify Entity Annotations
```bash
grep -c "@CordaSerializable" contracts/src/main/kotlin/za/co/payfi/clearing/persistence/Entities.kt
find . -path "*/persistence/Entities.kt" -not -path "./build/*"
```

## Restart Nginx
```bash
nginx -t && systemctl restart nginx
```

---

# 14. SARB Observer Vault Architecture

## The Finding
The SARB observer vnode's UTXO vault is empty. `QueryPaymentInstructionsFlow` returns `[]` for the SARB hash even after multiple successful payments between BankAlpha and BankBeta.

## Root Cause
`PaymentInstructionState.participantKeys` contains only the two bank keys (debtor bank + creditor bank). SARB is NOT a participant:

```kotlin
// In PaymentInstructionState:
val participantKeys: List<PublicKey>  // Debtor bank + creditor bank keys. SARB is NOT included.
```

Per the architecture design, SARB receives transaction data via a separate `session.send()` call after finality — not through the UTXO participant mechanism. This means:
- The state is NOT recorded in SARB's vault as a UTXO
- `findUnconsumedStatesByExactType()` (used by `QueryPaymentInstructionsFlow`) returns nothing
- The SARB responder flow receives and presumably stores data, but NOT as a queryable UTXO state

## Resolution Options

### Option A: Add SARB to participantKeys (Simplest for Demo)
Modify `SubmitPaymentInstructionFlow` to include SARB's public key in `participantKeys`:
```kotlin
val sarbKey = sarbMemberInfo.ledgerKeys.first()
val state = PaymentInstructionState(
    // ... all fields ...
    participantKeys = listOf(debtorBankKey, creditorBankKey, sarbKey)
)
```
**Pros:** SARB vault query works immediately with existing `QueryPaymentInstructionsFlow`. No new flows needed.
**Cons:** Changes the Corda privacy model — SARB becomes a full participant (can sign, receives state updates). SARB also receives the state TWICE (once as participant, once via `session.send()`). Contract verification may need updating. Requires CPI rebuild + full redeploy.

### Option B: SARB-Specific Query Flow (Architecturally Correct)
Create a separate flow that queries SARB's off-ledger storage (the DTO data sent via `session.send()` would need to be persisted by the SARB responder flow into a custom JPA table).
**Pros:** Preserves the intended privacy model. SARB sees data but is not a ledger participant.
**Cons:** Requires new JPA entity for SARB data, new table in SARB vault schema, new query flow, and SARB responder flow modification to persist the DTO.

### Option C: Frontend Aggregation (No Backend Changes)
Have the frontend query BankAlpha and BankBeta vaults and merge the results for the SARB panel display.
**Pros:** Zero backend changes.
**Cons:** SARB panel shows data from bank vaults, not from SARB's own perspective. Does not demonstrate true regulatory observer architecture. Duplicates may appear if both banks return the same transaction.

### Recommendation
~~For the demo/pilot: **Option A** (add SARB to participantKeys).~~

**v4.0 UPDATE: Option B is now fully implemented.** See Section 16 for the complete SARB observer backend architecture. The SARB responder persists DTOs to `sarb_transaction_records`, `QuerySarbTransactionsFlow` reads from that table, and `MarkSarbSettledFlow` archives records after settlement. The frontend SARB panel pulls data from the SARB vnode's own off-ledger storage — not from the bank vaults.

---

# 15. Settlement Architecture

## Overview (v4.0)
Settlement consumes unconsumed UTXO states on the bank vaults and marks SARB records as settled in the off-ledger table. After settlement, vault queries return empty — a clean slate for the next clearing cycle.

## Contract Command: Settle
Added to `PaymentInstructionContract.PaymentCommand`:
```kotlin
class Settle : PaymentCommand()
```

Verification:
```kotlin
private fun verifySettle(transaction: UtxoLedgerTransaction) {
    val inputs = transaction.getInputStates(PaymentInstructionState::class.java)
    require(inputs.isNotEmpty()) { "Settle: must consume at least one state" }
    val outputs = transaction.getOutputStates(PaymentInstructionState::class.java)
    require(outputs.isEmpty()) { "Settle: must not produce any output states" }
}
```

The Settle command consumes input states and produces no outputs — the states are archived on the ledger (still exist in vault history for audit, but no longer appear in `findUnconsumedStatesByExactType` queries).

## SettleTransactionsFlow
Initiating flow that consumes all unconsumed `PaymentInstructionState` UTXOs:

```kotlin
@InitiatingFlow(protocol = "settle-transactions")
class SettleTransactionsFlow : ClientStartableFlow {
```

For each unconsumed state:
1. Creates a transaction with the state as input and Settle command
2. Signs the transaction
3. Initiates sessions with counterparty banks for finality
4. Finalises (counterparty receives and accepts the consumption)

Returns: `{"settled": N, "message": "Settlement complete — N transactions archived"}`

## SettleTransactionsResponderFlow
```kotlin
@InitiatedBy(protocol = "settle-transactions")
class SettleTransactionsResponderFlow : ResponderFlow {
```

SARB nodes return immediately (not participants in settlement finality). Bank nodes call `receiveFinality` to accept the state consumption.

## MarkSarbSettledFlow
Runs on the SARB vnode after bank-side settlement. Marks all unsettled `SarbTransactionRecord` entries as `settled = true`:

```kotlin
class MarkSarbSettledFlow : ClientStartableFlow {
    // Uses persistenceService.findAll() filtered by !settled
    // Then persistenceService.merge() to update each record
}
```

Returns: `{"settled": N}`

## Settlement Lifecycle
```
1. Banks clear transactions → states are UNCONSUMED in vaults
2. System Operator triggers Net Settlement
3. SettleTransactionsFlow consumes all states → vaults return empty
4. MarkSarbSettledFlow marks SARB records → SARB query returns empty
5. Clean slate — new clearing cycle begins
```

## Frontend Integration
The "⚖ Net Settlement" button:
1. Prompts for confirmation
2. Calls `SettleTransactionsFlow` on BankAlpha
3. If successful, calls `MarkSarbSettledFlow` on SARB vnode
4. Refreshes all panels
5. All four panels clear to show post-settlement state

---

# 16. SARB Observer Backend

## Architecture (v4.0 — Option B Implemented)
The SARB observer node stores transaction data in its own off-ledger JPA table, separate from the UTXO ledger. This preserves the intended privacy model: SARB sees all transactions but is not a ledger participant.

## Data Flow
```
1. SubmitPaymentInstructionFlow finalises the UTXO transaction between banks
2. After finality, flow creates SarbNotificationDto with full FICA data
3. Flow calls session.send(dto) to SARB observer
4. PaymentInstructionResponderFlow (SARB branch) receives DTO
5. Responder persists DTO as SarbTransactionRecord in sarb_transaction_records table
6. QuerySarbTransactionsFlow reads from sarb_transaction_records (filtered by !settled)
7. Frontend SARB panel calls QuerySarbTransactionsFlow on SARB vnode hash
```

## SarbNotificationDto
Extended in v4.0 to include full FICA fields:
```kotlin
@CordaSerializable
data class SarbNotificationDto(
    val stateId: String,
    val instructionId: String,
    val endToEndId: String,
    val transactionId: String,
    val amount: String,
    val currency: String,
    val debtorName: String,
    val debtorIdNumber: String? = null,   // v4.0 — FICA
    val debtorIdType: String? = null,     // v4.0 — FICA
    val debtorAccount: String,
    val debtorAgentBranchCode: String,
    val creditorName: String,
    val creditorAccount: String,
    val creditorAgentBranchCode: String,
    val status: String,
    val feeApplicable: Boolean,
    val feeAmount: String,
    val feeTaxAmount: String? = null,     // v4.0
    val settlementDate: String,
    val remittanceInfo: String? = null    // v4.0
)
```

## SARB Responder Persistence
In `PaymentInstructionResponderFlow`:
```kotlin
if (myOrg == "SARB") {
    val dto = session.receive(SarbNotificationDto::class.java)
    persistenceService.persist("persist-sarb-${dto.stateId}",
        SarbTransactionRecord(
            stateId = dto.stateId,
            instructionId = dto.instructionId,
            // ... all fields mapped from DTO
        )
    )
    return
}
```

## QuerySarbTransactionsFlow
Reads from `sarb_transaction_records` and maps to `PaymentInstructionDto` for frontend compatibility:
```kotlin
class QuerySarbTransactionsFlow : ClientStartableFlow {
    override fun call(requestBody: ClientRequestBody): String {
        val results = persistenceService.findAll(SarbTransactionRecord::class.java)
            .execute().results.filter { !it.settled }
        val dtos = results.map { r -> PaymentInstructionDto(...) }
        return jsonMarshallingService.format(dtos)
    }
}
```

## What SARB Sees
Every transaction submitted to the network, with full FICA-compliant data: debtor name, ID number, ID type, accounts, branch codes, amounts, fees, VAT, settlement dates, and remittance references. This data persists independently of the UTXO lifecycle — even after bank-side settlement, the SARB records remain in the database (marked as `settled = true`) for regulatory audit purposes.

---

# Appendix A: File Layout

```
payfi-cordapp/
├── config/
│   ├── static-network-config.json
│   ├── combined-worker-compose.yaml       # From R3 template (flow-management-tool removed)
│   ├── gradle-plugin-default-key.pem      # From R3 template — CPI signing
│   └── r3-ca-key.pem                      # From R3 template — CPI signing
├── contracts/
│   ├── build.gradle                          # Has kotlin.plugin.jpa + javax.persistence
│   └── src/main/
│       ├── kotlin/za/co/payfi/clearing/
│       │   ├── contracts/
│       │   │   └── PaymentInstructionContract.kt
│       │   ├── persistence/
│       │   │   └── Entities.kt               # ONLY HERE — @CordaSerializable + @Column(name="snake_case")
│       │   └── states/
│       │       └── PaymentInstructionState.kt
│       └── resources/migration/
│           ├── db.changelog-master.xml        # Kept for future (not auto-discovered)
│           └── payfi-001-initial-tables.xml
├── workflows/
│   ├── build.gradle                          # Has cordapp-cpk2 + cordapp-cpb2, no cordaEmbedded, no Prowide
│   └── src/main/kotlin/za/co/payfi/clearing/
│       ├── flows/
│       │   ├── SubmitPaymentInstructionFlow.kt   # Submit + SarbNotificationDto + Responder (with SARB persist)
│       │   ├── LifecycleFlows.kt                 # QueryPaymentInstructionsFlow + PaymentInstructionDto
│       │   │                                     # + QuerySarbTransactionsFlow + SettleTransactionsFlow
│       │   │                                     # + MarkSarbSettledFlow + SettleTransactionsResponderFlow
│       │   └── (other flow files)
│       └── mapper/
│           ├── Pacs008Mapper.kt              # JDK DOM/XPath (no Prowide)
│           └── Pacs002ResponseBuilder.kt     # JDK DOM/Transformer (no Prowide)
│   └── (NO persistence/Entities.kt here — must not exist in workflows)
├── frontend/
│   ├── index.html
│   └── payfi-white-transparent.png
├── gradle.properties                         # Must have platformVersion=50200
├── settings.gradle
├── build.gradle                              # cordapp-configuration APPLIED (not apply false)
└── docs/
    └── PayFi_Corda52_Complete_Reference.md
```

---

# Appendix B: Entity Definitions (Authoritative)

See `contracts/src/main/kotlin/za/co/payfi/clearing/persistence/Entities.kt` for the full source. The 7 entities are:

1. **PaymentMessageMetadata** — Table: `payment_message_metadata` + collection table `metadata_linked_states`
2. **IdempotencyKey** — Table: `idempotency_keys` (unique on message_id + instruction_id)
3. **FeeAccrual** — Table: `fee_accruals`
4. **FeeRule** — Table: `fee_rules`
5. **SettlementReport** — Table: `settlement_reports`
6. **ParticipantStatusRecord** — Table: `participant_status`
7. **SarbTransactionRecord** — Table: `sarb_transaction_records` (v4.0 — off-ledger SARB observer storage with `settled` flag)

---

# Appendix C: Multi-LLM Review Process

The PayFi development uses a multi-LLM review cycle:
1. Claude produces complete rewritten files
2. A combined markdown circulates to ChatGPT, Gemini, Grok, and DeepSeek for review
3. Feedback returns to Claude for triage
4. Claude distinguishes valid fixes from false positives and applies accepted changes

Key findings from this process:
- All 4 LLMs independently agreed on `migration/` as the correct Liquibase path (this was correct for the CPK packaging but Corda still didn't auto-discover it)
- DeepSeek correctly identified that entity definitions in the original brief were wrong
- Gemini correctly warned about PostgreSQL case-sensitivity and predicted the entity-to-contracts fallback
- Grok provided complete, production-ready replacement code
- ChatGPT correctly identified the `@NamedQuery` JPQL trap

## v2.0 Session Findings (30 March 2026)
- The `cordapp-configuration` plugin issue was not caught by any LLM during prior reviews — it manifested only after a git sync disrupted the build
- The duplicate `Entities.kt` across modules was a git merge artifact that persisted undetected through multiple deploy cycles
- PostgreSQL permission model for Corda 5.2 vnode-specific users is undocumented — the DDL/DML user separation and required GRANTs were discovered empirically
- The `platformVersion` property interaction with `cordapp-configuration` plugin is poorly documented by R3 — the template ships with `999` which only works when the plugin is not applied (legacy mode)

## v3.0 Session Findings (31 March 2026)
- `deployCpis` does NOT rebind existing vnodes to the new CPI — this was documented as "usually sufficient for flow-only changes" in v1/v2 but consistently failed in practice. Every code change requires full `stopCordaAndCleanWorkspace` cycle.
- `PaymentInstructionState.participantKeys` containing `List<PublicKey>` (BouncyCastle) is incompatible with Jackson serialization — any flow returning raw states to the REST API will fail. All query flows must use DTOs.
- SARB observer receives data via `session.send()` after finality, not as a UTXO participant. This is architecturally intentional but means vault queries on the SARB vnode return empty. The frontend SARB panel requires either adding SARB to `participantKeys` or building a separate query mechanism.
- Frontend field names (`debtorBranch`, `feeVat`, `debtorId`) don't match DTO field names (`debtorAgentBranchCode`, `feeTaxAmount`, `debtorIdNumber`) — the `mapApiState` JavaScript function needs fallback chains for both naming conventions.
- `docker restart` on any Corda container causes irrecoverable PostgreSQL authentication failure — confirmed across two incidents in this session. The Gradle-managed lifecycle (`stopCordaAndCleanWorkspace` + `startCorda`) is the ONLY safe restart mechanism.
- Connection pool exhaustion cascades quickly when the frontend polls failing flows — 5-second intervals × 3 vnodes = 36 failing flow starts per minute, each holding a connection. Fix the root cause (query DTO) before connecting the frontend.

## v4.0 Session Findings (31 March 2026 — afternoon)
- Corda's UTXO model naturally supports settlement: consuming states makes them disappear from `findUnconsumedStatesByExactType` queries while preserving them in vault history for audit
- Flows that call `flowMessaging.initiateFlow()` MUST have `@InitiatingFlow(protocol = "...")` and the responder MUST have `@InitiatedBy(protocol = "...")` — the error is clear but easy to forget when adding new flows
- SARB off-ledger records require a separate settlement mechanism — the `settled` flag + `MarkSarbSettledFlow` pattern provides a clean archive-without-delete approach
- `SarbNotificationDto` needed to be extended with `debtorIdNumber`, `debtorIdType`, `feeTaxAmount`, and `remittanceInfo` to match the full FICA data visible in the frontend
- `persistenceService.merge()` works for updating existing JPA records (used in `MarkSarbSettledFlow` to set `settled = true`)
- `persistenceService.findAll()` returns ALL records including settled — the `.filter { !it.settled }` must be applied in the flow code, not via a JPA query
- Frontend field name mismatches between DTO and original JavaScript caused multiple silent failures (empty fields, missing data) — always verify the JavaScript `mapApiState` function against the actual DTO field names after any DTO change
- The poll interval at 5s with 3 vnodes generates 36 flow starts per minute. Changed to 15s with panel-open detection to reduce load and prevent auto-closing of expanded details

## Confirmed Current Vnode Hashes (31 March 2026)
- BankAlpha: `166B20F5ED8A`
- BankBeta: `F8CE3D7C528B`
- SARBObserver: `5C84CA45C720`
- NotaryRep1: `2BC18CDA0BCA`

## All Flows Available
| Flow | Module | Protocol | Purpose |
|---|---|---|---|
| `SubmitPaymentInstructionFlow` | SubmitPaymentInstructionFlow.kt | submit-payment-instruction | Submit pacs.008, validate, notarise, return pacs.002 |
| `PaymentInstructionResponderFlow` | SubmitPaymentInstructionFlow.kt | submit-payment-instruction | Counterparty: receive finality. SARB: persist DTO |
| `QueryPaymentInstructionsFlow` | LifecycleFlows.kt | — (ClientStartable) | Query bank vault for unconsumed states (returns DTOs) |
| `QuerySarbTransactionsFlow` | LifecycleFlows.kt | — (ClientStartable) | Query SARB off-ledger table (unsettled records only) |
| `SettleTransactionsFlow` | LifecycleFlows.kt | settle-transactions | Consume all unconsumed UTXO states (post-settlement) |
| `SettleTransactionsResponderFlow` | LifecycleFlows.kt | settle-transactions | Accept state consumption |
| `MarkSarbSettledFlow` | LifecycleFlows.kt | — (ClientStartable) | Mark SARB records as settled |
| `UpdatePaymentStatusFlow` | LifecycleFlows.kt | update-payment-status | Update status (SUBMITTED → VALIDATED → CLEARED) |
| `RequestCancellationFlow` | LifecycleFlows.kt | request-cancellation | Submit camt.056 cancellation request |
| `GenerateSettlementReportFlow` | LifecycleFlows.kt | — (ClientStartable) | Calculate bilateral net positions |
