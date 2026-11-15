package com.kerybotu.derpibooru.mirror.auth

import org.json.JSONObject

/** A bridge notification is only a hint; LoginActivity re-evaluates evidence in the main document. */
internal object LoginDomObserver {
    fun script(origin: String, flow: String): String = """
        (function() {
          if (window !== window.top || location.origin !== ${JSONObject.quote(origin)}) return;
          if (window.__dvLoginWatch) { window.__dvLoginWatch.check(); return; }
          var attemptKey = ${JSONObject.quote("dv-login-$flow")};
          var token = String(performance.timeOrigin) + ':' + Math.random();
          var stopped = false, queued = false, notified = false, signalAt = 0;
          function visible(el) {
            if (!el || !el.isConnected || !el.getClientRects().length) return false;
            for (var p = el; p && p.nodeType === 1; p = p.parentElement) {
              var s = getComputedStyle(p);
              if (p.hidden || p.getAttribute('aria-hidden') === 'true' ||
                  s.display === 'none' || s.visibility !== 'visible' || Number(s.opacity) === 0) return false;
            }
            return el.getBoundingClientRect().width > 0 && el.getBoundingClientRect().height > 0;
          }
          function attempted() {
            try { return sessionStorage.getItem(attemptKey) === 'submitted'; } catch (_) { return false; }
          }
          function evidence() {
            if (document.visibilityState !== 'visible') return false;
            // Retain the existing success flash, but only after this flow submitted the login form.
            var flash = Array.from(document.querySelectorAll('.flash.flash--success')).some(visible);
            var returned = location.pathname !== '/sessions/new' && location.pathname !== '/sessions';
            // Authenticated account controls also handle an already logged-in WebView session.
            var account = Array.from(document.querySelectorAll(
              'header a[data-method="delete"][href], .header a[data-method="delete"][href], header form[action], .header form[action]'
            )).some(function(el) {
              var u;
              try { u = new URL(el.getAttribute('href') || el.getAttribute('action'), location.href); }
              catch (_) { return false; }
              if (u.origin !== location.origin || !visible(el)) return false;
              var method = (el.getAttribute('data-method') ||
                  (el.querySelector('input[name="_method"]') || {}).value || '').toLowerCase();
              return u.pathname === '/sessions' && method === 'delete';
            });
            return account || (attempted() && returned && flash);
          }
          function stop() {
            stopped = true;
            observer.disconnect();
            document.removeEventListener('submit', submitted, true);
            document.removeEventListener('DOMContentLoaded', check);
            document.removeEventListener('visibilitychange', check);
            document.removeEventListener('transitionend', check, true);
            document.removeEventListener('animationend', check, true);
            document.removeEventListener('load', check, true);
            window.removeEventListener('pageshow', shown);
            window.removeEventListener('pagehide', hidden);
          }
          function check() {
            if (stopped || queued || notified) return;
            queued = true;
            requestAnimationFrame(function() {
              queued = false;
              if (stopped || notified || !evidence()) return;
              notified = true;
              signalAt = performance.now();
              observer.disconnect();
              window.AndroidLoginObserver.changed(token);
            });
          }
          function submitted(event) {
            var form = event.target, u;
            try { u = new URL(form.action, location.href); } catch (_) { return; }
            if (u.origin === location.origin && u.pathname === '/sessions' &&
                form.method.toLowerCase() === 'post') {
              try { sessionStorage.setItem(attemptKey, 'submitted'); } catch (_) {}
            }
          }
          var observer = new MutationObserver(check);
          function observe() {
            observer.observe(document, {subtree:true, childList:true, attributes:true,
              attributeFilter:['class','style','hidden','aria-hidden','href','action','data-method','value']});
          }
          function hidden() { stopped = true; observer.disconnect(); }
          function shown() { stopped = false; queued = false; notified = false; observe(); check(); }
          observe();
          window.__dvLoginWatch = {token:token, check:check, stop:stop,
            resume:function(expected) {
              if (!stopped && expected === token) { notified = false; observe(); check(); }
            },
            verify:function(expected) {
              return !stopped && expected === token && evidence()
                ? JSON.stringify({valid:true, elapsedMs:Math.round(performance.now() - signalAt)}) : null;
            }};
          document.addEventListener('submit', submitted, true);
          document.addEventListener('DOMContentLoaded', check);
          document.addEventListener('visibilitychange', check);
          document.addEventListener('transitionend', check, true);
          document.addEventListener('animationend', check, true);
          document.addEventListener('load', check, true);
          window.addEventListener('pageshow', shown);
          window.addEventListener('pagehide', hidden);
          check();
        })();
    """.trimIndent()
}
