plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.comunicador.set"
    compileSdk = 34

    defaultConfig {
        applicationId = "br.comunicador.set"
        minSdk = 26
        targetSdk = 34
        // O WebRTC traz bibliotecas nativas; só as dos celulares atuais (ARM) para o APK não pesar.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        versionCode = 8
        versionName = "0.8"
    }

    // Chave de assinatura fixa, guardada no projeto. Sem ela, cada APK gerado no GitHub
    // sairia com uma chave diferente e o celular recusaria instalar a versão nova por cima
    // da antiga. É uma chave de TESTE: antes de publicar na Google Play, troque por uma
    // chave guardada fora do repositório.
    signingConfigs {
        create("teste") {
            storeFile = file("comunicador-teste.keystore")
            storePassword = "comunicador"
            keyAlias = "comunicador"
            keyPassword = "comunicador"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("teste")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("teste")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // Avisos do verificador não impedem gerar o APK de teste.
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Motor WebRTC (o mesmo usado pelo LiveKit), para a voz pelo Cloudflare Realtime.
    implementation("io.github.webrtc-sdk:android:137.7151.05")
}
