import numpy as np
from scipy import signal
import io
import wave

from app.audio_dsp import apply_c_weighting, _bell_note, _sustained_chord

# Official Pioneer DMH-Z5290BT Standard-Mode Crossover Cutoff Steps (Hz)
PIONEER_XOVER_STEPS = [50, 63, 80, 100, 125, 160, 200]

# Official Pioneer DMH-Z5290BT 13-Band Graphic EQ Centers (Hz)
PIONEER_13BAND_EQ = [50, 80, 125, 200, 315, 500, 800, 1250, 2000, 3150, 5000, 8000, 12500]

# Pioneer DMH-Z5290BT Standard Mode:
# - Front Left / Right: 2-Way Component (Door Mid-Woofer + Tweeter on passive crossover, no separate tweeter channel)
# - Rear Left / Right: Coaxial Speakers
# - Subwoofer: L/R Sub Output
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
        # Spans Door Mid-Woofer (63-1250 Hz) + Component Tweeter (3150-8000 Hz) matching Pioneer 13-band EQ
        "harmonics": [63.0, 80.0, 125.0, 250.0, 500.0, 1250.0, 3150.0, 5000.0, 8000.0],
        "desc": "100% Left-panned chime. Tests FL Door Mid-Woofer + FL Tweeter together, sets FL dB, Front HPF & Tweeter EQ."
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
        "desc": "100% Right-panned chime. Balances FR Component (Mid + Tweeter) to match FL at driver's headrest."
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
        "desc": "100% Left-panned chime. Set Pioneer Fader to Rear during RL/RR test to isolate coaxials, then return Fader to 0."
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
        "desc": "100% Right-panned chime. Balances RR Coaxial to RL (-2 dB rear fill) and sets Rear HPF cutoff."
    },
    {
        "id": "subwoofer",
        "name": "5. Subwoofer (Sub L/R Pre-Out)",
        "speaker_type": "Subwoofer (Mono / Bridged Amp)",
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
        "name": "6. Crossover & Phase Blend (Components + Coaxials + Sub)",
        "speaker_type": "Full System 4.1 Transition",
        "pioneer_menu": "Audio -> Crossover -> Sub Phase (Normal/Reverse)",
        "xover_menu": "HPF / LPF Transition (50 - 125 Hz)",
        "pan": "stereo",
        "min_db": -24,
        "max_db": 10,
        "target_offset_db": 1.5,
        "freq_low": 40,
        "freq_high": 160,
        "harmonics": [40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0],
        "desc": "Plays 40-200 Hz transition notes on Sub + Door speakers together (Fader = 0) to verify Sub Phase (Normal vs Reverse)."
    }
]


