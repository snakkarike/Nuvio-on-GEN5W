/**
 * Nuvio on GEN5W - Node.js Frontend & Player Bridge Simulation
 *
 * Runs full headless integration tests:
 * 1. Mocks window.NativePlayer (@JavascriptInterface).
 * 2. Validates Stremio Addon manifest resolution.
 * 3. Verifies that stream selection properly triggers window.NativePlayer.load(...).
 * 4. Tests bidirectional event flow and vehicle park gate interlocks.
 */

const fs = require('fs');
const path = require('path');
const assert = require('assert');

// 1. Mock Browser Environment
const storage = {};
global.localStorage = {
    getItem: (key) => storage[key] || null,
    setItem: (key, val) => { storage[key] = String(val); },
    removeItem: (key) => { delete storage[key]; },
    clear: () => { Object.keys(storage).forEach(k => delete storage[k]); }
};

global.window = global;
global.document = {
    readyState: 'complete',
    addEventListener: () => {},
    createElement: (tag) => ({
        tagName: tag,
        style: {},
        className: '',
        children: [],
        appendChild: function(c) { this.children.push(c); },
        addEventListener: () => {},
        setAttribute: () => {},
        getAttribute: () => null,
        querySelector: () => null,
        querySelectorAll: () => []
    }),
    getElementById: (id) => ({
        id: id,
        style: {},
        className: '',
        value: '',
        textContent: '',
        innerHTML: '',
        addEventListener: () => {},
        appendChild: () => {},
        querySelector: () => null,
        querySelectorAll: () => []
    }),
    querySelectorAll: () => []
};

// Mock fetch for network operations
global.fetch = function(url, options) {
    if (url.includes('api.real-debrid.com/rest/1.0/unrestrict/link')) {
        return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({
                download: 'https://cdn.real-debrid.com/stream/mock-file.mp4',
                filename: 'Big.Buck.Bunny.1080p.mkv',
                filesize: 1500000000
            })
        });
    }
    return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve({})
    });
};

// 2. Mock Native Android Player (@JavascriptInterface)
const nativePlayerCalls = {
    load: [],
    play: 0,
    pause: 0,
    toggle: 0,
    seek: [],
    stop: 0,
    volume: [],
    aspect: []
};

let mockIsParked = true;

global.window.NativePlayer = {
    load: function(payloadJson) {
        const payload = JSON.parse(payloadJson);
        nativePlayerCalls.load.push(payload);
        // Simulate immediate native "loaded" and "stateChanged" events
        if (global.window.onNativePlayerEvent) {
            global.window.onNativePlayerEvent('loaded', { url: payload.url, title: payload.title });
            global.window.onNativePlayerEvent('stateChanged', { playWhenReady: true, playbackState: 3 });
        }
    },
    play: function() {
        nativePlayerCalls.play++;
        if (global.window.onNativePlayerEvent) {
            global.window.onNativePlayerEvent('stateChanged', { playWhenReady: true, playbackState: 3 });
        }
    },
    pause: function() {
        nativePlayerCalls.pause++;
        if (global.window.onNativePlayerEvent) {
            global.window.onNativePlayerEvent('stateChanged', { playWhenReady: false, playbackState: 3 });
        }
    },
    toggle: function() {
        nativePlayerCalls.toggle++;
    },
    seek: function(ms) {
        nativePlayerCalls.seek.push(ms);
        if (global.window.onNativePlayerEvent) {
            global.window.onNativePlayerEvent('timeupdate', { position: ms, duration: 120000 });
        }
    },
    stop: function() {
        nativePlayerCalls.stop++;
        if (global.window.onNativePlayerEvent) {
            global.window.onNativePlayerEvent('stopped', {});
        }
    },
    setVolume: function(v) {
        nativePlayerCalls.volume.push(v);
    },
    setAspectRatio: function(mode) {
        nativePlayerCalls.aspect.push(mode);
    },
    isParked: function() {
        return mockIsParked;
    },
    setMockParked: function(parked) {
        mockIsParked = parked;
        if (global.window.onNativePlayerEvent) {
            global.window.onNativePlayerEvent('parkStateChanged', {
                isParked: parked,
                reason: parked ? 'In Park' : 'Vehicle in motion'
            });
        }
    },
    log: function(msg) {}
};

// 3. Load Frontend Modules in Sequence
const JS_DIR = path.join(__dirname, '..', 'web', 'src', 'js');
const filesToLoad = [
    'polyfills.js',
    'addons.js',
    'debrid.js',
    'api.js',
    'player-bridge.js',
    'ui.js'
];

console.log('='.repeat(60));
console.log(' Nuvio on GEN5W - Node.js Player Bridge & UI Simulation');
console.log('='.repeat(60));

for (const f of filesToLoad) {
    const code = fs.readFileSync(path.join(JS_DIR, f), 'utf-8');
    eval(code);
    console.log(`[+] Loaded module: ${f}`);
}

// 4. Test Suite Execution
console.log('\n[*] Running Automated Integration Tests...');

// Test 1: Addon Manifest Resolution
console.log('\n--- Test 1: Stremio Addon Manager Defaults ---');
const addons = global.window.AddonManager.getAddons();
assert(addons.length >= 3, 'Expected at least 3 default addons configured');
const cinemeta = addons.find(a => a.manifest.id === 'com.linvo.cinemeta');
assert(cinemeta, 'Cinemeta official addon missing');
assert(cinemeta.manifest.resources.includes('catalog'), 'Cinemeta catalog resource missing');
console.log(`[✓] Cinemeta configured: ${cinemeta.manifest.name} (v${cinemeta.manifest.version})`);

