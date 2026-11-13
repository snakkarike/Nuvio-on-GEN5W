// Nuvio on GEN5W - Bridge Test Harness (ES5 compatible for Android 4.4 / Chromium 33)
(function() {
    'use strict';

    // DOM Elements
    var streamUrlInput = document.getElementById('streamUrlInput');
    var btnPresetMp4 = document.getElementById('btnPresetMp4');
    var btnPresetHls = document.getElementById('btnPresetHls');
    var btnLoadStream = document.getElementById('btnLoadStream');

    var btnPlayPause = document.getElementById('btnPlayPause');
    var btnRewind = document.getElementById('btnRewind');
    var btnForward = document.getElementById('btnForward');
    var btnStop = document.getElementById('btnStop');

    var seekSlider = document.getElementById('seekSlider');
    var currentTimeLabel = document.getElementById('currentTimeLabel');
    var durationLabel = document.getElementById('durationLabel');
    var volumeSlider = document.getElementById('volumeSlider');

    var parkStatusBadge = document.getElementById('parkStatusBadge');
    var parkStatusText = document.getElementById('parkStatusText');
    var gateCard = document.getElementById('gateCard');
    var gateStateHeader = document.getElementById('gateStateHeader');
    var gateStateDesc = document.getElementById('gateStateDesc');

    var btnSimulateDrive = document.getElementById('btnSimulateDrive');
    var btnSimulatePark = document.getElementById('btnSimulatePark');
    var logConsole = document.getElementById('logConsole');

    var aspectButtons = document.querySelectorAll('.btn-toggle');

    // Local State
    var isPlaying = false;
    var currentDuration = 0;
    var currentPosition = 0;
    var isUserSeeking = false;

    // Presets
    var PRESET_MP4 = 'https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4';
    var PRESET_HLS = 'https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8';

    function log(message, type) {
        type = type || 'info';
        var line = document.createElement('div');
        line.className = 'log-line ' + type;
        var now = new Date();
        var timeStr = (now.getHours() < 10 ? '0' : '') + now.getHours() + ':' +
                      (now.getMinutes() < 10 ? '0' : '') + now.getMinutes() + ':' +
                      (now.getSeconds() < 10 ? '0' : '') + now.getSeconds();
        line.textContent = '[' + timeStr + '] ' + message;
        logConsole.appendChild(line);
        logConsole.scrollTop = logConsole.scrollHeight;

        if (window.NativePlayer && window.NativePlayer.log) {
            try {
                window.NativePlayer.log('[' + type + '] ' + message);
            } catch (e) {}
        }
    }

    function formatTime(ms) {
        if (!ms || isNaN(ms)) return '00:00';
        var totalSec = Math.floor(ms / 1000);
        var hours = Math.floor(totalSec / 3600);
        var mins = Math.floor((totalSec % 3600) / 60);
        var secs = totalSec % 60;

        var minStr = (mins < 10 ? '0' : '') + mins;
        var secStr = (secs < 10 ? '0' : '') + secs;

        if (hours > 0) {
            var hrStr = (hours < 10 ? '0' : '') + hours;
            return hrStr + ':' + minStr + ':' + secStr;
        }
        return minStr + ':' + secStr;
    }

    function updateParkUi(isParked, reason) {
        if (isParked) {
            parkStatusBadge.className = 'park-indicator';
            parkStatusText.textContent = 'PARKED';
            gateCard.className = 'gate-status-card';
            gateStateHeader.textContent = 'Vehicle Stationary (In Park)';
            gateStateDesc.textContent = reason || 'Hardware video decoding active. Native overlay rendered.';
        } else {
            parkStatusBadge.className = 'park-indicator unparked';
            parkStatusText.textContent = 'DRIVING';
            gateCard.className = 'gate-status-card blocked';
            gateStateHeader.textContent = 'Vehicle in Motion / Gear Active';
            gateStateDesc.textContent = reason || 'Safety regulation active. Video display blocked on overlay.';
        }
    }

    // Bridge Native Callbacks
    window.onNativePlayerEvent = function(eventName, data) {
        data = data || {};
        log('Bridge event: ' + eventName + ' ' + JSON.stringify(data), 'event');

        switch (eventName) {
            case 'stateChanged':
                var state = data.playbackState;
                var playWhenReady = data.playWhenReady;
                // ExoPlayer states: 1=IDLE, 2=BUFFERING, 3=READY, 4=ENDED
                if (state === 3 && playWhenReady) {
                    isPlaying = true;
                    btnPlayPause.textContent = '\u275A\u275A Pause';
                } else {
                    isPlaying = false;
                    btnPlayPause.textContent = '\u25BA Play';
                }
                break;

            case 'timeupdate':
                currentPosition = data.position || 0;
                currentDuration = data.duration || 0;
                currentTimeLabel.textContent = formatTime(currentPosition);
                durationLabel.textContent = formatTime(currentDuration);

                if (!isUserSeeking && currentDuration > 0) {
                    seekSlider.value = Math.floor((currentPosition / currentDuration) * 100);
                }
                break;

            case 'parkStateChanged':
                updateParkUi(data.isParked, data.reason);
                break;

            case 'error':
                log('Playback error: ' + (data.error || 'Unknown error'), 'error');
                break;

            case 'stopped':
                isPlaying = false;
                btnPlayPause.textContent = '\u25BA Play';
                currentTimeLabel.textContent = '00:00';
                seekSlider.value = 0;
                break;
        }
    };

    // Event Listeners for UI
    btnPresetMp4.addEventListener('click', function() {
        streamUrlInput.value = PRESET_MP4;
        log('Selected MP4 preset: ' + PRESET_MP4);
    });

    btnPresetHls.addEventListener('click', function() {
        streamUrlInput.value = PRESET_HLS;
        log('Selected HLS preset: ' + PRESET_HLS);
    });

    btnLoadStream.addEventListener('click', function() {
        var url = streamUrlInput.value.trim();
        if (!url) {
            log('No URL provided', 'warn');
            return;
        }

        var payload = {
            url: url,
            title: 'Sample Stream',
            position: 0,
            headers: {
                'User-Agent': 'Nuvio-GEN5W/1.0'
            }
        };

        log('Invoking NativePlayer.load()');
        if (window.NativePlayer && window.NativePlayer.load) {
            window.NativePlayer.load(JSON.stringify(payload));
        } else {
            log('NativePlayer bridge not detected on window', 'warn');
        }
    });

    btnPlayPause.addEventListener('click', function() {
        if (!window.NativePlayer) return;
        window.NativePlayer.toggle();
    });

    btnRewind.addEventListener('click', function() {
        if (!window.NativePlayer) return;
        var target = Math.max(0, currentPosition - 10000);
        window.NativePlayer.seek(target);
        log('Seek -10s to ' + formatTime(target));
    });

    btnForward.addEventListener('click', function() {
        if (!window.NativePlayer) return;
        var target = Math.min(currentDuration, currentPosition + 10000);
        window.NativePlayer.seek(target);
        log('Seek +10s to ' + formatTime(target));
    });

    btnStop.addEventListener('click', function() {
        if (!window.NativePlayer) return;
        window.NativePlayer.stop();
        log('Invoked NativePlayer.stop()');
    });

    seekSlider.addEventListener('mousedown', function() { isUserSeeking = true; });
    seekSlider.addEventListener('touchstart', function() { isUserSeeking = true; });

    seekSlider.addEventListener('change', function() {
        isUserSeeking = false;
        if (!window.NativePlayer || currentDuration <= 0) return;
        var seekRatio = seekSlider.value / 100;
        var targetMs = Math.floor(currentDuration * seekRatio);
        window.NativePlayer.seek(targetMs);
        log('Seek to ' + formatTime(targetMs));
    });

    seekSlider.addEventListener('mouseup', function() { isUserSeeking = false; });
    seekSlider.addEventListener('touchend', function() { isUserSeeking = false; });

    volumeSlider.addEventListener('input', function() {
        if (!window.NativePlayer) return;
        var vol = volumeSlider.value / 100;
        window.NativePlayer.setVolume(vol);
    });

    // Aspect Ratio Toggle
    for (var i = 0; i < aspectButtons.length; i++) {
        aspectButtons[i].addEventListener('click', function() {
            for (var j = 0; j < aspectButtons.length; j++) {
                aspectButtons[j].className = 'btn-toggle';
            }
            this.className = 'btn-toggle active';
            var aspect = this.getAttribute('data-aspect');
            if (window.NativePlayer && window.NativePlayer.setAspectRatio) {
                window.NativePlayer.setAspectRatio(aspect);
                log('Aspect ratio changed to ' + aspect);
            }
        });
    }

    // Vehicle Safety Simulation
    btnSimulateDrive.addEventListener('click', function() {
        if (window.NativePlayer && window.NativePlayer.setMockParked) {
            window.NativePlayer.setMockParked(false);
            log('Triggered Mock: Vehicle IN-MOTION (Drive)', 'warn');
        }
    });

    btnSimulatePark.addEventListener('click', function() {
        if (window.NativePlayer && window.NativePlayer.setMockParked) {
            window.NativePlayer.setMockParked(true);
            log('Triggered Mock: Vehicle PARKED', 'info');
        }
    });

    // Initial check
    setTimeout(function() {
        if (window.NativePlayer) {
            log('Connected to NativePlayer bridge successfully.', 'info');
            if (window.NativePlayer.isParked) {
                var parked = window.NativePlayer.isParked();
                updateParkUi(parked, parked ? 'Vehicle in PARK' : 'Vehicle moving/gear engaged');
            }
        } else {
            log('Running in browser or bridge not ready yet.', 'warn');
        }
    }, 500);

})();

