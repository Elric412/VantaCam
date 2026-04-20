#!/bin/bash
set -e

split_module() {
  local MOD=$1
  local NS=$(echo $MOD | tr '-' '_')
  
  echo "Splitting $MOD..."
  
  # Ensure the subdirectories exist
  mkdir -p $MOD/api/src/main/java/com/leica/cam/$NS/api
  
  if [ -d "$MOD/src" ] && [ ! -d "$MOD/impl/src" ]; then
    mkdir -p $MOD/impl
    mv $MOD/src $MOD/impl/
  fi
  
  # if java source was moved to impl/src, move the kotlin files into the right impl package
  if [ -d "$MOD/impl/src/main/java/com/leica/cam" ]; then
    mkdir -p $MOD/impl/src/main/java/com/leica/cam/$NS/impl
    # move everything except $NS itself if it existed
    find $MOD/impl/src/main/java/com/leica/cam -mindepth 1 -maxdepth 1 ! -name "$NS" -exec mv {} $MOD/impl/src/main/java/com/leica/cam/$NS/impl/ \; 2>/dev/null || true
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

  if [ -f "$MOD/impl/build.gradle.kts" ]; then
    sed -i "s/namespace = \"com.leica.cam.[^\"]*\"/namespace = \"com.leica.cam.${NS}.impl\"/" $MOD/impl/build.gradle.kts
    
    # check if the dependency already exists
    if ! grep -q ":${MOD}:api" $MOD/impl/build.gradle.kts; then
      echo "dependencies { implementation(project(\":${MOD}:api\")) }" >> $MOD/impl/build.gradle.kts
    fi
  fi
}

for M in camera-core native-imaging-core imaging-pipeline color-science hypertone-wb neural-isp; do
  split_module $M
done
