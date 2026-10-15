/**
 * Audio Analyzer & Decibel Bubble Controller
 * Reconstructs Google Live Transcribe's acoustic meter and sound event classifier.
 */
class AudioAnalyzer {
  constructor(options = {}) {
    this.onLevelChange = options.onLevelChange || (() => {});
    this.onEventDetected = options.onEventDetected || (() => {});
    
    this.audioContext = null;
    this.analyser = null;
    this.microphone = null;
    this.stream = null;
    this.animationFrameId = null;
    this.isRunning = false;

    // Smoothed RMS level
    this.smoothedLevel = 0;
    this.smoothingFactor = 0.25;

    // Detection thresholds
    this.silenceThreshold = 0.04;
    this.speechThreshold = 0.10;
    this.loudThreshold = 0.70;

    // Consecutive frame counters for stable classification
    this.speechFrames = 0;
    this.applauseFrames = 0;
    this.musicFrames = 0;
    this.currentEvent = 'Silence';
  }

  async start(existingStream = null) {
    if (this.isRunning) return;

    try {
      if (existingStream) {
        this.stream = existingStream;
      } else {
        this.stream = await navigator.mediaDevices.getUserMedia({
          audio: {
            echoCancellation: true,
            noiseSuppression: false, // keep ambient for event detection
            autoGainControl: true
          }
        });
      }

      const AudioCtx = window.AudioContext || window.webkitAudioContext;
      this.audioContext = new AudioCtx();
      
      if (this.audioContext.state === 'suspended') {
        await this.audioContext.resume();
      }

      this.analyser = this.audioContext.createAnalyser();
      this.analyser.fftSize = 512;
      this.analyser.smoothingTimeConstant = 0.4;

      this.microphone = this.audioContext.createMediaStreamSource(this.stream);
      this.microphone.connect(this.analyser);

      this.isRunning = true;
      this.processLoop();
      return true;
    } catch (err) {
      console.error('[AudioAnalyzer] Failed to initialize microphone:', err);
      throw err;
    }
  }

  stop() {
    this.isRunning = false;
    if (this.animationFrameId) {
      cancelAnimationFrame(this.animationFrameId);
      this.animationFrameId = null;
    }
    if (this.microphone) {
      try { this.microphone.disconnect(); } catch (e) {}
      this.microphone = null;
    }
    if (this.audioContext) {
      try { this.audioContext.close(); } catch (e) {}
      this.audioContext = null;
    }
    if (this.stream) {
      this.stream.getTracks().forEach(track => track.stop());
      this.stream = null;
    }
    this.smoothedLevel = 0;
    this.onLevelChange(0, 0);
    this.updateEvent('Silence');
  }

  processLoop() {
    if (!this.isRunning || !this.analyser) return;

    const bufferLength = this.analyser.frequencyBinCount;
    const timeData = new Uint8Array(bufferLength);
    const freqData = new Uint8Array(bufferLength);

    this.analyser.getByteTimeDomainData(timeData);
    this.analyser.getByteFrequencyData(freqData);

    // Compute RMS amplitude
    const rms = AudioAnalyzer.calculateRMS(timeData);
    
    // Smooth level change
    this.smoothedLevel = (this.smoothedLevel * (1 - this.smoothingFactor)) + (rms * this.smoothingFactor);
    
    // Convert to decibel-like linear scale (0.0 to 1.0)
    const normalizedLevel = Math.min(1.0, this.smoothedLevel * 2.5);
    const dbEstimate = AudioAnalyzer.calculateDecibels(rms);

    this.onLevelChange(normalizedLevel, dbEstimate);

    // Sound Event Detection
    const detectedEvent = AudioAnalyzer.classifyAcousticEvent(rms, freqData, bufferLength);
    this.updateEvent(detectedEvent);

    this.animationFrameId = requestAnimationFrame(() => this.processLoop());
  }

  updateEvent(newEvent) {
    if (this.currentEvent !== newEvent) {
      this.currentEvent = newEvent;
      this.onEventDetected(newEvent);
    }
  }

  /**
   * Static helper: Compute RMS from 8-bit time-domain PCM samples
   */
  static calculateRMS(timeData) {
    let sum = 0;
    for (let i = 0; i < timeData.length; i++) {
      // 128 is zero reference in 8-bit unsigned PCM
      const norm = (timeData[i] - 128) / 128;
      sum += norm * norm;
    }
    return Math.sqrt(sum / timeData.length);
  }

  /**
   * Static helper: Decibel estimation
   */
  static calculateDecibels(rms) {
    if (rms < 0.0001) return -90;
    return Math.max(-90, Math.round(20 * Math.log10(rms)));
  }

  /**
   * Static helper: Classify audio into acoustic event categories
   */
  static classifyAcousticEvent(rms, freqData, binCount) {
    if (rms < 0.02) {
      return 'Silence';
    }

    if (rms > 0.65) {
      return 'Loud Noise';
    }

    // Frequency bands (sample rate usually 44.1k or 48k, bin width ~90Hz for 512 fft)
    // Low: 0-300Hz, Mid/Speech: 300-3400Hz, High: >3400Hz
    let lowEnergy = 0;
    let speechEnergy = 0;
    let highEnergy = 0;

    const lowCutoff = Math.floor(binCount * 0.1);
    const speechCutoff = Math.floor(binCount * 0.45);

    for (let i = 0; i < binCount; i++) {
      const val = freqData[i];
      if (i < lowCutoff) lowEnergy += val;
      else if (i < speechCutoff) speechEnergy += val;
      else highEnergy += val;
    }

    const totalEnergy = lowEnergy + speechEnergy + highEnergy + 0.0001;
    const speechRatio = speechEnergy / totalEnergy;
    const highRatio = highEnergy / totalEnergy;

    // Clapping / Applause: High burst energy in mid-high frequencies
    if (highRatio > 0.40 && rms > 0.15) {
      return 'Applause';
    }

    // Music: broad tonal spread with rich bass and high harmonics
    if (lowEnergy > totalEnergy * 0.35 && highRatio > 0.25 && rms > 0.1) {
      return 'Music';
    }

    // Speech: Dominant vocal format frequencies (300Hz - 3kHz)
    if (speechRatio > 0.35 || (rms > 0.05 && speechRatio > 0.25)) {
      return 'Speech';
    }

    return 'Ambient Sound';
  }
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = AudioAnalyzer;
}
