plugins {
    java
}

group = "cloud.mineplay"
version = "1.0.5"

val mockitoAgent = configurations.create("mockitoAgent")

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/") {
        content { includeGroup("me.clip") }
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

sourceSets {
    main {
        java.setSrcDirs(listOf("src"))
        java.include("cloud/**/*.java")
        resources.setSrcDirs(listOf("src/main/resources"))
    }
    test {
        java.setSrcDirs(listOf("src/test/java"))
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.200-stable")
    compileOnly("me.clip:placeholderapi:2.12.3")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("com.mysql:mysql-connector-j:26.7.0")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.papermc.paper:paper-api:26.3.build.200-stable")
    testImplementation("org.mockito:mockito-core:5.24.0")
    mockitoAgent("org.mockito:mockito-core:5.24.0") { isTransitive = false }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    filesMatching("plugin.yml") { expand("version" to pluginVersion) }
}

tasks.test {
    useJUnitPlatform()
    // Explicit agent avoids runtime self-attach restrictions on Java 25/Windows.
    jvmArgs("-javaagent:${mockitoAgent.singleFile}", "--enable-native-access=ALL-UNNAMED")
}

// The one deployable artifact includes SQLite and its native libraries. Paper supplies
// its API and logging API; no server classes or Vault implementation are bundled.
tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class", "META-INF/versions/**/module-info.class")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.wrapper {
    gradleVersion = "9.8.1"
    distributionType = Wrapper.DistributionType.BIN
    distributionSha256Sum = "dce76f55f8e251a3a1f130eb120f30b3d271de2b76c9b0729d316b5a1b6dc01f"
}

tasks.register<JavaExec>("migrateStorage") {
    group = "application"
    description = "Offline storage copy into an empty target; see README before use."
    classpath = sourceSets.main.get().runtimeClasspath + configurations.compileClasspath.get()
    mainClass.set("cloud.mineplay.mobarena.storage.StorageMigration")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
}
