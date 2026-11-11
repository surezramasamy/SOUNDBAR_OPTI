import numpy as np
from scipy import signal
import io
import wave

from app.audio_dsp import apply_c_weighting, _bell_note, _sustained_chord

# Official Pioneer DMH-Z5290BT Standard-Mode Crossover Cutoff Steps (Hz)
PIONEER_XOVER_STEPS = [50, 63, 80, 100, 125, 160, 200]

# Official Pioneer DMH-Z5290BT 13-Band Graphic EQ Centers (Hz)
PIONEER_13BAND_EQ = [50, 80, 125, 200, 315, 500, 800, 1250, 2000, 3150, 5000, 8000, 12500]

# Pioneer DMH-Z5290BT 4 Speakers + 1 Subwoofer + Crossover Blend Map
CAR_CHANNELS = [
    {
        "id": "front_left",
        "name": "1. Front Left Speaker (FL)",
        "pioneer_menu": "Audio -> Speaker Level -> Front Left",
        "xover_menu": "Audio -> Crossover -> Front HPF",
        "pan": "left",
        "min_db": -24,
        "max_db": 10,
        "target_offset_db": 0.0,
        "freq_low": 55,
        "freq_high": 4500,
        "harmonics": [63.0, 80.0, 100.0, 125.0, 250.0, 500.0, 1000.0, 2500.0],
        "desc": "100% Left-panned musical Ding-Dong + low-mid check (63-2500 Hz). Measures FL level & door bass limit."
    },
    {
        "id": "front_right",
        "name": "2. Front Right Speaker (FR)",
        "pioneer_menu": "Audio -> Speaker Level -> Front Right",
        "xover_menu": "Audio -> Crossover -> Front HPF",
        "pan": "right",
        "min_db": -24,
        "max_db": 10,
        "target_offset_db": 0.0,
        "freq_low": 55,
        "freq_high": 4500,
        "harmonics": [63.0, 80.0, 100.0, 125.0, 250.0, 500.0, 1000.0, 2500.0],
        "desc": "100% Right-panned musical Ding-Dong + low-mid check. Balances FR to match FL at driver's ear."
    },
    {
        "id": "rear_left",
        "name": "3. Rear Left Speaker (RL)",
        "pioneer_menu": "Audio -> Speaker Level -> Rear Left",
        "xover_menu": "Audio -> Crossover -> Rear HPF",
        "pan": "rear_left",
        "min_db": -24,
        "max_db": 10,
        "target_offset_db": -2.0,
        "freq_low": 65,
        "freq_high": 4000,
        "harmonics": [80.0, 100.0, 125.0, 160.0, 315.0, 630.0, 1250.0, 2500.0],
        "desc": "Left-panned chime. Tip: Temporarily fade Pioneer to Rear while testing RL/RR, or test in place (-2 dB target)."
    },
    {
        "id": "rear_right",
        "name": "4. Rear Right Speaker (RR)",
        "pioneer_menu": "Audio -> Speaker Level -> Rear Right",
        "xover_menu": "Audio -> Crossover -> Rear HPF",
        "pan": "rear_right",
        "min_db": -24,
        "max_db": 10,
        "target_offset_db": -2.0,
        "freq_low": 65,
        "freq_high": 4000,
        "harmonics": [80.0, 100.0, 125.0, 160.0, 315.0, 630.0, 1250.0, 2500.0],
        "desc": "Right-panned chime. Matched to RL at -2 dB below front stage so vocals stay in front of you."
    },
    {
        "id": "subwoofer",
        "name": "5. Subwoofer (SW)",
        "pioneer_menu": "Audio -> Speaker Level -> Subwoofer",
        "xover_menu": "Audio -> Crossover -> Subwoofer LPF",
        "pan": "sub",
        "min_db": -24,
        "max_db": 10,
        "target_offset_db": 3.0,
        "freq_low": 28,
        "freq_high": 130,
        "harmonics": [31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0],
        "desc": "Deep musical Boom-Dum bass chord (31.5-125 Hz). Finds cabin boom & optimal Sub LPF cutoff."
    },
    {
        "id": "crossover_blend",
        "name": "6. Crossover & Phase Blend (Sub + Doors)",
        "pioneer_menu": "Audio -> Crossover -> Sub Phase (Normal/Reverse)",
        "xover_menu": "HPF / LPF Transition (50 - 125 Hz)",
        "pan": "stereo",
        "min_db": -24,
        "max_db": 10,
        "target_offset_db": 1.5,
        "freq_low": 40,
        "freq_high": 160,
        "harmonics": [40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0],
        "desc": "Plays crossover transition notes (40-200 Hz) on Sub + Doors together to detect phase cancellation holes."
    }
]


