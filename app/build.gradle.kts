plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

// 버전 이름은 README 맨 끝 '## v…' 에서 읽음, 버전 번호는 빌드 번호로 자동 증가
val readmeVersion: String = run {
    val f = rootProject.file("README.md")
    if (!f.exists()) "0.0" else
        Regex("^## v([0-9][0-9.]*)", RegexOption.MULTILINE).findAll(f.readText())
            .lastOrNull()?.groupValues?.get(1) ?: "0.0"
}
val buildNumber: Int = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0) + 100

android {
    namespace = "kr.mytoilet.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "kr.mytoilet.app"
        minSdk = 24
        targetSdk = 36
        versionCode = buildNumber
        versionName = readmeVersion
        // 키는 gradle.properties의 kakaoKey가 있으면 그걸 쓰고, 없으면 GitHub 비밀값 KAKAO_KEY를 써요
        val kakaoKey = (project.findProperty("kakaoKey") as String?)?.trim().orEmpty()
            .ifBlank { System.getenv("KAKAO_KEY")?.trim().orEmpty() }
        buildConfigField("String", "KAKAO_KEY", "\"$kakaoKey\"")
        // 카카오 REST 키 (건물 이름 추천용): GitHub 비밀값 KAKAO_REST_KEY
        buildConfigField("String", "KAKAO_REST_KEY", "\"${System.getenv("KAKAO_REST_KEY")?.trim() ?: ""}\"")
        buildConfigField("String", "GITHUB_REPO", "\"jonginzz33-code/toilet-memo\"")
    }

    flavorDimensions += "store"
    productFlavors {
        // 내 폰 테스트용: 앱 안에서 바로 업데이트
        create("personal") {
            dimension = "store"
            buildConfigField("String", "UPDATE_MODE", "\"apk\"")
        }
        // 플레이스토어용: 업데이트는 스토어가 담당
        create("play") {
            dimension = "store"
            buildConfigField("String", "UPDATE_MODE", "\"store\"")
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // 출시용 키: GitHub 비밀값에만 보관 (없으면 테스트 키 사용)
        create("upload") {
            val ks = System.getenv("UPLOAD_KEYSTORE_FILE")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("UPLOAD_KEY_PASSWORD")
            } else {
                storeFile = file("debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    buildFeatures { buildConfig = true }

    // 개인정보처리방침 페이지(docs/legal)를 앱 안에도 넣음 (앱에서 메뉴 없이 내용만 보기)
    sourceSets["main"].assets.srcDir(rootProject.file("docs/legal"))

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

android.productFlavors.getByName("play").signingConfig = android.signingConfigs.getByName("upload")
android.productFlavors.getByName("personal").signingConfig = android.signingConfigs.getByName("debug")

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    implementation("com.kakao.maps.open:android:2.12.18")
    implementation("com.google.android.gms:play-services-location:21.3.0")

    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")

    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
}
