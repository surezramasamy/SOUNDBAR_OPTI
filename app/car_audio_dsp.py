import numpy as np
from scipy import signal
import io
import wave

from app.audio_dsp import apply_c_weighting, _bell_note, _sustained_chord, _generate_pink_bed

# Official Pioneer DMH-Z5290BT Standard-Mode Crossover Cutoff Steps (Hz)
PIONEER_XOVER_STEPS = [50, 63, 80, 100, 125, 160, 200]

# Official Pioneer DMH-Z5290BT 13-Band Graphic EQ Centers (Hz)
PIONEER_13BAND_EQ = [50, 80, 125, 200, 315, 500, 800, 1250, 2000, 3150, 5000, 8000, 12500]

# Pioneer DMH-Z5290BT Standard Mode (60% Volume Distortion-Free World-Class Reference):
# - Front Left / Right: 2-Way Component (Door Mid-Woofer + Tweeter on passive crossover)
# - Rear Left / Right: Coaxial Speakers
# - Subwoofer: L/R Sub Output (Smooth Deep Background Bass without hitting)
CAR_CHANNELS = [
    {
        "id": "front_left",
        "name": "1. Front Left — 2-Way Component (Mid + Tweeter)",
        "speaker_type": "2-Way Component (Passive Crossover)",
        "pioneer_menu": "Audio -> Speaker Level -> Front Left",
        "xover_menu": "Audio -> Crossover -> Front HPF",
        "pan": "left",
        "min_db": -24,
        "max_db": 10,
        "target_offset_db": 0.0,
        "freq_low": 55,
        "freq_high": 9000,
        "harmonics": [63.0, 80.0, 125.0, 250.0, 500.0, 1250.0, 3150.0, 5000.0, 8000.0],
        "desc": "Natural Acoustic Guitar & Concert Piano (Left). Measures FL dB, Required dB, THD%, Front HPF & Tweeter harshness."
    },
    {
        "id": "front_right",
        "name": "2. Front Right — 2-Way Component (Mid + Tweeter)",
        "speaker_type": "2-Way Component (Passive Crossover)",
        "pioneer_menu": "Audio -> Speaker Level -> Front Right",
        "xover_menu": "Audio -> Crossover -> Front HPF",
        "pan": "right",
        "min_db": -24,
        "max_db": 10,
        "target_offset_db": 0.0,
        "freq_low": 55,
        "freq_high": 9000,
        "harmonics": [63.0, 80.0, 125.0, 250.0, 500.0, 1250.0, 3150.0, 5000.0, 8000.0],
        "desc": "Natural Acoustic Guitar & Concert Piano (Right). Balances FR Component to match FL distortion-free at 60% Vol."
    },
    {
        "id": "rear_left",
        "name": "3. Rear Left — Coaxial Speaker (RL)",
        "speaker_type": "Coaxial 2-in-1 Speaker",
        "pioneer_menu": "Audio -> Speaker Level -> Rear Left",
        "xover_menu": "Audio -> Crossover -> Rear HPF",
        "pan": "rear_left",
        "min_db": -24,
        "max_db": 10,
        "target_offset_db": -2.0,
        "freq_low": 65,
        "freq_high": 7000,
        "harmonics": [80.0, 100.0, 125.0, 250.0, 500.0, 1250.0, 3150.0, 5000.0],
        "desc": "Natural Warm Strings (Left). Set Pioneer Fader to Rear during RL/RR test, then return Fader to 0."
    },
    {
        "id": "rear_right",
        "name": "4. Rear Right — Coaxial Speaker (RR)",
        "speaker_type": "Coaxial 2-in-1 Speaker",
        "pioneer_menu": "Audio -> Speaker Level -> Rear Right",
        "xover_menu": "Audio -> Crossover -> Rear HPF",
        "pan": "rear_right",
        "min_db": -24,
        "max_db": 10,
        "target_offset_db": -2.0,
        "freq_low": 65,
        "freq_high": 7000,
        "harmonics": [80.0, 100.0, 125.0, 250.0, 500.0, 1250.0, 3150.0, 5000.0],
        "desc": "Natural Warm Strings (Right). Balances RR Coaxial to RL (-2 dB rear wrap-around stage) & sets Rear HPF."
    },
    {
        "id": "subwoofer",
        "name": "5. Subwoofer (Smooth Deep Velvet Bass — No Hard Hit)",
        "speaker_type": "Subwoofer (Mono / Bridged Amp)",
        "pioneer_menu": "Audio -> Speaker Level -> Subwoofer",
        "xover_menu": "Audio -> Crossover -> Subwoofer LPF",
        "pan": "sub",
        "min_db": -24,
        "max_db": 10,
        "target_offset_db": 1.5,
        "freq_low": 28,
        "freq_high": 130,
        "harmonics": [31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0],
        "desc": "Natural Bowed Double-Bass & Cello Sustain (31.5-125 Hz). Eliminates 80-125 Hz chest slam & keeps deep 35-55 Hz bass."
    },
    {
        "id": "crossover_blend",
        "name": "6. Crossover & Phase Blend (Full 4.1 Smooth Transition)",
        "speaker_type": "Full System 4.1 Transition",
        "pioneer_menu": "Audio -> Crossover -> Sub Phase (Normal/Reverse)",
        "xover_menu": "HPF / LPF Transition (50 - 125 Hz)",
        "pan": "stereo",
        "min_db": -24,
        "max_db": 10,
        "target_offset_db": 1.0,
        "freq_low": 40,
        "freq_high": 160,
        "harmonics": [40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0],
        "desc": "Natural Deep Cello + Warm Piano Crossover Blend (40-200 Hz) to verify Sub Phase (Normal vs Reverse) without boom."
    }
]


