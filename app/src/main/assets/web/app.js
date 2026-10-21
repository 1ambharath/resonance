/**
 * Resonance Web Relay Client
 * Ultra-low latency PCM audio streaming over local WebSockets for iOS Safari, iPadOS, and desktop browsers.
 */

(function () {
  'use strict';

  // Constants
  const SAMPLE_RATE = 24000; // Native Resonance Android capture rate
  const RECONNECT_INTERVAL_MS = 2000;

  // Tiny base64 data URI of a 1-second silent WAV audio file.
  // Playing this in an HTML5 <audio> element forces iOS / iPadOS to switch
  // its audio session from AVAudioSessionCategoryAmbient to AVAudioSessionCategoryPlayback,
  // bypassing the hardware/Control Center Silent Mode switch.
  const SILENT_WAV_URI = 'data:audio/wav;base64,UklGRiQAAABXQVZFZm10IBAAAAABAAEARKwAAIhYAQACABAAZGF0YQAAAAA=';

  // DOM Elements
  const globalStatusPill = document.getElementById('globalStatusPill');
  const statusDot = document.getElementById('statusDot');
  const statusText = document.getElementById('statusText');

  const tabReceiver = document.getElementById('tabReceiver');
  const tabSender = document.getElementById('tabSender');
  const receiverSection = document.getElementById('receiverSection');
  const senderSection = document.getElementById('senderSection');

  const btnToggleListen = document.getElementById('btnToggleListen');
  const listenBtnIcon = document.getElementById('listenBtnIcon');
  const listenBtnText = document.getElementById('listenBtnText');
  const rxStreamBadge = document.getElementById('rxStreamBadge');
  const rxStatusDetail = document.getElementById('rxStatusDetail');
  const rxLevelBar = document.getElementById('rxLevelBar');
  const rxLevelText = document.getElementById('rxLevelText');

  const volumeSlider = document.getElementById('volumeSlider');
  const volumeValueBadge = document.getElementById('volumeValueBadge');
  const btnTestSound = document.getElementById('btnTestSound');
  const testSoundFeedback = document.getElementById('testSoundFeedback');

  const delaySlider = document.getElementById('delaySlider');
  const delayValueBadge = document.getElementById('delayValueBadge');
  const delayCushionLabel = document.getElementById('delayCushionLabel');
  const btnDelayMinus50 = document.getElementById('btnDelayMinus50');
  const btnDelayMinus10 = document.getElementById('btnDelayMinus10');
  const btnDelayReset = document.getElementById('btnDelayReset');
  const btnDelayPlus10 = document.getElementById('btnDelayPlus10');
  const btnDelayPlus50 = document.getElementById('btnDelayPlus50');
  const btnDelayMinus1 = document.getElementById('btnDelayMinus1');
  const btnDelayPlus1 = document.getElementById('btnDelayPlus1');

  const btnTogglePocket = document.getElementById('btnTogglePocket');
  const pocketOverlay = document.getElementById('pocketOverlay');
  const btnExitPocket = document.getElementById('btnExitPocket');

  const btnToggleWakeLock = document.getElementById('btnToggleWakeLock');
  const wakeLockStatus = document.getElementById('wakeLockStatus');
  const wakeLockIcon = document.getElementById('wakeLockIcon');

  const diagConn = document.getElementById('diagConn');
  const diagAudioState = document.getElementById('diagAudioState');
  const diagHardwareRate = document.getElementById('diagHardwareRate');
  const diagResampling = document.getElementById('diagResampling');
  const diagPackets = document.getElementById('diagPackets');
  const diagData = document.getElementById('diagData');
  const diagBuffer = document.getElementById('diagBuffer');

  const micSecureWarning = document.getElementById('micSecureWarning');
  const btnToggleBroadcast = document.getElementById('btnToggleBroadcast');
  const broadcastBtnText = document.getElementById('broadcastBtnText');
  const txStatusBadge = document.getElementById('txStatusBadge');
  const txLevelBar = document.getElementById('txLevelBar');
  const txLevelText = document.getElementById('txLevelText');

  // State
  let ws = null;
  let isWsConnected = false;
  let isListening = false;
  let isBroadcasting = false;
  let currentMode = 'receiver';

  let audioCtx = null;
  let masterGainNode = null;
  let silentAudioEl = null;
  let nextPlayTime = 0;
  let userDelayMs = 0;
  let masterVolume = 1.0;
  let consecutiveSilentPackets = 0;
  let needsReanchor = true;
  const baseJitterDelaySec = 0.080; // 80ms baseline jitter buffer (matches Android baseCushionMs = 80)

  let packetsCount = 0;
  let bytesCount = 0;

  let wakeLock = null;
  let wakeLockEnabled = true;

  let micStream = null;
  let micAudioCtx = null;
  let micProcessor = null;

  // Initialize
  function init() {
    setupTabSwitching();
    setupVolumeControls();
    setupDelayControls();
    setupPocketMode();
    setupWakeLock();
    checkSecurityContext();
    connectWebSocket();

    btnToggleListen.addEventListener('click', toggleListening);
    btnToggleBroadcast.addEventListener('click', toggleBroadcast);
  }

  // Check if browser environment supports microphone capture
  function checkSecurityContext() {
    const isSecure = window.isSecureContext || window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1';
    const hasMediaDevices = !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia);

    if (!isSecure || !hasMediaDevices) {
      if (micSecureWarning) {
        micSecureWarning.classList.remove('hidden');
      }
    }
  }

  // --- WebSocket Connection ---
  function connectWebSocket() {
    const loc = window.location;
    const wsProto = loc.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${wsProto}//${loc.host}/ws`;

    updateConnectionStatus('CONNECTING', 'warning');

    try {
      ws = new WebSocket(wsUrl);
      ws.binaryType = 'arraybuffer';

      ws.onopen = function () {
        isWsConnected = true;
        updateConnectionStatus('CONNECTED', 'connected');
        diagConn.textContent = 'Active (ws://' + loc.host + ')';
      };

      ws.onmessage = function (event) {
        if (event.data instanceof ArrayBuffer) {
          handleIncomingPcmPacket(event.data);
        }
      };

      ws.onclose = function () {
        isWsConnected = false;
        updateConnectionStatus('DISCONNECTED', 'disconnected');
        diagConn.textContent = 'Reconnecting...';
        setTimeout(connectWebSocket, RECONNECT_INTERVAL_MS);
      };

      ws.onerror = function () {
        try { ws.close(); } catch (_) {}
      };
    } catch (e) {
      setTimeout(connectWebSocket, RECONNECT_INTERVAL_MS);
    }
  }

  function updateConnectionStatus(text, state) {
    statusText.textContent = text;
    statusDot.className = 'status-dot';
    if (state === 'connected') {
      statusDot.classList.add('connected');
    } else if (state === 'disconnected') {
      statusDot.classList.add('disconnected');
    }
  }

  // --- Web Audio Initialization & iOS AudioSession Unlocker ---
  function ensureAudioContext() {
    if (!audioCtx) {
      const AudioContextClass = window.AudioContext || window.webkitAudioContext;
      audioCtx = new AudioContextClass();

      // Master Gain Node for volume control
      masterGainNode = audioCtx.createGain();
      masterGainNode.gain.value = masterVolume;
      masterGainNode.connect(audioCtx.destination);
    }

    if (audioCtx.state === 'suspended') {
      audioCtx.resume();
    }

    // iOS Audio Session Unlock Trick:
    // Play a silent audio element to force iOS into AVAudioSessionCategoryPlayback.
    // This allows sound to play through speakers/headphones even if the hardware Silent Switch is on.
    try {
      if (!silentAudioEl) {
        silentAudioEl = document.createElement('audio');
        silentAudioEl.setAttribute('playsinline', '');
        silentAudioEl.setAttribute('loop', '');
        silentAudioEl.src = SILENT_WAV_URI;
      }
      silentAudioEl.play().catch(() => {});
    } catch (_) {}

    // Prime the hardware output with a 1-sample silent buffer directly in the user gesture
    try {
      const scratch = audioCtx.createBuffer(1, 1, audioCtx.sampleRate);
      const scratchSource = audioCtx.createBufferSource();
      scratchSource.buffer = scratch;
      scratchSource.connect(audioCtx.destination);
      scratchSource.start(0);
    } catch (_) {}

    updateDiagnosticsCard();
  }

  function updateDiagnosticsCard() {
    if (audioCtx) {
      diagAudioState.textContent = audioCtx.state.toUpperCase();
      diagAudioState.style.color = audioCtx.state === 'running' ? '#166534' : '#D97706';
      diagHardwareRate.textContent = audioCtx.sampleRate.toLocaleString() + ' Hz';
      diagResampling.textContent = `24kHz → ${Math.round(audioCtx.sampleRate / 1000)}kHz (1:1 Native)`;
    }
  }

  function toggleListening() {
    ensureAudioContext();

    if (!isListening) {
      isListening = true;
      needsReanchor = true;
      consecutiveSilentPackets = 0;
      nextPlayTime = 0;
      btnToggleListen.classList.remove('btn-primary');
      btnToggleListen.classList.add('btn-secondary');
      listenBtnIcon.textContent = '⏸';
      listenBtnText.textContent = 'STOP LISTENING';
      rxStreamBadge.textContent = 'STREAMING';
      rxStreamBadge.className = 'pill-badge pill-success';
      rxStatusDetail.textContent = 'Live audio stream is active. Output routed to AirPods / Headphones / Speakers.';
      acquireWakeLock();
    } else {
      isListening = false;
      nextPlayTime = 0;
      btnToggleListen.classList.remove('btn-secondary');
      btnToggleListen.classList.add('btn-primary');
      listenBtnIcon.textContent = '▶';
      listenBtnText.textContent = 'START LISTENING';
      rxStreamBadge.textContent = 'PAUSED';
      rxStreamBadge.className = 'pill-badge';
      rxStatusDetail.textContent = 'Playout paused. Tap to resume listening.';
      rxLevelBar.style.width = '0%';
      rxLevelText.textContent = '-100 dBFS (0%)';
    }

    updateDiagnosticsCard();
  }

  // --- Linear Resampling Helper (24kHz -> Hardware Rate) ---
  function resampleLinear(inputSamples, inputRate, outputRate) {
    if (inputRate === outputRate || !outputRate) return inputSamples;
    const ratio = outputRate / inputRate;
    const outLength = Math.round(inputSamples.length * ratio);
    const output = new Float32Array(outLength);

    for (let i = 0; i < outLength; i++) {
      const srcPos = i / ratio;
      const srcIdx = Math.floor(srcPos);
      const frac = srcPos - srcIdx;
      const s0 = inputSamples[srcIdx] !== undefined ? inputSamples[srcIdx] : 0;
      const s1 = inputSamples[srcIdx + 1] !== undefined ? inputSamples[srcIdx + 1] : s0;
      output[i] = s0 + frac * (s1 - s0);
    }
    return output;
  }

  // --- Incoming Audio Packet Handler ---
  function handleIncomingPcmPacket(arrayBuffer) {
    packetsCount++;
    bytesCount += arrayBuffer.byteLength;

    if (packetsCount % 10 === 0) {
      diagPackets.textContent = packetsCount.toLocaleString();
      diagData.textContent = (bytesCount / (1024 * 1024)).toFixed(2) + ' MB';
      diagBuffer.textContent = userDelayMs + ' ms';
      updateDiagnosticsCard();
    }

    if (!isListening || !audioCtx) return;

    // Convert raw 16-bit little-endian PCM bytes to Float32 [-1.0 .. 1.0]
    const pcm16 = new Int16Array(arrayBuffer);
    const sampleCount = pcm16.length;
    if (sampleCount === 0) return;

    const float32Samples = new Float32Array(sampleCount);
    let sumSquares = 0;
    let peak = 0;

    for (let i = 0; i < sampleCount; i++) {
      const s = pcm16[i];
      const norm = s / 32768.0;
      float32Samples[i] = norm;
      sumSquares += (norm * norm);
      const absNorm = Math.abs(norm);
      if (absNorm > peak) peak = absNorm;
    }

    // Update real-time RMS meter (approx 20fps throttle)
    if (packetsCount % 3 === 0) {
      const rms = Math.sqrt(sumSquares / sampleCount);
      const rmsDbfs = rms > 0.00001 ? Math.max(-100, Math.round(20 * Math.log10(rms))) : -100;
      const peakPercent = Math.min(100, Math.round(peak * 100));

      rxLevelBar.style.width = peakPercent + '%';
      rxLevelText.textContent = `${rmsDbfs} dBFS (${peakPercent}%)`;
    }

    // Make sure context is active
    if (audioCtx.state === 'suspended') {
      audioCtx.resume();
    }

    // Resample incoming 24,000 Hz samples to match audioCtx.sampleRate (usually 48,000 Hz or 44,100 Hz).
    // This avoids Mobile Safari's internal resampler dropping or muting mismatched buffers!
    const resampledSamples = resampleLinear(float32Samples, SAMPLE_RATE, audioCtx.sampleRate);
    const audioBuffer = audioCtx.createBuffer(1, resampledSamples.length, audioCtx.sampleRate);
    audioBuffer.copyToChannel(resampledSamples, 0, 0);

    const source = audioCtx.createBufferSource();
    source.buffer = audioBuffer;
    source.connect(masterGainNode || audioCtx.destination);

    const baseCushionMs = 80;
    const targetDelaySec = Math.max(0.015, (baseCushionMs + userDelayMs) / 1000.0);
    const now = audioCtx.currentTime;

    const isPacketSilent = peak < 0.0002;
    if (isPacketSilent) {
      consecutiveSilentPackets++;
      // If sustained digital silence (>5 frames = 100ms), don't flood Web Audio with empty nodes;
      // ensure playback cursor snaps cleanly to target delay the instant active audio arrives
      if (consecutiveSilentPackets > 5) {
        needsReanchor = true;
        return;
      }
    } else {
      consecutiveSilentPackets = 0;
    }

    // Re-anchor nextPlayTime if transitioning from silence, or if fallen behind or drifted too far ahead
    if (needsReanchor || nextPlayTime <= now || nextPlayTime > (now + targetDelaySec + 0.25)) {
      nextPlayTime = now + targetDelaySec;
      needsReanchor = false;
    }

    source.start(nextPlayTime);
    nextPlayTime += audioBuffer.duration;
  }

  // --- Volume & Test Sound Controls ---
  function setupVolumeControls() {
    volumeSlider.addEventListener('input', function () {
      const val = parseInt(this.value, 10);
      masterVolume = val / 100.0;
      volumeValueBadge.textContent = val + '%';
      if (masterGainNode) {
        masterGainNode.gain.value = masterVolume;
      }
    });

    btnTestSound.addEventListener('click', playTestSound);
  }

  // Plays a pleasant short chime to verify hardware audio output
  function playTestSound() {
    ensureAudioContext();

    try {
      const osc = audioCtx.createOscillator();
      const gain = audioCtx.createGain();

      const now = audioCtx.currentTime;
      osc.type = 'sine';
      osc.frequency.setValueAtTime(523.25, now); // C5
      osc.frequency.setValueAtTime(659.25, now + 0.12); // E5
      osc.frequency.setValueAtTime(783.99, now + 0.24); // G5

      gain.gain.setValueAtTime(0.3 * masterVolume, now);
      gain.gain.exponentialRampToValueAtTime(0.001, now + 0.5);

      osc.connect(gain);
      gain.connect(audioCtx.destination);

      osc.start(now);
      osc.stop(now + 0.55);

      testSoundFeedback.textContent = 'Chime playing! If silent, check iPad mute.';
      testSoundFeedback.style.color = '#15803D';
      setTimeout(() => {
        testSoundFeedback.textContent = 'Tap to verify speakers';
        testSoundFeedback.style.color = '';
      }, 3000);
    } catch (e) {
      testSoundFeedback.textContent = 'Audio error: ' + e.message;
      testSoundFeedback.style.color = '#DC2626';
    }
  }

  // --- Delay Calibration Controls ---
  function setupDelayControls() {
    delaySlider.addEventListener('input', function () {
      setDelay(parseInt(this.value, 10));
    });

    btnDelayMinus50.addEventListener('click', () => adjustDelay(-50));
    btnDelayMinus10.addEventListener('click', () => adjustDelay(-10));
    btnDelayReset.addEventListener('click', () => setDelay(0));
    btnDelayPlus10.addEventListener('click', () => adjustDelay(10));
    btnDelayPlus50.addEventListener('click', () => adjustDelay(50));
    if (btnDelayMinus1) btnDelayMinus1.addEventListener('click', () => adjustDelay(-1));
    if (btnDelayPlus1) btnDelayPlus1.addEventListener('click', () => adjustDelay(1));
  }

  function adjustDelay(delta) {
    const newVal = Math.max(-500, Math.min(500, userDelayMs + delta));
    setDelay(newVal);
  }

  function setDelay(ms) {
    const prevDelayMs = userDelayMs;
    userDelayMs = ms;
    delaySlider.value = ms;
    const sign = ms > 0 ? '+' : '';
    delayValueBadge.textContent = `${sign}${ms} ms`;
    diagBuffer.textContent = `${sign}${ms} ms`;

    const baseCushionMs = 80;
    const cushion = Math.max(15, baseCushionMs + userDelayMs);
    if (delayCushionLabel) {
      delayCushionLabel.textContent = `Playout cushion: ${cushion} ms (${sign}${ms} ms sync offset)`;
    }

    // Dynamic buffer trimming on negative delay or adjustment:
    // Snaps playback playhead immediately without waiting for old queued delay
    if (audioCtx && isListening) {
      const now = audioCtx.currentTime;
      const targetDelaySec = cushion / 1000.0;
      if (ms < prevDelayMs || nextPlayTime > (now + targetDelaySec + 0.05)) {
        nextPlayTime = now + targetDelaySec;
      }
    }
  }

  // --- Tab Mode Switcher ---
  function setupTabSwitching() {
    tabReceiver.addEventListener('click', () => switchTab('receiver'));
    tabSender.addEventListener('click', () => switchTab('sender'));
  }

  function switchTab(mode) {
    currentMode = mode;
    if (mode === 'receiver') {
      tabReceiver.classList.add('active');
      tabSender.classList.remove('active');
      receiverSection.classList.remove('hidden');
      senderSection.classList.add('hidden');
    } else {
      tabSender.classList.add('active');
      tabReceiver.classList.remove('active');
      senderSection.classList.remove('hidden');
      receiverSection.classList.add('hidden');
      checkSecurityContext();
    }
  }

  // --- Broadcast Mode (Mic -> Android) ---
  async function toggleBroadcast() {
    if (!isBroadcasting) {
      // Pre-flight check for Secure Context (HTTPS or localhost)
      const isSecure = window.isSecureContext || window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1';
      if (!isSecure) {
        alert(
          'Microphone Broadcast is unavailable over insecure local HTTP (http://...).\n\n' +
          'Modern iOS/iPadOS browsers strictly require an HTTPS connection to access the microphone for privacy reasons.\n\n' +
          'Please use Listen Mode on this device to receive audio from Android.'
        );
        return;
      }

      if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
        alert('navigator.mediaDevices.getUserMedia is not supported or is blocked in this browser context.');
        return;
      }

      try {
        micStream = await navigator.mediaDevices.getUserMedia({
          audio: {
            sampleRate: SAMPLE_RATE,
            channelCount: 1,
            echoCancellation: true,
            noiseSuppression: true
          }
        });

        const AudioContextClass = window.AudioContext || window.webkitAudioContext;
        micAudioCtx = new AudioContextClass({ sampleRate: SAMPLE_RATE });
        const micSource = micAudioCtx.createMediaStreamSource(micStream);

        micProcessor = micAudioCtx.createScriptProcessor(1024, 1, 1);

        micProcessor.onaudioprocess = function (e) {
          if (!isBroadcasting || !ws || ws.readyState !== WebSocket.OPEN) return;

          const inputData = e.inputBuffer.getChannelData(0);
          const pcm16 = new Int16Array(inputData.length);
          let sumSquares = 0;
          let peak = 0;

          for (let i = 0; i < inputData.length; i++) {
            const s = Math.max(-1, Math.min(1, inputData[i]));
            pcm16[i] = s < 0 ? s * 0x8000 : s * 0x7FFF;
            sumSquares += (s * s);
            const abs = Math.abs(s);
            if (abs > peak) peak = abs;
          }

          ws.send(pcm16.buffer);

          const rms = Math.sqrt(sumSquares / inputData.length);
          const rmsDbfs = rms > 0.00001 ? Math.max(-100, Math.round(20 * Math.log10(rms))) : -100;
          const peakPercent = Math.min(100, Math.round(peak * 100));

          txLevelBar.style.width = peakPercent + '%';
          txLevelText.textContent = `${rmsDbfs} dBFS (${peakPercent}%)`;
        };

        micSource.connect(micProcessor);
        micProcessor.connect(micAudioCtx.destination);

        isBroadcasting = true;
        btnToggleBroadcast.classList.remove('btn-primary');
        btnToggleBroadcast.classList.add('btn-secondary');
        broadcastBtnText.textContent = 'STOP BROADCASTING';
        txStatusBadge.textContent = 'TRANSMITTING';
        txStatusBadge.className = 'pill-badge pill-success';
      } catch (err) {
        alert('Microphone access denied: ' + err.message);
      }
    } else {
      stopBroadcast();
    }
  }

  function stopBroadcast() {
    isBroadcasting = false;
    if (micStream) {
      micStream.getTracks().forEach(t => t.stop());
      micStream = null;
    }
    if (micProcessor) {
      try { micProcessor.disconnect(); } catch (_) {}
      micProcessor = null;
    }
    if (micAudioCtx) {
      try { micAudioCtx.close(); } catch (_) {}
      micAudioCtx = null;
    }

    btnToggleBroadcast.classList.remove('btn-secondary');
    btnToggleBroadcast.classList.add('btn-primary');
    broadcastBtnText.textContent = 'START MIC BROADCAST';
    txStatusBadge.textContent = 'IDLE';
    txStatusBadge.className = 'pill-badge';
    txLevelBar.style.width = '0%';
    txLevelText.textContent = '-100 dBFS (0%)';
  }

  // --- OLED Cinema / Pocket Mode ---
  function setupPocketMode() {
    btnTogglePocket.addEventListener('click', () => {
      pocketOverlay.classList.remove('hidden');
    });

    btnExitPocket.addEventListener('click', (e) => {
      e.stopPropagation();
      pocketOverlay.classList.add('hidden');
    });

    pocketOverlay.addEventListener('click', () => {
      pocketOverlay.classList.add('hidden');
    });
  }

  // --- Screen Wake Lock API ---
  function setupWakeLock() {
    btnToggleWakeLock.addEventListener('click', () => {
      wakeLockEnabled = !wakeLockEnabled;
      if (wakeLockEnabled) {
        acquireWakeLock();
      } else {
        releaseWakeLock();
      }
    });

    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible' && wakeLockEnabled && isListening) {
        acquireWakeLock();
      }
    });
  }

  async function acquireWakeLock() {
    if ('wakeLock' in navigator && wakeLockEnabled) {
      try {
        wakeLock = await navigator.wakeLock.request('screen');
        wakeLockStatus.textContent = 'Keep Screen On: Active';
        wakeLockIcon.textContent = '🔒';
        wakeLock.addEventListener('release', () => {
          if (wakeLockEnabled && isListening && document.visibilityState === 'visible') {
            acquireWakeLock();
          }
        });
      } catch (_) {
        wakeLockStatus.textContent = 'Keep Screen On: Dimmed';
      }
    }
  }

  function releaseWakeLock() {
    if (wakeLock) {
      try { wakeLock.release(); } catch (_) {}
      wakeLock = null;
    }
    wakeLockStatus.textContent = 'Keep Screen On: Off';
    wakeLockIcon.textContent = '🔓';
  }

  // Run on page load
  window.addEventListener('DOMContentLoaded', init);
})();
