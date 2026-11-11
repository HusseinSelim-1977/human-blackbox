# Human BlackBox Edge AI Emergency Detection System - Contracts

Shared data types, interfaces, and type definitions that all senior specialists must follow exactly.

# Data Types

## AudioChunk
A single audio frame/segment from the microphone sensor.

```python
class AudioChunk:
    """A single audio segment for detection."""
    def __init__(self,
                 audio_data: list[float],
                 timestamp: float,
                 duration_sec: float,
                 source_id: str):
        """
        Args:
            audio_data: List of amplitude values (mono), typically 16-bit samples
            timestamp: Unix timestamp in seconds when chunk was captured
            duration_sec: Length of chunk in seconds (e.g., 0.1 for 100ms)
            source_id: Unique identifier for the audio source (e.g., "mic-1")
        """
        self.audio_data = audio_data
        self.timestamp = timestamp
        self.duration_sec = duration_sec
        self.source_id = source_id
```

## ImuSample
A single inertial measurement unit sample (accelerometer + gyroscope + magnetometer).

```python
class ImuSample:
    """A single IMU measurement."""
    def __init__(self,
                 timestamp: float,
                 accelerometer: list[float],   # [x, y, z] in m/s^2
                 gyroscope: list[float],      # [x, y, z] in rad/s
                 magnetometer: list[float],   # [x, y, z] in μT
                 temperature: float,          # in °C
                 pressure: float,             # in hPa
                 source_id: str):
        """
        Args:
            timestamp: Unix timestamp in seconds
            accelerometer: 3-element list of acceleration values
            gyroscope: 3-element list of angular velocity values
            magnetometer: 3-element list of magnetic field values
            temperature: Temperature in Celsius
            pressure: Pressure in hPa
            source_id: Unique identifier for the IMU source
        """
        self.timestamp = timestamp
        self.accelerometer = accelerometer
        self.gyroscope = gyroscope
        self.magnetometer = magnetometer
        self.temperature = temperature
        self.pressure = pressure
        self.source_id = source_id
```

## Frame
A single vision frame from the edge camera.

```python
class Frame:
    """A single camera frame for detection."""
    def __init__(self,
                 image_data: list[int],      # Flattened pixel values or reference to buffer
                 timestamp: float,
                 width: int,
                 height: int,
                 source_id: str):
        """
        Args:
            image_data: List of pixel values (e.g., RGB format flattened), or a reference to the frame buffer
            timestamp: Unix timestamp in seconds when frame was captured
            width: Frame width in pixels
            height: Frame height in pixels
            source_id: Unique identifier for the camera source (e.g., "cam-1")
        """
        self.image_data = image_data
        self.timestamp = timestamp
        self.width = width
        self.height = height
        self.source_id = source_id
```

## ModalityScore
A score from a single modality detector (audio, motion, vision).

```python
class ModalityScore:
    """Score from a single modality detector."""
    def __init__(self,
                 score: float,                # Normalized score between 0.0 and 1.0
                 confidence: float,           # Confidence between 0.0 and 1.0
                 decision: str,               # "EMERGENCY", "NORMAL", or "INDETERMINATE"
                 timestamp: float,
                 modality_type: str,          # "AUDIO", "MOTION", "VISION"
                 raw_features: dict):         # Raw features used to compute the score
        """
        Args:
            score: Normalized detection score (0.0 = low confidence, 1.0 = high confidence)
            confidence: Confidence in the score (0.0 = no confidence, 1.0 = full confidence)
            decision: One of "EMERGENCY", "NORMAL", "INDETERMINATE"
            timestamp: Unix timestamp in seconds
            modality_type: Type of modality ("AUDIO", "MOTION", "VISION")
            raw_features: Dictionary of raw features (e.g., spectrogram, IMU vector, bounding boxes)
        """
        self.score = score
        self.confidence = confidence
        self.decision = decision
        self.timestamp = timestamp
        self.modality_type = modality_type
        self.raw_features = raw_features
```

# Interfaces

All interfaces must be implemented with the exact method signatures specified.

