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

    // 4. Lightweight ES6 Promise A+ Implementation
    if (typeof window.Promise !== 'function') {
        var PENDING = 0, FULFILLED = 1, REJECTED = 2;

        function PromisePolyfill(executor) {
            var self = this;
            self.state = PENDING;
            self.value = undefined;
            self.handlers = [];

            function resolve(result) {
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
            }

            function reject(error) {
                if (self.state !== PENDING) return;
                self.state = REJECTED;
                self.value = error;
                executeHandlers();
            }

            function executeHandlers() {
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
            }

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
        }

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

