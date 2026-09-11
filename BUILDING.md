# MYX 1.2.0

Android 8.0+ / ARM64 offline Xiangqi app and screenshot-specific floating assistant.

## Upgrade

- Official Pikafish 2026-09-06, pinned commit and SHA-256 checks in vendor-manifest.json.
- Default 4 search threads (capped at available processors), configurable 1/2/4.
- Default 256 MB hash, configurable 128/256/512 MB; low-RAM devices use 128 MB.
- Optional opponent-turn prethinking, enabled by default. It analyzes the current confirmed opponent position and warms the same engine's transposition table. It is not UCI predicted-move ponderhit. Speculative bestmove is never dispatched. The real opponent move cancels it and starts a new search with the actual full history.
- Pause, recognition failure, window changes and capture loss stop prethinking. Continuous calculation increases heat and battery use; disable it in Options if needed.
- Random1/Random2, MYX layout, WDL row, selected-piece handling and retry behavior remain.
- New upstream rejects illegal positions with a critical error and exits; MYX pauses rather than accepting a truncated move history.

## Reproduce

Requirements: Linux, Java 17, Python 3, git, 7z, g++, make, Android SDK Platform 35, Build Tools 35.0.0, NDK 27.2.12479018.

```
python3 tools/prepare_vendor.py
python3 tools/build_engine.py --host
python3 tools/test_engine_bridge.py --upstream build/pikafish-official-host
python3 tools/test_core.py
python3 tools/test_cloud.py
python3 tools/build_engine.py --ndk /path/to/android/ndk/27.2.12479018
python3 tools/build.py --unsigned --build-tools /path/to/build-tools/35.0.0 --android-jar /path/to/platforms/android-35/android.jar
```

The MYX Android workflow does this in GitHub Actions. Its output is **unsigned**, not an installable upgrade until signed with the original app certificate. Signing keys are never stored in this repository or sent to Actions. Sign locally using your own original keystore.

## Source and licensing

The app code is GPL-3.0-or-later. The exact upstream source is downloaded and verified by prepare_vendor.py; patch_engine.py adds only the appstate query interface without changing search/evaluation/rules. The patched full upstream source is included in the build artifact. The NNUE has a separate non-commercial license in app/src/main/assets/licenses/NNUE-License.md. Public source does not remove those terms.

User screenshots, signing secrets, and generated build outputs are excluded from this repository. Image regression fixtures remain local. No Android device validation or measured phone playing-strength gain is implied by host tests.