## RollingBuffer
Encrypted rolling buffer that stores the last N seconds of raw sensor data (audio, motion, vision) and computed scores.

```python
class RollingBuffer:
    """
    Encrypted rolling buffer for raw sensor data and computed scores.
    Stores up to `buffer_size_sec` seconds of data and automatically expires old data.
    All data is encrypted with a key derived from the device-unique secret.
    """
    
    def __init__(self,
                 buffer_size_sec: int,
                 encryption_key: str):
        """
        Args:
            buffer_size_sec: Maximum number of seconds of data to retain
            encryption_key: Key used for encryption (device-unique)
        """
        pass
    
    def append(self,
               data_chunk: Union[AudioChunk, ImuSample, Frame],
               modality_score: ModalityScore):
        """
        Append a new data chunk and modality score to the buffer.
        Automatically expires data older than buffer_size_sec.
        
        Args:
            data_chunk: The raw sensor data chunk to store
            modality_score: The modality score computed from the data chunk
        """
        pass
    
    def get_recent(self,
                   seconds: int) -> dict:
        """
        Get all data from the last N seconds.
        
        Args:
            seconds: Number of seconds of recent data to retrieve
            
        Returns:
            Dictionary with keys "audio", "motion", "vision" each containing the relevant chunks
        """
        pass
    
    def load_from_storage(self) -> None:
        """
        Load previously stored buffer from encrypted storage.
        """
        pass
    
    def save_to_storage(self) -> None:
        """
        Save the current buffer to encrypted storage.
        """
        pass
```

## AudioDetector.predict
Audio detection interface.

```python
class AudioDetector:
    """
    YAMNet-based audio detector that identifies emergency sounds.
    """
    
    def __init__(self,
                 model_path: str,
                 config: dict):
        """
        Args:
            model_path: Path to the trained YAMNet model file
            config: Configuration dict including thresholds and preprocessing settings
        """
        pass
    
    def predict(self,
                audio_chunk: AudioChunk,
                config: dict) -> ModalityScore:
        """
        Run audio detection on a single audio chunk.
        
        Args:
            audio_chunk: The audio data to detect
            config: Detection configuration
            
        Returns:
            ModalityScore with score, confidence, decision, etc.
        """
        pass
```

## MotionDetector.predict
Motion LSTM-based detector that identifies abnormal human motion patterns.

```python
class MotionDetector:
    """
    LSTM-based motion detector that identifies emergency motion patterns.
    """
    
    def __init__(self,
                 model_path: str,
                 config: dict):
        """
        Args:
            model_path: Path to the trained LSTM model file
            config: Configuration dict including thresholds and preprocessing settings
        """
        pass
    
    def predict(self,
                imu_sample: ImuSample,
                config: dict) -> ModalityScore:
        """
        Run motion detection on a single IMU sample.
        
        Args:
            imu_sample: The IMU data to detect
            config: Detection configuration
            
        Returns:
            ModalityScore with score, confidence, decision, etc.
        """
        pass
```

## VisionDetector.predict
YOLOv8-nano based vision detector that identifies emergency visual cues.

```python
class VisionDetector:
    """
    YOLOv8-nano based vision detector that identifies emergency visual cues.
    """
    
    def __init__(self,
                 model_path: str,
                 config: dict):
        """
        Args:
            model_path: Path to the trained YOLOv8-nano model file
            config: Configuration dict including thresholds and preprocessing settings
        """
        pass
    
    def predict(self,
                frame: Frame,
                config: dict) -> ModalityScore:
        """
        Run vision detection on a single frame.
        
        Args:
            frame: The camera frame to detect
            config: Detection configuration
            
        Returns:
            ModalityScore with score, confidence, decision, etc.
        """
        pass
```

## FusionStrategy.decide
Fusion strategy that combines modality scores to produce a final decision.

