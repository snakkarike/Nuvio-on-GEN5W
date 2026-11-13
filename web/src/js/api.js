/**
 * Nuvio on GEN5W - Stremio Addon Protocol API Client
 *
 * Implements /catalog, /meta, and /stream requests across registered addons.
 */
(function(window) {
    'use strict';

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

        return window.fetch(url, { timeout: 10000 })
            .then(function(res) {
                if (!res.ok) throw new Error('Catalog request failed with status ' + res.status);
                return res.json();
            })
            .then(function(data) {
                return (data && data.metas) ? data.metas : [];
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

        return window.fetch(url, { timeout: 10000 })
            .then(function(res) {
                if (!res.ok) throw new Error('Meta fetch failed with status ' + res.status);
                return res.json();
            })
            .then(function(data) {
                return (data && data.meta) ? data.meta : null;
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
            for (var i = 0; i < results.length; i++) {
                combined = combined.concat(results[i]);
            }
            return combined;
        });
    };

    window.StremioApi = new StremioApi();

})(window);

