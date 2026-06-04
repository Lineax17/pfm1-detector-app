This is an AI university project. 
This is an app, that imports a .tflite model and uses it for live object detection. 

The main view includes:
- live camera image
- object bounding box and detection certainty
- button at bottom for importing a .tflite model
- button at bottom for opening settings view

The settings view includes:
- basic logs
- button for saving very detailed, advanced logs to device storage as file
- two number input fields for specifying downsampling resolution, pre-filled with 320x320

Both the model importing and advanced logs saving shall utilize the system file manager.  
On successful object detection, the smartphone shall vibrate.  

## 📱 1. Project Profile & Target Hardware Baseline
* **Target Audience:** Modern consumer smartphones with a premium performance floor.
* **Minimum SDK:** API 34 (Android 14.0). This enforces a modern, high-performance runtime, eliminating pre-Android 14 structural adjustments, while retaining massive market coverage for current active hardware.
* **UI Framework:** 100% Declarative Jetpack Compose (Material 3). Absolute restriction: Do NOT generate legacy XML layout architectures, ViewGroups, or traditional view-bindings.
* **Build System:** Kotlin DSL (`.kts`) utilizing strict typing, mapping all dependencies directly inside Version Catalogs (`libs.versions.toml`).

---

## ⚡ 2. Core Visual AI Inference Pipeline Blueprint
The application implements a cross-platform, real-time on-device computer vision pipeline. Because the app runs on diverse, modern SoC architectures (Snapdragon, MediaTek, Exynos, Tensor), the framework must handle runtime hardware assignments defensively:

---

```
[Camera Sensor] 
       │
       ├──► Use Case A: Preview Viewport ──► Jetpack Compose UI (1080p Full Frame)
       │
       └──► Use Case B: ImageAnalysis ────► Center-Crop (1:1 Square) ──► Downsample (320x320)
                                                                              │
   ┌──────────────────────────────────────────────────────────────────────────┘
   ▼
[LiteRT Runtime Initialization Pipeline]
   │
   ├──► Try Route 1: GPU Delegate (Vulkan / OpenCL) ──► Success? ──► Execute on SoC GPU
   │                                                                     │
   └──► Try Route 2 (Fallback): XNNPACK CPU Delegate ◄───────────────────┘ (If GPU Fails)
                                     │
[Bounding Box Canvas] ◄── Coordinate Transformation ◄────────────────────┘
```

### Stage 1: Ingestion & High-Performance Stream Sharing (CameraX)
* Bind two distinct Use Cases to the CameraProvider lifecycle context concurrently:
    1. **`Preview` Use Case:** Targets full-frame resolution matching the screen aspect ratio, piped straight into a Compose `PreviewView` layout.
    2. **`ImageAnalysis` Use Case:** Utilizes `ResolutionSelector` to request low-overhead inputs (e.g., VGA 640x480). This must execute exclusively on a dedicated background execution thread pool (`Executors.newSingleThreadExecutor()`) to decouple processing from the UI framework.

### Stage 2: Balanced Pre-Processing & Scaling
* **Geometry Guardrail:** To prevent stretching or geometric distortions that compromise model evaluation, crop incoming frames to a **1:1 square aspect ratio from the center visual coordinates** before scaling down to the model's native input canvas (e.g., 320x320).
* Leverage `MediaPipe ImageBuilder` or direct `TensorImage` allocations to bypass memory-copy overhead (Zero-copy layout conversion preferred).

### Stage 3: Cross-Vendor Hardware Routing via LiteRT (Google AI Edge)
* Load local `.tflite` model bundles packaged with integrated Metadata.
* **Dynamic Delegate Strategy:** To maximize performance on diverse modern GPUs, implement a robust try-catch compilation cascade:
    1. Attempt to initialize the **LiteRT GPU Delegate** (targeting Vulkan/OpenCL) to compute vector layers directly on the device's graphics processing unit.
    2. If compilation fails at runtime due to vendor driver limitations, catch the exception cleanly and fall back to the **XNNPACK-backed CPU Delegate**. Configure multi-threading explicitly mapped to the host processing pool (`setNumThreads(Runtime.getRuntime().availableProcessors() / 2)`).
* All inference operations run strictly within an asynchronous Kotlin Coroutines scope (`Dispatchers.Default`), preserving 60 FPS UI responsiveness.

### Stage 4: Dynamic Coordinate Transformation & Compose Drawing
* The model produces relative layout bounding dimensions ([0.0, 1.0]).
* Calculate a transformation scaling matrix to translate those coordinates dynamically back onto the actual aspect ratio, sizing, and orientation of the active Jetpack Compose view canvas.
* Render boundary overlays utilizing standard Jetpack Compose `Canvas` layouts drawn directly on top of the viewfinder panel.

---

## 🛠️ 3. Rules of Engagement for the Gemini Agent

When writing, refactoring, or reviewing code in this repository, you must observe these operational instructions:

1. **Zero-Allocation Analysis:** Never allocate fresh memory buffers, custom data wrappers, or objects inside the continuous `ImageAnalysis.Analyzer` loop. All structures must be instantiated once at configuration time and recycled.
2. **UDF Architecture Compliance:** Expose inference analysis streams through a lifecycle-aware Android `ViewModel` utilizing Unidirectional Data Flow (UDF). Expose data as an immutable state wrapper (`StateFlow`).
3. **Defensive Processing:** Ensure software fallback paths are robust. The application must never crash or hang if a hardware-accelerated delegate fails to load or experiences a thermal block.
4. **No Legacy Code Overlap:** Completely ignore old backwards-compatibility wrappers, old permission flows, or obsolete compilation checks required by pre-Android 14 operating systems.