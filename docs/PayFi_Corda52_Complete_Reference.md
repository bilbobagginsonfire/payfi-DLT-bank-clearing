# PayFi Corda 5.2 — Complete Deployment Reference

## Document Version: 1.0
## Date: 27 March 2026
## Platform: PayFi Interbank ZAR Clearing (ISO 20022) on Corda 5.2
## GitHub: bilbobagginsonfire/payfi-prototype (branch: main)

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
- **Provider:** Hetzner
- **OS:** Ubuntu 24
- **IP:** 91.98.156.2 (IPv4), 2a01:4f8:c17:88a0::1 (IPv6)
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

## Gradle Properties (gradle.properties)
```properties
cordaApiVersion=5.2.0.52
cordaNotaryPluginsVersion=5.2.0.0
cordaPluginsVersion=7.0.4
cordaGradlePluginVersion=5.2.0.0
kotlinVersion=1.7.21
org.gradle.jvmargs=-Xmx4g
```

The `-Xmx4g` is required if embedding large libraries (e.g., Prowide) — the default heap causes OOM during OSGi analysis.

## Docker Compose Fix
The template's Docker Compose references the dead `openjdk:17-jdk` image. Fix in `docker-compose.yml`:

```yaml
# Replace:
image: openjdk:17-jdk
# With:
image: eclipse-temurin:17-jdk
```

Also remove the `flow-management-tool` service if present — it has no Dockerfile.

## Static Network Config
File: `config/static-network-config.json`
```json
[
  {"x500Name":"CN=BankAlpha, OU=PayFi, O=BankAlpha, L=Johannesburg, C=ZA", "cpi":"MyCorDapp"},
  {"x500Name":"CN=BankBeta, OU=PayFi, O=BankBeta, L=Cape Town, C=ZA", "cpi":"MyCorDapp"},
  {"x500Name":"CN=SARBObserver, OU=PayFi, O=SARB, L=Pretoria, C=ZA", "cpi":"MyCorDapp"},
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

## Entity Location
Entities MUST be in the **contracts** module for Corda 5.2 sandbox visibility:
```
contracts/src/main/kotlin/za/co/payfi/clearing/persistence/Entities.kt
```

NOT in the workflows module. If entities are in workflows, the persistence sandbox cannot find them.

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
# Should return 6 (one per entity) or 7 (including import)
```

Fix if missing:
```bash
sed -i 's/^@Entity/@CordaSerializable\n@Entity/' contracts/src/main/kotlin/za/co/payfi/clearing/persistence/Entities.kt
grep -q "import net.corda.v5.base.annotations.CordaSerializable" <file> || \
sed -i '/^import javax.persistence/a import net.corda.v5.base.annotations.CordaSerializable' <file>
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
**Manual PostgreSQL table creation in `vnode_vault_*` schemas.** See Section 8.

---

# 8. Manual Database Table Creation

Since Corda 5.2 does not auto-run custom Liquibase migrations, tables must be created manually in each vnode's vault schema after vnode creation.

## Step 1: Get Vault Schemas
```bash
docker exec corda-cluster-postgresql-1 psql -U postgres -d cordacluster -t -c \
  "SELECT schema_name FROM information_schema.schemata WHERE schema_name LIKE 'vnode_vault_%';"
```

## Step 2: Create Tables in Each Schema
For each `vnode_vault_<hash>` schema, run:

```sql
SET search_path TO vnode_vault_<hash>;

CREATE TABLE IF NOT EXISTS payment_message_metadata (
    id uuid PRIMARY KEY,
    original_message_id varchar(255) NOT NULL,
    message_creation_time timestamp NOT NULL,
    number_of_transactions int NOT NULL,
    message_digest varchar(64) NOT NULL,
    received_at timestamp NOT NULL
);

CREATE TABLE IF NOT EXISTS metadata_linked_states (
    metadata_id uuid NOT NULL REFERENCES payment_message_metadata(id),
    state_id uuid NOT NULL
);

