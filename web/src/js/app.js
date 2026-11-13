/**
 * Nuvio on GEN5W - Main Application Bootstrap
 */
(function() {
    'use strict';

    function onReady() {
        if (window.NuvioUI && window.NuvioUI.init) {
            window.NuvioUI.init();
        }
    }

    if (document.readyState === 'complete' || document.readyState === 'interactive') {
        setTimeout(onReady, 1);
    } else {
        document.addEventListener('DOMContentLoaded', onReady);
    }
})();

