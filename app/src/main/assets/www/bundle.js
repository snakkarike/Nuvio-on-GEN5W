/* Nuvio on GEN5W - Bundled ES5 Core */

/* --- Begin polyfills.js --- */
/**
 * Nuvio on GEN5W - ES5 / KitKat Android 4.4 Chromium 33 Polyfill Suite
 *
 * Chromium 33 lacks: Promise, Fetch, URLSearchParams, Object.assign,
 * Array.prototype.find/includes, String.prototype.startsWith/endsWith, and crypto.
 */
(function(window) {
    'use strict';

    // 1. Object.assign
    if (typeof Object.assign !== 'function') {
        Object.assign = function(target) {
            if (target === undefined || target === null) {
                throw new TypeError('Cannot convert undefined or null to object');
            }
            var output = Object(target);
            for (var index = 1; index < arguments.length; index++) {
                var source = arguments[index];
                if (source !== undefined && source !== null) {
                    for (var nextKey in source) {
                        if (Object.prototype.hasOwnProperty.call(source, nextKey)) {
                            output[nextKey] = source[nextKey];
                        }
                    }
                }
            }
            return output;
        };
    }

    // 2. String methods (startsWith, endsWith, includes)
    if (!String.prototype.startsWith) {
        String.prototype.startsWith = function(search, pos) {
            return this.substr(!pos || pos < 0 ? 0 : +pos, search.length) === search;
        };
    }
    if (!String.prototype.endsWith) {
        String.prototype.endsWith = function(search, this_len) {
            if (this_len === undefined || this_len > this.length) {
                this_len = this.length;
            }
            return this.substring(this_len - search.length, this_len) === search;
        };
    }
    if (!String.prototype.includes) {
        String.prototype.includes = function(search, start) {
            if (typeof start !== 'number') start = 0;
            if (start + search.length > this.length) return false;
            return this.indexOf(search, start) !== -1;
        };
    }

    // 3. Array methods (find, findIndex, includes)
    if (!Array.prototype.find) {
        Array.prototype.find = function(predicate) {
            if (this === null) throw new TypeError('Array.prototype.find called on null or undefined');
            if (typeof predicate !== 'function') throw new TypeError('predicate must be a function');
            var list = Object(this);
            var length = list.length >>> 0;
            var thisArg = arguments[1];
            for (var i = 0; i < length; i++) {
                var value = list[i];
                if (predicate.call(thisArg, value, i, list)) {
                    return value;
                }
            }
            return undefined;
        };
    }
    if (!Array.prototype.findIndex) {
        Array.prototype.findIndex = function(predicate) {
            if (this === null) throw new TypeError('Array.prototype.findIndex called on null or undefined');
            if (typeof predicate !== 'function') throw new TypeError('predicate must be a function');
            var list = Object(this);
            var length = list.length >>> 0;
            var thisArg = arguments[1];
            for (var i = 0; i < length; i++) {
                if (predicate.call(thisArg, list[i], i, list)) {
                    return i;
                }
            }
            return -1;
        };
    }
    if (!Array.prototype.includes) {
        Array.prototype.includes = function(searchElement, fromIndex) {
            if (this === null) throw new TypeError('"this" is null or not defined');
            var o = Object(this);
            var len = o.length >>> 0;
            if (len === 0) return false;
            var n = fromIndex | 0;
            var k = Math.max(n >= 0 ? n : len - Math.abs(n), 0);
            while (k < len) {
                if (o[k] === searchElement) return true;
                k++;
            }
            return false;
        };
    }

    // 4. Lightweight ES6 Promise A+ Implementation (Strict-mode compliant)
    var PENDING = 0;
    var FULFILLED = 1;
    var REJECTED = 2;

    var PromisePolyfill = function(executor) {
        var self = this;
        self.state = PENDING;
        self.value = undefined;
        self.handlers = [];

        var resolve = function(result) {
            if (self.state !== PENDING) return;
            if (result && (typeof result === 'object' || typeof result === 'function')) {
                var then = result.then;
                if (typeof then === 'function') {
                    then.call(result, resolve, reject);
                    return;
                }
            }
            self.state = FULFILLED;
            self.value = result;
            executeHandlers();
        };

        var reject = function(error) {
            if (self.state !== PENDING) return;
            self.state = REJECTED;
            self.value = error;
            executeHandlers();
        };

        var executeHandlers = function() {
            setTimeout(function() {
                for (var i = 0; i < self.handlers.length; i++) {
                    var handler = self.handlers[i];
                    if (self.state === FULFILLED) {
                        if (typeof handler.onFulfilled === 'function') {
                            try {
                                var res = handler.onFulfilled(self.value);
                                handler.resolve(res);
                            } catch (e) {
                                handler.reject(e);
                            }
                        } else {
                            handler.resolve(self.value);
                        }
                    } else if (self.state === REJECTED) {
                        if (typeof handler.onRejected === 'function') {
                            try {
                                var errRes = handler.onRejected(self.value);
                                handler.resolve(errRes);
                            } catch (e) {
                                handler.reject(e);
                            }
                        } else {
                            handler.reject(self.value);
                        }
                    }
                }
                self.handlers = [];
            }, 0);
        };

        self.then = function(onFulfilled, onRejected) {
            return new PromisePolyfill(function(res, rej) {
                self.handlers.push({
                    onFulfilled: onFulfilled,
                    onRejected: onRejected,
                    resolve: res,
                    reject: rej
                });
                if (self.state !== PENDING) {
                    executeHandlers();
                }
            });
        };

        self.catch = function(onRejected) {
            return self.then(null, onRejected);
        };

        try {
            executor(resolve, reject);
        } catch (e) {
            reject(e);
        }
    };

    PromisePolyfill.resolve = function(val) {
        return new PromisePolyfill(function(res) { res(val); });
    };
    PromisePolyfill.reject = function(err) {
        return new PromisePolyfill(function(res, rej) { rej(err); });
    };
    PromisePolyfill.all = function(promises) {
        return new PromisePolyfill(function(resolve, reject) {
            if (!promises || !promises.length) {
                resolve([]);
                return;
            }
            var results = [];
            var count = 0;
            for (var i = 0; i < promises.length; i++) {
                (function(idx) {
                    PromisePolyfill.resolve(promises[idx]).then(function(val) {
                        results[idx] = val;
                        count++;
                        if (count === promises.length) resolve(results);
                    }, reject);
                })(i);
            }
        });
    };

    if (typeof window.Promise !== 'function') {
        window.Promise = PromisePolyfill;
    }

    // 5. WHATWG Fetch Polyfill (XHR-based for KitKat WebView)
    if (typeof window.fetch !== 'function') {
        window.fetch = function(url, options) {
            options = options || {};
            var method = (options.method || 'GET').toUpperCase();
            var headers = options.headers || {};
            var body = options.body || null;

            return new window.Promise(function(resolve, reject) {
                var xhr = new XMLHttpRequest();
                xhr.open(method, url, true);

                if (options.timeout) {
                    xhr.timeout = options.timeout;
                }

                for (var key in headers) {
                    if (headers.hasOwnProperty(key)) {
                        xhr.setRequestHeader(key, headers[key]);
                    }
                }

                xhr.onload = function() {
                    var responseHeaders = {};
                    var rawHeaders = xhr.getAllResponseHeaders();
                    if (rawHeaders) {
                        var lines = rawHeaders.trim().split(/[\r\n]+/);
                        for (var i = 0; i < lines.length; i++) {
                            var parts = lines[i].split(': ');
                            var hKey = parts.shift();
                            var hVal = parts.join(': ');
                            responseHeaders[hKey.toLowerCase()] = hVal;
                        }
                    }

                    var response = {
                        ok: (xhr.status >= 200 && xhr.status < 300),
                        status: xhr.status,
                        statusText: xhr.statusText,
                        headers: {
                            get: function(name) { return responseHeaders[name.toLowerCase()] || null; }
                        },
                        text: function() {
                            return window.Promise.resolve(xhr.responseText);
                        },
                        json: function() {
                            try {
                                return window.Promise.resolve(JSON.parse(xhr.responseText));
                            } catch (e) {
                                return window.Promise.reject(new Error('Invalid JSON: ' + e.message));
                            }
                        }
                    };
                    resolve(response);
                };

                xhr.onerror = function() {
                    reject(new TypeError('Network request failed'));
                };
                xhr.ontimeout = function() {
                    reject(new TypeError('Network request timed out'));
                };

                xhr.send(body);
            });
        };
    }

    // 6. URLSearchParams Polyfill
    if (typeof window.URLSearchParams !== 'function') {
        window.URLSearchParams = function(queryString) {
            this.params = {};
            if (queryString) {
                if (queryString.charAt(0) === '?') {
                    queryString = queryString.slice(1);
                }
                var pairs = queryString.split('&');
                for (var i = 0; i < pairs.length; i++) {
                    var item = pairs[i].split('=');
                    var k = decodeURIComponent(item[0]);
                    var v = item.length > 1 ? decodeURIComponent(item[1]) : '';
                    this.params[k] = v;
                }
            }
            this.get = function(name) {
                return this.params[name] !== undefined ? this.params[name] : null;
            };
            this.set = function(name, value) {
                this.params[name] = String(value);
            };
        };
    }

})(window);

