import numpy as np
from scipy import signal
import io
import wave

# Samsung HW-Q930 9.1.4 Channel Map:
# Front L/R is the fixed baseline (controlled by Master Volume).
# All other 7 groups have dedicated dB adjustments on the Samsung Remote.
Q930_CHANNELS = [
    {
        "id": "front_left_right",
        "name": "1. Front L / R (Main Baseline)",
        "remote_menu": "MASTER VOLUME (Reference Anchor)",
        "min_db": 0,
        "max_db": 0,
        "target_offset_db": 0.0,
        "freq_low": 150,
        "freq_high": 6000,
        "desc": "Fixed reference anchor. Set comfortable master volume before starting."
    },
    {
        "id": "center",
        "name": "2. Center Channel",
        "remote_menu": "CH LEVEL -> CENTER",
        "min_db": -6,
        "max_db": 6,
        "target_offset_db": 0.5,
        "freq_low": 300,
        "freq_high": 3500,
        "desc": "Dialogue & vocals. Balanced to match Front L/R without harsh sibilance."
    },
    {
        "id": "side",
        "name": "3. Side Speakers (L/R)",
        "remote_menu": "CH LEVEL -> SIDE",
        "min_db": -6,
        "max_db": 6,
        "target_offset_db": 0.0,
        "freq_low": 300,
        "freq_high": 5500,
        "desc": "90-degree side-firing drivers. Often cause wall-bounce hitting if too high."
    },
    {
        "id": "wide",
        "name": "4. Wide Speakers (L/R)",
        "remote_menu": "CH LEVEL -> WIDE",
        "min_db": -6,
        "max_db": 6,
        "target_offset_db": 0.0,
        "freq_low": 300,
        "freq_high": 5500,
        "desc": "45-degree corner-firing drivers expanding the front stage."
    },
    {
        "id": "front_top",
        "name": "5. Front Top (Atmos Height L/R)",
        "remote_menu": "CH LEVEL -> FRONT TOP",
        "min_db": -6,
        "max_db": 6,
        "target_offset_db": -0.5,
        "freq_low": 400,
        "freq_high": 7500,
        "desc": "Up-firing ceiling drivers. Tamed if ceiling reflections create harsh peaks."
    },
    {
        "id": "rear",
        "name": "6. Rear Surround (Satellite L/R)",
        "remote_menu": "CH LEVEL -> REAR",
        "min_db": -6,
        "max_db": 6,
        "target_offset_db": -1.0,
        "freq_low": 160,
        "freq_high": 6000,
        "desc": "Rear satellites. Often sit close to the sofa and hit the ears if uncalibrated."
    },
    {
        "id": "rear_top",
        "name": "7. Rear Top (Rear Height L/R)",
        "remote_menu": "CH LEVEL -> REAR TOP",
        "min_db": -6,
        "max_db": 6,
        "target_offset_db": -1.0,
        "freq_low": 400,
        "freq_high": 7500,
        "desc": "Up-firing rear satellite drivers for overhead Atmos balance."
    },
    {
        "id": "subwoofer",
        "name": "8. Subwoofer (Woofer)",
        "remote_menu": "WOOFER BUTTON",
        "min_db": -12,
        "max_db": 6,
        "target_offset_db": 2.5,
        "freq_low": 28,
        "freq_high": 95,
        "desc": "8-inch wireless sub. Eliminates boomy chest/ear-hitting standing waves."
    }
]


def generate_bandlimited_pink_noise(duration_sec=3.5, sample_rate=44100, low_cut=100, high_cut=3000):
    """
    Generates deterministic band-limited pink noise with smooth fade envelopes
    for testing a specific speaker channel group without speaker pop.
    """
    rng = np.random.default_rng(42)
    num_samples = int(duration_sec * sample_rate)
    white = rng.standard_normal(num_samples)

    # Pink noise 1/f filter
    b = [0.049922035, -0.095993537, 0.050612699, -0.004408786]
    a = [1.0, -2.494956002, 2.017265875, -0.522189400]
    pink = signal.lfilter(b, a, white)

    nyquist = 0.5 * sample_rate
    low = max(15.0, float(low_cut)) / nyquist
    high = min(nyquist - 100.0, float(high_cut)) / nyquist

    sos = signal.butter(4, [low, high], btype="bandpass", output="sos")
    filtered = signal.sosfilt(sos, pink)

    # Smooth 100ms fade-in/out to prevent transient clicks
    fade_len = int(sample_rate * 0.1)
    if num_samples > 2 * fade_len:
        filtered[:fade_len] *= np.linspace(0.0, 1.0, fade_len)
        filtered[-fade_len:] *= np.linspace(1.0, 0.0, fade_len)

    # Normalize to -12 dBFS RMS
    rms = np.sqrt(np.mean(filtered ** 2)) + 1e-9
    target_rms = 10.0 ** (-12.0 / 20.0)
    normalized = np.clip(filtered * (target_rms / rms), -0.95, 0.95)

    # Stereo 16-bit PCM WAV (compatible with all Bluetooth/TV outputs)
    pcm16 = (normalized * 32767.0).astype(np.int16)
    stereo = np.column_stack((pcm16, pcm16)).ravel()

    buf = io.BytesIO()
    with wave.open(buf, "wb") as wav_file:
        wav_file.setnchannels(2)
        wav_file.setsampwidth(2)
        wav_file.setframerate(sample_rate)
        wav_file.writeframes(stereo.tobytes())

    return buf.getvalue()