CREATE TABLE IF NOT EXISTS idempotency_keys (
    id uuid PRIMARY KEY,
    message_id varchar(255) NOT NULL,
    instruction_id varchar(255) NOT NULL,
    created_at timestamp NOT NULL,
    response_payload text NOT NULL,
    state_id uuid NOT NULL,
    UNIQUE(message_id, instruction_id)
);

CREATE TABLE IF NOT EXISTS fee_accruals (
    id uuid PRIMARY KEY,
    state_id uuid NOT NULL,
    creditor_bank_branch_code varchar(6) NOT NULL,
    fee_amount decimal(10,2) NOT NULL,
    tax_amount decimal(10,2) NOT NULL,
    total_amount decimal(10,2) NOT NULL,
    transaction_amount decimal(19,2) NOT NULL,
    accrual_date date NOT NULL,
    invoiced boolean DEFAULT false,
    invoice_reference varchar(255),
    reversed boolean DEFAULT false,
    reversal_note varchar(500)
);

CREATE TABLE IF NOT EXISTS fee_rules (
    id uuid PRIMARY KEY,
    threshold_amount decimal(19,2) NOT NULL,
    fee_amount decimal(10,2) NOT NULL,
    currency varchar(3) DEFAULT 'ZAR',
    fee_payer varchar(50) DEFAULT 'CREDITOR_BANK',
    effective_from timestamp NOT NULL
);

CREATE TABLE IF NOT EXISTS settlement_reports (
    id uuid PRIMARY KEY,
    bank_branch_code varchar(6) NOT NULL,
    window_start timestamp NOT NULL,
    window_end timestamp NOT NULL,
    net_amount decimal(19,2) NOT NULL,
    gross_payable decimal(19,2) NOT NULL,
    gross_receivable decimal(19,2) NOT NULL,
    transaction_count int NOT NULL,
    generated_at timestamp NOT NULL
);

CREATE TABLE IF NOT EXISTS participant_status (
    id uuid PRIMARY KEY,
    branch_code varchar(6) NOT NULL,
    status varchar(20) NOT NULL,
    effective_from timestamp NOT NULL,
    reason varchar(500) NOT NULL,
    initiated_by varchar(255) NOT NULL,
    reinstated_at timestamp
);
```

## Important Notes
- Tables MUST be in `vnode_vault_<hash>` schemas, NOT in `public`
- Tables MUST be created AFTER `vNodesSetup` (schemas don't exist until vnodes are created)
- If you run `stopCordaAndCleanWorkspace`, the database is wiped — tables must be recreated
- Column names must be snake_case matching the `@Column(name = "...")` annotations exactly
- Do NOT use `gen_random_uuid()` defaults — Kotlin generates UUIDs
- Run the SQL as a single `docker exec` command per heredoc to avoid exhausting PostgreSQL connections

## One-Command Table Creation Script
```bash
docker exec -i corda-cluster-postgresql-1 psql -U postgres -d cordacluster << 'SQL'
-- Repeat the full CREATE TABLE block for each vnode_vault_<hash> schema
-- Use SET search_path TO <schema>; before each block
SQL
```

---

# 9. Deployment Workflow

## Full Clean Deploy (from scratch)

```bash
cd ~/payfi-cordapp

# 1. Build
./gradlew clean build

# 2. Start Corda (wipes everything)
./gradlew stopCordaAndCleanWorkspace
./gradlew startCorda
# Wait for API: curl -sk -u admin:admin https://localhost:8888/api/v5_2/cpi

# 3. Deploy CPI and create vnodes
./gradlew vNodesSetup -x startCorda

# 4. Create database tables (MUST be after vNodesSetup)
# Get vault schemas, then run SQL per schema (see Section 8)

