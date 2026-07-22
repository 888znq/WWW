// ==UserScript==
// @name         TRINITY PAIR SYNC — Omni-Broker Edition (v7, Android bridge build)
// @namespace    trinity-os
// @version      7.2-android
// @description  Auto-switches asset pair on OlympTrade AND PocketOption via native AndroidClipboard bridge or postMessage.
// @author       TRINITY OS
// @match        https://olymptrade.com/*
// @match        https://*.olymptrade.com/*
// @match        https://pocketoption.com/*
// @match        https://*.pocketoption.com/*
// @grant        none
// @run-at       document-idle
// ==/UserScript==
//
// Injected by MainActivity.java via WebView.evaluateJavascript() on page load
// (native WebView has no Tampermonkey/extension host, so this file is loaded
// as a plain asset string and run directly instead of being auto-matched).
//
// The only functional change from the desktop v7.2 script is inside
// checkClipboard(): the Web Clipboard permission dance (navigator.permissions
// + navigator.clipboard.readText()) doesn't work in Android WebView — there's
// no permission prompt surface for it, so it always throws NotAllowedError.
// That call is now routed through window.AndroidClipboard.read(), a
// @JavascriptInterface bridge backed by Android's native ClipboardManager,
// with a fallback to the original Web Clipboard call so this same file still
// behaves correctly if ever loaded somewhere AndroidClipboard isn't defined.

