#!/usr/bin/env bash
# setup.sh — one-time project bootstrap
set -euo pipefail

LLAMA_DIR="app/src/main/cpp/llama.cpp"

echo "▶ Checking llama.cpp submodule…"
if [ ! -f "$LLAMA_DIR/CMakeLists.txt" ]; then
    echo "  Cloning llama.cpp (this takes a minute)…"
    git submodule add https://github.com/ggerganov/llama.cpp "$LLAMA_DIR"
    git submodule update --init --recursive
else
    echo "  llama.cpp already present. Pulling latest…"
    git -C "$LLAMA_DIR" pull --ff-only
fi

echo ""
echo "▶ Model download hint:"
echo "  Download a Gemma 4 2B GGUF from HuggingFace, e.g.:"
echo "  https://huggingface.co/bartowski/google_gemma-4-2b-it-GGUF"
echo ""
echo "  Recommended quantisation for mobile: Q4_K_M (~1.5 GB)"
echo ""
echo "  Push to device:"
echo "    mkdir -p /sdcard/Download/models"
echo "    adb push gemma-4-2b-it-Q4_K_M.gguf /sdcard/Download/models/"
echo ""
echo "▶ Build:"
echo "  ./gradlew assembleDebug"
echo "  adb install -r app/build/outputs/apk/debug/app-debug.apk"
echo ""
echo "Done."
