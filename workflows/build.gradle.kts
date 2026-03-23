plugins { kotlin("jvm") }
val cordaApiVersion = "5.2.0"
val prowideVersion = "SRU2023-9.4.8" // Verify latest at https://github.com/prowide/prowide-iso20022
dependencies {
    implementation(project(":contracts"))
    compileOnly("net.corda:corda-ledger-utxo:$cordaApiVersion")
    compileOnly("net.corda:corda-base:$cordaApiVersion")
    compileOnly("net.corda:corda-application:$cordaApiVersion")
    compileOnly("net.corda:corda-membership:$cordaApiVersion")
    compileOnly("net.corda:corda-notary-plugin-api:$cordaApiVersion")
    implementation("com.prowidesoftware:pw-iso20022:$prowideVersion")
    compileOnly("javax.persistence:javax.persistence-api:2.2")
    testImplementation("net.corda:corda-ledger-utxo:$cordaApiVersion")
    testImplementation("net.corda:corda-base:$cordaApiVersion")
    testImplementation("net.corda:corda-application:$cordaApiVersion")
    testImplementation("com.prowidesoftware:pw-iso20022:$prowideVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.1")
}
