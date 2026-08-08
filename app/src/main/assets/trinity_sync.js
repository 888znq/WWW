// ==UserScript==
// @name         TRINITY PAIR SYNC — Omni-Broker Edition (v7.7, Extension iFrame Build)
// @namespace    trinity-os
// @version      7.7-ext
// @description  Auto-switches asset pairs across OlympTrade, PocketOption, and Quotex in Extension iFrames.
// @author       TRINITY OS
// @match        https://olymptrade.com/*
// @match        https://*.olymptrade.com/*
// @match        https://po.trade/*
// @match        https://*.po.trade/*
// @match        https://pocketoption.com/*
// @match        https://*.pocketoption.com/*
// @match        https://qxbroker.com/*
// @match        https://quotex.com/*
// @match        https://*.qxbroker.com/*
// @grant        none
// @run-at       document-idle
// ==/UserScript==

(function TRINITY_PAIR_SYNC_OMNI() {
    'use strict';

    if (window.__trinitySyncInjected) return;
    window.__trinitySyncInjected = true;

    // ── CONFIG ────────────────────────────────────────────────────
    const POLL_MS  = 400;
    const SHOW_HUD = true;

    // ── DETECT BROKER ─────────────────────────────────────────────
    const IS_OT = location.hostname.includes('olymptrade.com');
    const IS_PO = location.hostname.includes('pocketoption.com') || location.hostname.includes('po.trade');
    const IS_QX = location.hostname.includes('qxbroker') || location.hostname.includes('quotex');

    // ── STATE & LOGGING ───────────────────────────────────────────
    let lastSwitchedPair = null;
    let pollId           = null;
    let hudEl            = null;
    let lastClipboardRaw = null; 

    const log  = (m) => console.log(`%c[TRINITY-SYNC] ${m}`, 'color:#FFFFFF;font-weight:bold');
    const warn = (m) => console.warn(`%c[TRINITY-SYNC] ${m}`, 'color:#FFFFFF;font-weight:bold');
    const ok   = (m) => console.log(`%c[TRINITY-SYNC] ✅ ${m}`, 'color:#FFFFFF;font-weight:bold');

    // ── PAIR DATA (AOFS 28-Pair List) ──────────────────────────────
    const PAIRS = {
        'BTCUSD': { ot: '[data-test="asset-select-button-BTCUSD/ftt"]', poLabel: 'BTC/USD', keywords: ['btcusd', 'btc/usd', 'bitcoin'], display: '₿ BTCUSD' },
        'ETHUSD': { ot: '[data-test="asset-select-button-ETHUSD/ftt"]', poLabel: 'ETH/USD', keywords: ['ethusd', 'eth/usd', 'ethereum'], display: 'Ξ ETHUSD' },
        'LTCUSD': { ot: '[data-test="asset-select-button-LTCUSD/ftt"]', poLabel: 'LTC/USD', keywords: ['ltcusd', 'ltc/usd', 'litecoin'], display: 'Ł LTCUSD' },
        'XAGUSD': { ot: '[data-test="asset-select-button-XAGUSD/ftt"]', poLabel: 'XAG/USD', keywords: ['xagusd', 'xag/usd'], display: '🥈 XAGUSD' },
        'XAUUSD': { ot: '[data-test="asset-select-button-XAUUSD/ftt"]', poLabel: 'XAU/USD', keywords: ['xauusd', 'xau/usd'], display: '🥇 XAUUSD' },
        'XPTUSD': { ot: '[data-test="asset-select-button-PL/ftt"]', poLabel: 'XPT/USD', keywords: ['pl', 'platinum', 'xptusd', 'xpt/usd'], display: '⚪ XPTUSD' },
        'AUDCAD': { ot: '[data-test="asset-select-button-AUDCAD/ftt"]', poLabel: 'AUD/CAD', keywords: ['audcad', 'aud/cad'], display: '💱 AUDCAD' },
        'AUDCHF': { ot: '[data-test="asset-select-button-AUDCHF/ftt"]', poLabel: 'AUD/CHF', keywords: ['audchf', 'aud/chf'], display: '💱 AUDCHF' },
        'AUDJPY': { ot: '[data-test="asset-select-button-AUDJPY/ftt"]', poLabel: 'AUD/JPY', keywords: ['audjpy', 'aud/jpy'], display: '💱 AUDJPY' },
        'AUDNZD': { ot: '[data-test="asset-select-button-AUDNZD/ftt"]', poLabel: 'AUD/NZD', keywords: ['audnzd', 'aud/nzd'], display: '💱 AUDNZD' },
        'AUDUSD': { ot: '[data-test="asset-select-button-AUDUSD/ftt"]', poLabel: 'AUD/USD', keywords: ['audusd', 'aud/usd'], display: '💱 AUDUSD' },
        'EURAUD': { ot: '[data-test="asset-select-button-EURAUD/ftt"]', poLabel: 'EUR/AUD', keywords: ['euraud', 'eur/aud'], display: '💱 EURAUD' },
        'EURCAD': { ot: '[data-test="asset-select-button-EURCAD/ftt"]', poLabel: 'EUR/CAD', keywords: ['eurcad', 'eur/cad'], display: '💱 EURCAD' },
        'EURCHF': { ot: '[data-test="asset-select-button-EURCHF/ftt"]', poLabel: 'EUR/CHF', keywords: ['eurchf', 'eur/chf'], display: '💱 EURCHF' },
        'EURGBP': { ot: '[data-test="asset-select-button-EURGBP/ftt"]', poLabel: 'EUR/GBP', keywords: ['eurgbp', 'eur/gbp'], display: '💱 EURGBP' },
        'EURJPY': { ot: '[data-test="asset-select-button-EURJPY/ftt"]', poLabel: 'EUR/JPY', keywords: ['eurjpy', 'eur/jpy'], display: '💱 EURJPY' },
        'EURNZD': { ot: '[data-test="asset-select-button-EURNZD/ftt"]', poLabel: 'EUR/NZD', keywords: ['eurnzd', 'eur/nzd'], display: '💱 EURNZD' },
        'EURUSD': { ot: '[data-test="asset-select-button-EURUSD/ftt"]', poLabel: 'EUR/USD', keywords: ['eurusd', 'eur/usd'], display: '💱 EURUSD' },
        'GBPAUD': { ot: '[data-test="asset-select-button-GBPAUD/ftt"]', poLabel: 'GBP/AUD', keywords: ['gbpaud', 'gbp/aud'], display: '💱 GBPAUD' },
        'GBPCAD': { ot: '[data-test="asset-select-button-GBPCAD/ftt"]', poLabel: 'GBP/CAD', keywords: ['gbpcad', 'gbp/cad'], display: '💱 GBPCAD' },
        'GBPCHF': { ot: '[data-test="asset-select-button-GBPCHF/ftt"]', poLabel: 'GBP/CHF', keywords: ['gbpchf', 'gbp/chf'], display: '💱 GBPCHF' },
        'GBPJPY': { ot: '[data-test="asset-select-button-GBPJPY/ftt"]', poLabel: 'GBP/JPY', keywords: ['gbpjpy', 'gbp/jpy'], display: '💱 GBPJPY' },
        'GBPNZD': { ot: '[data-test="asset-select-button-GBPNZD/ftt"]', poLabel: 'GBP/NZD', keywords: ['gbpnzd', 'gbp/nzd'], display: '💱 GBPNZD' },
        'GBPUSD': { ot: '[data-test="asset-select-button-GBPUSD/ftt"]', poLabel: 'GBP/USD', keywords: ['gbpusd', 'gbp/usd'], display: '💱 GBPUSD' },
        'NZDJPY': { ot: '[data-test="asset-select-button-NZDJPY/ftt"]', poLabel: 'NZD/JPY', keywords: ['nzdjpy', 'nzd/jpy'], display: '💱 NZDJPY' },
        'NZDUSD': { ot: '[data-test="asset-select-button-NZDUSD/ftt"]', poLabel: 'NZD/USD', keywords: ['nzdusd', 'nzd/usd'], display: '💱 NZDUSD' },
        'USDCAD': { ot: '[data-test="asset-select-button-USDCAD/ftt"]', poLabel: 'USD/CAD', keywords: ['usdcad', 'usd/cad'], display: '💱 USDCAD' },
        'USDCHF': { ot: '[data-test="asset-select-button-USDCHF/ftt"]', poLabel: 'USD/CHF', keywords: ['usdchf', 'usd/chf'], display: '💱 USDCHF' },
        'USDJPY': { ot: '[data-test="asset-select-button-USDJPY/ftt"]', poLabel: 'USD/JPY', keywords: ['usdjpy', 'usd/jpy'], display: '💱 USDJPY' },
        'USDMXN': { ot: '[data-test="asset-select-button-USDMXN/ftt"]', poLabel: 'USD/MXN', keywords: ['usdmxn', 'usd/mxn'], display: '💱 USDMXN' },
        'USDNOK': { ot: '[data-test="asset-select-button-USDNOK/ftt"]', poLabel: 'USD/NOK', keywords: ['usdnok', 'usd/nok'], display: '💱 USDNOK' },
    };

    function buildHUD() {
        if (!SHOW_HUD || document.getElementById('_trinity_sync_hud')) return;

        const styleEl = document.createElement('style');
        styleEl.innerHTML = `
            @keyframes pulseBull {
                0%, 100% { text-shadow: 0 0 5px #FFFFFF; opacity: 1; transform: translateY(0px); }
                50% { text-shadow: 0 0 15px #FFFFFF; opacity: 0.5; transform: translateY(-3px); }
            }
            @keyframes pulseBear {
                0%, 100% { text-shadow: 0 0 5px #FFFFFF; opacity: 1; transform: translateY(0px); }
                50% { text-shadow: 0 0 15px #FFFFFF; opacity: 0.5; transform: translateY(3px); }
            }
            .trinity-bull { display: inline-block; color: #FFFFFF; font-size: 14px; animation: pulseBull 1.5s infinite ease-in-out; }
            .trinity-bear { display: inline-block; color: #FFFFFF; font-size: 14px; animation: pulseBear 1.5s infinite ease-in-out; }
        `;
        document.head.appendChild(styleEl);

        hudEl = document.createElement('div');
        hudEl.id = '_trinity_sync_hud';
        hudEl.style.cssText = `
            position: fixed; bottom: 25px; right: 25px;
            background: #2A1B12;
            border: 1px solid #E8D5BE; border-radius: 4px;
            padding: 8px 14px; z-index: 999999;
            font-family: 'JetBrains Mono', monospace; font-weight: 800; letter-spacing: 1px;
            display: flex; align-items: center; justify-content: center; gap: 12px;
            box-shadow: 0 4px 15px rgba(0, 0, 0, 0.5); pointer-events: none; user-select: none;
            transition: all 0.3s;
        `;
        hudEl.innerHTML = `
            <span class="trinity-bull">▲</span>
            <span id="trinity-hud-pair" style="color: #E8D5BE; font-size: 12px; min-width: 90px; text-align: center;">WATCHING</span>
            <span class="trinity-bear">▼</span>
        `;
        document.body.appendChild(hudEl);
    }

    function updateHUD(pairStr) {
        if (!hudEl) return;
        const span = document.getElementById('trinity-hud-pair');
        if (span) {
            span.innerText = PAIRS[pairStr] ? PAIRS[pairStr].display : pairStr;
            hudEl.style.borderColor = '#E8D5BE';
            hudEl.style.boxShadow = '0 0 15px rgba(232, 213, 190, 0.5)';
            span.style.color = '#E8D5BE';
            span.style.textShadow = '0 0 10px #E8D5BE';

            setTimeout(() => {
                span.style.color = '#E8D5BE';
                span.style.textShadow = 'none';
                hudEl.style.borderColor = '#E8D5BE';
                hudEl.style.boxShadow = '0 4px 15px rgba(0, 0, 0, 0.5)';
            }, 600);
        }
    }

    function nativeClick(el) {
        if (!el) return;

        const reactKey = Object.keys(el).find(k => k.startsWith('__reactProps$') || k.startsWith('__reactEventHandlers$'));
        if (reactKey && el[reactKey] && typeof el[reactKey].onClick === 'function') {
            try { el[reactKey].onClick({ preventDefault: () => {}, stopPropagation: () => {} }); } catch(e){}
        }

        const rect = el.getBoundingClientRect();
        const cx = rect.left + rect.width / 2;
        const cy = rect.top + rect.height / 2;
        const opts = { bubbles: true, cancelable: true, view: window, clientX: cx, clientY: cy, pointerId: 1, pointerType: 'mouse', isPrimary: true };

        ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(type => {
            try { el.dispatchEvent(new MouseEvent(type, opts)); } catch(e){}
        });
    }

    function switchOT(pairStr, pairConfig) {
        const menuBtn = document.querySelector('[data-test="asset-select-button"]') || document.querySelector('[data-test="asset-picker-button"]');
        if (menuBtn) nativeClick(menuBtn);

        setTimeout(() => {
            const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_ELEMENT);
            while (walker.nextNode()) {
                const el = walker.currentNode;
                const text = el.textContent.trim().toLowerCase();
                if (pairConfig.keywords.some(kw => text === kw)) {
                    nativeClick(el);
                    break;
                }
            }
        }, 500);
    }

    function switchPO(pairStr, pairConfig) {
        const menuBtn = document.querySelector('.current-symbol') || document.querySelector('[class*="asset-select"]');
        if (menuBtn) nativeClick(menuBtn);

        setTimeout(() => {
            const searchInput = document.querySelector('input[placeholder*="Search" i], input[class*="search" i]');
            if (searchInput) {
                searchInput.value = pairConfig.poLabel;
                searchInput.dispatchEvent(new Event('input', { bubbles: true }));
                searchInput.dispatchEvent(new Event('change', { bubbles: true }));

                setTimeout(() => {
                    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_ELEMENT);
                    while (walker.nextNode()) {
                        const el = walker.currentNode;
                        if (el.textContent.trim() === pairConfig.poLabel) {
                            nativeClick(el);
                            break;
                        }
                    }
                }, 500);
            }
        }, POLL_MS);
    }

    function switchQX(pairStr, pairConfig) {
        ok(`QX Switch initiated for ${pairStr} (${pairConfig.poLabel})`);

        const existingTab = Array.from(document.querySelectorAll('div, span, button')).find(el => {
            const txt = el.textContent ? el.textContent.trim().toUpperCase() : '';
            return (txt === pairConfig.poLabel || txt === `${pairConfig.poLabel} (OTC)`) && el.children.length === 0;
        });

        if (existingTab && existingTab.offsetWidth > 0) {
            let topTab = existingTab;
            for (let i = 0; i < 3; i++) {
                if (!topTab.parentElement) break;
                if (topTab.parentElement.offsetTop < 120) {
                    topTab = topTab.parentElement;
                    break;
                }
                topTab = topTab.parentElement;
            }
            ok(`QX Switch: Clicking top bar tab for ${pairStr}`);
            nativeClick(topTab);
            nativeClick(existingTab);
            return;
        }

        const menuBtn = document.getElementById('header-mobile-asset-btn') 
            || document.querySelector('[id*="asset-btn"]')
            || Array.from(document.querySelectorAll('div, button, a')).find(el => {
                const id = el.id ? el.id.toLowerCase() : '';
                const cls = el.className && typeof el.className === 'string' ? el.className.toLowerCase() : '';
                return (id.includes('asset') || cls.includes('asset')) && el.offsetWidth > 0;
            });

        if (menuBtn) {
            nativeClick(menuBtn);
        } else {
            warn(`QX: Asset header button not found.`);
        }

        setTimeout(() => {
            const allInputs = Array.from(document.querySelectorAll('input'));
            const searchInput = allInputs.find(input => {
                const val = input.value || '';
                const isTimeOrAmount = val === '100' || val.includes('00:') || input.classList.contains('mobile-time-input__block');
                return !isTimeOrAmount && input.offsetWidth > 0;
            }) || document.querySelector('input[placeholder*="earch" i]');

            if (searchInput) {
                searchInput.focus();

                const nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value").set;
                nativeSetter.call(searchInput, pairConfig.poLabel);
                
                searchInput.dispatchEvent(new Event('input', { bubbles: true }));
                searchInput.dispatchEvent(new Event('change', { bubbles: true }));

                setTimeout(() => {
                    const candidates = Array.from(document.querySelectorAll('div, span, li, p')).filter(el => {
                        if (el.children.length > 2) return false;
                        const txt = el.textContent ? el.textContent.trim().toUpperCase() : '';
                        return txt === pairConfig.poLabel || txt === `${pairConfig.poLabel} (OTC)`;
                    });

                    if (candidates.length > 0) {
                        const targetTextEl = candidates[candidates.length - 1];
                        let clickContainer = targetTextEl;

                        for (let i = 0; i < 4; i++) {
                            if (!clickContainer.parentElement) break;
                            const p = clickContainer.parentElement;
                            if (p.tagName === 'LI' || p.tagName === 'A' || p.tagName === 'BUTTON' || (p.className && typeof p.className === 'string' && p.className.includes('item')) || window.getComputedStyle(p).cursor === 'pointer') {
                                clickContainer = p;
                                break;
                            }
                            clickContainer = p;
                        }

                        ok(`QX Switch: Executing click for ${pairStr}`);
                        nativeClick(clickContainer);
                        nativeClick(targetTextEl);
                    } else {
                        warn(`QX: Could not locate ${pairStr} in search dropdown.`);
                    }
                }, 400);
            } else {
                warn("QX: Search input not found after opening menu.");
            }
        }, POLL_MS);
    }

    function executeSwitch(pairStr) {
        if (!pairStr) return;
        const cleanPair = pairStr.trim().toUpperCase();
        if (cleanPair === lastSwitchedPair) return;

        const pairConfig = PAIRS[cleanPair];
        if (!pairConfig) return;

        lastSwitchedPair = cleanPair;
        updateHUD(cleanPair);

        if (IS_OT)      switchOT(cleanPair, pairConfig);
        else if (IS_PO) switchPO(cleanPair, pairConfig);
        else if (IS_QX) switchQX(cleanPair, pairConfig);
        else            warn(`Unknown broker — cannot switch to ${cleanPair}.`);
    }

    async function checkClipboard() {
        try {
            const text = await navigator.clipboard.readText();
            const clean = (text || '').trim().toUpperCase();
            if (clean === lastClipboardRaw) return;
            lastClipboardRaw = clean;
            if (PAIRS[clean]) executeSwitch(clean);
        } catch (e) {}
    }

    window.addEventListener('message', (event) => {
        const pair = event.data?.trinityOS_pair || event.data?.pair;
        if (pair) {
            log(`postMessage received: ${pair}`);
            executeSwitch(pair.toString());
        }
    });

    window._trinitySync = {
        go: (pair) => executeSwitch(pair),
        list: () => { log('Active pairs: ' + Object.keys(PAIRS).join(', ')); },
        stop: () => { clearInterval(pollId); hudEl?.remove(); ok('Sync stopped.'); }
    };

    function init() {
        buildHUD();
        pollId = setInterval(checkClipboard, POLL_MS);

        let brokerName = 'Unknown Broker';
        if (IS_OT) brokerName = 'OlympTrade';
        else if (IS_PO) brokerName = 'PocketOption';
        else if (IS_QX) brokerName = 'Quotex';

        log(`══ TRINITY PAIR SYNC v7.7 EXTENSION ACTIVE — ${brokerName} ══`);
    }

    if (document.body) init();
    else document.addEventListener('DOMContentLoaded', init);

})();