const torrentio = addons.find(a => a.manifest.id === 'org.torrentio.stremio');
assert(torrentio, 'Torrentio stream addon missing');
assert(torrentio.manifest.resources.includes('stream'), 'Torrentio stream resource missing');
console.log(`[✓] Torrentio configured: ${torrentio.manifest.name}`);

// Test 2: Debrid Key Configuration
console.log('\n--- Test 2: Debrid Configuration ---');
global.window.DebridService.setRealDebridToken('TEST_RD_TOKEN_12345');
assert.strictEqual(global.window.DebridService.getRealDebridToken(), 'TEST_RD_TOKEN_12345');
assert.strictEqual(global.window.DebridService.hasActiveProvider(), true);
console.log('[✓] Real-Debrid token saved and retrieved from localStorage');

// Test 3: Stream Selection & Bridge Invocation
console.log('\n--- Test 3: Stream Resolution & NativePlayer.load() Trigger ---');
const sampleStream = {
    name: 'Torrentio [RD+ 1080p]',
    title: 'Big Buck Bunny 1080p BluRay\n💾 1.5 GB | ⚙️ Real-Debrid',
    url: 'https://download.real-debrid.com/d/TEST1234/bbb.mp4',
    behaviorHints: {
        proxyHeaders: {
            request: {
                'User-Agent': 'Nuvio-GEN5W/1.0',
                'Referer': 'https://real-debrid.com'
            }
        }
    }
};

let bridgeLoadedEventReceived = false;
global.window.PlayerBridge.addListener((event, data) => {
    if (event === 'loaded') {
        bridgeLoadedEventReceived = true;
    }
});

global.window.DebridService.resolveStream(sampleStream)
    .then(resolved => {
        assert.strictEqual(resolved.url, 'https://cdn.real-debrid.com/stream/mock-file.mp4');

        // Pass to PlayerBridge as NuvioUI does
        global.window.PlayerBridge.loadMedia({
            url: resolved.url,
            title: 'Big Buck Bunny',
            headers: {
                'User-Agent': 'Nuvio-GEN5W/1.0',
                'Referer': 'https://real-debrid.com'
            }
        });

        // Verify window.NativePlayer.load was called
        assert.strictEqual(nativePlayerCalls.load.length, 1, 'Expected NativePlayer.load() to be invoked once');
        const loadCall = nativePlayerCalls.load[0];
        assert.strictEqual(loadCall.url, 'https://cdn.real-debrid.com/stream/mock-file.mp4');
        assert.strictEqual(loadCall.headers['User-Agent'], 'Nuvio-GEN5W/1.0');
        assert.strictEqual(bridgeLoadedEventReceived, true, 'Expected bridge to emit loaded event');
        console.log(`[✓] NativePlayer.load() verified with URL: ${loadCall.url}`);
        console.log(`[✓] Injected headers: ${JSON.stringify(loadCall.headers)}`);

        // Test 4: Playback Deck Controls
        console.log('\n--- Test 4: Playback Deck Controls & Bidirectional State ---');
        global.window.PlayerBridge.seek(30000);
        assert.strictEqual(nativePlayerCalls.seek[0], 30000);
        assert.strictEqual(global.window.PlayerBridge.currentPosition, 30000);
        console.log('[✓] Seek command and position update verified (30000ms)');

        global.window.PlayerBridge.setAspectRatio('fill');
        assert.strictEqual(nativePlayerCalls.aspect[0], 'fill');
        console.log('[✓] Aspect ratio mode command verified ("fill")');

        global.window.PlayerBridge.pause();
        assert.strictEqual(nativePlayerCalls.pause, 1);
        assert.strictEqual(global.window.PlayerBridge.isPlaying, false);
        console.log('[✓] Pause command and state change verified');

        // Test 5: Vehicle Park Interlock Gate
        console.log('\n--- Test 5: Vehicle Safety Interlock Gate Simulation ---');
        let parkEventReceived = false;
        let lastParkState = null;

        global.window.PlayerBridge.addListener((event, data) => {
            if (event === 'parkChanged') {
                parkEventReceived = true;
                lastParkState = data.isParked;
            }
        });

        // Simulate vehicle driving
        global.window.NativePlayer.setMockParked(false);
        assert.strictEqual(parkEventReceived, true);
        assert.strictEqual(lastParkState, false);
        assert.strictEqual(global.window.PlayerBridge.isParked, false);
        console.log('[✓] Vehicle shift out of park intercepted: isParked = false');

        // Simulate returning to park
        global.window.NativePlayer.setMockParked(true);
        assert.strictEqual(lastParkState, true);
        assert.strictEqual(global.window.PlayerBridge.isParked, true);
        console.log('[✓] Vehicle return to park intercepted: isParked = true');

        console.log('\n' + '='.repeat(60));
        console.log(' [✓] ALL SIMULATION TESTS PASSED SUCCESSFULLY! (5/5)');
        console.log('='.repeat(60));
    })
    .catch(err => {
        console.error('[!] Test failed with error:', err);
        process.exit(1);
    });
