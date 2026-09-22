#!/usr/bin/env bash
# Uruchom w terminalu: source scripts/android-env.sh

_visualizer_project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

export JAVA_HOME="$_visualizer_project_root/.local-tools/jdk"
export ANDROID_HOME="$_visualizer_project_root/.local-tools/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$_visualizer_project_root/.local-tools/gradle-8.13/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

unset _visualizer_project_root
