plugins {
    id("com.android.library")
    alias(libs.plugins.jetbrains.kotlin.android)
    id("maven-publish")
    id("signing")
}

android {
    namespace = "com.hw.sdk.mylibrary"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        targetSdk = 34

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"]) // 修改这里

                groupId = "io.github.Huawei-yuan"
                artifactId = "mysdk"
                version = "1.0.0"

                pom {
                    name.set("MyAndroidSDK")
                    description.set("A simple SDK example for Maven Central publication")
                    url.set("https://github.com/Huawei-yuan/MyAndroidSDK")

                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }

                    developers {
                        developer {
                            id.set("Huawei-yuan")
                            name.set("Huawei Yuan")
                            email.set("huawei.yuan16@gmail.com")
                        }
                    }

                    scm {
                        connection.set("scm:git:github.com/Huawei-yuan/MyAndroidSDK.git")
                        developerConnection.set("scm:git:ssh://github.com/Huawei-yuan/MyAndroidSDK.git")
                        url.set("https://github.com/Huawei-yuan/MyAndroidSDK")
                    }
                }
            }
        }

        repositories {
            maven {
                name = "sonatype"
                url = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
                credentials {
                    username = findProperty("ossrhUsername") as String?
                    password = findProperty("ossrhPassword") as String?
                }
            }
        }
    }

    signing {
        sign(publishing.publications["release"])
    }
}