(function TRINITY_PAIR_SYNC_OMNI() {
    'use strict';

    if (window.__trinitySyncInjected) return;
    window.__trinitySyncInjected = true;

    // ── CONFIG ────────────────────────────────────────────────────
    const POLL_MS  = 400;
    const DEBOUNCE = 2500;
    const SHOW_HUD = true;

    // ── DETECT BROKER ─────────────────────────────────────────────
    const IS_OT = location.hostname.includes('olymptrade.com');
    const IS_PO = location.hostname.includes('pocketoption.com');

    // ── STATE & LOGGING ───────────────────────────────────────────
    let lastSwitchedPair = null;
    let lastSwitchTime   = 0;
    let switchCount      = 0;
    let pollId           = null;
    let clipboardGranted = false;
    let hudEl            = null;
    let lastClipboardRaw = null; // FIX: tracks raw clipboard content so we only react on actual change

    const log  = (m) => console.log(`%c[TRINITY-SYNC] ${m}`, 'color:#00E5FF;font-weight:bold');
    const warn = (m) => console.warn(`%c[TRINITY-SYNC] ${m}`, 'color:#FFD700;font-weight:bold');
    const ok   = (m) => console.log(`%c[TRINITY-SYNC] ✅ ${m}`, 'color:#00E676;font-weight:bold');

    // ── PAIR DATA (AOFS 28-Pair List) ──────────────────────────────
    const PAIRS = {
        // ── CRYPTO ──
        'BTCUSD': { ot: '[data-test="asset-select-button-BTCUSD/ftt"]', poLabel: 'BTC/USD', keywords: ['btcusd', 'btc/usd', 'bitcoin'], display: '₿ BTCUSD' },
        'ETHUSD': { ot: '[data-test="asset-select-button-ETHUSD/ftt"]', poLabel: 'ETH/USD', keywords: ['ethusd', 'eth/usd', 'ethereum'], display: 'Ξ ETHUSD' },

        // ── METALS ──
        'XAGUSD': { ot: '[data-test="asset-select-button-XAGUSD/ftt"]', poLabel: 'XAG/USD', keywords: ['xagusd', 'xag/usd'], display: '🥈 XAGUSD' },
        'XAUUSD': { ot: '[data-test="asset-select-button-XAUUSD/ftt"]', poLabel: 'XAU/USD', keywords: ['xauusd', 'xau/usd'], display: '🥇 XAUUSD' },
        'XPTUSD': { ot: '[data-test="asset-select-button-PL/ftt"]', poLabel: 'XPT/USD', keywords: ['pl', 'platinum', 'xptusd', 'xpt/usd'], display: '⚪ XPTUSD' },

        // ── FOREX (AOFS 28-Pair List) ──
        // AUD crosses
        'AUDCAD': { ot: '[data-test="asset-select-button-AUDCAD/ftt"]', poLabel: 'AUD/CAD', keywords: ['audcad', 'aud/cad'], display: '💱 AUDCAD' },
        'AUDCHF': { ot: '[data-test="asset-select-button-AUDCHF/ftt"]', poLabel: 'AUD/CHF', keywords: ['audchf', 'aud/chf'], display: '💱 AUDCHF' },
        'AUDJPY': { ot: '[data-test="asset-select-button-AUDJPY/ftt"]', poLabel: 'AUD/JPY', keywords: ['audjpy', 'aud/jpy'], display: '💱 AUDJPY' },
        'AUDNZD': { ot: '[data-test="asset-select-button-AUDNZD/ftt"]', poLabel: 'AUD/NZD', keywords: ['audnzd', 'aud/nzd'], display: '💱 AUDNZD' },
        'AUDUSD': { ot: '[data-test="asset-select-button-AUDUSD/ftt"]', poLabel: 'AUD/USD', keywords: ['audusd', 'aud/usd'], display: '💱 AUDUSD' },
        // EUR crosses
        'EURAUD': { ot: '[data-test="asset-select-button-EURAUD/ftt"]', poLabel: 'EUR/AUD', keywords: ['euraud', 'eur/aud'], display: '💱 EURAUD' },
        'EURCAD': { ot: '[data-test="asset-select-button-EURCAD/ftt"]', poLabel: 'EUR/CAD', keywords: ['eurcad', 'eur/cad'], display: '💱 EURCAD' },
        'EURCHF': { ot: '[data-test="asset-select-button-EURCHF/ftt"]', poLabel: 'EUR/CHF', keywords: ['eurchf', 'eur/chf'], display: '💱 EURCHF' },
        'EURGBP': { ot: '[data-test="asset-select-button-EURGBP/ftt"]', poLabel: 'EUR/GBP', keywords: ['eurgbp', 'eur/gbp'], display: '💱 EURGBP' },
        'EURJPY': { ot: '[data-test="asset-select-button-EURJPY/ftt"]', poLabel: 'EUR/JPY', keywords: ['eurjpy', 'eur/jpy'], display: '💱 EURJPY' },
        'EURNZD': { ot: '[data-test="asset-select-button-EURNZD/ftt"]', poLabel: 'EUR/NZD', keywords: ['eurnzd', 'eur/nzd'], display: '💱 EURNZD' },
        'EURUSD': { ot: '[data-test="asset-select-button-EURUSD/ftt"]', poLabel: 'EUR/USD', keywords: ['eurusd', 'eur/usd'], display: '💱 EURUSD' },
        // GBP crosses
        'GBPAUD': { ot: '[data-test="asset-select-button-GBPAUD/ftt"]', poLabel: 'GBP/AUD', keywords: ['gbpaud', 'gbp/aud'], display: '💱 GBPAUD' },
        'GBPCAD': { ot: '[data-test="asset-select-button-GBPCAD/ftt"]', poLabel: 'GBP/CAD', keywords: ['gbpcad', 'gbp/cad'], display: '💱 GBPCAD' },
        'GBPCHF': { ot: '[data-test="asset-select-button-GBPCHF/ftt"]', poLabel: 'GBP/CHF', keywords: ['gbpchf', 'gbp/chf'], display: '💱 GBPCHF' },
        'GBPJPY': { ot: '[data-test="asset-select-button-GBPJPY/ftt"]', poLabel: 'GBP/JPY', keywords: ['gbpjpy', 'gbp/jpy'], display: '💱 GBPJPY' },
        'GBPNZD': { ot: '[data-test="asset-select-button-GBPNZD/ftt"]', poLabel: 'GBP/NZD', keywords: ['gbpnzd', 'gbp/nzd'], display: '💱 GBPNZD' },
        'GBPUSD': { ot: '[data-test="asset-select-button-GBPUSD/ftt"]', poLabel: 'GBP/USD', keywords: ['gbpusd', 'gbp/usd'], display: '💱 GBPUSD' },
        // NZD crosses
        'NZDJPY': { ot: '[data-test="asset-select-button-NZDJPY/ftt"]', poLabel: 'NZD/JPY', keywords: ['nzdjpy', 'nzd/jpy'], display: '💱 NZDJPY' },
        'NZDUSD': { ot: '[data-test="asset-select-button-NZDUSD/ftt"]', poLabel: 'NZD/USD', keywords: ['nzdusd', 'nzd/usd'], display: '💱 NZDUSD' },
        // USD crosses
        'USDCAD': { ot: '[data-test="asset-select-button-USDCAD/ftt"]', poLabel: 'USD/CAD', keywords: ['usdcad', 'usd/cad'], display: '💱 USDCAD' },
        'USDCHF': { ot: '[data-test="asset-select-button-USDCHF/ftt"]', poLabel: 'USD/CHF', keywords: ['usdchf', 'usd/chf'], display: '💱 USDCHF' },
        'USDJPY': { ot: '[data-test="asset-select-button-USDJPY/ftt"]', poLabel: 'USD/JPY', keywords: ['usdjpy', 'usd/jpy'], display: '💱 USDJPY' },
        'USDMXN': { ot: '[data-test="asset-select-button-USDMXN/ftt"]', poLabel: 'USD/MXN', keywords: ['usdmxn', 'usd/mxn'], display: '💱 USDMXN' },
        'USDNOK': { ot: '[data-test="asset-select-button-USDNOK/ftt"]', poLabel: 'USD/NOK', keywords: ['usdnok', 'usd/nok'], display: '💱 USDNOK' },
    };

    // ── HUD INJECTION & ANIMATIONS ────────────────────────────────
    function buildHUD() {
        if (!SHOW_HUD) return;

        const styleEl = document.createElement('style');
        styleEl.innerHTML = `
            @keyframes pulseBull {
                0%, 100% { text-shadow: 0 0 5px #2ED573; opacity: 1; transform: translateY(0px); }
                50% { text-shadow: 0 0 15px #2ED573; opacity: 0.5; transform: translateY(-3px); }
            }
            @keyframes pulseBear {
                0%, 100% { text-shadow: 0 0 5px #E63946; opacity: 1; transform: translateY(0px); }
                50% { text-shadow: 0 0 15px #E63946; opacity: 0.5; transform: translateY(3px); }
            }
            .trinity-bull { display: inline-block; color: #2ED573; font-size: 14px; animation: pulseBull 1.5s infinite ease-in-out; }
            .trinity-bear { display: inline-block; color: #E63946; font-size: 14px; animation: pulseBear 1.5s infinite ease-in-out; }
        `;
        document.head.appendChild(styleEl);

        hudEl = document.createElement('div');
        hudEl.id = '_trinity_sync_hud';
        hudEl.style.cssText = `
            position: fixed; bottom: 25px; right: 25px;
            background: rgba(0, 4, 50, 0.85); backdrop-filter: blur(4px);
            border: 1px solid #00E5FF; border-radius: 4px;
            padding: 8px 14px; z-index: 999999;
            font-family: 'JetBrains Mono', monospace; font-weight: 800; letter-spacing: 1px;
            display: flex; align-items: center; justify-content: center; gap: 12px;
            box-shadow: 0 4px 15px rgba(0, 229, 255, 0.3); pointer-events: none; user-select: none;
            transition: all 0.3s;
        `;
        hudEl.innerHTML = `
            <span class="trinity-bull">▲</span>
            <span id="trinity-hud-pair" style="color: #00E5FF; font-size: 12px; min-width: 90px; text-align: center;">WATCHING</span>
            <span class="trinity-bear">▼</span>
        `;
        document.body.appendChild(hudEl);
    }

    function updateHUD(pairStr) {
        if (!hudEl) return;
        const span = document.getElementById('trinity-hud-pair');
        if (span) {
            span.innerText = PAIRS[pairStr] ? PAIRS[pairStr].display : pairStr;
            hudEl.style.borderColor = '#00E676';
            hudEl.style.boxShadow = '0 0 15px rgba(0, 230, 118, 0.5)';
            span.style.color = '#FFFFFF';
            span.style.textShadow = '0 0 10px #FFFFFF';

            setTimeout(() => {
                span.style.color = '#FFD700';
                span.style.textShadow = 'none';
                hudEl.style.borderColor = '#00E5FF';
                hudEl.style.boxShadow = '0 4px 15px rgba(0, 229, 255, 0.3)';
            }, 600);
        }
    }

    // ── OLYMPTRADE DOM SEARCH (4-Tier Fallback) ───────────────────
    function findOTTabElement(config) {
        // Strategy 1: Direct data-test
        try {
            const el = document.querySelector(config.ot);
            if (el) return { el, strategy: 'data-test (primary)' };
        } catch (e) {}

        // Strategy 2: data-asset-tab + keyword text
        try {
            for (const el of document.querySelectorAll('[data-asset-tab="true"]')) {
                const text = el.textContent.trim().toLowerCase();
                if (config.keywords.some(kw => text.includes(kw))) return { el, strategy: 'data-asset-tab + text' };
            }
        } catch (e) {}

        // Strategy 3: data-test prefix match
        try {
            for (const el of document.querySelectorAll('[data-test^="asset-select-button"]')) {
                const attr = (el.getAttribute('data-test') || '').toLowerCase();
                if (config.keywords.some(k => attr.includes(k))) return { el, strategy: 'data-test prefix-match' };
            }
        } catch (e) {}

        // Strategy 4: DOM Text Walk
        const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_ELEMENT);
        let best = null;
        while (walker.nextNode()) {
            const el = walker.currentNode;
            const tag = el.tagName.toLowerCase();
            if (!['div','span','button','li','a','p','label'].includes(tag)) continue;

            const text = el.textContent.trim().toLowerCase().replace(/[\s\/]+/g, '');
            const match = config.keywords.find(kw => text === kw.replace(/[\s\/]+/g, ''));
            if (match) {
                let target = el;
                for (let i = 0; i < 6; i++) {
                    const s = window.getComputedStyle(target);
                    if (s.cursor === 'pointer' || target.getAttribute('data-asset-tab') === 'true' || target.tagName === 'BUTTON') break;
                    if (target.parentElement) target = target.parentElement; else break;
                }
                const s = window.getComputedStyle(target);
                if (s.cursor === 'pointer' || target.getAttribute('data-asset-tab') === 'true') {
                    return { el: target, strategy: `text-walk: "${match}"` };
                }
                if (!best) best = { el: target, strategy: `text-walk fallback: "${match}"` };
            }
        }
        return best;
    }

    // ── POCKETOPTION DOM SEARCH ───────────────────────────────────
    function findPOElement(config) {
        const label = config.poLabel;

        // Strategy 1: data-value attribute (most reliable on PO)
        try {
            const el = document.querySelector(`[data-value="${label}"]`);
            if (el) return { el, strategy: 'data-value' };
        } catch (e) {}

        // Strategy 2: aria-label attribute
        try {
            const el = document.querySelector(`[aria-label="${label}"]`);
            if (el) return { el, strategy: 'aria-label' };
        } catch (e) {}

        // Strategy 3: Exact text match on known PO list-item selectors
        try {
            for (const el of document.querySelectorAll('.symbol-list__item, .assets-list__item, .asset-item, li[class*="asset"], li[class*="symbol"]')) {
                if (el.textContent.trim().includes(label)) return { el, strategy: 'class-list item' };
            }
        } catch (e) {}

        // Strategy 4: Broad text walk
        const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_ELEMENT);
        while (walker.nextNode()) {
            const el = walker.currentNode;
            const tag = el.tagName.toLowerCase();
            if (!['div','span','button','li','a','p','label'].includes(tag)) continue;
            if (el.textContent.trim() === label) {
                let target = el;
                for (let i = 0; i < 5; i++) {
                    const s = window.getComputedStyle(target);
                    if (s.cursor === 'pointer' || target.tagName === 'BUTTON' || target.tagName === 'A') break;
                    if (target.parentElement) target = target.parentElement; else break;
                }
                return { el: target, strategy: `text-walk: "${label}"` };
            }
        }
        return null;
    }

    // React Synthetic Click Simulator
    function nativeClick(el) {
        const rect = el.getBoundingClientRect();
        const cx = rect.left + rect.width / 2;
        const cy = rect.top + rect.height / 2;
        const common = { bubbles: true, cancelable: true, clientX: cx, clientY: cy };
        ['mouseover', 'mousedown', 'mouseup', 'click'].forEach(type => el.dispatchEvent(new MouseEvent(type, common)));
    }

    // ── OLYMPTRADE SWITCH ─────────────────────────────────────────
    function switchOT(pairStr, pairConfig) {
        let result = findOTTabElement(pairConfig);

        if (result) {
            ok(`OT Switch to ${pairStr} via [${result.strategy}]`);
            nativeClick(result.el);
        } else {
            warn(`OT Tab hidden. Opening menu for ${pairStr}...`);
            const menuBtn = document.querySelector('[data-test="asset-select-button"]')
                         || document.querySelector('[data-test="asset-picker-button"]');
            if (menuBtn) nativeClick(menuBtn);

            setTimeout(() => {
                const retryResult = findOTTabElement(pairConfig);
                if (retryResult) {
                    ok(`OT Switch (Post-Menu) to ${pairStr} via [${retryResult.strategy}]`);
                    nativeClick(retryResult.el);
                } else {
                    warn(`OT Switch completely failed for ${pairStr}.`);
                }
            }, 600);
        }
    }

    // ── POCKETOPTION SWITCH ───────────────────────────────────────
    function switchPO(pairStr, pairConfig) {
        ok(`PO Switch initiated for ${pairStr} (${pairConfig.poLabel})`);

        // Open the asset picker — try multiple known PO selectors
        const menuBtn = document.querySelector('.current-symbol')
                     || document.querySelector('[class*="current-symbol"]')
                     || document.querySelector('[class*="asset-select"]')
                     || document.querySelector('[class*="symbol-select"]');

        if (menuBtn) {
            nativeClick(menuBtn);
        } else {
            warn(`PO: Could not find asset menu button for ${pairStr}.`);
        }

        // After menu opens, find and click the target asset
        setTimeout(() => {
            const result = findPOElement(pairConfig);
            if (result) {
                ok(`PO Switch to ${pairStr} via [${result.strategy}]`);
                nativeClick(result.el);
            } else {
                warn(`PO Target not found: ${pairConfig.poLabel}. Retrying after search input...`);

                // Last resort: try typing into a search box if PO has one
                const searchInput = document.querySelector('input[placeholder*="Search"], input[placeholder*="search"], input[class*="search"]');
                if (searchInput) {
                    searchInput.value = pairConfig.poLabel;
                    searchInput.dispatchEvent(new Event('input', { bubbles: true }));
                    searchInput.dispatchEvent(new Event('change', { bubbles: true }));

                    setTimeout(() => {
                        const retryResult = findPOElement(pairConfig);
                        if (retryResult) {
                            ok(`PO Switch (Post-Search) to ${pairStr} via [${retryResult.strategy}]`);
                            nativeClick(retryResult.el);
                        } else {
                            warn(`PO Switch completely failed for ${pairStr}.`);
                        }
                    }, 500);
                }
            }
        }, POLL_MS);
    }

    // ── MASTER EXECUTION LOGIC ────────────────────────────────────
    // FIX: switching is now gated purely on the pair actually being different
    // from the last one we switched to — no time-based re-trigger. Previously,
    // once DEBOUNCE (2.5s) elapsed, unchanged clipboard content would still
    // cause a re-switch, which on OlympTrade could pop open the full pair-select
    // window mid-session even without any new manual selection.
    function executeSwitch(pairStr) {
        if (pairStr === lastSwitchedPair) return;

        const pairConfig = PAIRS[pairStr];
        if (!pairConfig) return;

        lastSwitchedPair = pairStr;
        lastSwitchTime = Date.now();
        switchCount++;
        updateHUD(pairStr);

        if (IS_OT)      switchOT(pairStr, pairConfig);
        else if (IS_PO) switchPO(pairStr, pairConfig);
        else            warn(`Unknown broker — cannot switch to ${pairStr}.`);
    }

    // ── EVENT TRIGGERS (ANDROID CLIPBOARD BRIDGE & POSTMESSAGE) ────
    // ANDROID FIX: the desktop version gated this on
    // navigator.permissions.query({name:'clipboard-read'}) + navigator.clipboard.readText().
    // Neither exists in Android WebView — navigator.permissions is undefined
    // there and the Web Clipboard API throws NotAllowedError with no way to
    // grant it (no onPermissionRequest() surface for it). So the read now
    // goes through window.AndroidClipboard.read(), a native ClipboardManager
    // bridge set up in MainActivity.java, with the original Web Clipboard
    // call kept as a fallback for any context where that bridge isn't present.
    async function checkClipboard() {
        try {
            const text = window.AndroidClipboard
                ? window.AndroidClipboard.read()
                : await navigator.clipboard.readText();
            const clean = (text || '').trim().toUpperCase();
            if (clean === lastClipboardRaw) return;
            lastClipboardRaw = clean;
            if (PAIRS[clean]) executeSwitch(clean);
        } catch (e) {}
    }

    window.addEventListener('message', (event) => {
        const pair = event.data?.trinityOS_pair || event.data?.pair;
        if (pair && PAIRS[pair.toUpperCase()]) {
            log(`postMessage received: ${pair}`);
            executeSwitch(pair.toUpperCase());
        }
    });

    // ── PUBLIC API & INITIALIZATION ───────────────────────────────
    window._trinitySync = {
        go: (pair) => executeSwitch(pair.toUpperCase()),
        scanOT: function() {
            if (!IS_OT) return warn('Not on OlympTrade.');
            log('── OT DOM Scan ──');
            for (const key of Object.keys(PAIRS)) {
                const r = findOTTabElement(PAIRS[key]);
                if (r) ok(`${key} → [${r.strategy}]: "${r.el.textContent.trim().slice(0, 40)}"`);
                else warn(`${key} → NOT FOUND`);
            }
        },
        scanPO: function() {
            if (!IS_PO) return warn('Not on PocketOption.');
            log('── PO DOM Scan ──');
            for (const key of Object.keys(PAIRS)) {
                const r = findPOElement(PAIRS[key]);
                if (r) ok(`${key} → [${r.strategy}]: "${r.el.textContent.trim().slice(0, 40)}"`);
                else warn(`${key} (${PAIRS[key].poLabel}) → NOT FOUND`);
            }
        },
        list: () => { log('Active pairs: ' + Object.keys(PAIRS).join(', ')); },
        stop: () => { clearInterval(pollId); hudEl?.remove(); ok('Sync stopped.'); }
    };

    function init() {
        buildHUD();

        // Only needed for the desktop Web Clipboard path — the native
        // AndroidClipboard bridge needs no permission unlock.
        if (!window.AndroidClipboard) {
            document.addEventListener('click', async function _unlockClip() {
                try { await navigator.clipboard.readText(); clipboardGranted = true; } catch(e) {}
                document.removeEventListener('click', _unlockClip);
            }, { once: true });
        }

        pollId = setInterval(checkClipboard, POLL_MS);

        log(`══ TRINITY PAIR SYNC v7 (Android bridge) ACTIVE — ${IS_OT ? 'OlympTrade' : IS_PO ? 'PocketOption' : 'Unknown Broker'} ══`);
        log(`Watching ${Object.keys(PAIRS).length} pairs | AndroidClipboard bridge: ${window.AndroidClipboard ? 'READY' : 'absent, using Web Clipboard fallback'} | postMessage: READY`);
    }

    if (document.body) init();
    else document.addEventListener('DOMContentLoaded', init);

})();
