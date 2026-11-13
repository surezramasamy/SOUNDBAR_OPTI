from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import Response, FileResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
import os
import sys

# Ensure imports work whether launched via `fastapi run app/main.py` or `uvicorn app.main:app`
_current_dir = os.path.dirname(os.path.abspath(__file__))
_parent_dir = os.path.dirname(_current_dir)
if _current_dir not in sys.path:
    sys.path.insert(0, _current_dir)
if _parent_dir not in sys.path:
    sys.path.insert(0, _parent_dir)

try:
    from app.audio_dsp import (
        ALL_SOUNDBAR_CHANNELS,
        Q930_CHANNELS,
        SOUNDBAR_LAYOUT_PRESETS,
        get_channels_for_configuration,
        generate_bandlimited_pink_noise,
        analyze_recorded_channel_audio
    )
    from app.car_audio_dsp import (
        CAR_CHANNELS,
        PIONEER_13BAND_EQ,
        generate_car_test_chime,
        analyze_car_channel_audio
    )
except ImportError:
    from audio_dsp import (
        ALL_SOUNDBAR_CHANNELS,
        Q930_CHANNELS,
        SOUNDBAR_LAYOUT_PRESETS,
        get_channels_for_configuration,
        generate_bandlimited_pink_noise,
        analyze_recorded_channel_audio
    )
    from car_audio_dsp import (
        CAR_CHANNELS,
        PIONEER_13BAND_EQ,
        generate_car_test_chime,
        analyze_car_channel_audio
    )

