#!/data/data/com.termux/files/usr/bin/sh
set -eu
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SDK_DIR=/data/data/com.termux/files/home/.cache/android-api/android-35
RESOURCE_SDK_DIR=/data/data/com.termux/files/home/.cache/android-api/android-9
TOOLS_DIR=/data/data/com.termux/files/usr/bin
OUT="$PROJECT_DIR/build"
GEN="$OUT/gen"
CLASSES="$OUT/classes"
DEX="$OUT/dex"
RES_COMPILED="$OUT/res-compiled.zip"
RES_APK="$OUT/resources.apk"
KEYSTORE="$PROJECT_DIR/spotify-vault-release.keystore"

echo ">> Limpando build..."
rm -rf "$OUT"
mkdir -p "$GEN" "$CLASSES" "$DEX"

echo ">> Compilando recursos (aapt2)..."
"$TOOLS_DIR/aapt2" compile --dir "$PROJECT_DIR/res" -o "$RES_COMPILED"
"$TOOLS_DIR/aapt2" link \
    -I "$RESOURCE_SDK_DIR/android.jar" \
    --manifest "$PROJECT_DIR/AndroidManifest.xml" \
    --java "$GEN" \
    --min-sdk-version 24 \
    --target-sdk-version 35 \
    --auto-add-overlay \
    -o "$RES_APK" -R "$RES_COMPILED"

echo ">> Compilando Java..."
find "$PROJECT_DIR/src" "$GEN" -type f -name '*.java' -print > "$OUT/sources.list"
javac --release 8 -parameters -encoding UTF-8 \
    -classpath "$SDK_DIR/android.jar" \
    -d "$CLASSES" \
    @"$OUT/sources.list"

echo ">> Gerando JAR..."
jar cf "$OUT/classes.jar" -C "$CLASSES" .

echo ">> Dexando (d8)..."
"$TOOLS_DIR/d8" --release --min-api 24 --lib "$SDK_DIR/android.jar" \
    --output "$DEX" "$OUT/classes.jar"

echo ">> Montando APK..."
cp "$RES_APK" "$OUT/unsigned.apk"
jar uf "$OUT/unsigned.apk" -C "$DEX" classes.dex
jar uf "$OUT/unsigned.apk" -C "$PROJECT_DIR" assets

echo ">> Alinhando (zipalign)..."
"$TOOLS_DIR/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/SpotifyVault-aligned.apk"

if [ ! -f "$KEYSTORE" ]; then
    echo ">> Gerando keystore..."
    keytool -genkeypair -noprompt \
        -keystore "$KEYSTORE" \
        -storepass spotifyvault \
        -keypass spotifyvault \
        -alias spotifyvault \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=SpotifyVault, OU=Local, O=SpotifyVault, L=Local, ST=Local, C=BR"
fi

echo ">> Assinando..."
"$TOOLS_DIR/apksigner" sign \
    --ks "$KEYSTORE" \
    --ks-pass pass:spotifyvault \
    --key-pass pass:spotifyvault \
    --out "$OUT/SpotifyVault.apk" \
    "$OUT/SpotifyVault-aligned.apk"

echo ">> Verificando assinatura..."
"$TOOLS_DIR/apksigner" verify --verbose "$OUT/SpotifyVault.apk"

DOCUMENTS_DIR=/storage/emulated/0/Documents
DOWNLOADS_DIR=/storage/emulated/0/Download
mkdir -p "$DOCUMENTS_DIR" "$DOWNLOADS_DIR" 2>/dev/null || true
cp "$OUT/SpotifyVault.apk" "$DOCUMENTS_DIR/SpotifyVault.apk" 2>/dev/null || echo "Aviso: não foi possível copiar para Documents"
cp "$OUT/SpotifyVault.apk" "$DOWNLOADS_DIR/SpotifyVault.apk" 2>/dev/null || echo "Aviso: não foi possível copiar para Download"

echo ""
echo "✓ APK criado: $OUT/SpotifyVault.apk"
ls -lh "$OUT/SpotifyVault.apk"
echo "✓ Copiado para: $DOCUMENTS_DIR/SpotifyVault.apk"
echo "✓ Copiado para: $DOWNLOADS_DIR/SpotifyVault.apk"
