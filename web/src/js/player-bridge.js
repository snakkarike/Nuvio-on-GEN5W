/**
 * Nuvio on GEN5W - Player Bridge Adapter
 *
 * Integrates directly with window.NativePlayer (@JavascriptInterface).
 * Replaces standard HTML5 <video> with native hardware-accelerated ExoPlayer.
 */
(function(window) {
    'use strict';

    function PlayerBridge() {
        this.listeners = [];
        this.isPlaying = false;
        this.currentPosition = 0;
        this.duration = 0;
        this.isParked = true;
        this.currentMedia = null;

        this.initBridge();
    }

    PlayerBridge.prototype.initBridge = function() {
        var self = this;

        // Register the global event dispatcher called by NativePlaybackBridge.java
        window.onNativePlayerEvent = function(eventName, data) {
            data = data || {};
            self.handleNativeEvent(eventName, data);
        };

        // Query initial park state if bridge is ready
        setTimeout(function() {
            if (window.NativePlayer && window.NativePlayer.isParked) {
                try {
                    self.isParked = window.NativePlayer.isParked();
                    self.emit('parkChanged', { isParked: self.isParked });
                } catch (e) {}
            }
        }, 300);
    };

    PlayerBridge.prototype.handleNativeEvent = function(eventName, data) {
        var self = this;

        switch (eventName) {
            case 'stateChanged':
                // ExoPlayer: 3 = STATE_READY, playWhenReady
                var ready = (data.playbackState === 3);
                self.isPlaying = ready && !!data.playWhenReady;
                self.emit('playStateChanged', { isPlaying: self.isPlaying });
                break;

            case 'timeupdate':
                self.currentPosition = data.position || 0;
                self.duration = data.duration || 0;
                self.emit('timeupdate', {
                    position: self.currentPosition,
                    duration: self.duration
                });
                break;

            case 'parkStateChanged':
                self.isParked = !!data.isParked;
                self.emit('parkChanged', {
                    isParked: self.isParked,
                    reason: data.reason || ''
                });
                break;

            case 'loaded':
                self.emit('loaded', data);
                break;

            case 'stopped':
                self.isPlaying = false;
                self.currentPosition = 0;
                self.emit('stopped', {});
                break;

            case 'error':
                self.emit('error', { error: data.error || 'Unknown playback error' });
                break;
        }
    };

    PlayerBridge.prototype.addListener = function(callback) {
        this.listeners.push(callback);
    };

    PlayerBridge.prototype.emit = function(event, data) {
        for (var i = 0; i < this.listeners.length; i++) {
            try {
                this.listeners[i](event, data);
            } catch (e) {
                console.error('PlayerBridge listener error:', e);
            }
        }
    };

    PlayerBridge.prototype.loadMedia = function(mediaOptions) {
        this.currentMedia = mediaOptions;
        var payload = {
            url: mediaOptions.url,
            title: mediaOptions.title || 'Nuvio Stream',
            position: mediaOptions.position || 0,
            headers: mediaOptions.headers || {
                'User-Agent': 'Nuvio-GEN5W/1.0 (Android 4.4.2)'
            }
        };

        if (window.NativePlayer && window.NativePlayer.load) {
            window.NativePlayer.load(JSON.stringify(payload));
        } else {
            console.warn('NativePlayer interface unavailable. Mocking load event.');
            this.handleNativeEvent('loaded', payload);
        }
    };

    PlayerBridge.prototype.play = function() {
        if (window.NativePlayer && window.NativePlayer.play) {
            window.NativePlayer.play();
        }
    };

    PlayerBridge.prototype.pause = function() {
        if (window.NativePlayer && window.NativePlayer.pause) {
            window.NativePlayer.pause();
        }
    };

    PlayerBridge.prototype.toggle = function() {
        if (window.NativePlayer && window.NativePlayer.toggle) {
            window.NativePlayer.toggle();
        }
    };

    PlayerBridge.prototype.seek = function(positionMs) {
        if (window.NativePlayer && window.NativePlayer.seek) {
            window.NativePlayer.seek(positionMs);
        }
    };

    PlayerBridge.prototype.stop = function() {
        if (window.NativePlayer && window.NativePlayer.stop) {
            window.NativePlayer.stop();
        }
    };

    PlayerBridge.prototype.setVolume = function(volume) {
        if (window.NativePlayer && window.NativePlayer.setVolume) {
            window.NativePlayer.setVolume(volume);
        }
    };

    PlayerBridge.prototype.setAspectRatio = function(mode) {
        if (window.NativePlayer && window.NativePlayer.setAspectRatio) {
            window.NativePlayer.setAspectRatio(mode);
        }
    };

    PlayerBridge.prototype.setMockParked = function(parked) {
        if (window.NativePlayer && window.NativePlayer.setMockParked) {
            window.NativePlayer.setMockParked(parked);
        }
    };

    window.PlayerBridge = new PlayerBridge();

})(window);

