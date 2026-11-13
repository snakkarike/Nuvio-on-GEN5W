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

    function DebridService() {
        this.rdToken = '';
        this.torboxToken = '';
        this.loadKeys();
    }

    DebridService.prototype.loadKeys = function() {
        try {
            this.rdToken = localStorage.getItem(STORAGE_KEY_RD) || '';
            this.torboxToken = localStorage.getItem(STORAGE_KEY_TORBOX) || '';
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

    DebridService.prototype.getRealDebridToken = function() {
        return this.rdToken;
    };

    DebridService.prototype.getTorboxToken = function() {
        return this.torboxToken;
    };

    DebridService.prototype.hasActiveProvider = function() {
        return !!(this.rdToken || this.torboxToken);
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
     * Resolves a Stremio stream item into a direct HTTP playable stream.
     */
    DebridService.prototype.resolveStream = function(streamItem) {
        var self = this;

        // 1. Direct URL already present (e.g. Torrentio-RD pre-resolved or direct HTTP stream)
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

        // 2. InfoHash provided (Torrent needing Debrid resolution)
        if (streamItem.infoHash) {
            if (!self.rdToken) {
                return window.Promise.reject(new Error('This stream requires a Real-Debrid token to stream on Gen5W.'));
            }

            // Real-Debrid torrent addition and resolution flow
            var addMagnetUrl = 'https://api.real-debrid.com/rest/1.0/torrents/addMagnet';
            var magnetBody = 'magnet=magnet:?xt=urn:btih:' + streamItem.infoHash;

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
        }

        return window.Promise.reject(new Error('Unsupported stream format.'));
    };

    window.DebridService = new DebridService();

})(window);

