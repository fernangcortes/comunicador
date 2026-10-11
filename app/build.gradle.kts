plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.comunicador.set"
    compileSdk = 36

    defaultConfig {
        applicationId = "br.comunicador.set"
        minSdk = 26
        targetSdk = 36
        // O WebRTC traz bibliotecas nativas; só as dos celulares atuais (ARM) para o APK não pesar.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        versionCode = 8
        versionName = "0.8"
    }

    // Chave definitiva: no GitHub vem dos segredos (variáveis de ambiente); no computador,
    // de comunicadorKeystore/comunicadorKeystorePassword no gradle.properties do usuário.
    // Ela fica FORA do repositório. Sem ela, o APK sai com a chave de teste (que está no
    // repositório de propósito), e não instala por cima de uma versão da Release.
    val chave = System.getenv("COMUNICADOR_KEYSTORE") ?: findProperty("comunicadorKeystore") as String?
    val senhaChave = System.getenv("COMUNICADOR_KEYSTORE_PASSWORD") ?: findProperty("comunicadorKeystorePassword") as String?
    signingConfigs {
        create("teste") {
            storeFile = file("comunicador-teste.keystore")
            storePassword = "comunicador"
            keyAlias = "comunicador"
            keyPassword = "comunicador"
        }
        if (chave != null && senhaChave != null) {
            create("definitiva") {
                storeFile = file(chave)
                storePassword = senhaChave
                keyAlias = "comunicador"
                keyPassword = senhaChave
            }
        }
    }
    val assinatura = signingConfigs.findByName("definitiva") ?: signingConfigs.getByName("teste").also {
        logger.warn("Aviso: chave definitiva não encontrada; o APK de release sai com a chave de TESTE.")
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("teste")
        }
        release {
            isMinifyEnabled = false
            signingConfig = assinatura
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Avisos do verificador não impedem gerar o APK de teste.
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Motor WebRTC (o mesmo usado pelo LiveKit), para a voz pelo Cloudflare Realtime.
    implementation("io.github.webrtc-sdk:android:137.7151.05")
    // Testes que rodam no computador: protocolo, ADPCM e criptografia.
    testImplementation("junit:junit:4.13.2")
}