/* --- End polyfills.js --- */

/* --- Begin addons.js --- */
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


/* --- End addons.js --- */

/* --- Begin debrid.js --- */
/**
 * Nuvio on GEN5W - Debrid Provider Integration
 *
 * Supports Real-Debrid and Torbox stream resolution.
 * Allows instant playback of cached torrent streams over direct high-speed HTTPS.
 */
(function(window) {
    'use strict';

    var STORAGE_KEY_RD = 'nuvio_gen5w_rd_token';
    var STORAGE_KEY_TORBOX = 'nuvio_gen5w_torbox_token';
    var STORAGE_KEY_ENGINE = 'nuvio_gen5w_stremio_engine';

    function DebridService() {
        this.rdToken = '';
        this.torboxToken = '';
        this.stremioEngineUrl = '';
        this.loadKeys();
    }

    DebridService.prototype.loadKeys = function() {
        try {
            this.rdToken = localStorage.getItem(STORAGE_KEY_RD) || '';
            this.torboxToken = localStorage.getItem(STORAGE_KEY_TORBOX) || '';
            this.stremioEngineUrl = localStorage.getItem(STORAGE_KEY_ENGINE) || '';
        } catch (e) {}
    };

    DebridService.prototype.setRealDebridToken = function(token) {
        this.rdToken = (token || '').trim();
        try {
            localStorage.setItem(STORAGE_KEY_RD, this.rdToken);
        } catch (e) {}
    };

    DebridService.prototype.setTorboxToken = function(token) {
        this.torboxToken = (token || '').trim();
        try {
            localStorage.setItem(STORAGE_KEY_TORBOX, this.torboxToken);
        } catch (e) {}
    };

    DebridService.prototype.setStremioEngineUrl = function(url) {
        this.stremioEngineUrl = (url || '').trim().replace(/\/$/, '');
        try {
            localStorage.setItem(STORAGE_KEY_ENGINE, this.stremioEngineUrl);
        } catch (e) {}
    };

    DebridService.prototype.getRealDebridToken = function() {
        return this.rdToken;
    };

    DebridService.prototype.getTorboxToken = function() {
        return this.torboxToken;
    };

    DebridService.prototype.getStremioEngineUrl = function() {
        return this.stremioEngineUrl;
    };

    DebridService.prototype.hasActiveProvider = function() {
        return !!(this.rdToken || this.torboxToken || this.stremioEngineUrl);
    };

    /**
     * Resolves a Real-Debrid download link or unrestrict URL.
     */
    DebridService.prototype.unrestrictRealDebrid = function(link) {
        if (!this.rdToken) {
            return window.Promise.reject(new Error('Real-Debrid API token not configured.'));
        }

        var endpoint = 'https://api.real-debrid.com/rest/1.0/unrestrict/link';
        var body = 'link=' + encodeURIComponent(link);

        return window.fetch(endpoint, {
            method: 'POST',
            headers: {
                'Authorization': 'Bearer ' + this.rdToken,
                'Content-Type': 'application/x-www-form-urlencoded'
            },
            body: body,
            timeout: 10000
        }).then(function(res) {
            if (!res.ok) throw new Error('Real-Debrid error: ' + res.status);
            return res.json();
        }).then(function(data) {
            if (!data || !data.download) {
                throw new Error('Real-Debrid did not return a valid download URL.');
            }
            return {
                url: data.download,
                filename: data.filename || '',
                filesize: data.filesize || 0
            };
        });
    };

    /**
     * Resolves an infoHash using Torbox API v1.
     */
    DebridService.prototype.resolveTorboxTorrent = function(infoHash, fileIdx) {
        var self = this;
        var token = self.torboxToken;
        if (!token) {
            return window.Promise.reject(new Error('Torbox API token is not configured in Settings.'));
        }

        var magnet = 'magnet:?xt=urn:btih:' + infoHash;
        var createUrl = 'https://api.torbox.app/v1/api/torrents/createtorrent';

        return window.fetch(createUrl, {
            method: 'POST',
            headers: {
                'Authorization': 'Bearer ' + token,
                'Content-Type': 'application/x-www-form-urlencoded'
            },
            body: 'magnet=' + encodeURIComponent(magnet),
            timeout: 12000
        }).then(function(res) {
            return res.json();
        }).then(function(createRes) {
            var torrentId = null;
            if (createRes && createRes.data) {
                torrentId = createRes.data.torrent_id || createRes.data.id;
            }

            // If createtorrent did not return an ID (e.g. already added), search user torrents
            var getTorrentIdPromise;
            if (torrentId) {
                getTorrentIdPromise = window.Promise.resolve(torrentId);
            } else {
                getTorrentIdPromise = window.fetch('https://api.torbox.app/v1/api/torrents/mylist?bypass_cache=true', {
                    headers: { 'Authorization': 'Bearer ' + token },
                    timeout: 10000
                }).then(function(r) { return r.json(); })
                .then(function(listRes) {
                    if (listRes && listRes.data && listRes.data.length) {
                        var targetHash = infoHash.toLowerCase();
                        for (var i = 0; i < listRes.data.length; i++) {
                            var t = listRes.data[i];
                            if (t.hash && t.hash.toLowerCase() === targetHash) {
                                return t.id || t.torrent_id;
                            }
                        }
                    }
                    var msg = (createRes && createRes.detail) ? createRes.detail : 'Could not register torrent on Torbox.';
                    throw new Error('Torbox: ' + msg);
                });
            }

            return getTorrentIdPromise.then(function(tid) {
                // Request torrent details to choose the main video file
                return window.fetch('https://api.torbox.app/v1/api/torrents/mylist?id=' + encodeURIComponent(tid), {
                    headers: { 'Authorization': 'Bearer ' + token },
                    timeout: 10000
                }).then(function(r) { return r.json(); })
                .then(function(detailRes) {
                    var selectedFileId = fileIdx !== undefined ? fileIdx : null;

                    var itemData = detailRes && detailRes.data ?
                        (Array.isArray(detailRes.data) ? detailRes.data[0] : detailRes.data) : null;

                    if (itemData && itemData.files && itemData.files.length > 0) {
                        var files = itemData.files;
                        var largest = files[0];
                        for (var f = 1; f < files.length; f++) {
                            if ((files[f].size || 0) > (largest.size || 0)) {
                                largest = files[f];
                            }
                        }
                        selectedFileId = largest.id !== undefined ? largest.id : 0;
                    }

                    var reqDlUrl = 'https://api.torbox.app/v1/api/torrents/requestdl?token=' +
                        encodeURIComponent(token) + '&torrent_id=' + encodeURIComponent(tid) +
                        (selectedFileId !== null ? '&file_id=' + encodeURIComponent(selectedFileId) : '') +
                        '&zip=false';

                    return window.fetch(reqDlUrl, {
                        headers: { 'Authorization': 'Bearer ' + token },
                        timeout: 12000
                    });
                }).then(function(r) { return r.json(); })
                .then(function(dlRes) {
                    if (!dlRes || !dlRes.success || !dlRes.data) {
                        var errDetail = (dlRes && dlRes.detail) ? dlRes.detail : 'Torrent is not yet cached on Torbox.';
                        throw new Error('Torbox: ' + errDetail);
                    }

                    var streamUrl = typeof dlRes.data === 'string' ? dlRes.data : (dlRes.data.url || dlRes.data.download_url);
                    if (!streamUrl) {
                        throw new Error('Torbox did not return a valid playable stream URL.');
                    }

                    return {
                        url: streamUrl,
                        filename: 'Torbox Stream',
                        headers: {}
                    };
                });
            });
        });
    };

    /**
     * Resolves an infoHash using Real-Debrid API.
     */
    DebridService.prototype.resolveRealDebridTorrent = function(infoHash) {
        var self = this;
        var addMagnetUrl = 'https://api.real-debrid.com/rest/1.0/torrents/addMagnet';
        var magnetBody = 'magnet=magnet:?xt=urn:btih:' + infoHash;

        return window.fetch(addMagnetUrl, {
            method: 'POST',
            headers: {
                'Authorization': 'Bearer ' + self.rdToken,
                'Content-Type': 'application/x-www-form-urlencoded'
            },
            body: magnetBody,
            timeout: 10000
        }).then(function(res) {
            return res.json();
        }).then(function(torrentData) {
            if (!torrentData || !torrentData.id) {
                throw new Error('Failed to register torrent with Real-Debrid.');
            }
            var torrentId = torrentData.id;
            var selectFileUrl = 'https://api.real-debrid.com/rest/1.0/torrents/selectFiles/' + torrentId;
            return window.fetch(selectFileUrl, {
                method: 'POST',
                headers: {
                    'Authorization': 'Bearer ' + self.rdToken,
                    'Content-Type': 'application/x-www-form-urlencoded'
                },
                body: 'files=all',
                timeout: 8000
            }).then(function() {
                var infoUrl = 'https://api.real-debrid.com/rest/1.0/torrents/info/' + torrentId;
                return window.fetch(infoUrl, {
                    headers: { 'Authorization': 'Bearer ' + self.rdToken }
                });
            }).then(function(res) {
                return res.json();
            }).then(function(info) {
                if (info.links && info.links.length > 0) {
                    return self.unrestrictRealDebrid(info.links[0]);
                }
                throw new Error('Torrent is not yet cached on Real-Debrid.');
            });
        });
    };

    /**
     * Resolves a Stremio stream item into a direct HTTP playable stream.
     */
    DebridService.prototype.resolveStream = function(streamItem) {
        var self = this;

        // 1. Direct URL already present (e.g. Torrentio-RD/Torbox pre-resolved or direct HTTP stream)
        if (streamItem.url) {
            // If it's a debrid unrestrict link (like real-debrid.com/d/...), unrestrict it
            if (streamItem.url.indexOf('real-debrid.com/d/') !== -1 && self.rdToken) {
                return self.unrestrictRealDebrid(streamItem.url);
            }
            return window.Promise.resolve({
                url: streamItem.url,
                filename: streamItem.title || streamItem.name || 'Video Stream',
                headers: streamItem.behaviorHints && streamItem.behaviorHints.proxyHeaders ?
                    streamItem.behaviorHints.proxyHeaders.request : {}
            });
        }

        // 2. InfoHash provided (Torrent needing Debrid or Stremio Engine resolution)
        if (streamItem.infoHash) {
            // Check if local/remote Stremio Streaming Server is configured
            if (self.stremioEngineUrl) {
                var fileIndex = streamItem.fileIdx !== undefined ? streamItem.fileIdx : 0;
                var engineStreamUrl = self.stremioEngineUrl + '/' + streamItem.infoHash + '/' + fileIndex;
                return window.Promise.resolve({
                    url: engineStreamUrl,
                    filename: streamItem.title || 'P2P Stream via Stremio Engine',
                    headers: {}
                });
            }

            // Check Torbox if configured
            if (self.torboxToken) {
                return self.resolveTorboxTorrent(streamItem.infoHash, streamItem.fileIdx);
            }

            // Check Real-Debrid if configured
            if (self.rdToken) {
                return self.resolveRealDebridTorrent(streamItem.infoHash);
            }

            return window.Promise.reject(new Error(
                'P2P BitTorrent stream detected. Gen5W car hardware (1GB RAM) cannot run a BitTorrent engine on-device to protect flash storage and BlueLink cellular data.\n' +
                'Please configure your Torbox or Real-Debrid token in Settings, or install Torrentio with Debrid pre-configured.'
            ));
        }

        return window.Promise.reject(new Error('Unsupported stream format.'));
    };

    window.DebridService = new DebridService();

})(window);


