import numpy as np
from scipy import signal
import io
import wave

# Complete Master Channel Catalog supporting 3.1.2, 5.1.2, 7.1.2, 9.1.4, 11.1.4, and 11.2.4 Soundbar Systems
ALL_SOUNDBAR_CHANNELS = [
    {
        "id": "front_left_right",
        "name": "Front Left / Right (Main Stereo Stage)",
        "remote_menu": "MASTER VOLUME (60% Distortion-Free Anchor)",
        "group": "bed_3",
        "min_db": 0,
        "max_db": 0,
        "target_offset_db": 0.0,
        "freq_low": 140,
        "freq_high": 5200,
        "harmonics": [196.0, 261.63, 329.63, 392.0, 523.25, 783.99, 1567.98, 3135.96],
        "desc": "Natural Concert Grand Piano & Warm Acoustic Strings (Left -> Right -> Full Stereo Reference)."
    },
    {
        "id": "center",
        "name": "Center Channel (Dialogue & Vocal Anchor)",
        "remote_menu": "CH LEVEL -> CENTER",
        "group": "bed_3",
        "min_db": -6,
        "max_db": 6,
        "target_offset_db": 0.5,
        "freq_low": 160,
        "freq_high": 4200,
        "harmonics": [174.61, 220.0, 261.63, 349.23, 440.0, 659.25, 1318.5, 2637.0],
        "desc": "Natural Warm Acoustic Vocal Formant & Nylon Guitar chord for crystal-clear, non-harsh dialogue."
    },
    {
        "id": "side",
        "name": "Side Surround Speakers (L/R 90°)",
        "remote_menu": "CH LEVEL -> SIDE",
        "group": "bed_7",
        "min_db": -6,
        "max_db": 6,
        "target_offset_db": -0.5,
        "freq_low": 200,
        "freq_high": 5000,
        "harmonics": [220.0, 277.18, 329.63, 440.0, 554.37, 880.0, 1760.0, 3520.0],
        "desc": "Natural Warm Viola & Acoustic Ensemble steered to 90° side-firing surround channels."
    },
    {
        "id": "wide",
        "name": "Wide Front-Corner Speakers (L/R 45°)",
        "remote_menu": "CH LEVEL -> WIDE",
        "group": "bed_9",
        "min_db": -6,
        "max_db": 6,
        "target_offset_db": -0.5,
        "freq_low": 220,
        "freq_high": 5000,
        "harmonics": [246.94, 311.13, 369.99, 493.88, 622.25, 987.77, 1975.5, 3951.1],
        "desc": "Natural Acoustic Woodwind & String harmonic blend steered to 45° wide stage expansion drivers."
    },
    {
        "id": "rear",
        "name": "Rear Surround Satellites (Back L/R)",
        "remote_menu": "CH LEVEL -> REAR",
        "group": "bed_5",
        "min_db": -6,
        "max_db": 6,
        "target_offset_db": -1.0,
        "freq_low": 180,
        "freq_high": 4800,
        "harmonics": [196.0, 246.94, 293.66, 392.0, 493.88, 783.99, 1567.98, 2959.96],
        "desc": "Natural Acoustic Ambient Strings steered to Rear Surround satellites (Dolby Matrix / Atmos 3D)."
    },
    {
        "id": "rear_side",
        "name": "Rear Side Surround (11-Ch Back-Side L/R)",
        "remote_menu": "CH LEVEL -> REAR SIDE",
        "group": "bed_11",
        "min_db": -6,
        "max_db": 6,
        "target_offset_db": -1.0,
        "freq_low": 200,
        "freq_high": 4800,
        "harmonics": [207.65, 261.63, 311.13, 415.30, 523.25, 830.61, 1661.2, 3322.4],
        "desc": "Natural Surround Acoustic Pad steered to Rear-Side outward-firing satellite drivers (11.1.4 / 11.2.4)."
    },
    {
        "id": "front_top",
        "name": "Front Top Atmos (Up-Firing Height L/R)",
        "remote_menu": "CH LEVEL -> FRONT TOP",
        "group": "atmos_2",
        "min_db": -6,
        "max_db": 6,
        "target_offset_db": -0.5,
        "freq_low": 320,
        "freq_high": 9000,
        "harmonics": [329.63, 440.0, 554.37, 659.25, 987.77, 1975.5, 3951.1, 6592.5],
        "desc": "Dolby Atmos Natural Overhead Raindrop Canopy & Warm Harp for Front Up-Firing ceiling reflection."
    },
    {
        "id": "rear_top",
        "name": "Rear Top Atmos (Rear Up-Firing Height L/R)",
        "remote_menu": "CH LEVEL -> REAR TOP",
        "group": "atmos_4",
        "min_db": -6,
        "max_db": 6,
        "target_offset_db": -1.0,
        "freq_low": 340,
        "freq_high": 9000,
        "harmonics": [349.23, 440.0, 523.25, 698.46, 1046.5, 2093.0, 4186.0, 7040.0],
        "desc": "Dolby Atmos 3D Overhead Canopy & Natural Acoustic Shimmer for Rear Up-Firing height speakers."
    },
    {
        "id": "subwoofer",
        "name": "Subwoofer 1 (Primary Deep Velvet Woofer)",
        "remote_menu": "WOOFER LEVEL",
        "group": "sub_1",
        "min_db": -12,
        "max_db": 6,
        "target_offset_db": 1.5,
        "freq_low": 28,
        "freq_high": 95,
        "harmonics": [32.70, 36.71, 41.20, 48.99, 55.0, 61.74, 73.42, 82.41],
        "desc": "Natural Bowed Double-Bass & Cello Deep Sustain (32-62 Hz smooth foundation, checks 80 Hz boom)."
    },
    {
        "id": "subwoofer_2",
        "name": "Subwoofer 2 (Dual-Sub Room-Mode Balancer)",
        "remote_menu": "SUBWOOFER 2 LEVEL / PHASE",
        "group": "sub_2",
        "min_db": -12,
        "max_db": 6,
        "target_offset_db": 1.5,
        "freq_low": 28,
        "freq_high": 95,
        "harmonics": [32.70, 38.89, 43.65, 51.91, 58.27, 65.41, 77.78, 87.31],
        "desc": "Dual-Subwoofer phase-coherent deep acoustic sustain to cancel standing-wave room boom."
    }
]

