/**
 * Nuvio on GEN5W - Stremio Addon Manager
 *
 * Implements the Stremio Addon v1.0 protocol specification.
 * Manages manifest retrieval, catalog definitions, and persistent addon storage.
 */
(function(window) {
    'use strict';

    var STORAGE_KEY = 'nuvio_gen5w_addons';

    var DEFAULT_ADDONS = [
        {
            transportUrl: 'https://v3-cinemeta.strem.io/manifest.json',
            manifest: {
                id: 'com.linvo.cinemeta',
                name: 'Cinemeta (Official)',
                version: '3.0.12',
                description: 'Official IMDb movies and TV series catalog metadata.',
                resources: ['catalog', 'meta'],
                types: ['movie', 'series'],
                catalogs: [
                    { type: 'movie', id: 'top', name: 'Popular Movies' },
                    { type: 'series', id: 'top', name: 'Popular TV Series' }
                ]
            }
        },
        {
            transportUrl: 'https://torrentio.strem.fun/manifest.json',
            manifest: {
                id: 'org.torrentio.stremio',
                name: 'Torrentio',
                version: '1.0.8',
                description: 'Debrid and community stream resolver for movies and series.',
                resources: ['stream'],
                types: ['movie', 'series'],
                catalogs: []
            }
        },
        {
            transportUrl: 'https://cyberflix.elfhosted.com/manifest.json',
            manifest: {
                id: 'org.cyberflix.catalog',
                name: 'CyberFlix Catalog',
                version: '1.4.0',
                description: 'Curated streaming catalogs from Netflix, Disney+, HBO, and Apple TV+.',
                resources: ['catalog'],
                types: ['movie', 'series'],
                catalogs: [
                    { type: 'movie', id: 'cyberflix_netflix', name: 'Netflix Movies' },
                    { type: 'series', id: 'cyberflix_netflix', name: 'Netflix Series' }
                ]
            }
        }
    ];

    function AddonManager() {
        this.addons = [];
        this.loadAddons();
    }

    AddonManager.prototype.loadAddons = function() {
        try {
            var saved = localStorage.getItem(STORAGE_KEY);
            if (saved) {
                this.addons = JSON.parse(saved);
            } else {
                this.addons = DEFAULT_ADDONS.slice(0);
                this.saveAddons();
            }
        } catch (e) {
            this.addons = DEFAULT_ADDONS.slice(0);
        }
    };

    AddonManager.prototype.saveAddons = function() {
        try {
            localStorage.setItem(STORAGE_KEY, JSON.stringify(this.addons));
        } catch (e) {
            console.error('Failed to save addons:', e);
        }
    };

    AddonManager.prototype.getAddons = function() {
        return this.addons;
    };

    AddonManager.prototype.getManifest = function(addonUrl) {
        var cleanUrl = addonUrl.replace(/\/manifest\.json$/, '') + '/manifest.json';
        return window.fetch(cleanUrl, { timeout: 8000 })
            .then(function(res) {
                if (!res.ok) throw new Error('HTTP ' + res.status);
                return res.json();
            });
    };

    AddonManager.prototype.installAddon = function(addonUrl) {
        var self = this;
        var cleanUrl = addonUrl.replace(/\/manifest\.json$/, '') + '/manifest.json';

        // Check if already installed
        for (var i = 0; i < self.addons.length; i++) {
            if (self.addons[i].transportUrl === cleanUrl) {
                return window.Promise.reject(new Error('Addon is already installed.'));
            }
        }

        return self.getManifest(cleanUrl).then(function(manifest) {
            if (!manifest || !manifest.id) {
                throw new Error('Invalid Stremio manifest format.');
            }
            var addonEntry = {
                transportUrl: cleanUrl,
                manifest: manifest
            };
            self.addons.push(addonEntry);
            self.saveAddons();
            return addonEntry;
        });
    };

    AddonManager.prototype.uninstallAddon = function(transportUrl) {
        this.addons = this.addons.filter(function(addon) {
            return addon.transportUrl !== transportUrl;
        });
        this.saveAddons();
    };

    AddonManager.prototype.getCatalogAddons = function(type) {
        return this.addons.filter(function(addon) {
            var manifest = addon.manifest;
            if (!manifest.resources || !manifest.resources.includes('catalog')) return false;
            if (!manifest.types || !manifest.types.includes(type)) return false;
            return manifest.catalogs && manifest.catalogs.length > 0;
        });
    };

    AddonManager.prototype.getStreamAddons = function(type) {
        return this.addons.filter(function(addon) {
            var manifest = addon.manifest;
            if (!manifest.resources) return false;
            var hasStream = manifest.resources.some(function(res) {
                return res === 'stream' || (res.name && res.name === 'stream');
            });
            if (!hasStream) return false;
            return !manifest.types || manifest.types.includes(type);
        });
    };

    window.AddonManager = new AddonManager();

})(window);

