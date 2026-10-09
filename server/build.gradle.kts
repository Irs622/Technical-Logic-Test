plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}


application {
    mainClass.set("shop.MainKt")
}

val ktor = "2.3.12"

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:$ktor")
    implementation("io.ktor:ktor-server-netty-jvm:$ktor")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("com.h2database:h2:2.2.224")
    implementation("com.zaxxer:HikariCP:5.1.0")
    runtimeOnly("com.mysql:mysql-connector-j:8.4.0")      // hanya dipakai bila MYSQL_URL diset
    implementation("ch.qos.logback:logback-classic:1.4.14")

    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktor")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

tasks.test {
    useJUnit()
    // Tanpa ini Gradle menganggap tes 'up-to-date' saat hanya MYSQL_URL yang berubah.
    inputs.property("mysqlUrl", providers.environmentVariable("MYSQL_URL").orElse(""))
    maxHeapSize = "1g"
    testLogging { events("passed", "failed"); showStandardStreams = false }
}