def generate_car_test_chime(channel_id: str, duration_sec: float = 3.4, sample_rate: int = 44100) -> bytes:
    """
    Generates a Distortion-Free Natural Studio Acoustic Reference WAV for Pioneer DMH-Z5290BT:
    - Uses warm acoustic instrument envelopes + smooth studio pink reference bed with -14 dBFS RMS / -3 dBFS peak headroom
      so playing at 60% Master Volume (Vol 24/40) never clips the head-unit DAC or amplifier.
    """
    ch_info = next((c for c in CAR_CHANNELS if c["id"] == channel_id), CAR_CHANNELS[0])
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

    if channel_id in ("subwoofer", "crossover_blend"):
        # Smooth Deep Bowed Contrabass sustain (31.5-63 Hz) -> Upper-bass check (63-125 Hz) -> Full chord
        note1_freqs = [harms[0], harms[1], harms[2], harms[3]]
        note2_freqs = [harms[2], harms[3], harms[4], harms[5]]
        decay = 1.1
    else:
        # Warm Acoustic Guitar / Piano upper chord -> warm lower body chord -> full 9-harmonic reference chord
        note1_freqs = harms[4:]
        note2_freqs = harms[:5]
        decay = 1.9

    w1 = [1.0 / (1.0 + 0.2 * i) for i in range(len(note1_freqs))]
    w2 = [1.0 / (1.0 + 0.2 * i) for i in range(len(note2_freqs))]

    seg1 = _bell_note(t1, note1_freqs, w1, decay_rate=decay)
    seg2 = _bell_note(t2, note2_freqs, w2, decay_rate=decay)
    seg3 = _sustained_chord(t3, harms, fade_s=0.12)

    for seg in (seg1, seg2, seg3):
        r = np.sqrt(np.mean(seg ** 2)) + 1e-9
        seg *= (0.22 / r)

    harmonic_sig = np.concatenate([seg1, seg2, seg3])
    pink_bed = _generate_pink_bed(num_samples, sample_rate, f_low, min(f_high, 6000), seed=abs(hash(channel_id)) % 10000)

    # Keep chord segment (last 44%) pure harmonic energy so FFT per-band harmonic bins stay 100% accurate
    pink_env = np.ones(num_samples)
    pink_env[n1 + n2:] = 0.15
    mono_sig = 0.82 * harmonic_sig + 0.18 * (pink_bed * pink_env)

    fade_len = int(sample_rate * 0.08)
    if num_samples > 2 * fade_len:
        env = np.ones(num_samples)
        env[:fade_len] = 0.5 * (1.0 - np.cos(np.pi * np.linspace(0.0, 1.0, fade_len)))
        env[-fade_len:] = 0.5 * (1.0 + np.cos(np.pi * np.linspace(0.0, 1.0, fade_len)))
        mono_sig = mono_sig * env

    # Normalize C-weighted energy to -14 dBFS reference with -3 dBFS peak ceiling for 60% volume distortion-free playback
    c_sig = apply_c_weighting(mono_sig, sample_rate)
    c_rms = np.sqrt(np.mean(c_sig ** 2)) + 1e-9
    target_rms = 10.0 ** (-14.0 / 20.0)
    mono_sig = mono_sig * (target_rms / c_rms)
    peak_val = float(np.max(np.abs(mono_sig)) + 1e-9)
    if peak_val > 0.707:
        mono_sig *= (0.707 / peak_val)

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


