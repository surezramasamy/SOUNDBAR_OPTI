# Samsung HW-Q930 (9.1.4) Test & Confirm dB Calibrator

A cloud-deployable **FastAPI + Mobile Web App** that tests all 8 speaker channel groups of the Samsung HW-Q930 9.1.4 soundbar using your phone's microphone at the sofa, calculates the exact **Required dB Levels** for your Samsung remote, and lets you **Confirm / Re-Test** each channel after adjusting it.

---

## How the Test $\to$ Set Remote $\to$ Confirm Workflow Works

On the Samsung HW-Q930, **Front Left / Right** has no individual `CH LEVEL` offset—its loudness is set by the Master Volume. All other 7 channel groups (`Center`, `Side`, `Wide`, `Front Top`, `Rear`, `Rear Top`, and `Woofer`) are adjusted relative to `Front L/R` on the remote.

1. **Pre-Test Setup on Samsung Remote:**
   * Press **SOUND MODE** and select **`SURROUND`** (or **`STANDARD`**).
   * Turn **SpaceFit Sound OFF** (in the SmartThings app or soundbar menu) so the soundbar's wall-mounted mic stops boosting room resonances.
   * Set all **CH LEVEL** values and **WOOFER** to **`0`** initially.
   * Set Master Volume to your normal movie listening volume (e.g., `18–22`).

2. **Step 1 — Initial Test (`Test All 8 Channels` or `Test (at 0 dB)`):**
   * Sit in your main sofa spot and hold the phone at **ear height** facing the soundbar.
   * Tap **`▶ Test All 8 Channels`** (or test any single channel).
   * The app measures **1. Front L/R** first as your **Reference Anchor**, then tests the remaining 7 channels and displays the exact **`Set Remote: +/-X dB`** badge for each channel.
   * If a channel has a room standing wave or harsh ceiling/wall reflection ("hitting sound"), the app flags the exact frequency (in Hz) and automatically lowers the recommended dB to prevent ear fatigue.

3. **Step 2 — Adjust Your Samsung Remote:**
   * Press the **`CH LEVEL`** button on your Samsung remote to cycle through:
     * `CENTER` (-6 to +6)
     * `SIDE` (-6 to +6)
     * `WIDE` (-6 to +6)
     * `FRONT TOP` (-6 to +6)
     * `REAR` (-6 to +6)
     * `REAR TOP` (-6 to +6)
   * Use the **`WOOFER`** button on the remote to set the recommended Subwoofer dB (-12 to +6).

4. **Step 3 — Verify with `Confirm dB`:**
   * After setting the recommended dB on your remote, tap the green **`Confirm dB`** button next to that channel in the app.
   * The app plays the test tone again, listens with the phone mic, and shows either:
     * **`✅ CONFIRMED: Level & tone are balanced`** (within $\pm 1.5\text{ dB}$ of target), or
     * **`🔧 FINE-TUNE: Adjust by +1 / -1 dB more`** if it needs one extra click.

---

## Running Locally

```bash
cd samsung_q930_calibration
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000
```
Open **`http://localhost:8000`** (or `http://<your-pc-lan-ip>:8000` on your phone browser).

> **Note for Mobile Microphone Access:** Mobile browsers (Chrome/Safari) require either `localhost` or `https://` to allow microphone access (`navigator.mediaDevices.getUserMedia`). Deploying to Render/Railway or using an HTTPS tunnel (`ngrok http 8000` / Cloudflare Tunnel) automatically provides HTTPS for your phone.

---

## Deploying to Cloud (Render / Railway / Docker)

### Option 1: Render.com / Railway
1. Push `samsung_q930_calibration/` to a GitHub repo.
2. Create a **New Web Service**:
   * **Build Command:** `pip install -r requirements.txt`
   * **Start Command:** `uvicorn app.main:app --host 0.0.0.0 --port 8000`
3. Open the generated `https://...` URL on your phone browser.

### Option 2: Docker
```bash
cd samsung_q930_calibration
docker build -t q930-calibrator .
docker run -d -p 8000:8000 q930-calibrator
```