/* --- End debrid.js --- */

/* --- Begin api.js --- */
/**
 * Nuvio on GEN5W - Stremio Addon Protocol API Client
 *
 * Implements /catalog, /meta, and /stream requests across registered addons.
 */
(function(window) {
    'use strict';

    var FALLBACK_MOVIES = [
        {
            id: 'tt1254207',
            type: 'movie',
            name: 'Big Buck Bunny',
            year: '2008',
            imdbRating: '7.3',
            poster: 'https://images.metahub.space/poster/medium/tt1254207/img',
            description: 'A large and lovable rabbit deals with bullying forest creatures in this iconic open-source animated film.',
            genres: ['Animation', 'Comedy', 'Short']
        },
        {
            id: 'tt2285752',
            type: 'movie',
            name: 'Tears of Steel',
            year: '2012',
            imdbRating: '6.5',
            poster: 'https://images.metahub.space/poster/medium/tt2285752/img',
            description: 'In a dystopian future, a group of warriors and scientists gather at the Oude Kerk in Amsterdam to stage a crucial event from the past.',
            genres: ['Action', 'Sci-Fi', 'Short']
        },
        {
            id: 'tt1727587',
            type: 'movie',
            name: 'Sintel',
            year: '2010',
            imdbRating: '7.4',
            poster: 'https://images.metahub.space/poster/medium/tt1727587/img',
            description: 'A lonely young woman searches for a baby dragon she nursed back to health.',
            genres: ['Animation', 'Adventure', 'Fantasy']
        },
        {
            id: 'tt0807840',
            type: 'movie',
            name: 'Elephants Dream',
            year: '2006',
            imdbRating: '6.8',
            poster: 'https://images.metahub.space/poster/medium/tt0807840/img',
            description: 'Two strange men explore the giant, complex machine in which they seem to live.',
            genres: ['Animation', 'Sci-Fi', 'Short']
        },
        {
            id: 'tt4947474',
            type: 'movie',
            name: 'Cosmos Laundromat',
            year: '2015',
            imdbRating: '7.2',
            poster: 'https://images.metahub.space/poster/medium/tt4947474/img',
            description: 'On a desolate island, a suicidal sheep named Franck meets a mysterious salesman who offers him the gift of a lifetime.',
            genres: ['Animation', 'Comedy', 'Fantasy']
        }
    ];

    var FALLBACK_SERIES = [
        {
            id: 'tt1695360',
            type: 'series',
            name: 'Pioneer One',
            year: '2010',
            imdbRating: '6.6',
            poster: 'https://images.metahub.space/poster/medium/tt1695360/img',
            description: 'An object from space spreads radiation over North America, turning out to be a Soviet space capsule.',
            genres: ['Drama', 'Sci-Fi']
        }
    ];

    function StremioApi() {}

    /**
     * Fetch items from a catalog addon
     */
    StremioApi.prototype.getCatalog = function(transportUrl, type, catalogId, skip) {
        var baseUrl = transportUrl.replace(/\/manifest\.json$/, '');
        var url = baseUrl + '/catalog/' + encodeURIComponent(type) + '/' + encodeURIComponent(catalogId);
        if (skip && skip > 0) {
            url += '/skip=' + skip;
        }
        url += '.json';

        return window.fetch(url, { timeout: 8000 })
            .then(function(res) {
                if (!res.ok) throw new Error('Catalog request failed with status ' + res.status);
                return res.json();
            })
            .then(function(data) {
                var items = (data && data.metas) ? data.metas : [];
                if (!items || items.length === 0) {
                    return type === 'series' ? FALLBACK_SERIES : FALLBACK_MOVIES;
                }
                return items;
            })
            .catch(function(err) {
                console.warn('Network catalog failed, using fallback library:', err);
                return type === 'series' ? FALLBACK_SERIES : FALLBACK_MOVIES;
            });
    };

    /**
     * Search catalogs for a query term
     */
    StremioApi.prototype.searchCatalog = function(transportUrl, type, catalogId, query) {
        var baseUrl = transportUrl.replace(/\/manifest\.json$/, '');
        var url = baseUrl + '/catalog/' + encodeURIComponent(type) + '/' + encodeURIComponent(catalogId) +
                  '/search=' + encodeURIComponent(query) + '.json';

        return window.fetch(url, { timeout: 10000 })
            .then(function(res) {
                if (!res.ok) throw new Error('Search failed with status ' + res.status);
                return res.json();
            })
            .then(function(data) {
                return (data && data.metas) ? data.metas : [];
            });
    };

    /**
     * Fetch rich metadata for an item (IMDb ID, description, episodes, etc.)
     */
    StremioApi.prototype.getMeta = function(transportUrl, type, id) {
        var baseUrl = transportUrl.replace(/\/manifest\.json$/, '');
        var url = baseUrl + '/meta/' + encodeURIComponent(type) + '/' + encodeURIComponent(id) + '.json';

        return window.fetch(url, { timeout: 8000 })
            .then(function(res) {
                if (!res.ok) throw new Error('Meta fetch failed with status ' + res.status);
                return res.json();
            })
            .then(function(data) {
                return (data && data.meta) ? data.meta : null;
            })
            .catch(function() {
                var pool = (type === 'series' ? FALLBACK_SERIES : FALLBACK_MOVIES);
                for (var i = 0; i < pool.length; i++) {
                    if (pool[i].id === id) return pool[i];
                }
                return null;
            });
    };

    /**
     * Query streams from a specific stream addon
     */
    StremioApi.prototype.getStreamsFromAddon = function(transportUrl, type, id) {
        var baseUrl = transportUrl.replace(/\/manifest\.json$/, '');
        var url = baseUrl + '/stream/' + encodeURIComponent(type) + '/' + encodeURIComponent(id) + '.json';

        return window.fetch(url, { timeout: 12000 })
            .then(function(res) {
                if (!res.ok) return [];
                return res.json();
            })
            .then(function(data) {
                return (data && data.streams) ? data.streams : [];
            })
            .catch(function() {
                return [];
            });
    };

    /**
     * Query streams across all installed addons
     */
    StremioApi.prototype.getAllStreams = function(type, id) {
        var self = this;
        var streamAddons = window.AddonManager.getStreamAddons(type);
        if (!streamAddons || streamAddons.length === 0) {
            return window.Promise.resolve([]);
        }

        var promises = streamAddons.map(function(addon) {
            return self.getStreamsFromAddon(addon.transportUrl, type, id)
                .then(function(streams) {
                    return streams.map(function(s) {
                        s._addonName = addon.manifest.name;
                        return s;
                    });
                });
        });

        return window.Promise.all(promises).then(function(results) {
            var combined = [];

            // Built-in Open Domain Direct HTTP Streams for zero-debrid testing
            if (id === 'tt1254207' || (id && id.indexOf('tt1254207') !== -1)) { // Big Buck Bunny
                combined.push({
                    name: 'Public Stream (No Debrid Needed)',
                    title: 'Big Buck Bunny (1080p MP4 Direct HTTP)',
                    url: 'https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4',
                    _addonName: 'Open Public Media'
                });
                combined.push({
                    name: 'HLS Stream (No Debrid Needed)',
                    title: 'Adaptive HLS Multi-Bitrate (.m3u8)',
                    url: 'https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8',
                    _addonName: 'Open Public Media'
                });
            } else if (id === 'tt2285752') { // Tears of Steel
                combined.push({
                    name: 'Public Stream (No Debrid Needed)',
                    title: 'Tears of Steel (1080p MP4 Direct HTTP)',
                    url: 'https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4',
                    _addonName: 'Open Public Media'
                });
            }

            for (var i = 0; i < results.length; i++) {
                combined = combined.concat(results[i]);
            }
            return combined;
        });
    };

    window.StremioApi = new StremioApi();

})(window);


