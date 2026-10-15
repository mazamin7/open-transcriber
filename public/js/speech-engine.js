/**
 * Speech Engine Coordinator
 * Manages live real-time speech recognition pipelines:
 * - Engine 1: On-Device Web Speech API (with Android offline speech pack support & continuous auto-restart)
 * - Engine 2: Local WebSocket Server (airgapped Python Faster-Whisper on local Wi-Fi/localhost)
 */
class SpeechEngine {
  constructor(options = {}) {
    this.engineType = options.engineType || 'webspeech'; // 'webspeech' | 'websocket' | 'mock'
    this.language = options.language || 'it-IT';
    this.wsUrl = options.wsUrl || 'ws://localhost:8765';
    
    this.onResult = options.onResult || (() => {});
    this.onError = options.onError || (() => {});
    this.onStateChange = options.onStateChange || (() => {});

    this.isListening = false;
    this.recognition = null;
    this.webSocket = null;
    this.mediaRecorder = null;
    this.stream = null;

    // Internal transcript state
    this.finalTranscript = '';
    this.interimTranscript = '';
    this.restartTimeout = null;
    this.manualStop = false;
  }

  setLanguage(lang) {
    this.language = lang;
    if (this.recognition) {
      this.recognition.lang = lang;
    }
  }

  setEngineType(type, customWsUrl = null) {
    const wasListening = this.isListening;
    if (wasListening) {
      this.stop();
    }
    this.engineType = type;
    if (customWsUrl) {
      this.wsUrl = customWsUrl;
    }
    if (wasListening) {
      this.start();
    }
  }

  clearTranscript() {
    this.finalTranscript = '';
    this.interimTranscript = '';
    this.onResult({
      interim: '',
      final: '',
      full: ''
    });
  }

  async start() {
    this.manualStop = false;
    if (this.engineType === 'webspeech') {
      return this.startWebSpeech();
    } else if (this.engineType === 'websocket') {
      return this.startWebSocket();
    } else if (this.engineType === 'mock') {
      return this.startMock();
    }
  }

  stop() {
    this.manualStop = true;
    if (this.restartTimeout) {
      clearTimeout(this.restartTimeout);
      this.restartTimeout = null;
    }

    if (this.recognition) {
      try { this.recognition.abort(); } catch (e) {}
      this.recognition = null;
    }

    if (this.webSocket) {
      try { this.webSocket.close(); } catch (e) {}
      this.webSocket = null;
    }

    if (this.mediaRecorder && this.mediaRecorder.state !== 'inactive') {
      try { this.mediaRecorder.stop(); } catch (e) {}
      this.mediaRecorder = null;
    }

    if (this.stream) {
      this.stream.getTracks().forEach(track => track.stop());
      this.stream = null;
    }

    this.isListening = false;
    this.onStateChange('idle');
  }

  /* ---------------------------------------------------------------
   * Engine 1: On-Device / Browser Web Speech API
   * --------------------------------------------------------------- */
  startWebSpeech() {
    const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SpeechRecognition) {
      const err = new Error('Speech Recognition is not supported in this browser. Use Chrome, Edge, or Samsung Internet with offline speech recognition packs.');
      this.onError(err);
      return false;
    }

    this.recognition = new SpeechRecognition();
    this.recognition.continuous = true;
    this.recognition.interimResults = true;
    this.recognition.maxAlternatives = 1;
    this.recognition.lang = this.language;

    this.recognition.onstart = () => {
      this.isListening = true;
      this.onStateChange('listening');
    };

    this.recognition.onresult = (event) => {
      let currentInterim = '';
      let newlyFinalized = '';

      for (let i = event.resultIndex; i < event.results.length; ++i) {
        const transcriptPart = event.results[i][0].transcript;
        if (event.results[i].isFinal) {
          newlyFinalized += transcriptPart;
          this.finalTranscript += (this.finalTranscript ? ' ' : '') + transcriptPart.trim();
        } else {
          currentInterim += transcriptPart;
        }
      }

      this.interimTranscript = currentInterim;

      this.onResult({
        interim: currentInterim,
        final: newlyFinalized,
        full: (this.finalTranscript + ' ' + currentInterim).trim()
      });
    };

    this.recognition.onerror = (event) => {
      // Don't treat benign pauses or no-speech as fatal
      if (event.error === 'no-speech') {
        return;
      }
      console.warn('[SpeechEngine] WebSpeech error event:', event.error);
      this.onError(new Error(`Speech recognition error: ${event.error}`));
    };

