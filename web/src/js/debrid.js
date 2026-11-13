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

