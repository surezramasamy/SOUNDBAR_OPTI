import numpy as np
from scipy import signal
import io
import wave

# Samsung HW-Q930 9.1.4 Channel Map with Musical Chime Harmonics
Q930_CHANNELS = [
    {
        "id": "front_left_right",
        "name": "1. Front L / R (Main Baseline)",
        "remote_menu": "MASTER VOLUME (Reference Anchor)",
        "min_db": 0,
        "max_db": 0,
        "target_offset_db": 0.0,
        "freq_low": 180,
        "freq_high": 4500,
        "harmonics": [220.0, 330.0, 440.0, 554.37, 659.25, 880.0, 1318.5, 2637.0],
        "desc": "Plays 'Ding' on Left, 'Dong' on Right, then warm stereo chord (Reference Anchor)."
    },
    {
        "id": "center",
        "name": "2. Center Channel",
        "remote_menu": "CH LEVEL -> CENTER",
        "min_db": -6,
        "max_db": 6,
        "target_offset_db": 0.5,
        "freq_low": 240,
        "freq_high": 3600,
        "harmonics": [261.63, 329.63, 392.0, 523.25, 659.25, 783.99, 1046.5, 2093.0],
        "desc": "Pure vocal-range musical 'Ding-Dong' chime locked to the Front Center speaker."
    },
    {
        "id": "side",
        "name": "3. Side Speakers (L/R)",
        "remote_menu": "CH LEVEL -> SIDE",
        "min_db": -6,
        "max_db": 6,
        "target_offset_db": 0.0,
        "freq_low": 280,
        "freq_high": 5000,
        "harmonics": [293.66, 369.99, 440.0, 587.33, 739.99, 880.0, 1479.98, 2959.96],
        "desc": "135-deg phase-steered musical chime for 90-deg side-firing speakers."
    },
    {
        "id": "wide",
        "name": "4. Wide Speakers (L/R)",
        "remote_menu": "CH LEVEL -> WIDE",
        "min_db": -6,
        "max_db": 6,
        "target_offset_db": 0.0,
        "freq_low": 280,
        "freq_high": 5000,
        "harmonics": [311.13, 392.0, 466.16, 622.25, 783.99, 932.33, 1567.98, 3135.96],
        "desc": "Wide stereo musical chime steered to 45-deg corner-firing drivers."
    },
    {
        "id": "front_top",
        "name": "5. Front Top (Atmos Height L/R)",
        "remote_menu": "CH LEVEL -> FRONT TOP",
        "min_db": -6,
        "max_db": 6,
        "target_offset_db": -0.5,
        "freq_low": 400,
        "freq_high": 8500,
        "harmonics": [440.0, 554.37, 659.25, 880.0, 1318.5, 2637.0, 5274.0, 7040.0],
        "desc": "High celestial Atmos bell chime for Front Up-Firing height speakers."
    },
    {
        "id": "rear",
        "name": "6. Rear Surround (Satellite L/R)",
        "remote_menu": "CH LEVEL -> REAR",
        "min_db": -6,
        "max_db": 6,
        "target_offset_db": -1.0,
        "freq_low": 200,
        "freq_high": 4500,
        "harmonics": [246.94, 311.13, 369.99, 493.88, 622.25, 739.99, 1244.5, 2489.0],
        "desc": "180-deg Dolby Surround matrixed 'Ding-Dong' chime for Rear Satellites."
    },
    {
        "id": "rear_top",
        "name": "7. Rear Top (Rear Height L/R)",
        "remote_menu": "CH LEVEL -> REAR TOP",
        "min_db": -6,
        "max_db": 6,
        "target_offset_db": -1.0,
        "freq_low": 400,
        "freq_high": 8500,
        "harmonics": [493.88, 622.25, 739.99, 987.77, 1479.98, 2959.96, 5919.9, 7458.6],
        "desc": "180-deg surround + high Atmos bell chime for Rear Up-Firing satellites."
    },
    {
        "id": "subwoofer",
        "name": "8. Subwoofer (Woofer)",
        "remote_menu": "WOOFER BUTTON",
        "min_db": -12,
        "max_db": 6,
        "target_offset_db": 2.5,
        "freq_low": 30,
        "freq_high": 95,
        "harmonics": [36.71, 41.20, 48.99, 55.0, 61.74, 65.41, 73.42, 82.41],
        "desc": "Deep warm bass 'Boom-Dum' musical pulse (36-85 Hz) for the Subwoofer."
    }
]


