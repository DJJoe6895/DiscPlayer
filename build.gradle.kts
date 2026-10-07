plugins {
    id("java")
    id("com.gradleup.shadow") version "9.6.1"
}

group = "dev.joe"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven(url = "https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
    maven(url = "https://maven.maxhenkel.de/repository/public") {
        name = "maxhenkel"
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
    compileOnly("de.maxhenkel.voicechat:voicechat-api:2.6.0")
    implementation("com.googlecode.soundlibs:mp3spi:1.9.5.4") {
        exclude(group = "junit", module = "junit")
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(26))
}

tasks.withType<JavaCompile> {
    options.release.set(25)
}