def generate_car_test_chime(channel_id: str, duration_sec: float = 3.2, sample_rate: int = 44100) -> bytes:
    """
    Generates a pleasant 'Ding-Dong + Harmonic Chord' WAV tailored to the Pioneer DMH-Z5290BT:
    - Hard Left (100% L, 0% R) for Front Left / Rear Left
    - Hard Right (0% L, 100% R) for Front Right / Rear Right
    - Centered Mono Bass for Subwoofer and Crossover Blend
    """
    ch_info = next((c for c in CAR_CHANNELS if c["id"] == channel_id), CAR_CHANNELS[0])
    harms = ch_info["harmonics"]

    num_samples = int(duration_sec * sample_rate)
    n1 = int(0.28 * num_samples)
    n2 = int(0.28 * num_samples)
    n3 = num_samples - n1 - n2

    t1 = np.arange(n1) / sample_rate
    t2 = np.arange(n2) / sample_rate
    t3 = np.arange(n3) / sample_rate

    if channel_id in ("subwoofer", "crossover_blend"):
        ding_freqs = [harms[3], harms[4], harms[5], harms[6]]
        dong_freqs = [harms[0], harms[1], harms[2], harms[3]]
        decay = 2.0
    else:
        ding_freqs = [harms[4], harms[5], harms[6], harms[7]]
        dong_freqs = [harms[0], harms[1], harms[2], harms[3], harms[4]]
        decay = 3.0

    w_ding = [1.0 / (1.0 + 0.2 * i) for i in range(len(ding_freqs))]
    w_dong = [1.0 / (1.0 + 0.2 * i) for i in range(len(dong_freqs))]

    seg_ding = _bell_note(t1, ding_freqs, w_ding, decay_rate=decay)
    seg_dong = _bell_note(t2, dong_freqs, w_dong, decay_rate=decay)
    seg_chord = _sustained_chord(t3, harms, fade_s=0.08)

    for seg in (seg_ding, seg_dong, seg_chord):
        r = np.sqrt(np.mean(seg ** 2)) + 1e-9
        seg *= (0.25 / r)

    mono_sig = np.concatenate([seg_ding, seg_dong, seg_chord])

    # Apply outer fade
    fade_len = int(sample_rate * 0.05)
    if num_samples > 2 * fade_len:
        env = np.ones(num_samples)
        env[:fade_len] = np.linspace(0.0, 1.0, fade_len)
        env[-fade_len:] = np.linspace(1.0, 0.0, fade_len)
        mono_sig = mono_sig * env

    # Normalize C-weighted energy to -12 dBFS reference
    c_sig = apply_c_weighting(mono_sig, sample_rate)
    c_rms = np.sqrt(np.mean(c_sig ** 2)) + 1e-9
    target_rms = 10.0 ** (-12.0 / 20.0)
    mono_sig = np.clip(mono_sig * (target_rms / c_rms), -0.95, 0.95)

    pan = ch_info["pan"]
    zeros = np.zeros_like(mono_sig)

    if pan in ("left", "rear_left"):
        left = mono_sig
        right = zeros
    elif pan in ("right", "rear_right"):
        left = zeros
        right = mono_sig
    else:
        left = mono_sig
        right = mono_sig

    pcm_l = (left * 32767.0).astype(np.int16)
    pcm_r = (right * 32767.0).astype(np.int16)
    stereo = np.column_stack((pcm_l, pcm_r)).ravel()

    buf = io.BytesIO()
    with wave.open(buf, "wb") as wf:
        wf.setnchannels(2)
        wf.setsampwidth(2)
        wf.setframerate(sample_rate)
        wf.writeframes(stereo.tobytes())

    return buf.getvalue()