def _bell_note(t, freqs, weights, decay_rate=3.2):
    """Synthesizes a warm, pleasant musical chime note with soft attack and bell decay."""
    attack = 1.0 - np.exp(-t / 0.018)
    decay = np.exp(-decay_rate * t)
    env = attack * decay
    wave_sum = np.zeros_like(t)
    for idx, (f, w) in enumerate(zip(freqs, weights)):
        # Slight phase offset per harmonic avoids sharp transient spikes
        phase = (idx * 0.71) % (2.0 * np.pi)
        wave_sum += w * np.sin(2.0 * np.pi * f * t + phase)
    return wave_sum * env


def _sustained_chord(t, freqs, fade_s=0.08):
    """Synthesizes a smooth, warm multi-harmonic chord with equal-energy harmonics for SPL/resonance analysis."""
    n = len(t)
    env = np.ones(n)
    fade_n = min(int(fade_s * (n / max(t[-1], 0.1))), n // 3)
    if fade_n > 1:
        env[:fade_n] = np.linspace(0.0, 1.0, fade_n)
        env[-fade_n:] = np.linspace(1.0, 0.0, fade_n)

    wave_sum = np.zeros(n)
    for idx, f in enumerate(freqs):
        phase = (idx * 1.13) % (2.0 * np.pi)
        # Gentle vibrato shimmer makes the chord pleasant to the ear
        vibrato = 0.0015 * np.sin(2.0 * np.pi * 4.5 * t + idx)
        wave_sum += np.sin(2.0 * np.pi * f * (t + vibrato) + phase)
    return (wave_sum / max(len(freqs), 1)) * env


def generate_bandlimited_pink_noise(
    duration_sec=3.2,
    sample_rate=44100,
    low_cut=100,
    high_cut=3000,
    channel_id="center"
):
    """
    Generates a pleasant 'Ding-Dong + Warm Harmonic Chord' musical test signal (WAV).
    - Segment 1 (0.0 - 0.9s): 'DING' (Higher musical bell triad)
    - Segment 2 (0.9 - 1.8s): 'DONG' (Warm resolving lower fifth bell triad)
    - Segment 3 (1.8 - 3.2s): 'WARM CHORD' (Full 8-harmonic balanced chord for accurate dB & peak check)
    Includes Dolby Surround Phase-Matrix spatial steering per channel.
    """
    ch_info = next((c for c in Q930_CHANNELS if c["id"] == channel_id), Q930_CHANNELS[1])
    harms = ch_info["harmonics"]

    num_samples = int(duration_sec * sample_rate)
    n1 = int(0.28 * num_samples)
    n2 = int(0.28 * num_samples)
    n3 = num_samples - n1 - n2

    t1 = np.arange(n1) / sample_rate
    t2 = np.arange(n2) / sample_rate
    t3 = np.arange(n3) / sample_rate

    if channel_id == "subwoofer":
        # Deep musical sub-bass swell: High bass note ("Boom") -> Low bass note ("Dum") -> Warm bass chord
        ding_freqs = [harms[3], harms[5], harms[6], harms[7]]
        dong_freqs = [harms[0], harms[1], harms[2], harms[4]]
        decay = 2.0
    else:
        # Classic pleasant "Ding" (upper major bell) -> "Dong" (lower resolving bell)
        ding_freqs = [harms[2], harms[4], harms[5], harms[6], harms[7]]
        dong_freqs = [harms[0], harms[1], harms[2], harms[3], harms[5]]
        decay = 3.0

    ding_weights = [1.0 / (1.0 + 0.25 * i) for i in range(len(ding_freqs))]
    dong_weights = [1.0 / (1.0 + 0.25 * i) for i in range(len(dong_freqs))]

    seg_ding = _bell_note(t1, ding_freqs, ding_weights, decay_rate=decay)
    seg_dong = _bell_note(t2, dong_freqs, dong_weights, decay_rate=decay)
    seg_chord = _sustained_chord(t3, harms, fade_s=0.08)

    # Normalize each segment to equal RMS so the meter stays steady across Ding -> Dong -> Chord
    for seg in (seg_ding, seg_dong, seg_chord):
        r = np.sqrt(np.mean(seg ** 2)) + 1e-9
        seg *= (0.25 / r)

    sig_a = np.concatenate([seg_ding, seg_dong, seg_chord])

    # Create a slightly detuned/phase-shifted companion signal for wide/stereo channels
    seg_ding_b = _bell_note(t1, [f * 1.003 for f in ding_freqs], ding_weights, decay_rate=decay)
    seg_dong_b = _bell_note(t2, [f * 0.997 for f in dong_freqs], dong_weights, decay_rate=decay)
    seg_chord_b = _sustained_chord(t3, [f * 1.002 for f in harms], fade_s=0.08)
    for seg in (seg_ding_b, seg_dong_b, seg_chord_b):
        r = np.sqrt(np.mean(seg ** 2)) + 1e-9
        seg *= (0.25 / r)
    sig_b = np.concatenate([seg_ding_b, seg_dong_b, seg_chord_b])

    # Apply Spatial Channel Steering
    if channel_id == "center":
        # Pure correlated in-phase mono (L = +R) -> Locks to Front Center
        left = sig_a
        right = sig_a

    elif channel_id == "subwoofer":
        # Pure in-phase LFE sub-bass -> Subwoofer
        left = sig_a
        right = sig_a

    elif channel_id == "front_left_right":
        # "Ding" on Left Speaker, "Dong" on Right Speaker, then Stereo Chord on both!
        left = np.concatenate([seg_ding * 1.414, np.zeros_like(seg_dong), seg_chord])
        right = np.concatenate([np.zeros_like(seg_ding), seg_dong * 1.414, seg_chord_b])

    elif channel_id in ("rear", "rear_top"):
        # 180-deg Out-of-Phase Dolby Surround Matrix (R = -L) + Hilbert quadrature
        # Nulls Front Center (L + R = 0) and steers chime to Rear Speakers
        analytic_a = signal.hilbert(sig_a)
        hilb_90 = np.imag(analytic_a)
        left = 0.92 * sig_a + 0.20 * hilb_90
        right = -0.92 * sig_a + 0.20 * hilb_90

    elif channel_id == "side":
        # 135-deg Hilbert phase-shifted stereo for 90-deg side-firing drivers
        analytic_a = signal.hilbert(sig_a)
        hilb_90 = np.imag(analytic_a)
        left = sig_a - 0.45 * sig_b
        right = -0.707 * sig_a + 0.707 * hilb_90 + 0.45 * sig_b

    elif channel_id in ("wide", "front_top"):
        # Decorrelated wide stereo with center cancellation
        left = sig_a - 0.40 * sig_b
        right = sig_b - 0.40 * sig_a

    else:
        left = sig_a
        right = sig_b

    # Smooth outer envelope
    fade_len = int(sample_rate * 0.05)
    if num_samples > 2 * fade_len:
        env = np.ones(num_samples)
        env[:fade_len] = np.linspace(0.0, 1.0, fade_len)
        env[-fade_len:] = np.linspace(1.0, 0.0, fade_len)
        left = left * env
        right = right * env

    # Normalize using C-weighted RMS so all 8 musical chimes measure identically (82.0 dB) in a flat room
    c_left = apply_c_weighting(left, sample_rate)
    c_right = apply_c_weighting(right, sample_rate)
    combined_c_rms = np.sqrt(0.5 * (np.mean(c_left ** 2) + np.mean(c_right ** 2))) + 1e-9
    target_rms = 10.0 ** (-12.0 / 20.0)
    gain = target_rms / combined_c_rms
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


def analyze_recorded_channel_audio(wav_bytes, freq_low=25, freq_high=8000, channel_id=None):
    """
    Analyzes 16-bit PCM WAV recorded by the phone microphone during the musical 'Ding-Dong' test.
    Computes C-weighted SPL dB and checks relative balance across the channel's musical harmonics
    to detect boomy room standing waves or harsh speaker resonance peaks.
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
        ch_data = audio.reshape(-1, num_channels)
        # If Left and Right are in-phase or alternating, sum power; if anti-phase, use dominant channel
        corr = np.corrcoef(ch_data[:, 0], ch_data[:, 1])[0, 1]
        if corr < -0.2:
            audio = ch_data[:, 0]
        else:
            audio = np.sqrt(0.5) * (ch_data[:, 0] + ch_data[:, 1]) if corr > 0.2 else (ch_data[:, 0] + ch_data[:, 1]) * np.sqrt(0.5)

    # Trim startup/ending latency (keep middle 75% of capture)
    if len(audio) > int(sample_rate * 0.8):
        s = int(len(audio) * 0.12)
        e = int(len(audio) * 0.88)
        audio = audio[s:e]

    # Band-limit around channel's active range to reject out-of-band ambient noise
    nyquist = 0.5 * sample_rate
    low = max(18.0, float(freq_low) * 0.75) / nyquist
    high = min(nyquist - 100.0, float(freq_high) * 1.15) / nyquist
    if low < high:
        sos = signal.butter(3, [low, high], btype="bandpass", output="sos")
        band_audio = signal.sosfilt(sos, audio)
    else:
        band_audio = audio

    # 1. C-Weighted SPL (dBC)
    c_weighted = apply_c_weighting(band_audio, sample_rate)
    rms_c = float(np.sqrt(np.mean(c_weighted ** 2)) + 1e-12)
    db_spl = 20.0 * np.log10(rms_c) + 94.0

    # 2. Harmonic Balance Analysis across the musical chime's note frequencies
    ch_info = next((c for c in Q930_CHANNELS if c["id"] == channel_id), None)
    harmonics = ch_info["harmonics"] if ch_info else [300.0, 500.0, 1000.0, 2000.0]

    n_fft = min(len(band_audio), 8192)
    if n_fft >= 512:
        win = np.hanning(n_fft)
        spec = np.abs(np.fft.rfft(band_audio[-n_fft:] * win))
        freqs = np.fft.rfftfreq(n_fft, d=1.0 / sample_rate)

        harm_amps = []
        for hf in harmonics:
            # Measure peak amplitude within +-6% of each musical harmonic note
            mask = (freqs >= hf * 0.94) & (freqs <= hf * 1.06)
            if np.any(mask):
                harm_amps.append((hf, float(np.max(spec[mask]))))

        if harm_amps:
            amps = np.array([a for _, a in harm_amps]) + 1e-12
            peak_idx = int(np.argmax(amps))
            peak_freq = float(harm_amps[peak_idx][0])
            peak_mag = float(amps[peak_idx])
            median_mag = float(np.median(amps))
            resonance_ratio = peak_mag / median_mag
            peak_over_median_db = 20.0 * np.log10(max(resonance_ratio, 1.0))
        else:
            peak_freq = 0.0
            resonance_ratio = 1.0
            peak_over_median_db = 0.0
    else:
        peak_freq = 0.0
        resonance_ratio = 1.0
        peak_over_median_db = 0.0

    # 3. Crest Factor & Harsh "Hitting" Detection
    peak_amp = float(np.max(np.abs(band_audio)) + 1e-12)
    crest_factor_db = 20.0 * np.log10(peak_amp / rms_c)

    # Flag if one musical note resonates > 8.5 dB above the rest of the chord
    is_harsh = bool(peak_over_median_db > 8.5 or crest_factor_db > 16.5)

    harsh_reason = "Clean musical response"
    if peak_over_median_db > 8.5:
        if peak_freq < 120:
            harsh_reason = f"Boomy bass resonance at {peak_freq:.0f} Hz (+{peak_over_median_db:.1f} dB spike)"
        else:
            harsh_reason = f"Harsh note spike at {peak_freq:.0f} Hz (+{peak_over_median_db:.1f} dB spike)"
    elif crest_factor_db > 16.5:
        harsh_reason = f"Sharp transient peak (Crest {crest_factor_db:.1f} dB)"

    return {
        "db_level": round(float(db_spl), 1),
        "peak_frequency_hz": round(peak_freq, 0),
        "resonance_ratio": round(float(resonance_ratio), 2),
        "peak_spike_db": round(float(peak_over_median_db), 1),
        "crest_factor_db": round(float(crest_factor_db), 1),
        "is_harsh": is_harsh,
        "diagnosis": harsh_reason
    }