# 5. Test
ALPHA_HASH=$(./gradlew listVNodes 2>&1 | grep BankAlpha | awk '{print $2}')
curl -sk -u admin:admin -X POST "https://localhost:8888/api/v5_2/flow/$ALPHA_HASH" ...
```

## Code-Only Update (no schema changes)
```bash
./gradlew clean build
./gradlew deployCpis -x startCorda
# Tables persist — no need to recreate
# Test with same vnode hashes
```

## When `stopCordaAndCleanWorkspace` is REQUIRED
- After changing entity classes
- After changing CPI structure (adding/removing CPKs)
- After the first CPI upload to a fresh cluster
- `deployCpis` alone does NOT update existing vnodes with new migrations

## When `deployCpis` is SUFFICIENT
- After changing flow logic only
- After changing contract verification logic
- After changing mapper code

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
# DNAT tcp dpt:80 to:10.42.0.7:80
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
        proxy_set_header Authorization "Basic YWRtaW46YWRtaW4=";
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
- Frontend: http://91.98.156.2:8080/
- API proxy: http://91.98.156.2:8080/api/

## Nginx Heredoc Warning
When using `cat > ... << EOF` with nginx configs, use `<< 'EOF'` (single-quoted) to prevent shell variable expansion of `$uri`, `$host`, etc. Without quotes, these are interpreted as shell variables and become empty strings.

---

# 11. Testing

## Test Payment (Rejected — No Debtor ID)
```bash
ALPHA_HASH=<current_hash>
curl -sk -u admin:admin -X POST "https://localhost:8888/api/v5_2/flow/$ALPHA_HASH" \
  -H "Content-Type: application/json" \
  -d '{"clientRequestId":"test-001","flowClassName":"za.co.payfi.clearing.flows.SubmitPaymentInstructionFlow","requestBody":{"pacs008Xml":"<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08\"><FIToFICstmrCdtTrf><GrpHdr><MsgId>MSG001</MsgId><CreDtTm>2026-03-27T12:00:00</CreDtTm><NbOfTxs>1</NbOfTxs><SttlmInf><SttlmMtd>CLRG</SttlmMtd></SttlmInf></GrpHdr><CdtTrfTxInf><PmtId><InstrId>INSTR001</InstrId><EndToEndId>E2E001</EndToEndId><TxId>TX001</TxId></PmtId><IntrBkSttlmAmt Ccy=\"ZAR\">5000.00</IntrBkSttlmAmt><Dbtr><Nm>John Doe</Nm></Dbtr><DbtrAcct><Id><Othr><Id>1234567890</Id></Othr></Id></DbtrAcct><DbtrAgt><FinInstnId><ClrSysMmbId><MmbId>100001</MmbId></ClrSysMmbId></FinInstnId></DbtrAgt><Cdtr><Nm>Jane Smith</Nm></Cdtr><CdtrAcct><Id><Othr><Id>0987654321</Id></Othr></Id></CdtrAcct><CdtrAgt><FinInstnId><ClrSysMmbId><MmbId>200002</MmbId></ClrSysMmbId></FinInstnId></CdtrAgt></CdtTrfTxInf></FIToFICstmrCdtTrf></Document>"}}'