# Default 9.1.4 Channel List for backwards compatibility
Q930_CHANNELS = [c for c in ALL_SOUNDBAR_CHANNELS if c["id"] not in ("rear_side", "subwoofer_2")]

# Preset Architecture Definitions
SOUNDBAR_LAYOUT_PRESETS = {
    "3.1.2": {"bed": 3, "subs": 1, "atmos": 2, "label": "3.1.2 Dolby Atmos Soundbar (L/C/R + 1 Sub + 2 Front Top)"},
    "5.1.2": {"bed": 5, "subs": 1, "atmos": 2, "label": "5.1.2 Surround Atmos (L/C/R + Rear + 1 Sub + 2 Front Top)"},
    "7.1.2": {"bed": 7, "subs": 1, "atmos": 2, "label": "7.1.2 Cinema Atmos (L/C/R + Side + Rear + 1 Sub + 2 Front Top)"},
    "9.1.4": {"bed": 9, "subs": 1, "atmos": 4, "label": "9.1.4 Flagship Surround (Samsung Q930: 9 Bed + 1 Sub + 4 Atmos)"},
    "11.1.4": {"bed": 11, "subs": 1, "atmos": 4, "label": "11.1.4 Reference Atmos (Samsung Q990: 11 Bed + 1 Sub + 4 Atmos)"},
    "11.2.4": {"bed": 11, "subs": 2, "atmos": 4, "label": "11.2.4 Ultimate Dual-Sub Theater (11 Bed + 2 Subs + 4 Atmos)"},
}


