#!/bin/bash
set -e

split_module() {
  local MOD=$1
  local NS=$(echo $MOD | tr '-' '_')
  
  echo "Splitting $MOD..."
  
  mkdir -p $MOD/api/src/main/java/com/leica/cam/$NS/api
  mkdir -p $MOD/impl/src/main
  
  if [ -d "$MOD/src/main/java" ]; then
    mv $MOD/src/main/java/com/leica/cam/* $MOD/impl/src/main/java/com/leica/cam/$NS/impl/ 2>/dev/null || true
  fi
  
  if [ -d "$MOD/src" ]; then
    mv $MOD/src $MOD/impl/
  fi
  if [ -f "$MOD/consumer-rules.pro" ]; then
    mv $MOD/consumer-rules.pro $MOD/impl/
  fi
  if [ -f "$MOD/build.gradle.kts" ]; then
    mv $MOD/build.gradle.kts $MOD/impl/
  fi
  
  cat << EOF > $MOD/api/build.gradle.kts
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.leica.cam.${NS}.api"
    compileSdk = 35
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":common"))
}
EOF

  cat << EOF > $MOD/build.gradle.kts
// Root empty project
EOF

  # Modify the implementation's build.gradle.kts to change namespace and add api dependency
  # We use sed to replace the namespace and append the api dependency
  if [ -f "$MOD/impl/build.gradle.kts" ]; then
    sed -i "s/namespace = \"com.leica.cam.${NS}\"/namespace = \"com.leica.cam.${NS}.impl\"/" $MOD/impl/build.gradle.kts
    
    # Simple hack to add dependency
    echo "dependencies { implementation(project(\":${MOD}:api\")) }" >> $MOD/impl/build.gradle.kts
  fi
}

# The modules to split
for M in camera-core native-imaging-core imaging-pipeline color-science hypertone-wb neural-isp; do
  split_module $M
done