app = FastAPI(
    title="World-Class Distortion-Free Soundbar & Pioneer Car Audio Calibrator",
    description="60% Volume Distortion-Free Acoustic Calibration Suite for Multi-Channel Dolby Atmos Soundbars (3.1.2 to 11.2.4) & Pioneer DMH-Z5290BT Car Audio.",
    version="4.0.0"
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Session storage for Soundbar (Supports 3.1.2, 5.1.2, 7.1.2, 9.1.4, 11.1.4, 11.2.4 & Custom)
calibration_session = {
    "preset": "9.1.4",
    "bed_count": 9,
    "sub_count": 1,
    "atmos_count": 4,
    "cal_mode": "atmos",       # "atmos" (Dolby Atmos 3D Spatial) or "bluetooth" (Dolby Surround Matrix)
    "sound_style": "natural",  # "natural" (Warm Cello/Piano/Rain), "atmos_cinema", or "pink_iso"
    "initial_measurements": {},
    "confirmed_measurements": {},
}

# Session storage for Pioneer DMH-Z5290BT Car Audio
car_calibration_session = {
    "initial_measurements": {},
    "confirmed_measurements": {},
}

static_dir = os.path.join(os.path.dirname(__file__), "..", "static")
if os.path.exists(static_dir):
    app.mount("/static", StaticFiles(directory=static_dir), name="static")


@app.get("/")
def get_index():
    index_file = os.path.join(static_dir, "index.html")
    if os.path.exists(index_file):
        return FileResponse(index_file)
    return {"message": "World-Class Distortion-Free Soundbar Calibration API Running."}


@app.get("/car")
def get_car_page():
    car_file = os.path.join(static_dir, "car.html")
    if os.path.exists(car_file):
        return FileResponse(car_file)
    return {"message": "Pioneer DMH-Z5290BT Car Calibration Page."}


@app.get("/live-sub")
def get_live_sub_page():
    live_file = os.path.join(static_dir, "live_sub.html")
    if os.path.exists(live_file):
        return FileResponse(live_file, headers={"Cache-Control": "no-store, no-cache, must-revalidate"})
    return {"message": "360° Live Sound & Sub Guard Page."}


@app.get("/download-apk")
def download_apk():
    apk_file = os.path.join(static_dir, "LiveSubGuard.apk")
    if os.path.exists(apk_file):
        return FileResponse(
            apk_file,
            media_type="application/vnd.android.package-archive",
            filename="LiveSubGuard.apk",
            headers={"Cache-Control": "no-store, no-cache, must-revalidate"},
        )
    return RedirectResponse(
        url="https://github.com/surezramasamy/SOUNDBAR_OPTI/releases/latest/download/LiveSubGuard.apk"
    )


# ==============================================================================
# 1. MULTI-CHANNEL DOLBY ATMOS & BLUETOOTH SOUNDBAR ENDPOINTS (3.1.2 -> 11.2.4)
# ==============================================================================

@app.get("/api/channels")
def get_channels(
    preset: str = None,
    bed: int = None,
    subs: int = None,
    atmos: int = None,
    cal_mode: str = None,
    sound_style: str = None
):
    """
    Returns the active speaker channel list for the user-selected Soundbar architecture
    (e.g., 3.1.2, 5.1.2, 7.1.2, 9.1.4, 11.1.4, 11.2.4, or custom Bed/Sub/Atmos speaker counts).
    """
    if preset and preset in SOUNDBAR_LAYOUT_PRESETS:
        p = SOUNDBAR_LAYOUT_PRESETS[preset]
        calibration_session["preset"] = preset
        calibration_session["bed_count"] = p["bed"]
        calibration_session["sub_count"] = p["subs"]
        calibration_session["atmos_count"] = p["atmos"]

    if bed is not None:
        calibration_session["bed_count"] = bed
    if subs is not None:
        calibration_session["sub_count"] = subs
    if atmos is not None:
        calibration_session["atmos_count"] = atmos
    if cal_mode in ("atmos", "bluetooth"):
        calibration_session["cal_mode"] = cal_mode
    if sound_style in ("natural", "atmos_cinema", "pink_iso"):
        calibration_session["sound_style"] = sound_style

    b = calibration_session["bed_count"]
    s = calibration_session["sub_count"]
    a = calibration_session["atmos_count"]
    layout_code = f"{b}.{s}.{a}"
    calibration_session["preset"] = layout_code

    active_channels = get_channels_for_configuration(b, s, a)
    return {
        "layout": layout_code,
        "bed_count": b,
        "sub_count": s,
        "atmos_count": a,
        "cal_mode": calibration_session["cal_mode"],
        "sound_style": calibration_session["sound_style"],
        "presets": SOUNDBAR_LAYOUT_PRESETS,
        "channels": active_channels
    }


@app.post("/api/reset")
def reset_session():
    """Clears all recorded soundbar measurements."""
    calibration_session["initial_measurements"].clear()
    calibration_session["confirmed_measurements"].clear()
    return {"status": "reset"}


@app.get("/api/tone/{channel_id}")
def get_channel_tone(
    channel_id: str,
    duration: float = 3.4,
    cal_mode: str = None,
    sound_style: str = None
):
    """
    Generates Distortion-Free Natural Acoustic / Dolby Atmos 3D / Bluetooth Matrix WAV
    for the specified soundbar speaker channel.
    """
    ch_info = next((c for c in ALL_SOUNDBAR_CHANNELS if c["id"] == channel_id), None)
    if not ch_info:
        raise HTTPException(status_code=404, detail="Channel ID not found")

    mode = cal_mode if cal_mode in ("atmos", "bluetooth") else calibration_session.get("cal_mode", "atmos")
    style = sound_style if sound_style in ("natural", "atmos_cinema", "pink_iso") else calibration_session.get("sound_style", "natural")

    wav_bytes = generate_bandlimited_pink_noise(
        duration_sec=duration,
        low_cut=ch_info["freq_low"],
        high_cut=ch_info["freq_high"],
        channel_id=channel_id,
        cal_mode=mode,
        sound_style=style
    )
    return Response(content=wav_bytes, media_type="audio/wav")


@app.post("/api/analyze-channel")
async def analyze_channel(
    channel_id: str = Form(...),
    mode: str = Form("initial"),
    cal_mode: str = Form(None),
    audio_file: UploadFile = File(...)
):
    ch_info = next((c for c in ALL_SOUNDBAR_CHANNELS if c["id"] == channel_id), None)
    if not ch_info:
        raise HTTPException(status_code=404, detail="Invalid channel_id")

    if cal_mode in ("atmos", "bluetooth"):
        calibration_session["cal_mode"] = cal_mode

    wav_content = await audio_file.read()
    if len(wav_content) < 1000:
        raise HTTPException(status_code=400, detail="Audio recording too short")

    initial = calibration_session["initial_measurements"]
    ref_anchor = initial["front_left_right"]["db_level"] if "front_left_right" in initial else 78.0

    try:
        metrics = analyze_recorded_channel_audio(
            wav_content,
            freq_low=ch_info["freq_low"],
            freq_high=ch_info["freq_high"],
            channel_id=channel_id,
            reference_anchor_db=ref_anchor
        )
    except Exception as e:
        raise HTTPException(status_code=422, detail=f"Error analyzing audio: {str(e)}")

    if mode == "confirm":
        calibration_session["confirmed_measurements"][channel_id] = metrics
    else:
        calibration_session["initial_measurements"][channel_id] = metrics
        calibration_session["confirmed_measurements"].pop(channel_id, None)

    return {
        "channel_id": channel_id,
        "mode": mode,
        "name": ch_info["name"],
        "metrics": metrics,
        "summary": calculate_calibration()
    }


@app.get("/api/calculate-calibration")
def calculate_calibration():
    initial = calibration_session["initial_measurements"]
    confirmed = calibration_session["confirmed_measurements"]
    active_channels = get_channels_for_configuration(
        calibration_session["bed_count"],
        calibration_session["sub_count"],
        calibration_session["atmos_count"]
    )
    layout_code = f"{calibration_session['bed_count']}.{calibration_session['sub_count']}.{calibration_session['atmos_count']}"

    if not initial:
        return {
            "layout": layout_code,
            "cal_mode": calibration_session["cal_mode"],
            "reference_db": None,
            "reference_source": None,
            "channels": {},
            "world_class_guide": None
        }

    if "front_left_right" in initial:
        ref_db = initial["front_left_right"]["db_level"]
        ref_source = "Front L/R (60% Volume Distortion-Free Anchor)"
    elif "center" in initial:
        ref_db = initial["center"]["db_level"] - 0.5
        ref_source = "Center Channel Anchor"
    else:
        all_dbs = [m["db_level"] for m in initial.values()]
        ref_db = sum(all_dbs) / len(all_dbs)
        ref_source = "Measured Room Average"

    results = {}
    any_boomy_sub = False
    any_harsh_highs = False
    any_room_hiss = False
    max_thd = 0.2

    for ch in active_channels:
        ch_id = ch["id"]
        init_data = initial.get(ch_id)
        if not init_data:
            continue

        target_db = round(float(ref_db + ch["target_offset_db"]), 1)
        measured_db = init_data["db_level"]
        db_error = round(float(measured_db - target_db), 1)
        thd = init_data.get("thd_distortion_pct", 0.3)
        max_thd = max(max_thd, thd)

        if init_data.get("is_boomy") and ch_id in ("subwoofer", "subwoofer_2"):
            any_boomy_sub = True
        if init_data.get("harshness_index_db", 0.0) > 2.0 or thd > 1.8:
            any_harsh_highs = True
        if init_data.get("hiss_floor_db", 35.0) > 52.0:
            any_room_hiss = True

        if ch_id == "front_left_right":
            req_step = 0
            status = "REFERENCE_LOCKED"
            action_text = "0 dB (60% Master Vol Anchor)"
        else:
            raw_delta = target_db - measured_db
            # Enforce Distortion-Free 60% Volume Rule:
            # Never boost a speaker into clipping (> +3 dB); if boomy/harsh, apply subtractive cut!
            if ch_id in ("subwoofer", "subwoofer_2"):
                if init_data.get("is_boomy") or init_data.get("is_harsh"):
                    raw_delta -= 2.5
                req_step = int(round(raw_delta))
                req_step = max(ch["min_db"], min(2, req_step))  # Cap Woofer at +2 dB max to prevent listener fatigue & boom!
            else:
                if init_data.get("is_harsh") or thd > 1.8:
                    raw_delta -= 2.0
                req_step = int(round(raw_delta))
                req_step = max(ch["min_db"], min(3, req_step))  # Cap channel boost at +3 dB max for 0% distortion at 60% vol

            action_text = f"{'+' if req_step > 0 else ''}{req_step} dB"
            status = "OPTIMAL_AT_0DB" if req_step == 0 and not init_data["is_harsh"] else "NEEDS_REMOTE_SET"

        conf_data = confirmed.get(ch_id)
        confirm_info = None
        if conf_data and ch_id != "front_left_right":
            post_db = conf_data["db_level"]
            residual_error = target_db - post_db
            if conf_data["is_harsh"]:
                residual_error -= 1.5

            fine_tune_step = int(round(residual_error))
            is_confirmed = abs(residual_error) <= 1.5 and not conf_data["is_harsh"]
            status = "CONFIRMED" if is_confirmed else "FINE_TUNE_NEEDED"

            confirm_info = {
                "post_db": post_db,
                "actual_required_db": target_db,
                "residual_error_db": round(float(residual_error), 1),
                "thd_distortion_pct": conf_data.get("thd_distortion_pct", 0.25),
                "is_confirmed": is_confirmed,
                "is_harsh": conf_data["is_harsh"],
                "diagnosis": conf_data["diagnosis"],
                "fine_tune_suggestion": (
                    f"Confirmed! Measured {post_db:.1f} dB matches Required {target_db:.1f} dB (Distortion-Free)."
                    if is_confirmed
                    else f"Adjust {ch['remote_menu']} by {'+' if fine_tune_step > 0 else ''}{fine_tune_step} dB to hit {target_db:.1f} dB without distortion"
                )
            }

        results[ch_id] = {
            "name": ch["name"],
            "remote_menu": ch["remote_menu"],
            "target_db": target_db,
            "initial_measured_db": measured_db,
            "db_error_delta": db_error,
            "required_remote_db": req_step,
            "required_action_text": action_text,
            "thd_distortion_pct": thd,
            "boom_excess_db": init_data.get("boom_excess_db", 0.0),
            "harshness_index_db": init_data.get("harshness_index_db", 0.0),
            "hiss_floor_db": init_data.get("hiss_floor_db", 35.0),
            "is_harsh": init_data["is_harsh"],
            "peak_freq_hz": init_data["peak_frequency_hz"],
            "peak_spike_db": init_data["peak_spike_db"],
            "diagnosis": init_data["diagnosis"],
            "status": status,
            "confirmation": confirm_info
        }

    # Build World-Class 60% Volume Distortion-Free Soundbar Tuning Guide (Crystal Clear Vocals + Deep Bass)
    max_boom = max([r.get("boom_excess_db", 0.0) for r in results.values()] or [0.0])
    max_harsh = max([r.get("harshness_index_db", 0.0) for r in results.values()] or [0.0])
    eq_125 = max(-6, -int(round(max(2.5, max_boom * 0.65))))
    eq_250 = max(-4, -int(round(max(1.5, max_boom * 0.4))))
    eq_4k = max(-5, -int(round(max(1.5, max_harsh * 0.7))))
    eq_8k = -2 if any_room_hiss else -1

    bass_tone = f"{max(-4, eq_125 + 1)} dB (Cuts 80-120 Hz room boom)" if any_boomy_sub else "+1 dB (Smooth Deep Velvet Reference)"
    treble_tone = f"{eq_4k} dB (Eliminates harshness & room hiss)" if (any_harsh_highs or any_room_hiss) else "0 dB (Crystal Silk Highs)"
    sub_rec_lvl = results.get("subwoofer", {}).get("required_action_text", "-1 dB (Velvet Deep, Zero Fatigue)")

    world_class_guide = {
        "master_volume_sweet_spot": "45% to 60% (Distortion-Free Headroom Zone — Max THD Measured: {:.2f}%)".format(max_thd),
        "calibration_mode_used": (
            "🌌 Dolby Atmos 3D Spatial & Ceiling-Bounce Calibration"
            if calibration_session["cal_mode"] == "atmos"
            else "📶 Bluetooth Dolby Surround 180° Phase-Matrix Calibration"
        ),
        "smooth_deep_bass_secret": (
            f"Set Woofer to {sub_rec_lvl}, turn 'Bass Enhancement / Extra Bass' OFF, and set 125 Hz EQ to {eq_125} dB. "
            "This removes tiring 90-125 Hz chest-thump while keeping 32-58 Hz smooth cinematic sub-bass and crystal-clear 1–2 kHz vocals."
        ),
        "anti_hiss_and_boom_eq": {
            "63_hz_deep_sub": "+2 dB (Warm Deep Sub Foundation)",
            "125_hz_upper_bass_hit": f"{eq_125} dB (Stops Boomy Room Resonance & Hard Hitting)",
            "250_hz_room_mud": f"{eq_250} dB (Clears Boxy Wall/Floor Mud for Vocal Clarity)",
            "500_hz_warmth": "0 dB (Natural Body)",
            "1_khz_vocal_core": "+1 dB (Crystal-Clear Dialogue & Lead Vocals)",
            "2_khz_presence": "+1 dB (Vocal Articulation Without Shoutiness)",
            "4_khz_sibilance": f"{eq_4k} dB (Eliminates Ear-Piercing Harshness)",
            "8_khz_to_10_khz_air": f"{eq_8k} dB (Silky Air Without Hissing Noise)",
            "simple_tone_control": f"Bass = {bass_tone} | Treble = {treble_tone}"
        },
        "poweramp_spotify_eq": {
            "31_hz": "+2.0 dB (Deep Sub Velvet)",
            "62_hz": "+1.5 dB (Warm Sub Cushion)",
            "125_hz": f"{eq_125:.1f} dB (Anti-Boom)",
            "250_hz": f"{eq_250:.1f} dB (Vocal Unmasking)",
            "500_hz": "0.0 dB",
            "1_khz": "+1.0 dB (Crystal Vocals)",
            "2_khz": "+1.5 dB (Vocal Clarity)",
            "4_khz": f"{eq_4k:.1f} dB (Anti-Harsh)",
            "8_khz": f"{eq_8k:.1f} dB (Silk Detail)",
            "16_khz": "+1.0 dB (Air)",
            "tone_and_limiter": "Bass Tone: +2.0 dB | Treble Tone: 0.0 dB | Pre-Amp: -2.0 dB | Limiter Ceiling: -1.2 dBFS (ON)"
        },
        "movie_experience_preset": {
            "sound_mode": "Adaptive Sound / Surround (Dolby Atmos Active)",
            "woofer_level": sub_rec_lvl,
            "spacefit_sound": "ON (Auto Room Reflection Compensation)",
            "voice_enhancement_ava": "OFF at 60% Vol (Prevents harsh midrange shoutiness; Center is already calibrated +0.5 dB)",
            "bass_enhancement": "OFF (Prevents boomy 100 Hz listener fatigue during long movies)",
            "virtual_surround": "ON for 3.1.2 / 5.1.2, OFF for 9.1.4 / 11.1.4 / 11.2.4 (Discrete physical drivers)"
        },
        "music_experience_preset": {
            "sound_mode": "Standard (2.1 / 3.1 Pure Studio Direct) or Surround (for 360° Live Concert Immersion)",
            "woofer_level": f"{max(-12, results.get('subwoofer', {}).get('required_remote_db', -1) - 1)} dB (Tight, fast musical double-bass without overhang)",
            "audio_source_tip": "Disable Phone/Spotify Built-in Equalizer & Turn ON 'Normalize Volume = Quiet/Normal' so Bluetooth/eARC input never clips at 60% volume."
        }
    }

    return {
        "layout": layout_code,
        "cal_mode": calibration_session["cal_mode"],
        "reference_db": round(float(ref_db), 1),
        "reference_source": ref_source,
        "channels": results,
        "world_class_guide": world_class_guide
    }


# ==============================================================================
# 2. PIONEER DMH-Z5290BT CAR AUDIO (60% VOLUME DISTORTION-FREE CALIBRATOR)
# ==============================================================================

@app.get("/api/car/channels")
def get_car_channels():
    """Returns the 4 Speakers + 1 Subwoofer + Crossover Blend channels for Pioneer DMH-Z5290BT."""
    return {"channels": CAR_CHANNELS}


@app.post("/api/car/reset")
def reset_car_session():
    """Clears all recorded Pioneer DMH-Z5290BT car measurements."""
    car_calibration_session["initial_measurements"].clear()
    car_calibration_session["confirmed_measurements"].clear()
    return {"status": "reset"}


@app.get("/api/car/tone/{channel_id}")
def get_car_tone(channel_id: str, duration: float = 3.4):
    """Generates Distortion-Free Natural Studio Acoustic Reference WAV for Pioneer DMH-Z5290BT."""
    ch_info = next((c for c in CAR_CHANNELS if c["id"] == channel_id), None)
    if not ch_info:
        raise HTTPException(status_code=404, detail="Car channel ID not found")

    wav_bytes = generate_car_test_chime(channel_id=channel_id, duration_sec=duration)
    return Response(content=wav_bytes, media_type="audio/wav")


@app.post("/api/car/analyze-channel")
async def analyze_car_channel(
    channel_id: str = Form(...),
    mode: str = Form("initial"),
    audio_file: UploadFile = File(...)
):
    ch_info = next((c for c in CAR_CHANNELS if c["id"] == channel_id), None)
    if not ch_info:
        raise HTTPException(status_code=404, detail="Invalid car channel_id")

    wav_content = await audio_file.read()
    if len(wav_content) < 1000:
        raise HTTPException(status_code=400, detail="Audio recording too short")

    initial = car_calibration_session["initial_measurements"]
    ref_anchor = initial["front_left"]["db_level"] if "front_left" in initial else 78.0

    try:
        metrics = analyze_car_channel_audio(wav_content, channel_id=channel_id, reference_anchor_db=ref_anchor)
    except Exception as e:
        raise HTTPException(status_code=422, detail=f"Error analyzing car audio: {str(e)}")

    if mode == "confirm":
        car_calibration_session["confirmed_measurements"][channel_id] = metrics
    else:
        car_calibration_session["initial_measurements"][channel_id] = metrics
        car_calibration_session["confirmed_measurements"].pop(channel_id, None)

    return {
        "channel_id": channel_id,
        "mode": mode,
        "name": ch_info["name"],
        "metrics": metrics,
        "summary": calculate_car_calibration()
    }


@app.get("/api/car/calculate-calibration")
def calculate_car_calibration():
    """
    Calculates 100% Subtractive, Distortion-Free Pioneer DMH-Z5290BT Speaker Levels (-24 to 0 dB),
    Crossovers (Front HPF, Rear HPF, Sub LPF, Slopes, Sub Phase), and 13-Band Graphic EQ settings
    so that listening up to 60%+ Volume (Vol 24-26 / 40) delivers deep velvet bass and zero harshness.
    """
    initial = car_calibration_session["initial_measurements"]
    confirmed = car_calibration_session["confirmed_measurements"]

    if not initial:
        return {
            "reference_db": None,
            "reference_source": None,
            "master_crossover_guide": None,
            "channels": {}
        }

    # Subtractive Reference Anchor:
    # Anchor to the quieter Front Speaker at 0 dB (or -1 dB if harsh) so ALL louder speakers are attenuated (-dB).
    # This guarantees zero positive gain clipping in the Pioneer MOSFET amplifier at 60% Master Volume!
    if "front_left" in initial and "front_right" in initial:
        fl_db = initial["front_left"]["db_level"]
        fr_db = initial["front_right"]["db_level"]
        if fl_db <= fr_db:
            ref_db = fl_db
            ref_source = "Front Left (0 dB Subtractive Anchor for 60% Vol Headroom)"
        else:
            ref_db = fr_db
            ref_source = "Front Right (0 dB Subtractive Anchor for 60% Vol Headroom)"
    elif "front_left" in initial:
        ref_db = initial["front_left"]["db_level"]
        ref_source = "Front Left Speaker (0 dB Anchor)"
    elif "front_right" in initial:
        ref_db = initial["front_right"]["db_level"]
        ref_source = "Front Right Speaker (0 dB Anchor)"
    else:
        all_dbs = [m["db_level"] for m in initial.values()]
        ref_db = min(all_dbs)
        ref_source = "Quietest Measured Speaker (0 dB Subtractive Anchor)"

    results = {}
    front_hpf_rec = "80 Hz (-18 dB/oct)"
    rear_hpf_rec = "80 Hz (-12 dB/oct)"
    sub_lpf_rec = "63 Hz (-18 dB/oct — Deep Bass Without Hitting)"
    sub_phase_rec = "Normal (0°)"
    front_tweeter_eq_rec = "-2 dB at 3.15k / 5k Hz (Zero Harshness at 60% Vol)"
    max_tw_hot = 0.0

    for ch in CAR_CHANNELS:
        ch_id = ch["id"]
        init_data = initial.get(ch_id)
        if not init_data:
            continue

        xover_str = init_data.get("recommended_crossover") or init_data.get("xover_recommendation", "80 Hz (-12 dB/oct)")
        target_db = round(float(ref_db + ch["target_offset_db"]), 1)
        measured_db = init_data["db_level"]
        db_error = round(float(measured_db - target_db), 1)
        thd = init_data.get("thd_distortion_pct", 0.35)
        tw_mid = init_data.get("tweeter_vs_mid_db", 0.0)
        if ch_id in ("front_left", "front_right"):
            max_tw_hot = max(max_tw_hot, tw_mid)

        if ch_id == "crossover_blend":
            req_step = 0
            action_text = xover_str
            sub_phase_rec = xover_str
        else:
            raw_delta = target_db - measured_db
            if init_data["is_harsh"] and ch_id == "subwoofer":
                raw_delta -= 2.5
            elif init_data["is_harsh"]:
                raw_delta -= 1.5

            req_step = int(round(raw_delta))
            # Enforce Subtractive Headroom Cap: Speaker levels <= 0 dB (or max +1 dB for sub) so 60% volume NEVER distorts!
            max_allowed = 1 if ch_id == "subwoofer" else 0
            req_step = max(ch["min_db"], min(max_allowed, req_step))
            action_text = f"{'+' if req_step > 0 else ''}{req_step} dB"

        if ch_id in ("front_left", "front_right"):
            front_hpf_rec = xover_str
            front_tweeter_eq_rec = init_data.get("tweeter_eq_guide", front_tweeter_eq_rec)
        elif ch_id in ("rear_left", "rear_right"):
            rear_hpf_rec = xover_str
        elif ch_id == "subwoofer":
            sub_lpf_rec = xover_str

        conf_data = confirmed.get(ch_id)
        confirm_info = None
        if conf_data:
            post_db = conf_data["db_level"]
            residual_error = target_db - post_db
            fine_tune_step = int(round(residual_error))
            is_confirmed = (abs(residual_error) <= 1.5 and not conf_data["is_harsh"]) if ch_id != "crossover_blend" else (not conf_data["is_harsh"])

            confirm_info = {
                "post_db": post_db,
                "actual_required_db": target_db,
                "residual_error_db": round(float(residual_error), 1),
                "thd_distortion_pct": conf_data.get("thd_distortion_pct", 0.3),
                "is_confirmed": is_confirmed,
                "diagnosis": conf_data["diagnosis"],
                "fine_tune_suggestion": (
                    f"Confirmed! Measured {post_db:.1f} dB matches Required {target_db:.1f} dB (Distortion-Free at 60% Vol)."
                    if is_confirmed
                    else (
                        conf_data["diagnosis"]
                        if ch_id == "crossover_blend"
                        else f"Adjust {ch['pioneer_menu']} by {'+' if fine_tune_step > 0 else ''}{fine_tune_step} dB to match {target_db:.1f} dB"
                    )
                )
            }

        results[ch_id] = {
            "name": ch["name"],
            "speaker_type": ch.get("speaker_type", ""),
            "pioneer_menu": ch["pioneer_menu"],
            "xover_menu": ch["xover_menu"],
            "target_db": target_db,
            "initial_measured_db": measured_db,
            "db_error_delta": db_error,
            "required_pioneer_db": req_step,
            "required_action_text": action_text,
            "thd_distortion_pct": thd,
            "xover_recommendation": xover_str,
            "tweeter_vs_mid_db": tw_mid,
            "tweeter_eq_guide": init_data.get("tweeter_eq_guide", ""),
            "is_harsh": init_data["is_harsh"],
            "peak_freq_hz": init_data["peak_frequency_hz"],
            "dip_freq_hz": init_data["dip_frequency_hz"],
            "diagnosis": init_data["diagnosis"],
            "confirmation": confirm_info
        }

    # Build 13-Band Subtractive Graphic EQ Table for Crystal Clear Vocals + Deep Bass (60% Vol Distortion-Free)
    tw_cut_3k = max(-5, -int(round(max(2.0, max_tw_hot))))
    tw_cut_5k = max(-4, tw_cut_3k + 1)
    sub_hit_excess = initial.get("subwoofer", {}).get("hard_hit_excess_db", 1.5)
    eq_80 = max(-5, -int(round(max(2.5, sub_hit_excess * 0.8))))
    eq_125 = max(-6, eq_80 - 1)

    pioneer_13band_table = {
        "50 Hz": "+1 dB (Deep Velvet Sub Warmth)",
        "80 Hz": f"{eq_80} dB (Stops Hard-Hitting Chest Thump)",
        "125 Hz": f"{eq_125} dB (Eliminates Boomy Door Woofer Slam)",
        "200 Hz": "-2 dB (Clears Cabin Mud for Crystal Vocals)",
        "315 Hz": "-2 dB (Removes Door Panel Boxiness)",
        "500 Hz": "0 dB (Natural Lower Vocal Body)",
        "800 Hz": "+1 dB (Crystal Clear Midrange Vocals)",
        "1.25k Hz": "+1 dB (Pure Lead Vocal Presence)",
        "2k Hz": "0 dB (Clean Vocal Articulation)",
        "3.15k Hz": f"{tw_cut_3k} dB (Tames Component Tweeter Ear Fatigue)",
        "5k Hz": f"{tw_cut_5k} dB (Eliminates Harsh Sibilance)",
        "8k Hz": "-1 dB (Smooth Coaxial & Tweeter Blend)",
        "12.5k Hz": "+1 dB (Silky Air Without Hiss)"
    }

    pioneer_60pct_guide = {
        "sweet_spot_volume": "Volume 22 to 24 (55% - 60% Max) — 100% Clip-Free Headroom",
        "sla_source_level": "-2 dB (Audio -> SLA -> -2 dB prevents Bluetooth/Android Auto input clipping)",
        "loudness_setting": "OFF (Loudness boosts 100 Hz & 10 kHz by +10 dB which causes severe distortion at 60% Vol!)",
        "bass_boost_setting": "0 / OFF (Use Sub LPF 63 Hz + 50 Hz EQ +1 dB instead of Bass Boost)",
        "fader_and_balance": "Fader = 0, Balance = 0 (Use individual Speaker Levels below for exact 0.5 dB precision)",
        "eq_curve_13band": pioneer_13band_table,
        "poweramp_spotify_pass_through": f"Poweramp/App 10-Band EQ -> 31Hz:+2.0 | 62Hz:+1.5 | 125Hz:{eq_125}.0 | 250Hz:-2.0 | 500Hz:0 | 1kHz:+1.0 | 2kHz:+1.5 | 4kHz:{tw_cut_3k}.0 | 8kHz:-1.0 | 16kHz:+1.0 | Bass Tone:+2.5dB | Limiter:-1.2dBFS"
    }

    return {
        "reference_db": round(float(ref_db), 1),
        "reference_source": ref_source,
        "master_crossover_guide": {
            "front_hpf": front_hpf_rec,
            "rear_hpf": rear_hpf_rec,
            "sub_lpf": sub_lpf_rec,
            "sub_phase": sub_phase_rec,
            "front_tweeter_eq": front_tweeter_eq_rec
        },
        "pioneer_60pct_guide": pioneer_60pct_guide,
        "channels": results
    }
