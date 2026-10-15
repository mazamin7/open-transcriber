/**
 * Accessibility & Physical Feedback Controller
 * Implements Google Live Transcribe's accessibility features:
 * - Dynamic scalable typography (18px to 64px)
 * - High-contrast & OLED black themes
 * - Continuous Screen Wake Lock (keeps display active during conversations)
 * - Haptic vibration alerts on speech onset
 */
class AccessibilityController {
  constructor(options = {}) {
    this.wakeLock = null;
    this.isWakeLockEnabled = options.wakeLock ?? true;
    this.isHapticsEnabled = options.haptics ?? true;
    this.fontSize = options.fontSize || 32; // Default 32px
    this.theme = options.theme || 'oled-black';

    this.lastSpeechTime = 0;
    this.silenceGapThresholdMs = 2500; // 2.5 seconds of silence before next speech triggers haptic
  }

  init() {
    this.loadPreferences();
    this.applyTheme(this.theme);
    this.applyFontSize(this.fontSize);
    if (this.isWakeLockEnabled) {
      this.requestWakeLock();
    }

    // Re-acquire wake lock if page becomes visible again
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible' && this.isWakeLockEnabled) {
        this.requestWakeLock();
      }
    });
  }

  loadPreferences() {
    try {
      const savedTheme = localStorage.getItem('transcribe_theme');
      if (savedTheme) this.theme = savedTheme;

      const savedSize = localStorage.getItem('transcribe_font_size');
      if (savedSize) this.fontSize = parseInt(savedSize, 10);

      const savedHaptics = localStorage.getItem('transcribe_haptics');
      if (savedHaptics !== null) this.isHapticsEnabled = savedHaptics === 'true';

      const savedWakeLock = localStorage.getItem('transcribe_wakelock');
      if (savedWakeLock !== null) this.isWakeLockEnabled = savedWakeLock === 'true';
    } catch (e) {
      console.warn('[Accessibility] Failed to load preferences:', e);
    }
  }

  applyTheme(themeName) {
    this.theme = themeName;
    document.body.className = '';
    document.body.classList.add(`theme-${themeName}`);
    try {
      localStorage.setItem('transcribe_theme', themeName);
    } catch (e) {}
  }

  applyFontSize(px) {
    this.fontSize = Math.max(16, Math.min(72, px));
    document.documentElement.style.setProperty('--transcript-font-size', `${this.fontSize}px`);
    try {
      localStorage.setItem('transcribe_font_size', this.fontSize.toString());
    } catch (e) {}
  }

  /**
   * Keep Screen Awake via Screen Wake Lock API
   */
  async requestWakeLock() {
    if (!('wakeLock' in navigator)) {
      console.log('[Accessibility] Wake Lock API not supported in this browser');
      return false;
    }
    try {
      this.wakeLock = await navigator.wakeLock.request('screen');
      this.wakeLock.addEventListener('release', () => {
        this.wakeLock = null;
      });
      console.log('[Accessibility] Screen Wake Lock active');
      return true;
    } catch (err) {
      console.warn('[Accessibility] Wake Lock request rejected:', err);
      return false;
    }
  }

  releaseWakeLock() {
    if (this.wakeLock) {
      this.wakeLock.release();
      this.wakeLock = null;
    }
  }

  setWakeLockEnabled(enabled) {
    this.isWakeLockEnabled = enabled;
    try {
      localStorage.setItem('transcribe_wakelock', enabled ? 'true' : 'false');
    } catch (e) {}
    if (enabled) {
      this.requestWakeLock();
    } else {
      this.releaseWakeLock();
    }
  }

  setHapticsEnabled(enabled) {
    this.isHapticsEnabled = enabled;
    try {
      localStorage.setItem('transcribe_haptics', enabled ? 'true' : 'false');
    } catch (e) {}
  }

  /**
   * Haptic Vibration on Speech Onset
   * Notifies the deaf user when someone starts talking after a period of silence.
   */
  notifySpeechOnset() {
    const now = Date.now();
    const elapsed = now - this.lastSpeechTime;
    this.lastSpeechTime = now;

    if (!this.isHapticsEnabled) return;

    if (elapsed > this.silenceGapThresholdMs) {
      if ('vibrate' in navigator) {
        // Double gentle pulse
        navigator.vibrate([80, 50, 80]);
      }
    }
  }
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = AccessibilityController;
}
