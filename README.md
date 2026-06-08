# PFM-1 Detector App
This is an app, that imports a .tflite model and uses it for live object detection - in this case the PFM-1 antipersonnel mine.  
To try the app go to [releases](https://mygit.th-deg.de/bi20450/pfm1-detector-app/-/releases).

<p align="center">
  <img src="Screenshot_20260608-193257.png" width="300" />
  <img src="Screenshot_20260608-193337.png" width="300" />
</p>

The main view includes:
- live camera image
- object bounding box and detection certainty
- bottom bar with buttons for importing a .tflite model and settings

The settings view includes:
- basic logs
- vibration toggle
- sound toggle
- notification interval slider
- detection threshold slider
- button for saving logs to device storage as text file
- two number input fields for specifying downsampling resolution, pre-filled with 320x320

Both the model importing and logs saving utilize the system file manager.  
On successful object detection, the smartphone vibrates and/or makes a sound.


### Under the hood

The app uses LiteRT. The pipeline looks like:  
Get camera feed -> center-crop -> downsample -> LiteRT