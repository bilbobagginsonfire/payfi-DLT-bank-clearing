plugins { kotlin("jvm") }
val cordaApiVersion = "5.2.0"
dependencies {
    compileOnly("net.corda:corda-ledger-utxo:$cordaApiVersion")
    compileOnly("net.corda:corda-base:$cordaApiVersion")
    compileOnly("net.corda:corda-application:$cordaApiVersion")
    testImplementation("net.corda:corda-ledger-utxo:$cordaApiVersion")
    testImplementation("net.corda:corda-base:$cordaApiVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.1")
}