def generate_car_test_chime(channel_id: str, duration_sec: float = 3.2, sample_rate: int = 44100) -> bytes:
    """
    Generates a pleasant 'Ding-Dong + Harmonic Chord' WAV tailored to Pioneer DMH-Z5290BT Standard Mode:
    - Front Left / Right: Plays Tweeter + Upper-Mid 'Ding' (500-8000 Hz), then Door Mid-Woofer 'Dong' (63-500 Hz),
      then full 9-harmonic chord spanning both Mid-Woofer and Component Tweeter.
    - Rear Left / Right: Plays Coaxial 'Ding-Dong + Chord' (80-5000 Hz) panned Left or Right.
    - Subwoofer / Crossover Blend: Plays deep bass 'Boom-Dum + Bass Chord' on both L+R channels.
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
        # "Ding" tests the Tweeter & Upper-Mid (1.25k - 8k Hz); "Dong" tests the Door Mid-Woofer (63 - 500 Hz)
        ding_freqs = harms[4:]
        dong_freqs = harms[:5]
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
    Analyzes recorded phone mic audio for Pioneer DMH-Z5290BT in Standard Mode:
    - Front 2-Way Component: Evaluates overall SPL dB, Door Mid-Woofer HPF cutoff (63/80/100/125 Hz),
      AND Tweeter-to-Mid-Woofer balance (3.15k/5k/8k Hz vs 250-1.25k Hz) since Tweeter has no separate
      channel level in Standard Mode (tuned via Pioneer 13-Band EQ or passive crossover switch).
    - Rear Coaxial: Evaluates Rear SPL dB, Rear HPF cutoff (80/100/125 Hz), and coaxial high-frequency smoothness.
    - Subwoofer & Crossover Blend: Evaluates Sub SPL dB, Sub LPF cutoff, 50/63/80 Hz cabin boom, and Sub Phase.
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
    db_spl = 20.0 * np.log10(rms_c) + 94.0

    # FFT analysis across the sustained harmonic chord (last 38% of trimmed recording where all 9 harmonics have equal energy)
    chord_slice = band_audio[int(len(band_audio) * 0.62):]
    n_fft = min(len(chord_slice), 8192)
    band_energies_db = {}
    harm_dict = {}

    if n_fft >= 512:
        win = np.hanning(n_fft)
        spec = np.abs(np.fft.rfft(chord_slice[-n_fft:] * win))
        freqs = np.fft.rfftfreq(n_fft, d=1.0 / sample_rate)

        harm_amps = []
        for hf in harmonics:
            mask = (freqs >= hf * 0.90) & (freqs <= hf * 1.10)
            if np.any(mask):
                amp = float(np.sqrt(np.sum(spec[mask] ** 2))) + 1e-12
                harm_amps.append((hf, amp))
                harm_dict[hf] = amp
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

    # Calculate Component Tweeter (3.15k-8k Hz) vs Mid-Woofer (250-1.25k Hz) balance
    mid_amps = [v for f, v in harm_dict.items() if 200 <= f <= 1500]
    tw_amps = [v for f, v in harm_dict.items() if f >= 3000]
    if mid_amps and tw_amps:
        mid_avg = float(np.mean(mid_amps)) + 1e-12
        tw_avg = float(np.mean(tw_amps)) + 1e-12
        tweeter_vs_mid_db = round(float(20.0 * np.log10(tw_avg / mid_avg)), 1)
    else:
        tweeter_vs_mid_db = 0.0

    tweeter_eq_guide = "Tweeter Balanced (0 dB on 3.15k/5k/8k EQ)"
    if channel_id in ("front_left", "front_right"):
        if tweeter_vs_mid_db > 2.5:
            eq_cut = max(-6, -int(round(tweeter_vs_mid_db)))
            tweeter_eq_guide = f"Component Tweeter +{tweeter_vs_mid_db:.1f} dB hot -> Set Pioneer 13-Band EQ (3.15k / 5k / 8k Hz) to {eq_cut} dB (or passive box -3 dB)"
            is_harsh = True
        elif tweeter_vs_mid_db < -3.5:
            eq_boost = min(4, int(round(-tweeter_vs_mid_db * 0.7)))
            tweeter_eq_guide = f"Component Tweeter -{abs(tweeter_vs_mid_db):.1f} dB recessed -> Boost Pioneer EQ (5k / 8k Hz) by +{eq_boost} dB"

    # Recommend optimal Pioneer DMH-Z5290BT Crossover (HPF / LPF) & Diagnosis
    if channel_id in ("front_left", "front_right"):
        if peak_over_median_db > 8.5 and peak_freq <= 125:
            rec_hpf = 100 if peak_freq <= 90 else 125
            rec_slope = "-18 dB/oct"
            diagnosis = f"Door woofer resonance at {peak_freq:.0f} Hz -> Raise Front HPF to {rec_hpf} Hz ({rec_slope})"
        elif tweeter_vs_mid_db > 2.5:
            rec_hpf = 80
            rec_slope = "-12 dB/oct"
            diagnosis = f"Component Tweeter is +{tweeter_vs_mid_db:.1f} dB hotter than Mid-Woofer -> Cut 3.15k/5k/8k Hz on Pioneer EQ"
        else:
            rec_hpf = 80
            rec_slope = "-12 dB/oct"
            diagnosis = f"2-Way Component balanced (Tweeter/Mid delta {tweeter_vs_mid_db:+.1f} dB) -> Front HPF 80 Hz (-12 dB/oct)"
        xover_rec = f"Front HPF: {rec_hpf} Hz ({rec_slope})"

    elif channel_id in ("rear_left", "rear_right"):
        if peak_over_median_db > 8.5 and peak_freq <= 140:
            rec_hpf = 100 if peak_freq <= 100 else 125
            rec_slope = "-12 dB/oct"
            diagnosis = f"Rear coaxial bass boom at {peak_freq:.0f} Hz -> Set Rear HPF to {rec_hpf} Hz ({rec_slope})"
        else:
            rec_hpf = 80
            rec_slope = "-12 dB/oct"
            diagnosis = "Rear coaxial clean -> Set Rear HPF to 80 Hz (-12 dB/oct) so coaxial tweeter stays clear"
        xover_rec = f"Rear HPF: {rec_hpf} Hz ({rec_slope})"
        tweeter_eq_guide = f"Coaxial HF/Mid balance: {tweeter_vs_mid_db:+.1f} dB"

    elif channel_id == "subwoofer":
        if is_harsh and peak_freq >= 80:
            rec_lpf = 63
            rec_slope = "-24 dB/oct"
            diagnosis = f"Upper-bass boom at {peak_freq:.0f} Hz -> Lower Sub LPF to 63 Hz (-24 dB/oct)"
            tweeter_eq_guide = f"Cut {int(peak_freq)} Hz on Pioneer 13-Band EQ by -3 dB"
        elif is_harsh and peak_freq < 80:
            rec_lpf = 80
            rec_slope = "-24 dB/oct"
            diagnosis = f"Cabin standing-wave boom at {peak_freq:.0f} Hz -> Cut {peak_freq:.0f} Hz on Pioneer 13-Band EQ by -3 dB"
            tweeter_eq_guide = f"Cut {int(peak_freq)} Hz on Pioneer 13-Band EQ by -3 dB"
        else:
            rec_lpf = 80
            rec_slope = "-18 dB/oct"
            diagnosis = "Clean deep bass -> Set Sub LPF to 80 Hz (-18 dB/oct)"
            tweeter_eq_guide = "Sub EQ flat (0 dB at 50 / 80 Hz)"
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
            diagnosis = "Smooth 50-125 Hz transition between Subwoofer, Front Components & Rear Coaxials!"
        tweeter_eq_guide = "Phase & Crossover transition verified"

    return {
        "db_level": round(float(db_spl), 1),
        "peak_frequency_hz": round(peak_freq, 0),
        "dip_frequency_hz": round(dip_freq, 0),
        "peak_spike_db": round(float(peak_over_median_db), 1),
        "dip_depth_db": round(float(dip_below_median_db), 1),
        "tweeter_vs_mid_db": tweeter_vs_mid_db,
        "tweeter_eq_guide": tweeter_eq_guide,
        "is_harsh": is_harsh,
        "xover_recommendation": xover_rec,
        "diagnosis": diagnosis,
        "band_energies_db": band_energies_db
    }