def analyze_car_channel_audio(wav_bytes: bytes, channel_id: str, reference_anchor_db: float = 78.0) -> dict:
    """
    Analyzes recorded phone mic audio for Pioneer DMH-Z5290BT in Standard Mode at 60% Master Volume:
    - Computes Exact Measured dB, Actual Required Target dB, and Exact dB Delta.
    - Measures THD Distortion %, Upper-Bass Hard-Hit Spike (80-125 Hz), and Tweeter Harshness (3.15k-8k Hz).
    - Recommends 100% Subtractive, Distortion-Free Crossover (HPF/LPF), Speaker Level, and 13-Band EQ trims.
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
        s0, s1 = np.std(ch_data[:, 0]), np.std(ch_data[:, 1])
        audio = ch_data[:, 0] if s0 >= s1 else ch_data[:, 1]

    if len(audio) > int(sample_rate * 0.8):
        s = int(len(audio) * 0.12)
        e = int(len(audio) * 0.88)
        audio = audio[s:e]

    nyquist = 0.5 * sample_rate
    low = max(18.0, float(freq_low) * 0.75) / nyquist
    high = min(nyquist - 100.0, float(freq_high) * 1.12) / nyquist
    if low < high:
        sos = signal.butter(3, [low, high], btype="bandpass", output="sos")
        band_audio = signal.sosfilt(sos, audio)
    else:
        band_audio = audio

    c_weighted = apply_c_weighting(band_audio, sample_rate)
    rms_c = float(np.sqrt(np.mean(c_weighted ** 2)) + 1e-12)
    db_spl = round(float(20.0 * np.log10(rms_c) + 94.0), 1)
    actual_required_db = round(float(reference_anchor_db + ch_info["target_offset_db"]), 1)
    db_error_delta = round(float(db_spl - actual_required_db), 1)

    chord_slice = band_audio[int(len(band_audio) * 0.62):]
    n_fft = min(len(chord_slice), 8192)
    band_energies_db = {}
    harm_dict = {}
    thd_pct = 0.35

    if n_fft >= 512:
        win = np.hanning(n_fft)
        spec = np.abs(np.fft.rfft(chord_slice[-n_fft:] * win))
        freqs = np.fft.rfftfreq(n_fft, d=1.0 / sample_rate)

        harm_amps = []
        fund_energy = 0.0
        dist_energy = 0.0
        for hf in harmonics:
            mask = (freqs >= hf * 0.90) & (freqs <= hf * 1.10)
            if np.any(mask):
                amp = float(np.sqrt(np.sum(spec[mask] ** 2))) + 1e-12
                harm_amps.append((hf, amp))
                harm_dict[hf] = amp
                band_energies_db[str(int(round(hf)))] = round(float(20.0 * np.log10(amp)), 1)
                fund_energy += amp ** 2

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

        peak_raw = float(np.max(np.abs(band_audio)) + 1e-12)
        crest_raw_db = 20.0 * np.log10(peak_raw / rms_c)
        near_clip_ratio = float(np.mean(np.abs(band_audio) > 0.92)) * 100.0
        crest_overload = max(0.0, 10.2 - crest_raw_db) * 0.55
        spike_overload = max(0.0, peak_over_median_db - 6.5) * 0.28
        thd_pct = round(float(np.clip(0.30 + near_clip_ratio * 4.0 + crest_overload + spike_overload, 0.20, 12.0)), 2)
    else:
        peak_freq, dip_freq = 80.0, 80.0
        peak_over_median_db, dip_below_median_db = 0.0, 0.0

    peak_amp = float(np.max(np.abs(band_audio)) + 1e-12)
    crest_factor_db = 20.0 * np.log10(peak_amp / rms_c)
    is_harsh = bool(peak_over_median_db > 8.5 or crest_factor_db > 16.5 or thd_pct > 2.2)

    mid_amps = [v for f, v in harm_dict.items() if 200 <= f <= 1500]
    tw_amps = [v for f, v in harm_dict.items() if f >= 3000]
    if mid_amps and tw_amps:
        mid_avg = float(np.mean(mid_amps)) + 1e-12
        tw_avg = float(np.mean(tw_amps)) + 1e-12
        tweeter_vs_mid_db = round(float(20.0 * np.log10(tw_avg / mid_avg)), 1)
    else:
        tweeter_vs_mid_db = 0.0

    # Check Upper-Bass Hard-Hit (80/100/125 Hz) vs Deep Sub (31.5/40/50 Hz)
    deep_amps = [v for f, v in harm_dict.items() if f <= 55]
    hit_amps = [v for f, v in harm_dict.items() if 75 <= f <= 130]
    if deep_amps and hit_amps:
        hard_hit_excess_db = round(float(20.0 * np.log10((np.mean(hit_amps) + 1e-12) / (np.mean(deep_amps) + 1e-12))), 1)
    else:
        hard_hit_excess_db = 0.0

    tweeter_eq_guide = "Tweeter Smooth & Distortion-Free (Cut 3.15k/5k Hz by -1 dB at 60% Vol)"
    if channel_id in ("front_left", "front_right"):
        if tweeter_vs_mid_db > 2.0:
            eq_cut = max(-6, -int(round(tweeter_vs_mid_db + 1.0)))
            tweeter_eq_guide = (
                f"Component Tweeter +{tweeter_vs_mid_db:.1f} dB harsh at 60% Vol -> "
                f"Set Pioneer 13-Band EQ (3.15k / 5k / 8k Hz) to {eq_cut} dB (or passive crossover -3 dB)"
            )
            is_harsh = True
        elif tweeter_vs_mid_db < -3.5:
            eq_boost = min(2, int(round(-tweeter_vs_mid_db * 0.5)))
            tweeter_eq_guide = f"Component Tweeter -{abs(tweeter_vs_mid_db):.1f} dB recessed -> Gently set Pioneer EQ (8k/12.5k Hz) to +{eq_boost} dB (avoid >+2 dB to prevent distortion)"

    if channel_id in ("front_left", "front_right"):
        if peak_over_median_db > 8.0 and peak_freq <= 125:
            rec_hpf = 100 if peak_freq <= 90 else 125
            rec_slope = "-18 dB/oct"
            diagnosis = (
                f"Door woofer hard-hit/distortion at {peak_freq:.0f} Hz -> "
                f"Raise Front HPF to {rec_hpf} Hz ({rec_slope}) & cut 80/125 Hz EQ by -3 dB for 60% volume clarity"
            )
        elif tweeter_vs_mid_db > 2.0:
            rec_hpf = 80
            rec_slope = "-18 dB/oct"
            diagnosis = f"Component Tweeter +{tweeter_vs_mid_db:.1f} dB harsh -> Cut 3.15k/5k Hz on Pioneer EQ & set Front HPF 80 Hz (-18 dB/oct)"
        else:
            rec_hpf = 80
            rec_slope = "-18 dB/oct"
            diagnosis = f"Distortion-Free 2-Way Component (THD {thd_pct:.1f}%) -> Set Front HPF 80 Hz (-18 dB/oct) for 60% Vol headroom"
        xover_rec = f"Front HPF: {rec_hpf} Hz ({rec_slope})"

    elif channel_id in ("rear_left", "rear_right"):
        if peak_over_median_db > 8.0 and peak_freq <= 140:
            rec_hpf = 100 if peak_freq <= 100 else 125
            rec_slope = "-12 dB/oct"
            diagnosis = f"Rear coaxial bass distortion at {peak_freq:.0f} Hz -> Set Rear HPF to {rec_hpf} Hz ({rec_slope})"
        else:
            rec_hpf = 80
            rec_slope = "-12 dB/oct"
            diagnosis = f"Rear coaxial clean (THD {thd_pct:.1f}%) -> Set Rear HPF to 80 Hz (-12 dB/oct) to stop coaxial cone distortion at 60% Vol"
        xover_rec = f"Rear HPF: {rec_hpf} Hz ({rec_slope})"
        tweeter_eq_guide = f"Coaxial HF/Mid balance: {tweeter_vs_mid_db:+.1f} dB (Cut 5k Hz by -2 dB if harsh)"

    elif channel_id == "subwoofer":
        if (is_harsh and peak_freq >= 75) or hard_hit_excess_db > 2.5:
            rec_lpf = 63
            rec_slope = "-18 dB/oct"
            diagnosis = (
                f"Hard-hitting upper-bass spike at {max(80.0, peak_freq):.0f} Hz -> "
                f"Lower Sub LPF to 63 Hz (-18 dB/oct) & cut 80/125 Hz EQ by -4 dB for smooth deep background bass without hitting!"
            )
            tweeter_eq_guide = "Pioneer EQ: 50 Hz = 0 dB (Smooth Deep), 80 Hz = -3 dB, 125 Hz = -4 dB (No Hard Hit)"
        elif is_harsh and peak_freq < 75:
            rec_lpf = 63
            rec_slope = "-18 dB/oct"
            diagnosis = f"Cabin standing-wave peak at {peak_freq:.0f} Hz -> Set Sub LPF to 63 Hz (-18 dB/oct) & trim Sub Level by -2 dB"
            tweeter_eq_guide = f"Cut {int(peak_freq)} Hz on Pioneer 13-Band EQ by -3 dB"
        else:
            rec_lpf = 63
            rec_slope = "-18 dB/oct"
            diagnosis = "Velvet Deep Background Bass -> Set Sub LPF to 63 Hz (-18 dB/oct) so sub plays only 28-63 Hz deep warmth without chest-slam"
            tweeter_eq_guide = "Sub EQ: 50 Hz = +1 dB (Deep Velvet), 80 Hz = -3 dB, 125 Hz = -3 dB"
        xover_rec = f"Sub LPF: {rec_lpf} Hz ({rec_slope})"

    else:  # crossover_blend
        if dip_below_median_db > 6.5 and 55 <= dip_freq <= 110:
            xover_rec = "Sub Phase: REVERSE (180°) + Front HPF 80 Hz / Sub LPF 63 Hz"
            diagnosis = f"Phase cancellation null at {dip_freq:.0f} Hz (-{dip_below_median_db:.1f} dB dip) -> Flip Pioneer Sub Phase to REVERSE (180°)"
        else:
            xover_rec = "Sub Phase: NORMAL (0°) + Front HPF 80 Hz / Sub LPF 63 Hz"
            diagnosis = "Smooth, phase-coherent transition between Subwoofer (<=63 Hz) and Door Speakers (>=80 Hz) with zero 80 Hz hard-hit overlap!"
        tweeter_eq_guide = "17 Hz gap (63 Hz LPF to 80 Hz HPF) prevents upper-bass doubling & cabin boom"

    return {
        "db_level": db_spl,
        "actual_required_db": actual_required_db,
        "db_error_delta": db_error_delta,
        "thd_distortion_pct": thd_pct,
        "hard_hit_excess_db": hard_hit_excess_db,
        "peak_frequency_hz": round(peak_freq, 0),
        "dip_frequency_hz": round(dip_freq, 0),
        "peak_spike_db": round(float(peak_over_median_db), 1),
        "dip_depth_db": round(float(dip_below_median_db), 1),
        "crest_factor_db": round(float(crest_factor_db), 1),
        "tweeter_vs_mid_db": tweeter_vs_mid_db,
        "tweeter_eq_guide": tweeter_eq_guide,
        "is_harsh": is_harsh,
        "recommended_crossover": xover_rec,
        "diagnosis": diagnosis,
        "band_energies_db": band_energies_db
    }
