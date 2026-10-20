/**
 * Resonance Web Relay Client
 * Ultra-low latency PCM audio streaming over local WebSockets for iOS Safari.
 */

(function () {
  'use strict';

  // Constants
  const SAMPLE_RATE = 24000; // Native Resonance Android capture rate
  const RECONNECT_INTERVAL_MS = 2000;

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

  const delaySlider = document.getElementById('delaySlider');
  const delayValueBadge = document.getElementById('delayValueBadge');
  const btnDelayMinus50 = document.getElementById('btnDelayMinus50');
  const btnDelayMinus10 = document.getElementById('btnDelayMinus10');
  const btnDelayReset = document.getElementById('btnDelayReset');
  const btnDelayPlus10 = document.getElementById('btnDelayPlus10');
  const btnDelayPlus50 = document.getElementById('btnDelayPlus50');

  const btnTogglePocket = document.getElementById('btnTogglePocket');
  const pocketOverlay = document.getElementById('pocketOverlay');
  const btnExitPocket = document.getElementById('btnExitPocket');

  const btnToggleWakeLock = document.getElementById('btnToggleWakeLock');
  const wakeLockStatus = document.getElementById('wakeLockStatus');
  const wakeLockIcon = document.getElementById('wakeLockIcon');

  const diagConn = document.getElementById('diagConn');
  const diagPackets = document.getElementById('diagPackets');
  const diagData = document.getElementById('diagData');
  const diagBuffer = document.getElementById('diagBuffer');

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
  let currentMode = 'receiver'; // 'receiver' or 'sender'

  let audioCtx = null;
  let nextPlayTime = 0;
  let userDelayMs = 0;
  const baseJitterDelaySec = 0.035; // 35ms baseline jitter buffer

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
    setupDelayControls();
    setupPocketMode();
    setupWakeLock();
    connectWebSocket();

    btnToggleListen.addEventListener('click', toggleListening);
    btnToggleBroadcast.addEventListener('click', toggleBroadcast);
  }

  // --- WebSocket Connection ---
  function connectWebSocket() {
    const loc = window.location;
    const wsProto = loc.protocol === 'https:' ? 'wss:' : 'ws:';
    // Connect to same host:port with /ws path
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

  // --- Audio Playout Engine (Web Audio API) ---
  function initAudioContext() {
    if (!audioCtx) {
      const AudioContextClass = window.AudioContext || window.webkitAudioContext;
      audioCtx = new AudioContextClass();
    }
    if (audioCtx.state === 'suspended') {
      audioCtx.resume();
    }
  }

  function toggleListening() {
    initAudioContext();

    if (!isListening) {
      isListening = true;
      btnToggleListen.classList.remove('btn-primary');
      btnToggleListen.classList.add('btn-secondary');
      listenBtnIcon.textContent = '⏸';
      listenBtnText.textContent = 'STOP LISTENING';
      rxStreamBadge.textContent = 'STREAMING';
      rxStreamBadge.className = 'pill-badge pill-success';
      rxStatusDetail.textContent = 'Live audio stream is active. Output routed to AirPods / Headphones.';
      acquireWakeLock();
    } else {
      isListening = false;
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
  }

  function handleIncomingPcmPacket(arrayBuffer) {
    packetsCount++;
    bytesCount += arrayBuffer.byteLength;

    if (packetsCount % 10 === 0) {
      diagPackets.textContent = packetsCount.toLocaleString();
      diagData.textContent = (bytesCount / (1024 * 1024)).toFixed(2) + ' MB';
      diagBuffer.textContent = userDelayMs + ' ms';
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

    // Create an AudioBuffer and schedule playout
    // Native Resonance stream is 24kHz Mono
    const audioBuffer = audioCtx.createBuffer(1, sampleCount, SAMPLE_RATE);
    audioBuffer.copyToChannel(float32Samples, 0, 0);

    const source = audioCtx.createBufferSource();
    source.buffer = audioBuffer;
    source.connect(audioCtx.destination);

    const now = audioCtx.currentTime;
    const targetDelaySec = baseJitterDelaySec + (userDelayMs / 1000.0);

    // If nextPlayTime is in the past or way in future, re-anchor with target delay
    if (nextPlayTime < now || nextPlayTime > (now + targetDelaySec + 0.5)) {
      nextPlayTime = now + targetDelaySec;
    }

    source.start(nextPlayTime);
    nextPlayTime += audioBuffer.duration;
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
  }

  function adjustDelay(delta) {
    const newVal = Math.max(0, Math.min(500, userDelayMs + delta));
    setDelay(newVal);
  }

  function setDelay(ms) {
    userDelayMs = ms;
    delaySlider.value = ms;
    delayValueBadge.textContent = ms + ' ms';
    diagBuffer.textContent = ms + ' ms';
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
    }
  }

  // --- Broadcast Mode (Mic -> Android) ---
  async function toggleBroadcast() {
    if (!isBroadcasting) {
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

        // Use ScriptProcessor (512 or 1024 samples) to capture PCM
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
        alert('Microphone access denied or unavailable: ' + err.message);
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
