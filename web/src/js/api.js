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

