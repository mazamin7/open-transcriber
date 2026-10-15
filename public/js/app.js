/**
 * Google Live Transcribe Clone - Main Application Orchestrator
 * Connects speech recognition, decibel analyzer, two-way deaf communication,
 * accessibility controls, and offline storage into an integrated experience.
 */

document.addEventListener('DOMContentLoaded', async () => {
  // DOM References
  const micBtn = document.getElementById('mic-btn');
  const keyboardBtn = document.getElementById('keyboard-btn');
  const clearScreenBtn = document.getElementById('clear-screen-btn');
  const resumeScrollChip = document.getElementById('resume-scroll-chip');
  const transcriptViewport = document.getElementById('transcript-viewport');
  const transcriptContent = document.getElementById('transcript-content');
  const finalTextEl = document.getElementById('final-text');
  const interimTextEl = document.getElementById('interim-text');
  const emptyState = document.getElementById('empty-state');

  // Top Bar References
  const bubbleOuterRing = document.getElementById('bubble-outer-ring');
  const bubbleCenterDot = document.getElementById('bubble-center-dot');
  const soundEventPill = document.getElementById('sound-event-pill');
  const settingsBtn = document.getElementById('settings-btn');
  const historyBtn = document.getElementById('history-btn');

  // Two-Way Tray References
  const typeBackTray = document.getElementById('type-back-tray');
  const closeTrayBtn = document.getElementById('close-tray-btn');
  const flipBtn = document.getElementById('flip-btn');
  const speakBtn = document.getElementById('speak-btn');
  const sendReplyBtn = document.getElementById('send-reply-btn');
  const typeBackInputBox = document.getElementById('type-back-input-box');
  const typeBackTextarea = document.getElementById('type-back-textarea');
  const quickChipsContainer = document.getElementById('quick-chips-container');
  const addChipBtn = document.getElementById('add-chip-btn');

  // Settings Modal References
  const settingsModal = document.getElementById('settings-modal');
  const closeSettingsBtn = document.getElementById('close-settings-btn');
  const fontSizeSlider = document.getElementById('font-size-slider');
  const fontSizeValue = document.getElementById('font-size-value');
  const themeSelect = document.getElementById('theme-select');
  const engineSelect = document.getElementById('engine-select');
  const langSelect = document.getElementById('lang-select');
  const hapticsToggle = document.getElementById('haptics-toggle');
  const wakelockToggle = document.getElementById('wakelock-toggle');
  const wsUrlRow = document.getElementById('ws-url-row');
  const wsUrlInput = document.getElementById('ws-url-input');

  // History Modal References
  const historyModal = document.getElementById('history-modal');
  const closeHistoryBtn = document.getElementById('close-history-btn');
  const historySearch = document.getElementById('history-search');
  const historyList = document.getElementById('history-list');
  const exportTxtBtn = document.getElementById('export-txt-btn');
  const exportJsonBtn = document.getElementById('export-json-btn');
  const shredDataBtn = document.getElementById('shred-data-btn');

  // Auto-scroll tracking
  let isAutoScrollLocked = true;
  let sessionStartTime = null;

  // Initialize Subsystems
  const storage = new LocalTranscriptStorage();
  await storage.init().catch(e => console.warn('[App] Storage fallback:', e));

  const accessibility = new AccessibilityController();
  accessibility.init();

  const twoWay = new TwoWayCommunication({
    onSendResponse: (text) => appendUserReplyToTranscript(text)
  });

  // Audio Analyzer
  const analyzer = new AudioAnalyzer({
    onLevelChange: (normalizedLevel, db) => {
      // Scale bubble outer ring (0.6 to 2.2 scale)
      const scale = 0.6 + (normalizedLevel * 1.6);
      bubbleOuterRing.style.transform = `scale(${scale})`;

      // Change color if approaching clipping / loud
      if (normalizedLevel > 0.75) {
        bubbleOuterRing.style.backgroundColor = 'rgba(255, 171, 0, 0.4)';
        bubbleCenterDot.style.backgroundColor = 'var(--accent-warn)';
      } else {
        bubbleOuterRing.style.backgroundColor = 'rgba(0, 230, 118, 0.3)';
        bubbleCenterDot.style.backgroundColor = 'var(--accent-color)';
      }
    },
    onEventDetected: (event) => {
      soundEventPill.textContent = event;
      soundEventPill.className = 'sound-event-pill';
      if (event === 'Speech') {
        soundEventPill.classList.add('event-speech');
      } else if (event === 'Applause') {
        soundEventPill.classList.add('event-applause');
      } else if (event === 'Music') {
        soundEventPill.classList.add('event-music');
      } else if (event === 'Loud Noise') {
        soundEventPill.classList.add('event-loud');
      }
    }
  });

  // Speech Recognition Engine
  const speech = new SpeechEngine({
    engineType: localStorage.getItem('transcribe_engine') || 'webspeech',
    language: localStorage.getItem('transcribe_lang') || 'en-US',
    wsUrl: localStorage.getItem('transcribe_ws_url') || 'ws://localhost:8765',
    onResult: (result) => {
      // Trigger haptic pulse when new speech starts
      if (result.interim || result.final) {
        accessibility.notifySpeechOnset();
      }

      // Hide empty state if there's text
      if (result.full.trim()) {
        emptyState.style.display = 'none';
      }

      finalTextEl.textContent = speech.finalTranscript;
      interimTextEl.textContent = speech.interimTranscript;

      if (isAutoScrollLocked) {
        scrollToBottom();
      }
    },
    onError: (err) => {
      console.warn('[App] Speech Engine Error:', err.message);
      soundEventPill.textContent = 'Mic Error';
      soundEventPill.className = 'sound-event-pill event-loud';
    },
    onStateChange: (state) => {
      if (state === 'listening') {
        micBtn.classList.add('active');
        bubbleCenterDot.classList.remove('idle');
      } else {
        micBtn.classList.remove('active');
        bubbleCenterDot.classList.add('idle');
      }
    }
  });

  /* -------------------------------------------------------------
   * Auto-scroll Behavior
   * ------------------------------------------------------------- */
  function scrollToBottom() {
    transcriptViewport.scrollTop = transcriptViewport.scrollHeight;
  }

  transcriptViewport.addEventListener('scroll', () => {
    const isAtBottom = transcriptViewport.scrollHeight - transcriptViewport.scrollTop <= transcriptViewport.clientHeight + 40;
    if (isAtBottom) {
      isAutoScrollLocked = true;
      resumeScrollChip.classList.remove('visible');
    } else {
      isAutoScrollLocked = false;
      if (speech.isListening && (speech.finalTranscript || speech.interimTranscript)) {
        resumeScrollChip.classList.add('visible');
      }
    }
  });

  resumeScrollChip.addEventListener('click', () => {
    isAutoScrollLocked = true;
    scrollToBottom();
    resumeScrollChip.classList.remove('visible');
  });

  /* -------------------------------------------------------------
   * Microphone Toggle
   * ------------------------------------------------------------- */
  micBtn.addEventListener('click', async () => {
    if (speech.isListening) {
      // Stop session
      speech.stop();
      analyzer.stop();
      bubbleOuterRing.style.transform = 'scale(0.6)';

      // Auto-save session if text exists
      if (speech.finalTranscript.trim()) {
        const duration = sessionStartTime ? Math.round((Date.now() - sessionStartTime) / 1000) : 0;
        await storage.saveConversation(speech.finalTranscript, duration);
      }
      sessionStartTime = null;
    } else {
      // Start session
      try {
        sessionStartTime = Date.now();
        await analyzer.start();
        speech.start();
      } catch (err) {
        alert('Could not access microphone. Please grant microphone permissions in your browser.');
      }
    }
  });

  /* -------------------------------------------------------------
   * Screen Clear & Conversation Reset
   * ------------------------------------------------------------- */
  clearScreenBtn.addEventListener('click', async () => {
    if (speech.finalTranscript.trim()) {
      const duration = sessionStartTime ? Math.round((Date.now() - sessionStartTime) / 1000) : 0;
      await storage.saveConversation(speech.finalTranscript, duration);
    }
    speech.clearTranscript();
    finalTextEl.textContent = '';
    interimTextEl.textContent = '';
    emptyState.style.display = 'flex';
    sessionStartTime = Date.now();
  });

  /* -------------------------------------------------------------
   * Two-Way Deaf Communication & Type-Back
   * ------------------------------------------------------------- */
  keyboardBtn.addEventListener('click', () => {
    typeBackTray.classList.toggle('open');
    if (typeBackTray.classList.contains('open')) {
      typeBackTextarea.focus();
    }
  });

  closeTrayBtn.addEventListener('click', () => {
    typeBackTray.classList.remove('open');
  });

  flipBtn.addEventListener('click', () => {
    const isFlipped = twoWay.toggleFlip(typeBackInputBox);
    flipBtn.style.color = isFlipped ? 'var(--accent-color)' : 'var(--text-primary)';
  });

  speakBtn.addEventListener('click', () => {
    const text = typeBackTextarea.value.trim();
    if (text) {
      twoWay.speakText(text, speech.language);
    }
  });

  sendReplyBtn.addEventListener('click', () => {
    const text = typeBackTextarea.value.trim();
    if (text) {
      appendUserReplyToTranscript(text);
      if (document.getElementById('speak-on-send-toggle')?.checked) {
        twoWay.speakText(text, speech.language);
      }
      typeBackTextarea.value = '';
      typeBackTray.classList.remove('open');
    }
  });

  function appendUserReplyToTranscript(text) {
    emptyState.style.display = 'none';
    const replyNode = document.createElement('div');
    replyNode.style.cssText = `
      margin: 12px 0;
      padding: 10px 16px;
      border-radius: 12px;
      background: rgba(0, 176, 255, 0.15);
      border-left: 4px solid var(--accent-blue);
      color: var(--accent-blue);
      font-size: 0.9em;
      font-weight: 600;
    `;
    replyNode.textContent = `[Reply]: ${text}`;
    transcriptContent.insertBefore(replyNode, finalTextEl);
    scrollToBottom();
  }

  /* -------------------------------------------------------------
   * Quick Response Chips
   * ------------------------------------------------------------- */
  function renderQuickChips() {
    quickChipsContainer.innerHTML = '';
    twoWay.customChips.forEach(chipText => {
      const chip = document.createElement('button');
      chip.className = 'quick-chip';
      chip.textContent = chipText;
      chip.addEventListener('click', () => {
        typeBackTextarea.value = chipText;
        typeBackTray.classList.add('open');
      });
      quickChipsContainer.appendChild(chip);
    });
  }

  addChipBtn.addEventListener('click', () => {
    const custom = prompt('Enter a custom quick phrase to save:');
    if (custom && custom.trim()) {
      twoWay.addCustomChip(custom);
      renderQuickChips();
    }
  });

  renderQuickChips();

  /* -------------------------------------------------------------
   * Settings Modal Handling
   * ------------------------------------------------------------- */
  settingsBtn.addEventListener('click', () => {
    // Populate values
    fontSizeSlider.value = accessibility.fontSize;
    fontSizeValue.textContent = `${accessibility.fontSize}px`;
    themeSelect.value = accessibility.theme;
    engineSelect.value = speech.engineType;
    langSelect.value = speech.language;
    hapticsToggle.checked = accessibility.isHapticsEnabled;
    wakelockToggle.checked = accessibility.isWakeLockEnabled;
    wsUrlInput.value = speech.wsUrl;
    wsUrlRow.style.display = speech.engineType === 'websocket' ? 'flex' : 'none';

    settingsModal.classList.add('open');
  });

  closeSettingsBtn.addEventListener('click', () => {
    settingsModal.classList.remove('open');
  });

  fontSizeSlider.addEventListener('input', (e) => {
    const size = parseInt(e.target.value, 10);
    fontSizeValue.textContent = `${size}px`;
    accessibility.applyFontSize(size);
  });

  themeSelect.addEventListener('change', (e) => {
    accessibility.applyTheme(e.target.value);
  });

  engineSelect.addEventListener('change', (e) => {
    const selected = e.target.value;
    wsUrlRow.style.display = selected === 'websocket' ? 'flex' : 'none';
    speech.setEngineType(selected, wsUrlInput.value.trim());
    localStorage.setItem('transcribe_engine', selected);
  });

  wsUrlInput.addEventListener('change', (e) => {
    const url = e.target.value.trim();
    speech.wsUrl = url;
    localStorage.setItem('transcribe_ws_url', url);
  });

  langSelect.addEventListener('change', (e) => {
    speech.setLanguage(e.target.value);
    localStorage.setItem('transcribe_lang', e.target.value);
  });

  hapticsToggle.addEventListener('change', (e) => {
    accessibility.setHapticsEnabled(e.target.checked);
  });

  wakelockToggle.addEventListener('change', (e) => {
    accessibility.setWakeLockEnabled(e.target.checked);
  });

  /* -------------------------------------------------------------
   * History Modal Handling
   * ------------------------------------------------------------- */
  historyBtn.addEventListener('click', async () => {
    await loadHistoryList();
    historyModal.classList.add('open');
  });

  closeHistoryBtn.addEventListener('click', () => {
    historyModal.classList.remove('open');
  });

  async function loadHistoryList(filter = '') {
    historyList.innerHTML = '';
    const records = await storage.searchConversations(filter);
    
    if (records.length === 0) {
      historyList.innerHTML = '<div style="color:var(--text-secondary); text-align:center; padding:20px;">No saved conversations yet.</div>';
      return;
    }

    records.forEach(rec => {
      const item = document.createElement('div');
      item.className = 'history-item';
      
      const dateStr = new Date(rec.timestamp).toLocaleString();
      item.innerHTML = `
        <div class="history-item-date">${dateStr} · ${rec.wordCount || 0} words</div>
        <div class="history-item-preview">${rec.text}</div>
        <div class="history-item-actions">
          <button class="btn-secondary copy-history-btn" style="padding:6px 12px; font-size:12px;">Copy</button>
          <button class="btn-secondary delete-history-btn" style="padding:6px 12px; font-size:12px; color:#ff5252;">Delete</button>
        </div>
      `;

      item.querySelector('.copy-history-btn').addEventListener('click', () => {
        navigator.clipboard.writeText(rec.text);
        alert('Copied conversation to clipboard!');
      });

      item.querySelector('.delete-history-btn').addEventListener('click', async () => {
        if (confirm('Delete this conversation?')) {
          await storage.deleteConversation(rec.id);
          await loadHistoryList(historySearch.value);
        }
      });

      historyList.appendChild(item);
    });
  }

  historySearch.addEventListener('input', (e) => {
    loadHistoryList(e.target.value);
  });

  exportTxtBtn.addEventListener('click', () => storage.exportData('txt'));
  exportJsonBtn.addEventListener('click', () => storage.exportData('json'));

  shredDataBtn.addEventListener('click', async () => {
    if (confirm('CRITICAL PRIVACY WIPE: This will permanently shred and wipe all local transcripts. Are you sure?')) {
      await storage.shredAllData();
      await storage.init();
      await loadHistoryList();
      alert('All conversation data has been permanently wiped.');
    }
  });

  /* -------------------------------------------------------------
   * Service Worker Registration (100% Offline PWA)
   * ------------------------------------------------------------- */
  if ('serviceWorker' in navigator) {
    try {
      const reg = await navigator.serviceWorker.register('./sw.js');
      console.log('[App] Offline Service Worker registered:', reg.scope);
    } catch (e) {
      console.warn('[App] Service worker registration error:', e);
    }
  }
});
