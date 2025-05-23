/*
 * This file was generated by the Gradle 'init' task.
 */


plugins {
    id("com.draeger.medical.version-conventions")
    id("com.draeger.medical.java-conventions")
    id("org.somda.sdc.xjc")
}

val jaxb: Configuration by configurations.creating
val schemaDir = "src/main"


dependencies {
    api(enforcedPlatform(libs.com.draeger.medical.sdccc.bom))
    api(libs.org.glassfish.jaxb.jaxb.core)
    api(libs.org.glassfish.jaxb.jaxb.runtime)
    api(libs.jakarta.xml.bind.jakarta.xml.bind.api)
    api(libs.io.github.threeten.jaxb.threeten.jaxb.core)
    api(libs.org.jvnet.jaxb.jaxb.plugins)

    testImplementation(libs.org.junit.jupiter.junit.jupiter.api)
    testImplementation(libs.org.junit.jupiter.junit.jupiter.engine)
}

description = "SDCri is a set of Java libraries that implements a network communication framework conforming " +
    "with the IEEE 11073 SDC specifications. This project implements the model for IEEE 11073-10207."

xjc {
    jaxbClasspath = jaxb
    schemaLocation = layout.projectDirectory.dir(schemaDir)
}

description = "BICEPS model"

val testsJar by tasks.registering(Jar::class) {
    archiveClassifier.set("tests")
    from(sourceSets["test"].output)
}