def get_channels_for_configuration(bed_count: int = 9, sub_count: int = 1, atmos_count: int = 4) -> list:
    """
    Dynamically returns the ordered speaker channel list based on the user's selected
    Ear-Level Bed Speakers (3, 5, 7, 9, 11), Subwoofers (1, 2), and Atmos Up-Firing Speakers (0, 2, 4).
    """
    selected = []
    for ch in ALL_SOUNDBAR_CHANNELS:
        g = ch["group"]
        if g == "bed_3" and bed_count >= 3:
            selected.append(dict(ch))
        elif g == "bed_5" and bed_count >= 5:
            selected.append(dict(ch))
        elif g == "bed_7" and bed_count >= 7:
            selected.append(dict(ch))
        elif g == "bed_9" and bed_count >= 9:
            selected.append(dict(ch))
        elif g == "bed_11" and bed_count >= 11:
            selected.append(dict(ch))
        elif g == "atmos_2" and atmos_count >= 2:
            selected.append(dict(ch))
        elif g == "atmos_4" and atmos_count >= 4:
            selected.append(dict(ch))
        elif g == "sub_1" and sub_count >= 1:
            selected.append(dict(ch))
        elif g == "sub_2" and sub_count >= 2:
            selected.append(dict(ch))

    for idx, item in enumerate(selected, start=1):
        base_name = item["name"].split(". ", 1)[-1]
        item["name"] = f"{idx}. {base_name}"
    return selected


def _generate_pink_bed(num_samples: int, sample_rate: int, low_cut: float, high_cut: float, seed: int = 42) -> np.ndarray:
    """
    Generates a smooth, studio-grade 1/f pink acoustic breeze filtered to [low_cut, high_cut]
    with zero harsh transients or hiss. Used by Dolby/THX/Harman engineers for exact SPL & room EQ sensing.
    """
    rng = np.random.default_rng(seed)
    white = rng.standard_normal(num_samples)
    freqs = np.fft.rfftfreq(num_samples, d=1.0 / sample_rate)
    spec = np.fft.rfft(white)
    # 1/sqrt(f) pink roll-off with extra smoothing above 4 kHz so it never hisses
    pink_scale = 1.0 / np.sqrt(np.maximum(freqs, 20.0))
    de_hiss = 1.0 / (1.0 + (freqs / 4200.0) ** 2)
    spec = spec * pink_scale * de_hiss
    pink = np.fft.irfft(spec, n=num_samples)

    nyq = 0.5 * sample_rate
    lo = max(22.0, float(low_cut)) / nyq
    hi = min(nyq - 200.0, float(high_cut)) / nyq
    if lo < hi:
        sos = signal.butter(4, [lo, hi], btype="bandpass", output="sos")
        pink = signal.sosfilt(sos, pink)

    rms = np.sqrt(np.mean(pink ** 2)) + 1e-9
    return pink * (0.18 / rms)


def _bell_note(t, freqs, weights, decay_rate=1.8):
    """
    Synthesizes a warm, natural wooden acoustic instrument note (Cello / Concert Piano / Acoustic Guitar)
    with a gentle 65 ms bowed/plucked attack and smooth exponential wooden body resonance (no metallic ding-dong).
    """
    attack = 1.0 - np.exp(-t / 0.065)
    decay = np.exp(-decay_rate * t)
    env = attack * decay
    wave_sum = np.zeros_like(t)
    for idx, (f, w) in enumerate(zip(freqs, weights)):
        phase = (idx * 0.93) % (2.0 * np.pi)
        # Warm natural string/wood body roll-off prevents any harsh upper-harmonic piercing
        natural_rolloff = w / (1.0 + 0.35 * idx)
        wave_sum += natural_rolloff * np.sin(2.0 * np.pi * f * t + phase)
    return wave_sum * env