def apply_c_weighting(samples, sample_rate):
    """Applies standard ANSI S1.4 C-Weighting filter for home theater SPL analysis."""
    f1 = 20.598997
    f4 = 12194.217
    c_num = [(2 * np.pi * f4) ** 2 * (10 ** (0.062 / 20.0)), 0.0, 0.0]
    c_den = np.convolve(
        [1.0, 4 * np.pi * f1, (2 * np.pi * f1) ** 2],
        [1.0, 4 * np.pi * f4, (2 * np.pi * f4) ** 2]
    )
    b_z, a_z = signal.bilinear(c_num, c_den, fs=sample_rate)
    return signal.lfilter(b_z, a_z, samples)


def analyze_recorded_channel_audio(wav_bytes, freq_low=25, freq_high=8000):
    """
    Analyzes 16-bit PCM WAV recorded by the phone microphone.
    Returns C-weighted SPL proxy, in-band RMS, dominant resonant peak frequency,
    and whether harsh 'hitting' acoustic spikes are present.
    """
    with wave.open(io.BytesIO(wav_bytes), "rb") as wf:
        sample_rate = wf.getframerate()
        num_frames = wf.getnframes()
        raw_data = wf.readframes(num_frames)
        sample_width = wf.getsampwidth()
        num_channels = wf.getnchannels()

    if sample_width == 2:
        audio = np.frombuffer(raw_data, dtype=np.int16).astype(np.float32) / 32768.0
    elif sample_width == 4:
        audio = np.frombuffer(raw_data, dtype=np.int32).astype(np.float32) / 2147483648.0
    else:
        audio = (np.frombuffer(raw_data, dtype=np.uint8).astype(np.float32) - 128.0) / 128.0

    if num_channels > 1:
        audio = audio.reshape(-1, num_channels).mean(axis=1)

    # Trim startup/ending latency (keep middle 70% of capture)
    if len(audio) > int(sample_rate * 0.8):
        s = int(len(audio) * 0.15)
        e = int(len(audio) * 0.85)
        audio = audio[s:e]

    # Band-limit around channel's active range to reject out-of-band ambient noise
    nyquist = 0.5 * sample_rate
    low = max(18.0, float(freq_low) * 0.8) / nyquist
    high = min(nyquist - 100.0, float(freq_high) * 1.15) / nyquist
    if low < high:
        sos = signal.butter(3, [low, high], btype="bandpass", output="sos")
        band_audio = signal.sosfilt(sos, audio)
    else:
        band_audio = audio

    # 1. C-Weighted SPL (dBC proxy)
    c_weighted = apply_c_weighting(band_audio, sample_rate)
    rms_c = float(np.sqrt(np.mean(c_weighted ** 2)) + 1e-12)
    db_spl = 20.0 * np.log10(rms_c) + 94.0

    # 2. Welch Power Spectral Density with 1/f Pink-Noise Tilt Compensation
    nperseg = min(len(band_audio), 4096)
    if nperseg >= 256:
        freqs, psd = signal.welch(band_audio, fs=sample_rate, nperseg=nperseg)
        # Compensate for 1/f pink noise slope so a flat room response has a flat spectrum
        pink_comp_psd = psd * np.maximum(freqs, 1.0)
        mask = (freqs >= freq_low * 1.15) & (freqs <= freq_high * 0.85)
        if np.any(mask):
            band_freqs = freqs[mask]
            band_amp = np.sqrt(pink_comp_psd[mask])
        else:
            band_freqs = freqs[1:]
            band_amp = np.sqrt(pink_comp_psd[1:])

        peak_idx = int(np.argmax(band_amp))
        peak_freq = float(band_freqs[peak_idx])
        peak_mag = float(band_amp[peak_idx])
        median_mag = float(np.median(band_amp) + 1e-12)
        resonance_ratio = peak_mag / median_mag
        peak_over_median_db = 20.0 * np.log10(max(resonance_ratio, 1.0))
    else:
        peak_freq = 0.0
        resonance_ratio = 1.0
        peak_over_median_db = 0.0

    # 3. Crest Factor & Harsh "Hitting" Detection
    peak_amp = float(np.max(np.abs(band_audio)) + 1e-12)
    crest_factor_db = 20.0 * np.log10(peak_amp / rms_c)

    # A channel causes "hitting" ear fatigue if a narrow resonant peak spikes > 9.5 dB
    # above the pink-compensated median or if transients spike > 16.5 dB crest
    is_harsh = bool(peak_over_median_db > 9.5 or crest_factor_db > 16.5)

    harsh_reason = "Clean response"
    if peak_over_median_db > 9.5:
        if peak_freq < 120:
            harsh_reason = f"Boomy room standing wave at {peak_freq:.0f} Hz (+{peak_over_median_db:.1f} dB spike)"
        else:
            harsh_reason = f"Harsh reflective spike at {peak_freq:.0f} Hz (+{peak_over_median_db:.1f} dB spike)"
    elif crest_factor_db > 16.5:
        harsh_reason = f"Sharp transient peaks (Crest {crest_factor_db:.1f} dB)"

    return {
        "db_level": round(float(db_spl), 1),
        "peak_frequency_hz": round(peak_freq, 0),
        "resonance_ratio": round(float(resonance_ratio), 2),
        "peak_spike_db": round(float(peak_over_median_db), 1),
        "crest_factor_db": round(float(crest_factor_db), 1),
        "is_harsh": is_harsh,
        "diagnosis": harsh_reason
    }