def analyze_car_channel_audio(wav_bytes: bytes, channel_id: str) -> dict:
    """
    Analyzes recorded phone mic audio for a Pioneer DMH-Z5290BT speaker or subwoofer.
    Measures C-weighted SPL dB, energy at each Pioneer crossover step (50, 63, 80, 100, 125, 160 Hz),
    detects door panel rattle / cabin boom / crossover cancellation holes, and returns metrics.
    """
    ch_info = next((c for c in CAR_CHANNELS if c["id"] == channel_id), CAR_CHANNELS[0])
    freq_low = ch_info["freq_low"]
    freq_high = ch_info["freq_high"]
    harmonics = ch_info["harmonics"]

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
        # Pick active channel (since FL/FR are hard-panned L or R)
        s0, s1 = np.std(ch_data[:, 0]), np.std(ch_data[:, 1])
        audio = ch_data[:, 0] if s0 >= s1 else ch_data[:, 1]

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

    c_weighted = apply_c_weighting(band_audio, sample_rate)
    rms_c = float(np.sqrt(np.mean(c_weighted ** 2)) + 1e-12)
    db_spl = 20.0 * np.log10(rms_c) + 94.0

    # FFT analysis across the harmonic chord (last 45% of recording where all harmonics sound together)
    chord_slice = band_audio[int(len(band_audio) * 0.45):]
    n_fft = min(len(chord_slice), 8192)
    band_energies_db = {}

    if n_fft >= 512:
        win = np.hanning(n_fft)
        spec = np.abs(np.fft.rfft(chord_slice[-n_fft:] * win))
        freqs = np.fft.rfftfreq(n_fft, d=1.0 / sample_rate)

        harm_amps = []
        for hf in harmonics:
            mask = (freqs >= hf * 0.92) & (freqs <= hf * 1.08)
            if np.any(mask):
                amp = float(np.max(spec[mask])) + 1e-12
                harm_amps.append((hf, amp))
                band_energies_db[str(int(round(hf)))] = round(float(20.0 * np.log10(amp)), 1)

        if harm_amps:
            amps = np.array([a for _, a in harm_amps])
            peak_idx = int(np.argmax(amps))
            dip_idx = int(np.argmin(amps))
            peak_freq = float(harm_amps[peak_idx][0])
            dip_freq = float(harm_amps[dip_idx][0])
            median_mag = float(np.median(amps)) + 1e-12
            peak_over_median_db = 20.0 * np.log10(max(amps[peak_idx] / median_mag, 1.0))
            dip_below_median_db = 20.0 * np.log10(max(median_mag / (amps[dip_idx] + 1e-12), 1.0))
        else:
            peak_freq, dip_freq = 80.0, 80.0
            peak_over_median_db, dip_below_median_db = 0.0, 0.0
    else:
        peak_freq, dip_freq = 80.0, 80.0
        peak_over_median_db, dip_below_median_db = 0.0, 0.0

    peak_amp = float(np.max(np.abs(band_audio)) + 1e-12)
    crest_factor_db = 20.0 * np.log10(peak_amp / rms_c)
    is_harsh = bool(peak_over_median_db > 8.5 or crest_factor_db > 16.5)

    # Recommend optimal Pioneer DMH-Z5290BT Crossover (HPF / LPF) for this channel
    if channel_id in ("front_left", "front_right"):
        if is_harsh and peak_freq <= 125:
            rec_hpf = 100 if peak_freq <= 90 else 125
            rec_slope = "-24 dB/oct"
            diagnosis = f"Door resonance/rattle at {peak_freq:.0f} Hz (+{peak_over_median_db:.1f} dB) -> Raise Front HPF to {rec_hpf} Hz"
        elif dip_below_median_db > 9.0 and dip_freq >= 80:
            rec_hpf = 100
            rec_slope = "-12 dB/oct"
            diagnosis = f"Weak door midbass below 100 Hz -> Set Front HPF to 100 Hz"
        else:
            rec_hpf = 80
            rec_slope = "-12 dB/oct"
            diagnosis = "Clean door response -> Standard 80 Hz HPF (-12 dB/oct)"
        xover_rec = f"Front HPF: {rec_hpf} Hz ({rec_slope})"

    elif channel_id in ("rear_left", "rear_right"):
        if is_harsh and peak_freq <= 140:
            rec_hpf = 100 if peak_freq <= 100 else 125
            rec_slope = "-18 dB/oct"
            diagnosis = f"Rear door boom at {peak_freq:.0f} Hz -> Raise Rear HPF to {rec_hpf} Hz"
        else:
            rec_hpf = 80
            rec_slope = "-12 dB/oct"
            diagnosis = "Clean rear fill -> Standard 80 Hz HPF (-12 dB/oct)"
        xover_rec = f"Rear HPF: {rec_hpf} Hz ({rec_slope})"

    elif channel_id == "subwoofer":
        if is_harsh and peak_freq >= 80:
            rec_lpf = 63
            rec_slope = "-24 dB/oct"
            diagnosis = f"Upper-bass boom at {peak_freq:.0f} Hz -> Lower Sub LPF to 63 Hz (-24 dB/oct)"
        elif is_harsh and peak_freq < 80:
            rec_lpf = 80
            rec_slope = "-24 dB/oct"
            diagnosis = f"Cabin standing-wave boom at {peak_freq:.0f} Hz -> Cut {peak_freq:.0f} Hz on Pioneer 13-Band EQ by -3 dB"
        else:
            rec_lpf = 80
            rec_slope = "-18 dB/oct"
            diagnosis = "Clean deep bass -> Set Sub LPF to 80 Hz (-18 dB/oct)"
        xover_rec = f"Sub LPF: {rec_lpf} Hz ({rec_slope})"

    else:  # crossover_blend
        if dip_below_median_db > 7.5 and 50 <= dip_freq <= 125:
            xover_rec = "Sub Phase: FLIP (Normal <-> Reverse 180°)"
            diagnosis = f"Phase cancellation hole at {dip_freq:.0f} Hz (-{dip_below_median_db:.1f} dB dip) -> Flip Sub Phase on Pioneer!"
            is_harsh = True
        elif peak_over_median_db > 8.5 and 50 <= peak_freq <= 125:
            xover_rec = "Sub Phase: OK | Set HPF/LPF Slopes to -24 dB/oct"
            diagnosis = f"Crossover overlap boom at {peak_freq:.0f} Hz (+{peak_over_median_db:.1f} dB) -> Steepen HPF/LPF slope to -24 dB/oct"
        else:
            xover_rec = "Sub Phase: Optimal (Keep current Normal/Reverse)"
            diagnosis = "Smooth 50-125 Hz transition between Subwoofer and Door Speakers!"

    return {
        "db_level": round(float(db_spl), 1),
        "peak_frequency_hz": round(peak_freq, 0),
        "dip_frequency_hz": round(dip_freq, 0),
        "peak_spike_db": round(float(peak_over_median_db), 1),
        "dip_depth_db": round(float(dip_below_median_db), 1),
        "is_harsh": is_harsh,
        "xover_recommendation": xover_rec,
        "diagnosis": diagnosis,
        "band_energies_db": band_energies_db
    }
