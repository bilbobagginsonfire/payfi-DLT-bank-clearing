plugins {
    kotlin("jvm") version "1.9.22" apply false
    id("net.corda.plugins.cordapp-cpb2") version "7.0.3" apply false
}

allprojects {
    group = "za.co.payfi.clearing"
    version = "1.0-SNAPSHOT"
    repositories {
        mavenCentral()
        maven { url = uri("https://software.r3.com/artifactory/corda-os-maven") }
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions { jvmTarget = "17"; freeCompilerArgs = listOf("-Xjsr305=strict") }
    }
    tasks.withType<Test> { useJUnitPlatform() }
    dependencies {
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.10.1")
        "testRuntimeOnly"("org.junit.jupiter:junit-jupiter-engine:5.10.1")
    }
}
