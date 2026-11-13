/**
 * Nuvio on GEN5W - Main Application Bootstrap
 */
(function() {
    'use strict';

    var initialized = false;

    function onReady() {
        if (initialized) return;
        initialized = true;

        try {
            console.log('Nuvio UI Bootstrap: viewport ' + window.innerWidth + 'x' + window.innerHeight + ', screen ' + screen.width + 'x' + screen.height);
            if (window.NuvioUI && window.NuvioUI.init) {
                window.NuvioUI.init();
            }
        } catch (e) {
            console.error('Nuvio bootstrap error:', e);
            if (window.NativePlayer && window.NativePlayer.log) {
                try {
                    window.NativePlayer.log('Nuvio bootstrap error: ' + (e.message || e));
                } catch (ignored) {}
            }
        }
    }

    // On KitKat WebView, invoke on ready or immediately after document body is present
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', onReady);
    }
    // Safety fallback: ensure execution is never blocked by dropped DOM events
    setTimeout(onReady, 100);
})();
