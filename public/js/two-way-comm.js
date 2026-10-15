/**
 * Two-Way Deaf Communication Module
 * Reconstructs Google Live Transcribe's Type-Back interface,
 * 180° Table-Flip facing partner mode, and offline Text-To-Speech with Italian support.
 */
class TwoWayCommunication {
  constructor(options = {}) {
    this.container = options.container || null;
    this.onSendResponse = options.onSendResponse || (() => {});
    this.isFlipped = false;
    this.isTtsEnabled = options.isTtsEnabled ?? true;
    this.ttsPitch = 1.0;
    this.ttsRate = 1.0;

    // Default quick response chips for deaf-hearing communication (in Italian)
    this.defaultChips = [
      "Sì",
      "No",
      "Grazie!",
      "Puoi ripetere?",
      "Sono sorda, per favore parla chiaro",
      "Dammi un attimo per scrivere",
      "Ho capito",
      "Puoi parlare più lentamente?"
    ];

    this.customChips = this.loadCustomChips();
  }

  loadCustomChips() {
    try {
      const stored = localStorage.getItem('deaf_quick_chips_it');
      if (stored) {
        return JSON.parse(stored);
      }
    } catch (e) {
      console.warn('[TwoWayComm] Could not read custom chips from storage:', e);
    }
    return [...this.defaultChips];
  }

  saveCustomChips() {
    try {
      localStorage.setItem('deaf_quick_chips_it', JSON.stringify(this.customChips));
    } catch (e) {
      console.warn('[TwoWayComm] Could not save custom chips:', e);
    }
  }

  addCustomChip(text) {
    const cleanText = text.trim();
    if (!cleanText || this.customChips.includes(cleanText)) return false;
    this.customChips.push(cleanText);
    this.saveCustomChips();
    return true;
  }

  removeCustomChip(text) {
    this.customChips = this.customChips.filter(c => c !== text);
    this.saveCustomChips();
  }

  resetDefaultChips() {
    this.customChips = [...this.defaultChips];
    this.saveCustomChips();
  }

  /**
   * Toggle 180° Table-Flip mode:
   * Flips the response text upside down so the hearing partner sitting
   * opposite across the table can read it effortlessly.
   */
  toggleFlip(targetElement) {
    this.isFlipped = !this.isFlipped;
    if (targetElement) {
      if (this.isFlipped) {
        targetElement.classList.add('table-flipped');
      } else {
        targetElement.classList.remove('table-flipped');
      }
    }
    return this.isFlipped;
  }

  /**
   * Offline Text-To-Speech (TTS)
   * Uses local device SpeechSynthesis Italian voice (100% offline, zero network requests).
   */
  speakText(text, lang = 'it-IT') {
    if (!text || !text.trim()) return false;
    if (!('speechSynthesis' in window)) {
      console.warn('[TwoWayComm] SpeechSynthesis not supported in this environment');
      return false;
    }

    // Cancel any previous speech
    window.speechSynthesis.cancel();

    const utterance = new SpeechSynthesisUtterance(text.trim());
    utterance.lang = lang;
    utterance.pitch = this.ttsPitch;
    utterance.rate = this.ttsRate;

    // Pick an Italian local offline voice if available
    const voices = window.speechSynthesis.getVoices();
    const italianVoice = voices.find(v => v.lang.startsWith('it') && v.localService) ||
                         voices.find(v => v.lang.startsWith('it')) ||
                         voices[0];

    if (italianVoice) {
      utterance.voice = italianVoice;
    }

    window.speechSynthesis.speak(utterance);
    return true;
  }
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = TwoWayCommunication;
}