/* --- End api.js --- */

/* --- Begin player-bridge.js --- */
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


/* --- End player-bridge.js --- */

/* --- Begin ui.js --- */
/**
 * Nuvio on GEN5W - Automotive Touch UI Manager
 *
 * Implements catalog browsing, search, addon management,
 * debrid stream resolution, and in-vehicle media playback controls.
 */
(function(window) {
    'use strict';

    function NuvioUI() {
        this.currentTab = 'movies';
        this.currentMeta = null;
        this.currentEpisode = null;
        this.isPlayerOsdVisible = false;
        this.osdHideTimeout = null;
    }

    NuvioUI.prototype.init = function() {
        var self = this;

        self.bindNavigation();
        self.bindSearch();
        self.bindAddonsTab();
        self.bindSettingsTab();
        self.bindDetailModal();
        self.bindPlayerOsd();
        self.bindPlayerBridgeEvents();

        // Load initial catalog
        self.switchTab('movies');
    };

    NuvioUI.prototype.switchTab = function(tabName) {
        this.currentTab = tabName;

        // Update nav buttons
        var navBtns = document.querySelectorAll('.nav-item');
        for (var i = 0; i < navBtns.length; i++) {
            var btn = navBtns[i];
            if (btn.getAttribute('data-tab') === tabName) {
                btn.className = 'nav-item active';
            } else {
                btn.className = 'nav-item';
            }
        }

        // Hide all views
        var views = document.querySelectorAll('.tab-view');
        for (var j = 0; j < views.length; j++) {
            views[j].style.display = 'none';
        }

        // Show target view
        var activeView = document.getElementById('view-' + tabName);
        if (activeView) {
            activeView.style.display = 'block';
        }

        if (tabName === 'movies') {
            this.loadCatalog('movie', 'top', 'catalog-movies-grid');
        } else if (tabName === 'series') {
            this.loadCatalog('series', 'top', 'catalog-series-grid');
        } else if (tabName === 'addons') {
            this.renderAddonsList();
        } else if (tabName === 'settings') {
            this.renderSettings();
        }
    };

    NuvioUI.prototype.bindNavigation = function() {
        var self = this;
        var navItems = document.querySelectorAll('.nav-item');
        for (var i = 0; i < navItems.length; i++) {
            navItems[i].addEventListener('click', function() {
                var tab = this.getAttribute('data-tab');
                self.switchTab(tab);
            });
        }
    };

    NuvioUI.prototype.loadCatalog = function(type, catalogId, containerId) {
        var container = document.getElementById(containerId);
        if (!container) return;

        container.innerHTML = '<div class="loader-spinner">Loading titles...</div>';

        var cinemeta = 'https://v3-cinemeta.strem.io/manifest.json';
        window.StremioApi.getCatalog(cinemeta, type, catalogId, 0)
            .then(function(items) {
                container.innerHTML = '';
                if (!items || items.length === 0) {
                    container.innerHTML = '<div class="empty-state">No titles available.</div>';
                    return;
                }

                for (var i = 0; i < items.length; i++) {
                    var card = NuvioUI.createMediaCard(items[i], type);
                    container.appendChild(card);
                }
            })
            .catch(function(err) {
                container.innerHTML = '<div class="error-state">Failed to load catalog: ' + err.message + '</div>';
            });
    };

    NuvioUI.createMediaCard = function(item, type) {
        var card = document.createElement('div');
        card.className = 'media-card';

        var posterImg = item.poster || 'data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" width="140" height="200" fill="%23222"><rect width="100%" height="100%"/></svg>';
        var year = item.releaseInfo || (item.year ? item.year : '');

        card.innerHTML =
            '<div class="card-poster-wrapper">' +
                '<img class="card-poster" src="' + posterImg + '" alt="' + (item.name || '') + '" onerror="this.src=\'data:image/svg+xml;utf8,<svg xmlns=\\\'http://www.w3.org/2000/svg\\\' width=\\\'140\\\' height=\\\'200\\\' fill=\\\'%23111\\\'><rect width=\\\'100%\\\' height=\\\'100%\\\'/></svg>\'">' +
                (item.imdbRating ? '<div class="card-badge">' + item.imdbRating + '</div>' : '') +
            '</div>' +
            '<div class="card-info">' +
                '<div class="card-title">' + (item.name || 'Untitled') + '</div>' +
                '<div class="card-meta">' + year + '</div>' +
            '</div>';

        card.addEventListener('click', function() {
            window.NuvioUI.openDetailModal(type, item.id);
        });

        return card;
    };

    NuvioUI.prototype.bindSearch = function() {
        var self = this;
        var searchInput = document.getElementById('searchQueryInput');
        var btnSearch = document.getElementById('btnExecuteSearch');
        var resultsGrid = document.getElementById('search-results-grid');

        var doSearch = function() {
            var q = (searchInput.value || '').trim();
            if (!q) return;

            resultsGrid.innerHTML = '<div class="loader-spinner">Searching...</div>';
            var cinemeta = 'https://v3-cinemeta.strem.io/manifest.json';

            window.StremioApi.searchCatalog(cinemeta, 'movie', 'top', q)
                .then(function(movieResults) {
                    return window.StremioApi.searchCatalog(cinemeta, 'series', 'top', q)
                        .then(function(seriesResults) {
                            var all = (movieResults || []).concat(seriesResults || []);
                            resultsGrid.innerHTML = '';
                            if (all.length === 0) {
                                resultsGrid.innerHTML = '<div class="empty-state">No matching titles found.</div>';
                                return;
                            }
                            for (var i = 0; i < all.length; i++) {
                                var card = NuvioUI.createMediaCard(all[i], all[i].type || 'movie');
                                resultsGrid.appendChild(card);
                            }
                        });
                })
                .catch(function(err) {
                    resultsGrid.innerHTML = '<div class="error-state">Search error: ' + err.message + '</div>';
                });
        }

        btnSearch.addEventListener('click', doSearch);
        searchInput.addEventListener('keydown', function(e) {
            if (e.keyCode === 13) {
                doSearch();
            }
        });
    };

    NuvioUI.prototype.openDetailModal = function(type, id) {
        var self = this;
        var modal = document.getElementById('detailModal');
        var body = document.getElementById('detailModalBody');
        modal.style.display = 'block';
        body.innerHTML = '<div class="loader-spinner">Loading details & streams...</div>';

        var cinemeta = 'https://v3-cinemeta.strem.io/manifest.json';
        window.StremioApi.getMeta(cinemeta, type, id)
            .then(function(meta) {
                if (!meta) {
                    body.innerHTML = '<div class="error-state">Title details could not be found.</div>';
                    return;
                }
                self.currentMeta = meta;
                self.renderDetailBody(type, meta);
            })
            .catch(function(err) {
                body.innerHTML = '<div class="error-state">Failed to load title: ' + err.message + '</div>';
            });
    };

    NuvioUI.prototype.renderDetailBody = function(type, meta) {
        var self = this;
        var body = document.getElementById('detailModalBody');

        var poster = meta.poster || '';
        var title = meta.name || '';
        var year = meta.year || meta.releaseInfo || '';
        var genres = (meta.genres || []).join(' • ');
        var desc = meta.description || 'No overview available.';

        var html =
            '<div class="detail-container">' +
                '<div class="detail-sidebar">' +
                    '<img class="detail-poster" src="' + poster + '" alt="' + title + '">' +
                '</div>' +
                '<div class="detail-main">' +
                    '<div class="detail-header">' +
                        '<h1 class="detail-title">' + title + '</h1>' +
                        '<div class="detail-sub">' + year + (genres ? ' | ' + genres : '') + '</div>' +
                    '</div>' +
                    '<p class="detail-description">' + desc + '</p>';

        if (type === 'series' && meta.videos && meta.videos.length > 0) {
            html += '<div class="episodes-section">' +
                        '<h3 class="section-subtitle">Episodes</h3>' +
                        '<div class="episodes-list" id="episodesList">';
            for (var i = 0; i < meta.videos.length; i++) {
                var ep = meta.videos[i];
                var epLabel = 'S' + ep.season + ' E' + ep.episode + ': ' + (ep.name || ep.title || 'Episode ' + ep.episode);
                html += '<button class="btn-episode" data-epid="' + ep.id + '">' + epLabel + '</button>';
            }
            html +=     '</div>' +
                    '</div>';
        }

        html +=     '<div class="streams-section">' +
                        '<h3 class="section-subtitle">Available Streams</h3>' +
                        '<div class="streams-list" id="streamsListContainer">' +
                            '<div class="loader-spinner">Querying addon streams...</div>' +
                        '</div>' +
                    '</div>' +
                '</div>' +
            '</div>';

        body.innerHTML = html;

        // Series episode selection
        if (type === 'series' && meta.videos && meta.videos.length > 0) {
            var epBtns = body.querySelectorAll('.btn-episode');
            for (var j = 0; j < epBtns.length; j++) {
                epBtns[j].addEventListener('click', function() {
                    for (var k = 0; k < epBtns.length; k++) epBtns[k].className = 'btn-episode';
                    this.className = 'btn-episode active';
                    var epId = this.getAttribute('data-epid');
                    self.loadStreamsForId(type, epId);
                });
            }
            // Auto-select first episode
            if (epBtns[0]) epBtns[0].click();
        } else {
            self.loadStreamsForId(type, meta.id);
        }
    };

    NuvioUI.prototype.loadStreamsForId = function(type, id) {
        var container = document.getElementById('streamsListContainer');
        if (!container) return;

        container.innerHTML = '<div class="loader-spinner">Querying community & debrid streams...</div>';

        window.StremioApi.getAllStreams(type, id)
            .then(function(streams) {
                container.innerHTML = '';
                if (!streams || streams.length === 0) {
                    container.innerHTML = '<div class="empty-state">No streams returned by installed addons.</div>';
                    return;
                }

                for (var i = 0; i < streams.length; i++) {
                    var s = streams[i];
                    var itemEl = document.createElement('div');
                    itemEl.className = 'stream-item';

                    var name = s.name || s._addonName || 'Stream';
                    var title = s.title || s.description || 'Direct Stream';
                    var hasDirectUrl = !!s.url;
                    var hasDebridConfigured = window.DebridService.hasActiveProvider();

                    var typeBadgeClass = 'stream-addon-badge';
                    var typeLabel = name;
                    if (hasDirectUrl) {
                        typeLabel += ' [Direct HTTP]';
                    } else if (hasDebridConfigured) {
                        typeLabel += ' [Debrid Cloud]';
                    } else {
                        typeLabel += ' [P2P - Debrid Req.]';
                        typeBadgeClass += ' warning';
                    }

                    var isHevc = /hevc|h\.?265|x265/i.test(title);
                    var isH264 = /h\.?264|x264|avc/i.test(title);
                    var codecBadge = '';
                    if (isHevc) {
                        codecBadge = ' <span class="stream-addon-badge warning" style="background-color:#7f1d1d;color:#fca5a5;border:1px solid #ef4444;">⚠️ x265/HEVC (KitKat Incompatible)</span>';
                    } else if (isH264) {
                        codecBadge = ' <span class="stream-addon-badge" style="background-color:#14532d;color:#86efac;border:1px solid #22c55e;">✅ H.264 (Compatible)</span>';
                    }

                    itemEl.innerHTML =
                        '<div class="stream-info">' +
                            '<span class="' + typeBadgeClass + '">' + typeLabel + '</span>' + codecBadge +
                            '<div class="stream-title-text">' + title.replace(/\n/g, ' ') + '</div>' +
                        '</div>' +
                        '<button class="btn btn-play-stream">&#9658; Play</button>';

                    (function(streamObj, btnEl) {
                        btnEl.addEventListener('click', function(e) {
                            window.NuvioUI.startPlayback(streamObj, btnEl);
                        });
                    })(s, itemEl.querySelector('.btn-play-stream'));

                    container.appendChild(itemEl);
                }
            })
            .catch(function(err) {
                container.innerHTML = '<div class="error-state">Failed to fetch streams: ' + err.message + '</div>';
            });
    };

    NuvioUI.prototype.startPlayback = function(streamObj, btnEl) {
        var self = this;
        var btn = btnEl || (window.event && window.event.target);
        if (btn) btn.textContent = 'Resolving...';

        window.DebridService.resolveStream(streamObj)
            .then(function(resolved) {
                if (btn) btn.textContent = 'Playing';
                // Close detail modal
                document.getElementById('detailModal').style.display = 'none';

                // Load stream over the native bridge
                window.PlayerBridge.loadMedia({
                    url: resolved.url,
                    title: (self.currentMeta ? self.currentMeta.name : '') + ' - ' + (resolved.filename || ''),
                    headers: resolved.headers
                });

                self.showPlayerOsd();
            })
            .catch(function(err) {
                if (btn) btn.textContent = '\u25BA Play';
                alert('Playback Error: ' + err.message);
            });
    };

    NuvioUI.prototype.bindDetailModal = function() {
        var modal = document.getElementById('detailModal');
        var btnClose = document.getElementById('btnDetailClose');
        btnClose.addEventListener('click', function() {
            modal.style.display = 'none';
        });
    };

    // In-Vehicle Player OSD (On-Screen Display)
    NuvioUI.prototype.bindPlayerOsd = function() {
        var self = this;
        var osd = document.getElementById('playerOsd');
        var btnCloseOsd = document.getElementById('btnOsdClose');
        var btnPlayPause = document.getElementById('btnOsdPlayPause');
        var btnBack15 = document.getElementById('btnOsdBack15');
        var btnFwd15 = document.getElementById('btnOsdFwd15');
        var btnAspect = document.getElementById('btnOsdAspect');
        var scrubSlider = document.getElementById('osdScrubSlider');

        btnCloseOsd.addEventListener('click', function() {
            window.PlayerBridge.stop();
            osd.style.display = 'none';
            self.isPlayerOsdVisible = false;
        });

        btnPlayPause.addEventListener('click', function() {
            window.PlayerBridge.toggle();
        });

        btnBack15.addEventListener('click', function() {
            var target = Math.max(0, window.PlayerBridge.currentPosition - 15000);
            window.PlayerBridge.seek(target);
        });

        btnFwd15.addEventListener('click', function() {
            var target = Math.min(window.PlayerBridge.duration, window.PlayerBridge.currentPosition + 15000);
            window.PlayerBridge.seek(target);
        });

        var aspectModes = ['fit', 'fill', 'stretch'];
        var currentAspectIdx = 0;
        btnAspect.addEventListener('click', function() {
            currentAspectIdx = (currentAspectIdx + 1) % aspectModes.length;
            var mode = aspectModes[currentAspectIdx];
            btnAspect.textContent = mode.toUpperCase();
            window.PlayerBridge.setAspectRatio(mode);
        });

        scrubSlider.addEventListener('change', function() {
            if (window.PlayerBridge.duration > 0) {
                var seekTarget = (scrubSlider.value / 100) * window.PlayerBridge.duration;
                window.PlayerBridge.seek(seekTarget);
            }
        });
    };

    NuvioUI.prototype.showPlayerOsd = function() {
        var osd = document.getElementById('playerOsd');
        osd.style.display = 'flex';
        this.isPlayerOsdVisible = true;
    };

    NuvioUI.prototype.bindPlayerBridgeEvents = function() {
        var self = this;
        var btnPlayPause = document.getElementById('btnOsdPlayPause');
        var scrubSlider = document.getElementById('osdScrubSlider');
        var timeLabel = document.getElementById('osdTimeDisplay');
        var parkBadge = document.getElementById('globalParkBadge');

        window.PlayerBridge.addListener(function(event, data) {
            switch (event) {
                case 'playStateChanged':
                    btnPlayPause.textContent = data.isPlaying ? '\u275A\u275A' : '\u25BA';
                    break;

                case 'timeupdate':
                    if (data.duration > 0) {
                        scrubSlider.value = Math.floor((data.position / data.duration) * 100);
                        timeLabel.textContent = formatMs(data.position) + ' / ' + formatMs(data.duration);
                    }
                    break;

                case 'parkChanged':
                    if (data.isParked) {
                        parkBadge.className = 'status-badge parked';
                        parkBadge.textContent = 'PARKED';
                    } else {
                        parkBadge.className = 'status-badge in-motion';
                        parkBadge.textContent = 'IN MOTION';
                    }
                    break;

                case 'stopped':
                    document.getElementById('playerOsd').style.display = 'none';
                    self.isPlayerOsdVisible = false;
                    break;
            }
        });
    };

    NuvioUI.prototype.bindAddonsTab = function() {
        var self = this;
        var btnInstall = document.getElementById('btnInstallAddon');
        var inputUrl = document.getElementById('addonUrlInput');

        btnInstall.addEventListener('click', function() {
            var url = (inputUrl.value || '').trim();
            if (!url) return;
            btnInstall.textContent = 'Installing...';
            window.AddonManager.installAddon(url)
                .then(function() {
                    btnInstall.textContent = 'Install Addon';
                    inputUrl.value = '';
                    self.renderAddonsList();
                })
                .catch(function(err) {
                    btnInstall.textContent = 'Install Addon';
                    alert('Addon Error: ' + err.message);
                });
        });
    };

    NuvioUI.prototype.renderAddonsList = function() {
        var container = document.getElementById('addonsListContainer');
        var addons = window.AddonManager.getAddons();
        container.innerHTML = '';

        for (var i = 0; i < addons.length; i++) {
            var a = addons[i];
            var m = a.manifest;
            var item = document.createElement('div');
            item.className = 'addon-card';

            item.innerHTML =
                '<div class="addon-info">' +
                    '<div class="addon-name">' + m.name + ' <span class="addon-version">v' + (m.version || '1.0') + '</span></div>' +
                    '<div class="addon-desc">' + (m.description || '') + '</div>' +
                '</div>' +
                '<button class="btn btn-danger btn-uninstall-addon" data-url="' + a.transportUrl + '">Uninstall</button>';

            (function(transportUrl) {
                item.querySelector('.btn-uninstall-addon').addEventListener('click', function() {
                    window.AddonManager.uninstallAddon(transportUrl);
                    window.NuvioUI.renderAddonsList();
                });
            })(a.transportUrl);

            container.appendChild(item);
        }
    };

    NuvioUI.prototype.bindSettingsTab = function() {
        var self = this;
        var btnSave = document.getElementById('btnSaveSettings');
        var rdInput = document.getElementById('inputRdToken');
        var torboxInput = document.getElementById('inputTorboxToken');
        var engineInput = document.getElementById('inputEngineUrl');

        function saveAll() {
            window.DebridService.setRealDebridToken(rdInput.value);
            window.DebridService.setTorboxToken(torboxInput.value);
            if (engineInput) {
                window.DebridService.setStremioEngineUrl(engineInput.value);
            }
        }

        // Auto-save on blur / input change so tokens are never lost
        rdInput.addEventListener('change', saveAll);
        torboxInput.addEventListener('change', saveAll);
        if (engineInput) engineInput.addEventListener('change', saveAll);

        btnSave.addEventListener('click', function() {
            saveAll();
            alert('Settings saved successfully.');
        });
    };

    NuvioUI.prototype.renderSettings = function() {
        var rdInput = document.getElementById('inputRdToken');
        var torboxInput = document.getElementById('inputTorboxToken');
        var engineInput = document.getElementById('inputEngineUrl');
        rdInput.value = window.DebridService.getRealDebridToken();
        torboxInput.value = window.DebridService.getTorboxToken();
        if (engineInput) {
            engineInput.value = window.DebridService.getStremioEngineUrl();
        }
    };

    function formatMs(ms) {
        if (!ms) return '00:00';
        var totalSec = Math.floor(ms / 1000);
        var mins = Math.floor(totalSec / 60);
        var secs = totalSec % 60;
        return (mins < 10 ? '0' : '') + mins + ':' + (secs < 10 ? '0' : '') + secs;
    }

    window.NuvioUI = new NuvioUI();

})(window);


/* --- End ui.js --- */

/* --- Begin app.js --- */
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

/* --- End app.js --- */
