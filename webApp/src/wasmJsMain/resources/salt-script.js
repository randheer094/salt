// Script runtime for the Network composer: a small, Postman-style `pm` API.
// Called from Kotlin as saltRun(code, contextJson) and returns JSON. The code runs in this tab, on the local Salt page.
// Supported: pm.environment / pm.variables / pm.globals, pm.request, pm.response, pm.test, pm.expect, console.log.
// Not supported: pm.sendRequest, async/await in tests, require().
(function () {
  const STATUS = {
    200: 'OK', 201: 'Created', 202: 'Accepted', 204: 'No Content', 301: 'Moved Permanently', 302: 'Found', 304: 'Not Modified',
    400: 'Bad Request', 401: 'Unauthorized', 403: 'Forbidden', 404: 'Not Found', 409: 'Conflict', 422: 'Unprocessable Entity',
    429: 'Too Many Requests', 500: 'Internal Server Error', 502: 'Bad Gateway', 503: 'Service Unavailable', 504: 'Gateway Timeout',
  };
  const show = (v) => { try { const s = JSON.stringify(v); return s === undefined ? String(v) : s; } catch (e) { return String(v); } };
  const same = (a, b) => show(a) === show(b);
  const typeOf = (v) => (v === null ? 'null' : Array.isArray(v) ? 'array' : typeof v);

  // Chai-like assertions: expect(x).to.equal(y), .to.have.property('a'), .to.be.above(3), .to.not.include('z') ...
  function expect(actual) {
    let negate = false;
    const api = {};
    const check = (ok, msg) => { if (negate ? ok : !ok) throw new Error(negate ? msg.replace('to ', 'not to ') : msg); };
    ['to', 'be', 'been', 'is', 'that', 'which', 'and', 'has', 'have', 'with', 'at', 'of', 'same', 'but', 'does', 'still', 'also', 'deep', 'a_', 'an_']
      .forEach((k) => Object.defineProperty(api, k, { get: () => api }));
    Object.defineProperty(api, 'not', { get: () => { negate = !negate; return api; } });
    const flag = (name, test, words) => Object.defineProperty(api, name, { get: () => { check(test(), `expected ${show(actual)} to ${words}`); return api; } });
    flag('ok', () => !!actual, 'be truthy');
    flag('true', () => actual === true, 'be true');
    flag('false', () => actual === false, 'be false');
    flag('null', () => actual === null, 'be null');
    flag('undefined', () => actual === undefined, 'be undefined');
    flag('exist', () => actual !== null && actual !== undefined, 'exist');
    flag('empty', () => actual == null || (typeof actual === 'object' ? Object.keys(actual).length === 0 : actual.length === 0), 'be empty');
    const method = (names, fn) => names.forEach((n) => { api[n] = (...args) => { fn(...args); return api; }; });
    method(['equal', 'equals', 'eq'], (v) => check(actual === v, `expected ${show(actual)} to equal ${show(v)}`));
    method(['eql', 'eqls'], (v) => check(same(actual, v), `expected ${show(actual)} to deeply equal ${show(v)}`));
    method(['include', 'includes', 'contain', 'contains'], (v) => check(
      typeof actual === 'string' ? actual.includes(v)
        : Array.isArray(actual) ? actual.some((x) => same(x, v))
        : actual != null && typeof v === 'object' && Object.keys(v).every((k) => same(actual[k], v[k])),
      `expected ${show(actual)} to include ${show(v)}`));
    method(['above', 'gt', 'greaterThan'], (v) => check(actual > v, `expected ${show(actual)} to be above ${show(v)}`));
    method(['below', 'lt', 'lessThan'], (v) => check(actual < v, `expected ${show(actual)} to be below ${show(v)}`));
    method(['least', 'gte'], (v) => check(actual >= v, `expected ${show(actual)} to be at least ${show(v)}`));
    method(['most', 'lte'], (v) => check(actual <= v, `expected ${show(actual)} to be at most ${show(v)}`));
    method(['a', 'an'], (t) => check(typeOf(actual) === String(t).toLowerCase(), `expected ${show(actual)} to be a ${t}`));
    method(['property'], (k, ...v) => check(actual != null && k in Object(actual) && (v.length === 0 || same(actual[k], v[0])), `expected ${show(actual)} to have property ${show(k)}${v.length ? ' = ' + show(v[0]) : ''}`));
    method(['lengthOf', 'length'], (n) => check(actual != null && actual.length === n, `expected ${show(actual)} to have length ${n}`));
    method(['match'], (re) => check(re.test(actual), `expected ${show(actual)} to match ${re}`));
    method(['oneOf'], (list) => check(list.some((x) => same(x, actual)), `expected ${show(actual)} to be one of ${show(list)}`));
    return api;
  }

  globalThis.saltRun = function (code, contextJson) {
    const ctx = JSON.parse(contextJson);
    const env = Object.assign({}, ctx.env || {});
    const locals = {};
    const req = ctx.request;
    const tests = [];
    const logs = [];
    const log = (...a) => logs.push(a.map((x) => (typeof x === 'string' ? x : show(x))).join(' '));
    const con = { log, info: log, warn: log, error: log, debug: log };

    const variables = (store, fallback) => ({
      get: (k) => (k in store ? store[k] : fallback ? fallback[k] : undefined),
      set: (k, v) => { store[k] = String(v); },
      unset: (k) => { delete store[k]; },
      has: (k) => k in store || (!!fallback && k in fallback),
      clear: () => { Object.keys(store).forEach((k) => delete store[k]); },
      toObject: () => Object.assign({}, fallback || {}, store),
      replaceIn: (s) => String(s).replace(/\{\{\s*([^{}]+?)\s*\}\}/g, (m, k) => (k in store ? store[k] : fallback && k in fallback ? fallback[k] : m)),
    });
    const headerApi = (list) => ({
      get: (n) => { const h = list.find((x) => x.name.toLowerCase() === String(n).toLowerCase()); return h ? h.value : undefined; },
      has: (n) => list.some((x) => x.name.toLowerCase() === String(n).toLowerCase()),
      all: () => list.slice(),
      toObject: () => Object.fromEntries(list.map((h) => [h.name, h.value])),
    });

    const pm = {
      environment: variables(env),
      variables: variables(locals, env),
      globals: variables(env),             // globals and collection variables live in the active environment
      collectionVariables: variables(env),
      expect,
      test: (name, fn) => {
        try { fn(); tests.push({ name, ok: true }); }
        catch (e) { tests.push({ name, ok: false, error: String((e && e.message) || e) }); }
      },
      request: {
        get method() { return req.method; }, set method(v) { req.method = String(v).toUpperCase(); },
        get url() { return req.url; }, set url(v) { req.url = String(v); },
        headers: Object.assign(headerApi(req.headers), {
          add: (h) => req.headers.push({ name: h.key || h.name, value: String(h.value) }),
          upsert: (h) => {
            const name = h.key || h.name;
            const i = req.headers.findIndex((x) => x.name.toLowerCase() === String(name).toLowerCase());
            if (i >= 0) req.headers[i].value = String(h.value); else req.headers.push({ name, value: String(h.value) });
          },
          remove: (n) => { const i = req.headers.findIndex((x) => x.name.toLowerCase() === String(n).toLowerCase()); if (i >= 0) req.headers.splice(i, 1); },
        }),
        body: { get raw() { return req.body; }, set raw(v) { req.body = String(v); } },
      },
    };

    if (ctx.response) {
      const r = ctx.response;
      const asserts = (name, ok, words) => { if (!ok) throw new Error(`expected response ${words}`); };
      pm.response = {
        code: r.status, status: STATUS[r.status] || '', responseTime: r.durationMs,
        headers: headerApi(r.headers || []),
        text: () => r.body,
        json: () => JSON.parse(r.body),
        to: {
          have: {
            status: (s) => asserts('status', typeof s === 'number' ? r.status === s : (STATUS[r.status] || '') === s, `to have status ${s}, got ${r.status}`),
            header: (n, v) => { const h = (r.headers || []).find((x) => x.name.toLowerCase() === String(n).toLowerCase()); asserts('header', !!h && (v === undefined || h.value === v), `to have header ${n}${v === undefined ? '' : ' = ' + v}`); },
          },
          be: {
            get ok() { asserts('ok', r.status >= 200 && r.status < 300, `to be ok (2xx), got ${r.status}`); return true; },
            get success() { asserts('success', r.status >= 200 && r.status < 300, `to be successful, got ${r.status}`); return true; },
            get clientError() { asserts('clientError', r.status >= 400 && r.status < 500, `to be a client error, got ${r.status}`); return true; },
            get serverError() { asserts('serverError', r.status >= 500 && r.status < 600, `to be a server error, got ${r.status}`); return true; },
          },
        },
      };
    }

    let error = null;
    try {
      new Function('pm', 'console', 'expect', code)(pm, con, expect);
    } catch (e) {
      error = String((e && e.message) || e);
    }
    return JSON.stringify({ env, request: req, tests, logs, error });
  };
})();