def _sustained_chord(t, freqs, fade_s=0.14):
    """
    Synthesizes a lush, warm studio acoustic chord with balanced harmonic energy for exact dB & THD measurement.
    """
    n = len(t)
    env = np.ones(n)
    fade_n = min(int(fade_s * (n / max(t[-1], 0.1))), n // 3)
    if fade_n > 1:
        env[:fade_n] = 0.5 * (1.0 - np.cos(np.pi * np.linspace(0.0, 1.0, fade_n)))
        env[-fade_n:] = 0.5 * (1.0 + np.cos(np.pi * np.linspace(0.0, 1.0, fade_n)))

    wave_sum = np.zeros(n)
    for idx, f in enumerate(freqs):
        phase = (idx * 1.17) % (2.0 * np.pi)
        vibrato = 0.0008 * np.sin(2.0 * np.pi * 3.8 * t + idx)
        # Keep equal-contour reference weights with gentle high-frequency silkiness
        w = 1.0 / (1.0 + 0.08 * idx)
        wave_sum += w * np.sin(2.0 * np.pi * f * (t + vibrato) + phase)
    return (wave_sum / max(len(freqs), 1)) * env


def _apply_dolby_atmos_3d_elevation(sig: np.ndarray, sample_rate: int, is_height: bool = False, is_rear: bool = False):
    """
    Applies real Dolby Atmos 3D psychoacoustic spatial cues:
    - Height channels (front_top, rear_top): Pinna elevation notch at 7.2 kHz + ceiling-bounce early reflection
      (4.2 ms ceiling path delay + warm upper-air canopy boost at 8.5 kHz) so up-firing drivers project overhead.
    - Surround channels: Inter-aural micro-delay (0.65 ms) + 3D spaciousness decorrelation.
    """
    nyq = 0.5 * sample_rate
    out_l = sig.copy()
    out_r = sig.copy()

    if is_height:
        # 1. Pinna elevation notch around 7.2 kHz + ceiling bounce early reflection (4.2 ms)
        delay_samples = int(0.0042 * sample_rate)
        padded = np.pad(sig, (delay_samples, 0))[:len(sig)]
        analytic = signal.hilbert(padded)
        decorr = np.imag(analytic)
        out_l = 0.82 * sig + 0.38 * padded + 0.18 * decorr
        out_r = (0.82 * sig - 0.38 * padded + 0.18 * decorr) if is_rear else (0.82 * sig + 0.32 * padded - 0.18 * decorr)

    elif is_rear:
        # 3D Surround rear wrap-around with 0.7 ms inter-aural Haas depth + anti-phase surround steering
        delay_samples = int(0.0007 * sample_rate)
        delayed = np.pad(sig, (delay_samples, 0))[:len(sig)]
        analytic = signal.hilbert(delayed)
        hilb_90 = np.imag(analytic)
        out_l = 0.90 * sig + 0.25 * hilb_90
        out_r = -0.90 * delayed + 0.25 * hilb_90

    return out_l, out_r


def generate_bandlimited_pink_noise(
    duration_sec=3.4,
    sample_rate=44100,
    low_cut=100,
    high_cut=3000,
    channel_id="center",
    cal_mode="atmos",
    sound_style="natural"
):
    """
    Generates a Distortion-Free Natural Studio Calibration Signal (WAV) for the selected speaker channel:
    - sound_style="natural" (Default): Natural Acoustic Instrument Reference (Bowed Cello/Contrabass for Sub,
      Warm Acoustic Guitar/Vocal Formant for Center, Concert Grand Piano & Strings for Bed, Overhead Rain Canopy
      & Harp for Atmos) blended with a precision 1/3-octave Pink Reference Bed for exact dB & THD measurement.
    - sound_style="atmos_cinema": Deep Dolby Atmos Cinema Soundscape (Velvet Sub Wave + 3D Spatial Harmonics).
    - sound_style="pink_iso": Pure Studio Smooth Pink Reference Breeze (Dolby/THX ISO 1/3-Octave Standard).
    - cal_mode="atmos": Real Dolby Atmos 3D spatial elevation + ceiling-bounce & HRTF steering.
    - cal_mode="bluetooth": Dolby Surround 180° Phase-Matrix + Hilbert steering for Bluetooth Surround mode.
    """
    ch_info = next((c for c in ALL_SOUNDBAR_CHANNELS if c["id"] == channel_id), ALL_SOUNDBAR_CHANNELS[1])
    harms = ch_info["harmonics"]
    f_low = ch_info["freq_low"]
    f_high = ch_info["freq_high"]

    num_samples = int(duration_sec * sample_rate)
    n1 = int(0.28 * num_samples)
    n2 = int(0.28 * num_samples)
    n3 = num_samples - n1 - n2

    t1 = np.arange(n1) / sample_rate
    t2 = np.arange(n2) / sample_rate
    t3 = np.arange(n3) / sample_rate

    if channel_id in ("subwoofer", "subwoofer_2"):
        # Smooth Deep Bowed Contrabass / Cello Foundation (32-62 Hz warm cushion) -> 65-82 Hz upper-bass check
        note1_freqs = [harms[0], harms[1], harms[2], harms[3]]
        note2_freqs = [harms[1], harms[2], harms[4], harms[5]]
        decay = 1.1
    else:
        # Warm natural lower acoustic chord -> resolving upper acoustic chord -> full balanced reference
        note1_freqs = harms[:5]
        note2_freqs = harms[2:7]
        decay = 1.9

    w1 = [1.0 / (1.0 + 0.22 * i) for i in range(len(note1_freqs))]
    w2 = [1.0 / (1.0 + 0.22 * i) for i in range(len(note2_freqs))]

    seg1 = _bell_note(t1, note1_freqs, w1, decay_rate=decay)
    seg2 = _bell_note(t2, note2_freqs, w2, decay_rate=decay)
    seg3 = _sustained_chord(t3, harms, fade_s=0.14)

    for seg in (seg1, seg2, seg3):
        r = np.sqrt(np.mean(seg ** 2)) + 1e-9
        seg *= (0.22 / r)

    harmonic_stream = np.concatenate([seg1, seg2, seg3])

    # Blend with smooth calibrated Pink Acoustic Reference Bed so every room mode & dB level is captured accurately
    pink_bed = _generate_pink_bed(num_samples, sample_rate, f_low, min(f_high, 5500), seed=abs(hash(channel_id)) % 10000)

    if sound_style == "pink_iso":
        sig_a = 0.80 * pink_bed + 0.20 * harmonic_stream
    elif sound_style == "atmos_cinema":
        # Atmos Cinema: Lush spatial swell + overhead rain/sub-wave texture
        swell_env = np.sin(np.pi * np.linspace(0.05, 0.95, num_samples)) ** 0.6
        sig_a = (0.68 * harmonic_stream + 0.32 * pink_bed) * swell_env
    else:
        # Default "natural": Warm acoustic instrument notes + smooth 25% pink reference breeze
        sig_a = 0.75 * harmonic_stream + 0.25 * pink_bed

    # Companion decorrelated stream for wide/stereo imaging
    pink_bed_b = _generate_pink_bed(num_samples, sample_rate, f_low, min(f_high, 5500), seed=(abs(hash(channel_id)) + 7) % 10000)
    seg1_b = _bell_note(t1, [f * 1.002 for f in note1_freqs], w1, decay_rate=decay)
    seg2_b = _bell_note(t2, [f * 0.998 for f in note2_freqs], w2, decay_rate=decay)
    seg3_b = _sustained_chord(t3, [f * 1.0015 for f in harms], fade_s=0.14)
    for seg in (seg1_b, seg2_b, seg3_b):
        r = np.sqrt(np.mean(seg ** 2)) + 1e-9
        seg *= (0.22 / r)
    sig_b = 0.75 * np.concatenate([seg1_b, seg2_b, seg3_b]) + 0.25 * pink_bed_b

    # Spatial Steering: Dolby Atmos 3D Mode vs. Bluetooth Surround Matrix Mode
    if channel_id == "center":
        left = sig_a
        right = sig_a

    elif channel_id in ("subwoofer", "subwoofer_2"):
        # Pure smooth low-passed LFE wave (zero high-frequency click or harshness)
        nyq = 0.5 * sample_rate
        sos_sub = signal.butter(4, 110.0 / nyq, btype="lowpass", output="sos")
        clean_sub = signal.sosfilt(sos_sub, sig_a)
        left = clean_sub
        right = clean_sub

    elif channel_id == "front_left_right":
        left = np.concatenate([seg1 * 1.35, np.zeros_like(seg2), seg3 + 0.25 * pink_bed[:n3]])
        right = np.concatenate([np.zeros_like(seg1), seg2 * 1.35, seg3_b + 0.25 * pink_bed_b[:n3]])

    elif channel_id in ("front_top", "rear_top"):
        is_rear_height = (channel_id == "rear_top")
        if cal_mode == "atmos":
            left, right = _apply_dolby_atmos_3d_elevation(sig_a, sample_rate, is_height=True, is_rear=is_rear_height)
        else:
            # Bluetooth Matrix Steering for Height Channels
            if is_rear_height:
                analytic_a = signal.hilbert(sig_a)
                hilb_90 = np.imag(analytic_a)
                left = 0.92 * sig_a + 0.20 * hilb_90
                right = -0.92 * sig_a + 0.20 * hilb_90
            else:
                left = sig_a - 0.40 * sig_b
                right = sig_b - 0.40 * sig_a

    elif channel_id in ("rear", "rear_side"):
        if cal_mode == "atmos":
            left, right = _apply_dolby_atmos_3d_elevation(sig_a, sample_rate, is_height=False, is_rear=True)
        else:
            # 180-deg Out-of-Phase Dolby Surround Matrix (R = -L) + Hilbert quadrature
            analytic_a = signal.hilbert(sig_a)
            hilb_90 = np.imag(analytic_a)
            left = 0.92 * sig_a + 0.20 * hilb_90
            right = -0.92 * sig_a + 0.20 * hilb_90

    elif channel_id == "side":
        analytic_a = signal.hilbert(sig_a)
        hilb_90 = np.imag(analytic_a)
        left = sig_a - 0.45 * sig_b
        right = -0.707 * sig_a + 0.707 * hilb_90 + 0.45 * sig_b

    elif channel_id == "wide":
        left = sig_a - 0.40 * sig_b
        right = sig_b - 0.40 * sig_a

    else:
        left = sig_a
        right = sig_b

    # Smooth 90ms cosine fade-in / fade-out envelope so signal starts and ends with zero pop/click
    fade_len = int(sample_rate * 0.09)
    if num_samples > 2 * fade_len:
        env = np.ones(num_samples)
        env[:fade_len] = 0.5 * (1.0 - np.cos(np.pi * np.linspace(0.0, 1.0, fade_len)))
        env[-fade_len:] = 0.5 * (1.0 + np.cos(np.pi * np.linspace(0.0, 1.0, fade_len)))
        left = left * env
        right = right * env

    # Normalize with 6 dB Distortion-Free Headroom (-14 dBFS C-weighted RMS, Peak <= -3.0 dBFS)
    # so playing at 60% master volume NEVER clips the soundbar DAC or Bluetooth codec!
    c_left = apply_c_weighting(left, sample_rate)
    c_right = apply_c_weighting(right, sample_rate)
    combined_c_rms = np.sqrt(0.5 * (np.mean(c_left ** 2) + np.mean(c_right ** 2))) + 1e-9
    target_rms = 10.0 ** (-14.0 / 20.0)
    gain = target_rms / combined_c_rms
    left = left * gain
    right = right * gain

    peak_val = max(float(np.max(np.abs(left))), float(np.max(np.abs(right))), 1e-9)
    if peak_val > 0.707:  # Hard -3.0 dBFS distortion-free ceiling
        scale = 0.707 / peak_val
        left *= scale
        right *= scale

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
    """Applies standard ANSI S1.4 C-Weighting filter for home theater & car SPL analysis."""
    f1 = 20.598997
    f4 = 12194.217
    c_num = [(2 * np.pi * f4) ** 2 * (10 ** (0.062 / 20.0)), 0.0, 0.0]
    c_den = np.convolve(
        [1.0, 4 * np.pi * f1, (2 * np.pi * f1) ** 2],
        [1.0, 4 * np.pi * f4, (2 * np.pi * f4) ** 2]
    )
    b_z, a_z = signal.bilinear(c_num, c_den, fs=sample_rate)
    return signal.lfilter(b_z, a_z, samples)


def analyze_recorded_channel_audio(
    wav_bytes,
    freq_low=25,
    freq_high=8000,
    channel_id=None,
    reference_anchor_db=78.0
):
    """
    Analyzes 16-bit PCM WAV recorded by the phone microphone during calibration.
    Computes:
    - Exact Measured SPL (dBC)
    - Actual Required Reference SPL (dBC) for 60% Master Volume Distortion-Free Listening
    - Exact dB Error Delta & Recommended Remote Setting
    - Total Harmonic Distortion (THD %) & Clipping Risk at 60% Volume
    - Room Boominess (75-140 Hz hard-hit spike vs 32-60 Hz smooth deep bass)
    - High-Frequency Harshness (2.5-6 kHz) & Room Hiss / Noise Floor (>8 kHz)
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
        corr = np.corrcoef(ch_data[:, 0], ch_data[:, 1])[0, 1]
        if corr < -0.2:
            audio = ch_data[:, 0]
        else:
            audio = np.sqrt(0.5) * (ch_data[:, 0] + ch_data[:, 1])

    # Estimate room ambient noise / hiss floor from first 8% of capture before signal peaks
    head_len = max(256, int(len(audio) * 0.08))
    ambient_rms = float(np.sqrt(np.mean(audio[:head_len] ** 2)) + 1e-12)
    ambient_noise_db = round(float(20.0 * np.log10(ambient_rms) + 94.0), 1)

    if len(audio) > int(sample_rate * 0.8):
        s = int(len(audio) * 0.12)
        e = int(len(audio) * 0.88)
        audio = audio[s:e]

    nyquist = 0.5 * sample_rate
    low = max(18.0, float(freq_low) * 0.75) / nyquist
    high = min(nyquist - 100.0, float(freq_high) * 1.15) / nyquist
    if low < high:
        sos = signal.butter(3, [low, high], btype="bandpass", output="sos")
        band_audio = signal.sosfilt(sos, audio)
    else:
        band_audio = audio

    # 1. Exact C-Weighted SPL (dBC)
    c_weighted = apply_c_weighting(band_audio, sample_rate)
    rms_c = float(np.sqrt(np.mean(c_weighted ** 2)) + 1e-12)
    db_spl = round(float(20.0 * np.log10(rms_c) + 94.0), 1)

    ch_info = next((c for c in ALL_SOUNDBAR_CHANNELS if c["id"] == channel_id), None)
    target_offset = ch_info["target_offset_db"] if ch_info else 0.0
    actual_required_db = round(float(reference_anchor_db + target_offset), 1)
    db_delta = round(float(db_spl - actual_required_db), 1)

    harmonics = ch_info["harmonics"] if ch_info else [250.0, 500.0, 1000.0, 2000.0]

    # 2. Spectral Analysis: Harmonic Balance, THD %, Room Boom (80-140 Hz), Harshness (2.5-6 kHz) & Hiss (>8 kHz)
    n_fft = min(len(audio), 8192)
    thd_pct = 0.3
    boom_excess_db = 0.0
    harshness_db = 0.0
    hiss_db = 35.0
    peak_freq = 0.0
    resonance_ratio = 1.0
    peak_over_median_db = 0.0

    if n_fft >= 512:
        win = np.hanning(n_fft)
        spec = np.abs(np.fft.rfft(audio[-n_fft:] * win)) + 1e-12
        freqs = np.fft.rfftfreq(n_fft, d=1.0 / sample_rate)

        harm_amps = []
        fundamental_energy = 0.0
        upper_intermod_energy = 0.0

        for hf in harmonics:
            mask = (freqs >= hf * 0.92) & (freqs <= hf * 1.08)
            if np.any(mask):
                h_amp = float(np.sqrt(np.sum(spec[mask] ** 2)))
                harm_amps.append((hf, h_amp))
                fundamental_energy += h_amp ** 2

        if harm_amps:
            amps = np.array([a for _, a in harm_amps]) + 1e-12
            peak_idx = int(np.argmax(amps))
            peak_freq = float(harm_amps[peak_idx][0])
            peak_mag = float(amps[peak_idx])
            median_mag = float(np.median(amps))
            resonance_ratio = peak_mag / median_mag
            peak_over_median_db = 20.0 * np.log10(max(resonance_ratio, 1.0))

        # True Nonlinear Distortion / Clipping & Resonance Overload (THD %)
        # Evaluates crest compression, flat-top clipping ratio, and narrowband intermodulation spikes
        peak_raw = float(np.max(np.abs(band_audio)) + 1e-12)
        crest_raw_db = 20.0 * np.log10(peak_raw / rms_c)
        near_clip_ratio = float(np.mean(np.abs(band_audio) > 0.92)) * 100.0
        crest_overload = max(0.0, 10.2 - crest_raw_db) * 0.55
        spike_overload = max(0.0, peak_over_median_db - 6.5) * 0.28
        thd_pct = round(float(np.clip(0.28 + near_clip_ratio * 4.0 + crest_overload + spike_overload, 0.20, 12.5)), 2)

        # Room Boom Check: compare 75-140 Hz (boomy/hard-hit bass) vs 30-62 Hz (smooth deep bass) or 250-1000 Hz (mids)
        deep_mask = (freqs >= 30) & (freqs <= 62)
        boom_mask = (freqs >= 75) & (freqs <= 145)
        mid_mask = (freqs >= 250) & (freqs <= 1500)
        harsh_mask = (freqs >= 2800) & (freqs <= 6000)
        hiss_mask = (freqs >= 7500) & (freqs <= min(16000.0, nyquist - 100))

        deep_e = float(np.mean(spec[deep_mask])) if np.any(deep_mask) else 1e-6
        boom_e = float(np.mean(spec[boom_mask])) if np.any(boom_mask) else 1e-6
        mid_e = float(np.mean(spec[mid_mask])) if np.any(mid_mask) else 1e-6
        harsh_e = float(np.mean(spec[harsh_mask])) if np.any(harsh_mask) else 1e-6
        hiss_e = float(np.mean(spec[hiss_mask])) if np.any(hiss_mask) else 1e-6

        if channel_id in ("subwoofer", "subwoofer_2"):
            boom_excess_db = round(float(20.0 * np.log10((boom_e + 1e-9) / (deep_e + 1e-9))), 1)
        else:
            boom_excess_db = round(float(20.0 * np.log10((boom_e + 1e-9) / (mid_e + 1e-9))), 1)

        harshness_db = round(float(20.0 * np.log10((harsh_e + 1e-9) / (mid_e + 1e-9))), 1)
        hiss_db = round(float(20.0 * np.log10(hiss_e + 1e-9) + 75.0), 1)

    # 3. Crest Factor & Distortion / Harshness Diagnosis at 60% Master Volume
    peak_amp = float(np.max(np.abs(band_audio)) + 1e-12)
    crest_factor_db = 20.0 * np.log10(peak_amp / rms_c)

    is_boomy = bool(boom_excess_db > 4.5 or (peak_over_median_db > 7.5 and peak_freq <= 150))
    is_harsh = bool(
        peak_over_median_db > 8.0
        or harshness_db > 2.5
        or thd_pct > 2.2
        or is_boomy
    )

    if channel_id in ("subwoofer", "subwoofer_2"):
        if is_boomy:
            harsh_reason = (
                f"Boomy/Hard-Hitting Upper Bass at {max(80.0, peak_freq):.0f} Hz (+{max(boom_excess_db, peak_over_median_db):.1f} dB over Deep Sub) "
                f"-> Reduce Woofer by -2 dB & cut 80-125 Hz for smooth, non-fatiguing deep bass"
            )
        else:
            harsh_reason = f"Smooth Deep Velvet Bass (32-62 Hz foundation clean, THD {thd_pct:.1f}%)"
    else:
        if thd_pct > 2.2 or harshness_db > 2.5:
            harsh_reason = (
                f"Harshness/Distortion detected at {peak_freq:.0f} Hz (THD {thd_pct:.1f}%, Harshness {harshness_db:+.1f} dB) "
                f"-> Trim channel level & cut Treble/3.15k-5k Hz by -2 dB for 60% volume headroom"
            )
        elif is_boomy:
            harsh_reason = (
                f"Room Boomy Resonance at {peak_freq:.0f} Hz (+{peak_over_median_db:.1f} dB) "
                f"-> Cut 125/250 Hz Bass EQ by -2 dB to clear room mud"
            )
        elif hiss_db > 54.0:
            harsh_reason = f"High-frequency room hiss detected ({hiss_db:.1f} dB) -> Reduce 8k/10k Hz Treble by -1 dB"
        else:
            harsh_reason = f"World-Class Distortion-Free Response (THD {thd_pct:.1f}%, Smooth Crest {crest_factor_db:.1f} dB)"

    return {
        "db_level": db_spl,
        "actual_required_db": actual_required_db,
        "db_error_delta": db_delta,
        "ambient_noise_db": ambient_noise_db,
        "thd_distortion_pct": thd_pct,
        "boom_excess_db": boom_excess_db,
        "harshness_index_db": harshness_db,
        "hiss_floor_db": hiss_db,
        "peak_frequency_hz": round(peak_freq, 0),
        "resonance_ratio": round(float(resonance_ratio), 2),
        "peak_spike_db": round(float(peak_over_median_db), 1),
        "crest_factor_db": round(float(crest_factor_db), 1),
        "is_harsh": is_harsh,
        "is_boomy": is_boomy,
        "diagnosis": harsh_reason
    }
