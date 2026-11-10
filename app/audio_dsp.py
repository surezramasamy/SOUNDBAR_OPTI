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
        "desc": "Alternates Hard-Left then Hard-Right (uncorrelated) so Center & Rears stay silent."
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
        "desc": "Pure 0-deg in-phase mono (L = +R). Locks strictly to the Front Center speaker."
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
        "desc": "135-deg Hilbert phase-shifted stereo to steer audio to 90-deg side-firing drivers."
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
        "desc": "Decorrelated wide stereo with center-nulling to steer to 45-deg wide drivers."
    },
    {
        "id": "front_top",
        "name": "5. Front Top (Atmos Height L/R)",
        "remote_menu": "CH LEVEL -> FRONT TOP",
        "min_db": -6,
        "max_db": 6,
        "target_offset_db": -0.5,
        "freq_low": 600,
        "freq_high": 9000,
        "desc": "Decorrelated stereo + 7.5 kHz Atmos elevation cues for Front Up-Firing drivers."
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
        "desc": "180-deg Out-of-Phase Dolby Surround Matrix (L = -R) + Hilbert quadrature to force Rear Satellites."
    },
    {
        "id": "rear_top",
        "name": "7. Rear Top (Rear Height L/R)",
        "remote_menu": "CH LEVEL -> REAR TOP",
        "min_db": -6,
        "max_db": 6,
        "target_offset_db": -1.0,
        "freq_low": 600,
        "freq_high": 9500,
        "desc": "180-deg Out-of-Phase Surround Matrix + 7.5 kHz elevation cues for Rear Top Up-Firing satellites."
    },
    {
        "id": "subwoofer",
        "name": "8. Subwoofer (Woofer)",
        "remote_menu": "WOOFER BUTTON",
        "min_db": -12,
        "max_db": 6,
        "target_offset_db": 2.5,
        "freq_low": 28,
        "freq_high": 90,
        "desc": "Deep 28-90 Hz LFE bass routed strictly to the wireless Subwoofer."
    }
]


def _make_pink_band(rng, num_samples, sample_rate, low_cut, high_cut):
    """Helper to generate a single band-limited pink noise array."""
    white = rng.standard_normal(num_samples)
    b = [0.049922035, -0.095993537, 0.050612699, -0.004408786]
    a = [1.0, -2.494956002, 2.017265875, -0.522189400]
    pink = signal.lfilter(b, a, white)

    nyquist = 0.5 * sample_rate
    low = max(15.0, float(low_cut)) / nyquist
    high = min(nyquist - 100.0, float(high_cut)) / nyquist
    sos = signal.butter(4, [low, high], btype="bandpass", output="sos")
    return signal.sosfilt(sos, pink)


def _apply_elevation_boost(sig, sample_rate):
    """
    Applies a peaking boost around 7.5 kHz (Blauert's vertical elevation band)
    which Samsung's Adaptive / Surround upmixer uses to steer energy to Top height channels.
    """
    f0 = 7500.0
    Q = 2.0
    gain_db = 6.0
    A = 10.0 ** (gain_db / 40.0)
    w0 = 2.0 * np.pi * f0 / sample_rate
    alpha = np.sin(w0) / (2.0 * Q)
    b0 = 1.0 + alpha * A
    b1 = -2.0 * np.cos(w0)
    b2 = 1.0 - alpha * A
    a0 = 1.0 + alpha / A
    a1 = -2.0 * np.cos(w0)
    a2 = 1.0 - alpha / A
    return signal.lfilter([b0 / a0, b1 / a0, b2 / a0], [1.0, a1 / a0, a2 / a0], sig)


