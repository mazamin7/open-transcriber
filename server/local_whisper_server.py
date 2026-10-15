"""
Air-Gapped Offline Whisper WebSocket Server
Provides local, private speech-to-text inference for the Live Transcribe PWA.
Zero internet connectivity required. All audio is processed purely in RAM.
"""

import asyncio
import json
import os
import sys

try:
    import websockets
    import numpy as np
    from faster_whisper import WhisperModel
except ImportError:
    print("\n[Notice] Required packages not found.")
    print("To run the local Python Whisper backend, install dependencies:")
    print("    pip install -r requirements.txt\n")
    print("Note: The Live Transcribe web app runs independently via on-device speech recognition without this server!")

HOST = "0.0.0.0"
PORT = 8765
MODEL_SIZE = os.environ.get("WHISPER_MODEL", "tiny.en")
COMPUTE_TYPE = "int8" # fast on CPU

model = None

def get_model():
    global model
    if model is None:
        print(f"[WhisperServer] Loading Faster-Whisper model '{MODEL_SIZE}' (compute={COMPUTE_TYPE})...")
        model = WhisperModel(MODEL_SIZE, device="cpu", compute_type=COMPUTE_TYPE)
        print("[WhisperServer] Model loaded successfully.")
    return model

async def handle_client(websocket):
    print(f"[WhisperServer] Client connected: {websocket.remote_address}")
    whisper = get_model()
    audio_buffer = bytearray()
    SAMPLE_RATE = 16000
    CHUNK_BYTES = SAMPLE_RATE * 2 * 2 # 2 seconds of 16-bit PCM @ 16kHz

    try:
        async for message in websocket:
            if isinstance(message, bytes):
                audio_buffer.extend(message)

                if len(audio_buffer) >= CHUNK_BYTES:
                    # Convert 16-bit PCM to float32 normalized [-1.0, 1.0]
                    pcm_data = np.frombuffer(audio_buffer, dtype=np.int16).astype(np.float32) / 32768.0
                    audio_buffer.clear()

                    # Transcribe
                    segments, info = whisper.transcribe(
                        pcm_data,
                        beam_size=1,
                        language="en",
                        vad_filter=True,
                        vad_parameters=dict(min_silence_duration_ms=400)
                    )

                    for seg in segments:
                        text = seg.text.strip()
                        if text:
                            payload = {
                                "type": "transcript",
                                "text": text,
                                "isFinal": True
                            }
                            await websocket.send(json.dumps(payload))
            elif isinstance(message, str):
                # Handle control messages if any
                pass

    except websockets.exceptions.ConnectionClosed:
        print(f"[WhisperServer] Client disconnected: {websocket.remote_address}")
    except Exception as e:
        print(f"[WhisperServer] Error handling client: {e}")

async def main():
    print(f"\n=======================================================")
    print(f"  Offline Whisper WebSocket Server listening on {HOST}:{PORT}")
    print(f"=======================================================\n")
    async with websockets.serve(handle_client, HOST, PORT):
        await asyncio.Future() # run forever

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\n[WhisperServer] Shutting down.")
