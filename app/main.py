from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import Response, FileResponse
from fastapi.staticfiles import StaticFiles
import os

from app.audio_dsp import (
    Q930_CHANNELS,
    generate_bandlimited_pink_noise,
    analyze_recorded_channel_audio
)

app = FastAPI(
    title="Samsung HW-Q930 9.1.4 Channel Test & dB Confirmation API",
    description="Measures each of the 8 channel groups via phone mic, calculates required dB levels, and verifies/confirms after manual remote adjustment.",
    version="2.1.0"
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Session storage for initial measurements and post-adjustment confirmations
calibration_session = {
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


@app.get("/api/channels")
def get_channels():
    """Returns all 8 channel groups for the Samsung HW-Q930 (9.1.4)."""
    return {"channels": Q930_CHANNELS}


@app.post("/api/reset")
def reset_session():
    """Clears all recorded measurements to start a fresh calibration."""
    calibration_session["initial_measurements"].clear()
    calibration_session["confirmed_measurements"].clear()
    return {"status": "reset"}


@app.get("/api/tone/{channel_id}")
def get_channel_tone(channel_id: str, duration: float = 3.5):
    """Generates band-limited reference pink noise (WAV) for the specified channel."""
    ch_info = next((c for c in Q930_CHANNELS if c["id"] == channel_id), None)
    if not ch_info:
        raise HTTPException(status_code=404, detail="Channel ID not found")

    wav_bytes = generate_bandlimited_pink_noise(
        duration_sec=duration,
        low_cut=ch_info["freq_low"],
        high_cut=ch_info["freq_high"]
    )
    return Response(content=wav_bytes, media_type="audio/wav")


@app.post("/api/analyze-channel")
async def analyze_channel(
    channel_id: str = Form(...),
    mode: str = Form("initial"),
    audio_file: UploadFile = File(...)
):
    """
    Analyzes recorded microphone WAV from the phone at the listening position.
    - mode='initial': measures raw response (with remote at 0 dB) to compute required dB.
    - mode='confirm': re-measures after user sets the recommended dB on the remote to confirm balance.
    """
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
            freq_high=ch_info["freq_high"]
        )
    except Exception as e:
        raise HTTPException(status_code=422, detail=f"Error analyzing audio: {str(e)}")

    if mode == "confirm":
        calibration_session["confirmed_measurements"][channel_id] = metrics
    else:
        calibration_session["initial_measurements"][channel_id] = metrics
        # Clear any old confirmation when re-running initial measurement
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
    """
    Computes the required Samsung Remote dB setting for each of the 8 channel groups
    and verifies whether re-tested channels are confirmed balanced.
    """
    initial = calibration_session["initial_measurements"]
    confirmed = calibration_session["confirmed_measurements"]

    if not initial:
        return {
            "reference_db": None,
            "reference_source": None,
            "channels": {}
        }

    # Reference Anchor: Front Left/Right (since Front L/R has no CH LEVEL offset on Q930)
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

        # If channel is Front L/R, it is the 0 dB fixed reference anchor
        if ch_id == "front_left_right":
            req_step = 0
            status = "REFERENCE_LOCKED"
            action_text = "0 dB (Fixed Anchor)"
        else:
            raw_delta = target_db - measured_db

            # Anti-Harshness / Anti-Hitting Protection:
            # If the channel exhibits a strong room resonance spike or harsh transient crest,
            # pull back the target so it does not hit the ears when volume increases.
            harsh_cut = 0.0
            if init_data["is_harsh"]:
                harsh_cut = 2.5 if ch_id == "subwoofer" else 1.5
                raw_delta -= harsh_cut

            req_step = int(round(raw_delta))
            req_step = max(ch["min_db"], min(ch["max_db"], req_step))
            action_text = f"{'+' if req_step > 0 else ''}{req_step} dB"
            status = "OPTIMAL_AT_0DB" if req_step == 0 and not init_data["is_harsh"] else "NEEDS_REMOTE_SET"

        # Check if user has already adjusted remote and run a Confirmation Re-Test
        conf_data = confirmed.get(ch_id)
        confirm_info = None
        if conf_data and ch_id != "front_left_right":
            post_db = conf_data["db_level"]
            # Target after adjustment (including harshness cushion if still harsh)
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