```python
class FusionStrategy:
    """
    Fusion strategy that combines modality scores to produce a final decision.
    Uses weighted averaging with threshold-based decision.
    """
    
    def __init__(self,
                 config: dict):
        """
        Args:
            config: Fusion configuration including weights and thresholds
        """
        pass
    
    def decide(self,
               modality_scores: list[ModalityScore],
               config: dict) -> ModalityScore:
        """
        Combine modality scores to produce a final decision.
        
        Args:
            modality_scores: List of ModalityScore objects from all modalities
            config: Fusion configuration
            
        Returns:
            ModalityScore with final score, confidence, and decision ("EMERGENCY" or "NORMAL")
        """
        pass
```

## AlertSender.send
Alert sender that sends emergency alert after user-cancellable countdown.

```python
class AlertSender:
    """
    Sends emergency alert after a 15-second user-cancellable countdown.
    Includes encryption, timestamping, and user consent verification.
    """
    
    def __init__(self,
                 device_id: str,
                 encryption_key: str,
                 user_consent_manager: object):
        """
        Args:
            device_id: Unique device identifier
            encryption_key: Key for encrypting alert payload
            user_consent_manager: Object that manages user consent and recording indicator
        """
        pass
    
    def send(self,
             decision: ModalityScore,
             evidence_buffer: RollingBuffer,
             user_id: str) -> bool:
        """
        Send an emergency alert after user-cancellable countdown.
        
        Args:
            decision: The final ModalityScore with decision="EMERGENCY"
            evidence_buffer: The RollingBuffer containing evidence
            user_id: The user identifier
            
        Returns:
            True if alert was sent successfully, False if cancelled by user
        """
        pass
    
    def cancel_user_countdown(self) -> None:
        """
        Cancel the current user countdown (called by user interface when user cancels).
        """
        pass
```

## EvidenceStore.save/load
Evidence store that saves raw evidence and can load it for review.

```python
class EvidenceStore:
    """
    Stores raw evidence (audio, motion, vision) for later review.
    All evidence is encrypted and tied to the user session.
    """
    
    def __init__(self,
                 storage_path: str,
                 encryption_key: str):
        """
        Args:
            storage_path: Path to encrypted storage location
            encryption_key: Key for encryption
        """
        pass
    
    def save(self,
             evidence: dict,
             session_id: str) -> bool:
        """
        Save evidence for review.
        
        Args:
            evidence: Dictionary containing raw audio, motion, and vision data
            session_id: Unique session identifier
            
        Returns:
            True if saved successfully
        """
        pass
    
    def load(self,
             session_id: str) -> dict:
        """
        Load evidence for review.
        
        Args:
            session_id: Unique session identifier
            
        Returns:
            Dictionary containing the saved evidence
        """
        pass
```

# Section 4: Territory Map

Each senior specialist owns exactly the files below. No specialist may edit another
specialist's files. `modules/types.py`, `modules/__init__.py`, `config/`,
`docs/CONTRACTS.md`, `docs/ASSUMPTIONS.md` and `README.md` are coordinator-owned
and read-only during the solo stage.

| # | Role | Owned implementation | Owned tests | Done file |
|---|------|----------------------|-------------|-----------|
| 1 | audio-detector | `modules/audio_detector.py` | `tests/test_audio_detector.py` | `swarm/done/audio-detector.md` |
| 2 | motion-detector | `modules/motion_detector.py` | `tests/test_motion_detector.py` | `swarm/done/motion-detector.md` |
| 3 | vision-detector | `modules/vision_detector.py` | `tests/test_vision_detector.py` | `swarm/done/vision-detector.md` |
| 4 | fusion-engine | `modules/fusion.py` | `tests/test_fusion.py` | `swarm/done/fusion-engine.md` |
| 5 | buffer-crypto | `modules/rolling_buffer.py` | `tests/test_rolling_buffer.py` | `swarm/done/buffer-crypto.md` |
| 6 | alert-consent | `modules/alert_sender.py`, `modules/user_consent.py` | `tests/test_alert_sender.py` | `swarm/done/alert-consent.md` |
| 7 | evidence-store | `modules/evidence_store.py` | `tests/test_evidence_store.py` | `swarm/done/evidence-store.md` |
| 8 | integration-safety | `modules/pipeline.py`, `docs/ARCHITECTURE.md` | `tests/test_pipeline.py` | `swarm/done/integration-safety.md` |

