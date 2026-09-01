# EO1 custom ffmpeg-kit libraries (HEIC)

Build scripts for **libde265** and **libheif** (CMake, RTTI, llvm-ar).

## FFmpeg LTS n6.0 limitation

ffmpeg-kit **LTS** uses FFmpeg **n6.0**, which has **no** `--enable-libheif` / `--enable-libde265` configure options (upstream FFmpeg does not expose a libheif decoder flag even on master; HEIC demux is native in newer FFmpeg mov code).

So these libraries **cannot** be wired into `ffmpeg`/`ffprobe` on the current LTS AAR. On-device HEIC through FFmpeg requires either:

- Moving off LTS to a newer FFmpeg with native HEIC demux + built-in HEVC, or
- A separate `heif-convert` / JNI path that links libheif directly (scripts here produce the static libs for that).

**Current app behavior:** HEIC/HEIF downloads are converted when FFmpeg can decode them; otherwise Immich **`preview`** (JPEG) is the fallback. Oversized JPEG/PNG resampling still uses FFmpeg with the committed AAR. Rebuild with `--enable-android-zlib` so PNG decode works.

## Apply scripts

```bash
KIT=ffmpeg/ffmpeg-kit
./eo1-ffmpeg/native/apply-to-ffmpeg-kit.sh "$KIT"
# Prints android.sh flags (android-zlib + custom lib download/build)
```

Scripts successfully build static `libde265.a` / `libheif.a` under `prebuilt/android-*-lts/` on macOS with NDK r22 + llvm-ar shims (`debug.sh`).

Jenkins `REBUILD_FFMPEG_NATIVE=1` applies the same flags; FFmpeg configure will skip unknown libheif enable until the kit’s FFmpeg is upgraded — prefer verifying configure before committing a new LFS AAR.