    this.recognition.onend = () => {
      if (!this.manualStop) {
        // Continuous auto-restart (standard requirement for long-running Live Transcribe)
        this.restartTimeout = setTimeout(() => {
          if (!this.manualStop) {
            try {
              this.recognition.start();
            } catch (err) {
              console.warn('[SpeechEngine] Failed auto-restart, retrying...', err);
              this.onStateChange('restarting');
            }
          }
        }, 150);
      } else {
        this.isListening = false;
        this.onStateChange('idle');
      }
    };

    try {
      this.recognition.start();
      return true;
    } catch (err) {
      this.onError(err);
      return false;
    }
  }

  /* ---------------------------------------------------------------
   * Engine 2: Local WebSocket Server (e.g. Faster-Whisper on LAN)
   * --------------------------------------------------------------- */
  async startWebSocket() {
    try {
      this.onStateChange('connecting');
      this.webSocket = new WebSocket(this.wsUrl);
      this.webSocket.binaryType = 'arraybuffer';

      this.webSocket.onopen = async () => {
        this.isListening = true;
        this.onStateChange('listening');
        // Start streaming mic audio
        await this.startAudioStreamToWebSocket();
      };

      this.webSocket.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data);
          if (data.type === 'transcript') {
            if (data.isFinal) {
              this.finalTranscript += (this.finalTranscript ? ' ' : '') + data.text.trim();
              this.interimTranscript = '';
            } else {
              this.interimTranscript = data.text;
            }

            this.onResult({
              interim: this.interimTranscript,
              final: data.isFinal ? data.text : '',
              full: (this.finalTranscript + ' ' + this.interimTranscript).trim()
            });
          }
        } catch (err) {
          console.error('[SpeechEngine] Malformed JSON from WebSocket:', err);
        }
      };

      this.webSocket.onerror = (err) => {
        console.error('[SpeechEngine] WebSocket error:', err);
        this.onError(new Error('Cannot connect to local Whisper server. Check that local_whisper_server.py is running.'));
      };

      this.webSocket.onclose = () => {
        if (!this.manualStop) {
          this.onStateChange('disconnected');
        } else {
          this.isListening = false;
          this.onStateChange('idle');
        }
      };

      return true;
    } catch (err) {
      this.onError(err);
      return false;
    }
  }

  async startAudioStreamToWebSocket() {
    this.stream = await navigator.mediaDevices.getUserMedia({
      audio: {
        sampleRate: 16000,
        channelCount: 1,
        echoCancellation: true
      }
    });

    const AudioCtx = window.AudioContext || window.webkitAudioContext;
    const ctx = new AudioCtx({ sampleRate: 16000 });
    const source = ctx.createMediaStreamSource(this.stream);
    
    // ScriptProcessor to stream raw 16kHz float32 or int16 PCM
    const processor = ctx.createScriptProcessor(4096, 1, 1);
    processor.onaudioprocess = (e) => {
      if (!this.isListening || !this.webSocket || this.webSocket.readyState !== WebSocket.OPEN) return;
      const inputData = e.inputBuffer.getChannelData(0);
      
      // Convert to 16-bit PCM
      const pcm16 = new Int16Array(inputData.length);
      for (let i = 0; i < inputData.length; i++) {
        const s = Math.max(-1, Math.min(1, inputData[i]));
        pcm16[i] = s < 0 ? s * 0x8000 : s * 0x7FFF;
      }
      this.webSocket.send(pcm16.buffer);
    };

    source.connect(processor);
    processor.connect(ctx.destination);
  }

  /* ---------------------------------------------------------------
   * Engine 3: Mock Engine (Unit Testing & Offline Verification)
   * --------------------------------------------------------------- */
  startMock() {
    this.isListening = true;
    this.onStateChange('listening');
    return true;
  }

  injectMockResult(text, isFinal = true) {
    if (isFinal) {
      this.finalTranscript += (this.finalTranscript ? ' ' : '') + text.trim();
      this.interimTranscript = '';
    } else {
      this.interimTranscript = text;
    }

    this.onResult({
      interim: this.interimTranscript,
      final: isFinal ? text : '',
      full: (this.finalTranscript + ' ' + this.interimTranscript).trim()
    });
  }
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = SpeechEngine;
}