```

Expected: `RJCT` with `CH09: debtorIdNumber must not be blank`

## Test Payment (Accepted — With Debtor ID)
Add `<Id><PrvtId><Othr><Id>8501015800086</Id></Othr></PrvtId></Id>` inside `<Dbtr>` after `<Nm>`.

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
**Workaround:** Manual table creation in vault schemas (see Section 8).
**Impact:** Every `stopCordaAndCleanWorkspace` requires table recreation.

## Issue 2: `startCorda` Gradle Task Hangs
**Symptom:** `startCorda` sits at 50% even though all Docker containers are healthy and the API responds.
**Workaround:** Check API health in a second terminal. Once API responds, Ctrl+C the Gradle task and proceed with `vNodesSetup -x startCorda`.

## Issue 3: PostgreSQL Connection Exhaustion
**Symptom:** `FATAL: sorry, too many clients already`
**Cause:** Failed flows create retrying persistence requests that hold connections.
**Workaround:** `./gradlew stopCordaAndCleanWorkspace` and start fresh.

## Issue 4: GitHub Pulls Overwrite @CordaSerializable
**Symptom:** After `git checkout payfi/main -- ...`, entity annotations may be missing.
**Workaround:** Always verify and re-add after git operations (see Section 6).

## Issue 5: Vnode Hashes Change on Every Redeploy
**Symptom:** Every `stopCordaAndCleanWorkspace` + `vNodesSetup` generates new vnode short hashes.
**Impact:** Frontend API calls, test scripts, and manual curl commands need updated hashes.
**Workaround:** Always run `./gradlew listVNodes` after deployment and update references.

## Issue 6: `deployCpis` vs Full Redeploy
**Symptom:** `deployCpis` uploads a new CPI but vnodes may use the old one (cached sandbox).
**Workaround:** For entity/annotation changes, always do full `stopCordaAndCleanWorkspace` cycle. For flow-only changes, `deployCpis` is usually sufficient.

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
./gradlew vNodesSetup -x startCorda
# Then create tables (Section 8)
```

## Code-Only Deploy
```bash
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
docker logs corda-cluster-corda-1 2>&1 | grep "relation.*does not exist" | tail -5
docker logs corda-cluster-corda-1 2>&1 | grep "missing requirement" | tail -5
```

## Check Database Tables
```bash
docker exec corda-cluster-postgresql-1 psql -U postgres -d cordacluster -c \
  "SELECT tablename FROM pg_tables WHERE schemaname LIKE 'vnode_vault_%' AND tablename='payment_message_metadata';"
```

## Restart Nginx
```bash
nginx -t && systemctl restart nginx
```

---

# Appendix A: File Layout

```
payfi-cordapp/
├── config/
│   └── static-network-config.json
├── contracts/
│   ├── build.gradle                          # Has kotlin.plugin.jpa + javax.persistence
│   └── src/main/
│       ├── kotlin/za/co/payfi/clearing/
│       │   ├── contracts/
│       │   │   └── PaymentInstructionContract.kt
│       │   ├── persistence/
│       │   │   └── Entities.kt               # @CordaSerializable + @Column(name="snake_case")
│       │   └── states/
│       │       └── PaymentInstructionState.kt
│       └── resources/migration/
│           ├── db.changelog-master.xml        # Kept for future (not auto-discovered)
│           └── payfi-001-initial-tables.xml
├── workflows/
│   ├── build.gradle                          # No cordaEmbedded, no Prowide
│   └── src/main/kotlin/za/co/payfi/clearing/
│       ├── flows/
│       │   ├── SubmitPaymentInstructionFlow.kt
│       │   └── LifecycleFlows.kt
│       └── mapper/
│           ├── Pacs008Mapper.kt              # JDK DOM/XPath (no Prowide)
│           └── Pacs002ResponseBuilder.kt     # JDK DOM/Transformer (no Prowide)
├── frontend/
│   ├── index.html
│   └── payfi-white-transparent.png
├── gradle.properties
├── settings.gradle
└── build.gradle
```

---

# Appendix B: Entity Definitions (Authoritative)

See `contracts/src/main/kotlin/za/co/payfi/clearing/persistence/Entities.kt` for the full source. The 6 entities are:

1. **PaymentMessageMetadata** — Table: `payment_message_metadata` + collection table `metadata_linked_states`
2. **IdempotencyKey** — Table: `idempotency_keys` (unique on message_id + instruction_id)
3. **FeeAccrual** — Table: `fee_accruals`
4. **FeeRule** — Table: `fee_rules`
5. **SettlementReport** — Table: `settlement_reports`
6. **ParticipantStatusRecord** — Table: `participant_status`

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
