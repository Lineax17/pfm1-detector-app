This is an AI university project. 
This is an app, that imports a .tflite model and uses it for live object detection. 

The main view includes:
- live camera image
- object bounding box and detection certainty
- bottom bar for importing a .tflite model and settings

The settings view includes:
- basic logs
- vibration toggle
- button for saving very detailed, advanced logs to device storage as file
- two number input fields for specifying downsampling resolution, pre-filled with 320x320

Both the model importing and advanced logs saving shall utilize the system file manager.  
On successful object detection, the smartphone shall vibrate.  

## 📱 1. Project Profile & Target Hardware Baseline
* **Target Audience:** Modern consumer smartphones with a premium performance floor.
* **Minimum SDK:** API 34 (Android 14.0).
* **UI Framework:** 100% Declarative Jetpack Compose (Material 3).
* **Build System:** Kotlin DSL (`.kts`) utilizing Version Catalogs (`libs.versions.toml`).

---

## ⚡ 2. Core Visual AI Inference Pipeline Blueprint
The application implements a real-time on-device computer vision pipeline using **LiteRT** (formerly TensorFlow Lite).

```
[Camera Sensor] 
       │
       ├──► Use Case A: Preview Viewport ──► Jetpack Compose UI
       │
       └──► Use Case B: ImageAnalysis ────► Center-Crop ──► Downsample
                                                                 │
   ┌─────────────────────────────────────────────────────────────┘
   ▼
[LiteRT Interpreter Pipeline]
   │
   ├──► Hardware: GPU Delegate (Vulkan/OpenCL) with CPU Fallback
   │
   ├──► 16 KB Alignment: Native support for Android 15+ devices
   │
   ├──► Pre-processing: Standard Android APIs (Matrix, Bitmap)
   │
   ├──► Inference: Manual Input/Output Tensor Mapping
   │
   └──► Post-processing: Parsing [Locations, Classes, Scores, Count] tensors
```

### Stage 1: Ingestion (CameraX)
* `ImageAnalysis` Use Case captures frames in `RGBA_8888` format.
* Processing executes on a dedicated background thread pool.

### Stage 2: Manual Pre-Processing (Standard Android APIs)
* Frames are converted to `Bitmap` and processed using standard Android `Matrix` and `Bitmap.createScaledBitmap`.
* Pixels are manually loaded into a `DirectByteBuffer` in RGB format for the LiteRT Interpreter.
* Center-cropping is performed to maintain aspect ratio before scaling to the target resolution.

### Stage 3: Low-Level Inference (Interpreter)
* Utilizes **LiteRT 1.4.2+** for 16 KB page size compatibility on modern Android devices.
* Employs `litert-metadata` for label extraction from the `.tflite` model's `labelmap.txt`.
* Maps multiple output tensors: Locations [1, 10, 4], Classes [1, 10], Scores [1, 10], and Number of Detections [1].

### Stage 4: Coordinate Transformation & Compose Drawing
* Translates relative model coordinates back to the Compose view canvas.
* Renders overlays using Jetpack Compose `Canvas`.

---

## 🛠️ 3. Rules of Engagement for the Gemini Agent

1. **Low-Level Control:** Use the base TFLite Interpreter instead of high-level task libraries for better compatibility with custom/quantized models.
2. **Zero-Allocation Analysis:** Minimize fresh memory allocations inside the `analyze` loop. Use `TensorImage` and recycled buffers where possible.
3. **UDF Architecture Compliance:** Expose inference results through a `ViewModel` utilizing Unidirectional Data Flow (UDF) and `StateFlow`.
4. **Defensive Processing:** Ensure robust fallback from GPU to CPU delegates.
5. **No Legacy Code Overlap:** Target Android 14+ exclusively.
