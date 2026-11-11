from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import Response, FileResponse
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
        Q930_CHANNELS,
        generate_bandlimited_pink_noise,
        analyze_recorded_channel_audio
    )
    from app.car_audio_dsp import (
        CAR_CHANNELS,
        generate_car_test_chime,
        analyze_car_channel_audio
    )
except ImportError:
    from audio_dsp import (
        Q930_CHANNELS,
        generate_bandlimited_pink_noise,
        analyze_recorded_channel_audio
    )
    from car_audio_dsp import (
        CAR_CHANNELS,
        generate_car_test_chime,
        analyze_car_channel_audio
    )

app = FastAPI(
    title="Samsung Q930 Soundbar & Pioneer DMH-Z5290BT Car Audio Calibrator",
    description="Phone-mic acoustic calibration suite for Samsung 9.1.4 Soundbar and Pioneer DMH-Z5290BT 4.1 Car Audio.",
    version="3.0.0"
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Session storage for Samsung Q930 Soundbar
calibration_session = {
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
    return {"message": "Samsung Q930 Calibration API Running."}


@app.get("/car")
def get_car_page():
    car_file = os.path.join(static_dir, "car.html")
    if os.path.exists(car_file):
        return FileResponse(car_file)
    return {"message": "Pioneer DMH-Z5290BT Car Calibration Page."}


# ==============================================================================
# 1. SAMSUNG HW-Q930 9.1.4 SOUNDBAR ENDPOINTS
# ==============================================================================

@app.get("/api/channels")
def get_channels():
    """Returns all 8 channel groups for the Samsung HW-Q930 (9.1.4)."""
    return {"channels": Q930_CHANNELS}


@app.post("/api/reset")
def reset_session():
    """Clears all recorded Samsung Q930 measurements."""
    calibration_session["initial_measurements"].clear()
    calibration_session["confirmed_measurements"].clear()
    return {"status": "reset"}


@app.get("/api/tone/{channel_id}")
def get_channel_tone(channel_id: str, duration: float = 3.2):
    """Generates musical Ding-Dong chime (WAV) for the specified Samsung Q930 channel."""
    ch_info = next((c for c in Q930_CHANNELS if c["id"] == channel_id), None)
    if not ch_info:
        raise HTTPException(status_code=404, detail="Channel ID not found")

    wav_bytes = generate_bandlimited_pink_noise(
        duration_sec=duration,
        low_cut=ch_info["freq_low"],
        high_cut=ch_info["freq_high"],
        channel_id=channel_id
    )
    return Response(content=wav_bytes, media_type="audio/wav")


@app.post("/api/analyze-channel")
async def analyze_channel(
    channel_id: str = Form(...),
    mode: str = Form("initial"),
    audio_file: UploadFile = File(...)
):
    ch_info = next((c for c in Q930_CHANNELS if c["id"] == channel_id), None)
    if not ch_info:
        raise HTTPException(status_code=404, detail="Invalid channel_id")

    wav_content = await audio_file.read()
    if len(wav_content) < 1000:
        raise HTTPException(status_code=400, detail="Audio recording too short")

    try:
        metrics = analyze_recorded_channel_audio(
            wav_content,
            freq_low=ch_info["freq_low"],
            freq_high=ch_info["freq_high"],
            channel_id=channel_id
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

    if not initial:
        return {
            "reference_db": None,
            "reference_source": None,
            "channels": {}
        }

    if "front_left_right" in initial:
        ref_db = initial["front_left_right"]["db_level"]
        ref_source = "Front L/R (Master Volume Anchor)"
    elif "center" in initial:
        ref_db = initial["center"]["db_level"]
        ref_source = "Center Channel"
    else:
        all_dbs = [m["db_level"] for m in initial.values()]
        ref_db = sum(all_dbs) / len(all_dbs)
        ref_source = "Measured Average"

    results = {}

    for ch in Q930_CHANNELS:
        ch_id = ch["id"]
        init_data = initial.get(ch_id)
        if not init_data:
            continue

        target_db = ref_db + ch["target_offset_db"]
        measured_db = init_data["db_level"]

        if ch_id == "front_left_right":
            req_step = 0
            status = "REFERENCE_LOCKED"
            action_text = "0 dB (Fixed Anchor)"
        else:
            raw_delta = target_db - measured_db
            if init_data["is_harsh"]:
                harsh_cut = 2.5 if ch_id == "subwoofer" else 1.5
                raw_delta -= harsh_cut

            req_step = int(round(raw_delta))
            req_step = max(ch["min_db"], min(ch["max_db"], req_step))
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
                "residual_error_db": round(float(residual_error), 1),
                "is_confirmed": is_confirmed,
                "is_harsh": conf_data["is_harsh"],
                "diagnosis": conf_data["diagnosis"],
                "fine_tune_suggestion": (
                    "Confirmed! Level & tone are balanced."
                    if is_confirmed
                    else f"Adjust {ch['remote_menu']} by {'+' if fine_tune_step > 0 else ''}{fine_tune_step} dB more"
                )
            }

        results[ch_id] = {
            "name": ch["name"],
            "remote_menu": ch["remote_menu"],
            "target_db": round(float(target_db), 1),
            "initial_measured_db": measured_db,
            "required_remote_db": req_step,
            "required_action_text": action_text,
            "is_harsh": init_data["is_harsh"],
            "peak_freq_hz": init_data["peak_frequency_hz"],
            "peak_spike_db": init_data["peak_spike_db"],
            "diagnosis": init_data["diagnosis"],
            "status": status,
            "confirmation": confirm_info
        }

    return {
        "reference_db": round(float(ref_db), 1),
        "reference_source": ref_source,
        "channels": results
    }


# ==============================================================================
# 2. PIONEER DMH-Z5290BT CAR AUDIO (4 SPEAKERS + 1 SUBWOOFER) ENDPOINTS
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
def get_car_tone(channel_id: str, duration: float = 3.2):
    """Generates Left/Right-panned or Sub musical Ding-Dong chime WAV for Pioneer DMH-Z5290BT."""
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

    try:
        metrics = analyze_car_channel_audio(wav_content, channel_id=channel_id)
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
    Calculates required Pioneer DMH-Z5290BT Speaker Levels (-24 to +10 dB) AND
    Crossover settings (Front HPF, Rear HPF, Subwoofer LPF, Slopes, and Sub Phase).
    In a car, the farther front speaker (or quieter front speaker) is used as the 0 dB reference
    so the closer front speaker is attenuated (-dB) to center the soundstage without clipping.
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

    # Determine Reference Anchor:
    # If both Front Left and Front Right are measured, use the quieter one (further from driver)
    # as the 0 dB anchor so the closer speaker is cut (-dB) instead of boosting into clipping.
    if "front_left" in initial and "front_right" in initial:
        fl_db = initial["front_left"]["db_level"]
        fr_db = initial["front_right"]["db_level"]
        if fl_db <= fr_db:
            ref_db = fl_db
            ref_source = "Front Left (Far-Side Anchor = 0 dB)"
        else:
            ref_db = fr_db
            ref_source = "Front Right (Far-Side Anchor = 0 dB)"
    elif "front_left" in initial:
        ref_db = initial["front_left"]["db_level"]
        ref_source = "Front Left Speaker"
    elif "front_right" in initial:
        ref_db = initial["front_right"]["db_level"]
        ref_source = "Front Right Speaker"
    else:
        all_dbs = [m["db_level"] for m in initial.values()]
        ref_db = sum(all_dbs) / len(all_dbs)
        ref_source = "Measured Average"

    results = {}
    front_hpf_rec = "80 Hz (-12 dB/oct)"
    rear_hpf_rec = "80 Hz (-12 dB/oct)"
    sub_lpf_rec = "80 Hz (-18 dB/oct)"
    sub_phase_rec = "Normal (0°)"

    for ch in CAR_CHANNELS:
        ch_id = ch["id"]
        init_data = initial.get(ch_id)
        if not init_data:
            continue

        target_db = ref_db + ch["target_offset_db"]
        measured_db = init_data["db_level"]

        if ch_id == "crossover_blend":
            req_step = 0
            action_text = init_data["xover_recommendation"]
            sub_phase_rec = init_data["xover_recommendation"]
        else:
            raw_delta = target_db - measured_db
            if init_data["is_harsh"] and ch_id == "subwoofer":
                raw_delta -= 2.0
            elif init_data["is_harsh"]:
                raw_delta -= 1.0

            req_step = int(round(raw_delta))
            req_step = max(ch["min_db"], min(ch["max_db"], req_step))
            action_text = f"{'+' if req_step > 0 else ''}{req_step} dB"

        if ch_id in ("front_left", "front_right"):
            front_hpf_rec = init_data["xover_recommendation"]
        elif ch_id in ("rear_left", "rear_right"):
            rear_hpf_rec = init_data["xover_recommendation"]
        elif ch_id == "subwoofer":
            sub_lpf_rec = init_data["xover_recommendation"]

        conf_data = confirmed.get(ch_id)
        confirm_info = None
        if conf_data:
            post_db = conf_data["db_level"]
            residual_error = target_db - post_db
            fine_tune_step = int(round(residual_error))
            is_confirmed = (abs(residual_error) <= 1.5 and not conf_data["is_harsh"]) if ch_id != "crossover_blend" else (not conf_data["is_harsh"])

            confirm_info = {
                "post_db": post_db,
                "residual_error_db": round(float(residual_error), 1),
                "is_confirmed": is_confirmed,
                "diagnosis": conf_data["diagnosis"],
                "fine_tune_suggestion": (
                    "Confirmed! Speaker level & crossover response are balanced."
                    if is_confirmed
                    else (
                        conf_data["diagnosis"]
                        if ch_id == "crossover_blend"
                        else f"Adjust {ch['pioneer_menu']} by {'+' if fine_tune_step > 0 else ''}{fine_tune_step} dB more"
                    )
                )
            }

        results[ch_id] = {
            "name": ch["name"],
            "pioneer_menu": ch["pioneer_menu"],
            "xover_menu": ch["xover_menu"],
            "target_db": round(float(target_db), 1),
            "initial_measured_db": measured_db,
            "required_pioneer_db": req_step,
            "required_action_text": action_text,
            "xover_recommendation": init_data["xover_recommendation"],
            "is_harsh": init_data["is_harsh"],
            "peak_freq_hz": init_data["peak_frequency_hz"],
            "dip_freq_hz": init_data["dip_frequency_hz"],
            "diagnosis": init_data["diagnosis"],
            "confirmation": confirm_info
        }

    return {
        "reference_db": round(float(ref_db), 1),
        "reference_source": ref_source,
        "master_crossover_guide": {
            "front_hpf": front_hpf_rec,
            "rear_hpf": rear_hpf_rec,
            "sub_lpf": sub_lpf_rec,
            "sub_phase": sub_phase_rec
        },
        "channels": results
    }