def generate_bandlimited_pink_noise(
    duration_sec=3.5,
    sample_rate=44100,
    low_cut=100,
    high_cut=3000,
    channel_id="center"
):
    """
    Generates stereo 16-bit PCM WAV with Dolby Surround / Phase-Matrix spatial encoding
    so that over Bluetooth (in Surround / Adaptive mode), the Samsung Q930 steers audio
    to the intended speaker group (Rear, Rear Top, Side, Wide, Front L/R, Center, Sub).
    """
    rng = np.random.default_rng(42)
    num_samples = int(duration_sec * sample_rate)

    sig_a = _make_pink_band(rng, num_samples, sample_rate, low_cut, high_cut)
    sig_b = _make_pink_band(rng, num_samples, sample_rate, low_cut, high_cut)

    if channel_id == "center":
        # Pure correlated in-phase mono (L = +S, R = +S) -> 100% Front Center speaker
        left = sig_a
        right = sig_a

    elif channel_id == "subwoofer":
        # Pure in-phase LFE bass (28-90 Hz) -> 100% Subwoofer
        left = sig_a
        right = sig_a

    elif channel_id == "front_left_right":
        # Alternate Hard-Left then Hard-Right with uncorrelated stereo tail
        # Prevents the Surround decoder from collapsing L/R into the Center speaker
        half = num_samples // 2
        left = np.zeros(num_samples)
        right = np.zeros(num_samples)
        left[:half] = sig_a[:half]
        right[half:] = sig_b[half:]

    elif channel_id == "rear":
        # Pure Dolby Surround / Pro Logic Matrix Encoding for Rear Satellites:
        # Zero-delay 180-deg out-of-phase (R = -L) makes Center (L + R) = 0.0,
        # forcing 100% of the signal into the Surround difference channel (L - R) -> Rear Speakers!
        analytic_a = signal.hilbert(sig_a)
        hilb_90 = np.imag(analytic_a)
        left = 0.92 * sig_a + 0.20 * hilb_90
        right = -0.92 * sig_a + 0.20 * hilb_90

    elif channel_id == "rear_top":
        # Rear Top Atmos Height:
        # 180-deg Out-of-Phase Surround Matrix (Center L+R = 0 -> steers to Rear Satellites)
        # + 7.5 kHz HRTF Elevation Cues (steers to Rear Up-Firing Top drivers)
        elev_a = _apply_elevation_boost(sig_a, sample_rate)
        analytic_a = signal.hilbert(elev_a)
        hilb_90 = np.imag(analytic_a)
        left = 0.92 * elev_a + 0.20 * hilb_90
        right = -0.92 * elev_a + 0.20 * hilb_90

    elif channel_id == "side":
        # Side-firing (90-deg): 135-deg Hilbert phase shift + opposite stereo pan
        analytic = signal.hilbert(sig_a)
        hilb_90 = np.imag(analytic)
        left = sig_a - 0.5 * sig_b
        right = -0.707 * sig_a + 0.707 * hilb_90 + 0.5 * sig_b

    elif channel_id == "wide":
        # Wide-firing (45-deg): Decorrelated dual pink noise with out-of-phase crossfeed
        # Suppresses center channel while keeping front-hemisphere width
        left = sig_a - 0.38 * sig_b
        right = sig_b - 0.38 * sig_a

    elif channel_id == "front_top":
        # Front Top Atmos Height: Decorrelated stereo + center cancellation + 7.5 kHz elevation cue
        elev_a = _apply_elevation_boost(sig_a, sample_rate)
        elev_b = _apply_elevation_boost(sig_b, sample_rate)
        left = elev_a - 0.45 * elev_b
        right = elev_b - 0.45 * elev_a

    else:
        left = sig_a
        right = sig_b

    # Smooth 100ms fade-in/out to prevent transient clicks
    fade_len = int(sample_rate * 0.1)
    if num_samples > 2 * fade_len:
        env = np.ones(num_samples)
        env[:fade_len] = np.linspace(0.0, 1.0, fade_len)
        env[-fade_len:] = np.linspace(1.0, 0.0, fade_len)
        left = left * env
        right = right * env

    # Normalize combined stereo power to -12 dBFS RMS so all test signals have equal reference energy
    combined_rms = np.sqrt(0.5 * (np.mean(left ** 2) + np.mean(right ** 2))) + 1e-9
    target_rms = 10.0 ** (-12.0 / 20.0)
    gain = target_rms / combined_rms
    left = np.clip(left * gain, -0.95, 0.95)
    right = np.clip(right * gain, -0.95, 0.95)

    pcm_l = (left * 32767.0).astype(np.int16)
    pcm_r = (right * 32767.0).astype(np.int16)
    stereo = np.column_stack((pcm_l, pcm_r)).ravel()

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
        audio = audio.reshape(-1, num_channels)[:, 0]

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
