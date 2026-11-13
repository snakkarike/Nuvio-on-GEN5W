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

        function doSearch() {
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

                    itemEl.innerHTML =
                        '<div class="stream-info">' +
                            '<span class="stream-addon-badge">' + name + '</span>' +
                            '<div class="stream-title-text">' + title.replace(/\n/g, ' ') + '</div>' +
                        '</div>' +
                        '<button class="btn btn-play-stream">&#9658; Play</button>';

                    (function(streamObj) {
                        itemEl.querySelector('.btn-play-stream').addEventListener('click', function() {
                            window.NuvioUI.startPlayback(streamObj);
                        });
                    })(s);

                    container.appendChild(itemEl);
                }
            })
            .catch(function(err) {
                container.innerHTML = '<div class="error-state">Failed to fetch streams: ' + err.message + '</div>';
            });
    };

    NuvioUI.prototype.startPlayback = function(streamObj) {
        var self = this;
        var btn = event.target;
        btn.textContent = 'Resolving...';

        window.DebridService.resolveStream(streamObj)
            .then(function(resolved) {
                btn.textContent = 'Playing';
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
                btn.textContent = '\u25BA Play';
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

        btnSave.addEventListener('click', function() {
            window.DebridService.setRealDebridToken(rdInput.value);
            window.DebridService.setTorboxToken(torboxInput.value);
            alert('Settings saved successfully.');
        });
    };

    NuvioUI.prototype.renderSettings = function() {
        var rdInput = document.getElementById('inputRdToken');
        var torboxInput = document.getElementById('inputTorboxToken');
        rdInput.value = window.DebridService.getRealDebridToken();
        torboxInput.value = window.DebridService.getTorboxToken();
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

