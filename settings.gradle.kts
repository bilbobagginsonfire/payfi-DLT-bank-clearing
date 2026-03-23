rootProject.name = "payfi-platform"
include("contracts")
include("workflows")
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://software.r3.com/artifactory/corda-os-maven") }
    }
}