Dependency order (by contract, not by waiting): detectors (1-3) -> fusion (4);
buffer (5), evidence (7) and alerting (6) are independent; integration (8) wires
1-7 through their public constructors and may use stubs in its own tests.

# Barrier & Handoff Protocol

Each senior specialist must follow this exact handoff protocol:

1. Upon completion of their solo task, write `swarm/done/<role>.md` with:
   - Task summary (≤ 200 words)
   - Implementation details
   - Tests run and results
   - Any assumptions made (# ASSUMPTION)
   - Files modified/created

2. The barrier is automatically triggered when ALL 8 `swarm/done/<role>.md` files exist.

3. The roundtable stage begins only after all 8 done files are present.

# Roundtable Review Flow

1. **Judge**: Each senior reviews their own done file and votes on clarity and correctness (1-5 scale).
2. **Consolidate**: All reviews are placed in `swarm/reviews/` as `reviews/<role>-judge.md`.
3. **Jointly modify**: All seniors collaborate in `swarm/reviews/` to resolve issues in `swarm/issues/`.
4. **Re-judge**: After joint modifications, all seniors re-vote on the consolidated version.

# Voting Requirements

- Final sign-off vote needs 6/8 SHIP (Senior Independent Permission) votes
- Each vote must be recorded in `swarm/votes/` as `votes/<role>-signoff.md`
- Votes must include: decision (SHIP/REJECT), reason, and timestamp

# Deliverables

Upon final sign-off, the following files must be present:

- `README.md` - Project overview and usage
- `docs/ARCHITECTURE.md` - Detailed architecture diagram and data flow
- `docs/CONTRACTS.md` - This file
- `docs/ASSUMPTIONS.md` - Documented assumptions
- `modules/` - Implementation of all modules (AudioDetector, MotionDetector, VisionDetector, FusionStrategy, AlertSender, EvidenceStore, RollingBuffer)
- `tests/` - All unit and integration tests
- `config/` - Configuration files
- `swarm/` - All swarm artifacts (done/, reviews/, issues/, votes/, results/)

# Safety Non-Negotiables

All implementations must include:

1. **Visible recording indicator**: A clear visual indicator that audio/video is being recorded at all times.
2. **Explicit mic/camera consent**: A consent dialog shown before recording begins, with ability to revoke consent at any time.
3. **User-cancellable 15s countdown**: Before any alert is sent, a 15-second countdown timer is shown to the user. The user can cancel the alert at any point during this countdown.
4. **Raw media stays on-device**: All raw media (audio, video, IMU data) remains on the device unless the user explicitly opts in to share for analysis (opt-in by default for evidence, opt-out for personal data).

# Assumptions

# ASSUMPTION: Encryption
- The encryption key is derived from a device-unique secret stored securely on the device.
- We do not implement the actual key derivation or encryption algorithm; we just assume it exists.

# ASSUMPTION: User Interface
- The user interface provides a clear visual recording indicator and a cancel button for the 15-second countdown.
- The user interface calls `AlertSender.cancel_user_countdown()` when the user cancels the alert.

# ASSUMPTION: Model Availability
- All models (YAMNet, LSTM, YOLOv8-nano) are available at `modules/models/` as pre-trained files.

# ASSUMPTION: Sensor Calibration
- All sensors are properly calibrated before use; no calibration routines are needed.

# ASSUMPTION: Network
- The device has periodic network connectivity for sync, but the system must function offline.

# ASSUMPTION: False Rates
- False positive rate < 1%
- False negative rate < 0.5%

# ASSUMPTION: Buffer Size
- Buffer size is 1024 samples as per config.

# ASSUMPTION: Time Synchronization
- All timestamps are synchronized via the device's internal clock; no external NTP needed for this implementation.

# ASSUMPTION: Data Types
- `audio_data` is a list of floats representing amplitude values.
- `image_data` is a list of integers representing pixel values (RGB format).
- `accelerometer`, `gyroscope`, `magnetometer` are lists of 3 floats each.

</function>
</tool_call>