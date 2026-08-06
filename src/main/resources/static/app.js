/* =========================================================================
   JobTrail — single-page client. No framework, no build step, no CDN.
   ========================================================================= */
(() => {
    'use strict';

    /* ------------------------------ helpers ------------------------------ */

    const $ = (sel, root = document) => root.querySelector(sel);
    const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));

    const STATUS_LABEL = {
        DRAFT: 'Draft', QUEUED: 'Queued', SENT: 'Sent', OPENED: 'Opened',
        REPLIED: 'Replied', CLOSED: 'Closed', FAILED: 'Failed'
    };
    const STATUS_ORDER = ['DRAFT', 'QUEUED', 'SENT', 'OPENED', 'REPLIED', 'CLOSED', 'FAILED'];
    const STATUS_VAR = {
        DRAFT: '--s-draft', QUEUED: '--s-queued', SENT: '--s-sent', OPENED: '--s-opened',
        REPLIED: '--s-replied', CLOSED: '--s-closed', FAILED: '--s-failed'
    };
    const MSG_STATUS_LABEL = {
        QUEUED: 'Queued', SENDING: 'Sending', SENT: 'Sent', FAILED: 'Failed', CANCELLED: 'Cancelled'
    };

    function esc(value) {
        return String(value ?? '').replace(/[&<>"']/g, c =>
            ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    }

    function statusColor(status) {
        return getComputedStyle(document.documentElement)
            .getPropertyValue(STATUS_VAR[status] || '--muted').trim() || '#898781';
    }

    async function api(path, options = {}) {
        const { method = 'GET', body } = options;
        const res = await fetch(path, {
            method,
            headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
            body: body === undefined ? undefined : JSON.stringify(body)
        });
        const text = await res.text();
        let data = null;
        if (text) {
            try { data = JSON.parse(text); } catch { data = null; }
        }
        if (!res.ok) {
            throw new Error((data && data.error) || `${res.status} ${res.statusText}`);
        }
        return data;
    }

    function toast(message, kind = 'ok') {
        const node = document.createElement('div');
        node.className = `toast ${kind}`;
        node.innerHTML = `<svg><use href="#${kind === 'ok' ? 'i-check' : 'i-x'}"/></svg>
                          <div class="msg">${esc(message)}</div>`;
        $('#toasts').appendChild(node);
        setTimeout(() => {
            node.classList.add('leaving');
            setTimeout(() => node.remove(), 220);
        }, kind === 'ok' ? 3200 : 6000);
    }

    function initials(name, email) {
        const source = (name && name.trim()) || (email || '?');
        const parts = source.replace(/@.*/, '').split(/[\s._-]+/).filter(Boolean);
        return ((parts[0] || '?')[0] + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase();
    }

    function fmtWhen(iso) {
        if (!iso) return '—';
        const d = new Date(iso);
        const now = new Date();
        if (d.toDateString() === now.toDateString()) {
            return 'Today ' + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
        }
        const days = Math.round((now - d) / 86400000);
        if (days === 1) return 'Yesterday';
        if (days > 1 && days < 7) return `${days} days ago`;
        if (days === -1) return 'Tomorrow';
        if (days < -1 && days > -14) return `in ${Math.abs(days)} days`;
        return d.toLocaleDateString([], { day: 'numeric', month: 'short' });
    }

    function fmtEta(seconds) {
        if (seconds < 0) return 'paused';
        if (seconds === 0) return 'next up';
        if (seconds < 60) return `${seconds}s`;
        const m = Math.floor(seconds / 60);
        if (m < 60) return `${m}m ${seconds % 60}s`;
        const h = Math.floor(m / 60);
        return `${h}h ${m % 60}m`;
    }

    function clock(seconds) {
        if (seconds < 60) return `${seconds}s`;
        return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
    }

    function pill(status) {
        return `<span class="pill s-${status}"><i class="dot"></i>${esc(STATUS_LABEL[status] || status)}</span>`;
    }

    /* ------------------------------- state ------------------------------- */

    const state = {
        view: 'dashboard',
        stats: null,
        queue: null,
        queueAt: 0,
        settings: null,
        templates: [],
        activeTemplateId: null,
        outreach: [],
        demoLoaded: false,
        openDrawerId: null
    };

    /* ------------------------------- charts ------------------------------ */

    function barPath(x, y, w, h, r) {
        const rr = Math.max(0, Math.min(r, w / 2, h));
        if (h <= 0) return '';
        return `M${x},${y + h} L${x},${y + rr} Q${x},${y} ${x + rr},${y} `
             + `L${x + w - rr},${y} Q${x + w},${y} ${x + w},${y + rr} L${x + w},${y + h} Z`;
    }

    function niceMax(value) {
        if (value <= 4) return 4;
        const pow = Math.pow(10, Math.floor(Math.log10(value)));
        return Math.ceil(value / (pow / 2)) * (pow / 2);
    }

    /** 14-day sends. One series, so no legend box — the card title names it. */
    function renderSendsChart(days) {
        const host = $('#chart-sends');
        const W = 620, H = 180, padL = 30, padR = 8, padT = 12, padB = 26;
        const plotW = W - padL - padR;
        const plotH = H - padT - padB;
        const max = niceMax(Math.max(1, ...days.map(d => d.sent)));
        const slot = plotW / days.length;
        const barW = Math.max(6, slot - 6);

        const ticks = [0, max / 2, max];
        const grid = ticks.map(t => {
            const y = padT + plotH - (t / max) * plotH;
            return `<line x1="${padL}" y1="${y}" x2="${W - padR}" y2="${y}"/>`;
        }).join('');
        const labels = ticks.map(t => {
            const y = padT + plotH - (t / max) * plotH;
            return `<text class="chart-label" x="${padL - 8}" y="${y + 3.5}" text-anchor="end">${t}</text>`;
        }).join('');

        const bars = days.map((d, i) => {
            const x = padL + i * slot + (slot - barW) / 2;
            const h = (d.sent / max) * plotH;
            const y = padT + plotH - h;
            const date = new Date(d.date + 'T00:00:00');
            const label = date.toLocaleDateString([], { day: 'numeric', month: 'short' });
            const body = d.sent === 0
                ? `<path d="${barPath(x, padT + plotH - 2, barW, 2, 1)}" fill="var(--grid)"/>`
                : `<path class="bar" d="${barPath(x, y, barW, h, 4)}" fill="var(--s-sent)"/>`;
            return `${body}<rect x="${padL + i * slot}" y="${padT}" width="${slot}" height="${plotH}"
                        fill="transparent" data-tip="${esc(label)}|${d.sent} email${d.sent === 1 ? '' : 's'} sent"/>`;
        }).join('');

        // Count back from the newest day so the right-hand label is always the
        // last bar and the spacing stays even.
        const xLabels = days.map((d, i) => {
            if ((days.length - 1 - i) % 3 !== 0) return '';
            const date = new Date(d.date + 'T00:00:00');
            const x = padL + i * slot + slot / 2;
            return `<text class="chart-label" x="${x}" y="${H - 8}" text-anchor="middle">${date.getDate()}</text>`;
        }).join('');

        host.innerHTML = `<svg viewBox="0 0 ${W} ${H}" role="img"
                aria-label="Emails sent per day over the last 14 days">
            <g class="chart-grid">${grid}</g>
            ${labels}
            <g class="chart-axis"><line x1="${padL}" y1="${padT + plotH}" x2="${W - padR}" y2="${padT + plotH}"/></g>
            ${bars}${xLabels}
        </svg>`;
        wireTips(host);
    }

    /**
     * Pipeline composition. Every segment is repeated in the legend with its
     * name and count, so the hue is never the only way to read it.
     */
    function renderPipeline(byStatus) {
        const host = $('#chart-pipeline');
        const legend = $('#pipeline-legend');
        const items = STATUS_ORDER
            .map(s => ({ status: s, count: (byStatus.find(b => b.status === s) || {}).count || 0 }))
            .filter(i => i.count > 0);

        const total = items.reduce((sum, i) => sum + i.count, 0);
        if (total === 0) {
            host.innerHTML = '';
            legend.innerHTML = '<div class="list-empty">No threads yet.</div>';
            return;
        }

        const W = 420, H = 38, gap = 2, radius = 5;
        const usable = W - gap * Math.max(0, items.length - 1);
        let x = 0;
        const segments = items.map(i => {
            const w = Math.max(4, (i.count / total) * usable);
            const rect = `<rect class="bar" x="${x}" y="0" width="${w}" height="${H}" rx="${radius}"
                    fill="var(${STATUS_VAR[i.status]})"
                    data-tip="${esc(STATUS_LABEL[i.status])}|${i.count} of ${total} threads"/>`;
            x += w + gap;
            return rect;
        }).join('');

        host.innerHTML = `<svg viewBox="0 0 ${W} ${H}" preserveAspectRatio="none"
                style="height:38px" role="img" aria-label="Pipeline by status">${segments}</svg>`;

        legend.innerHTML = items.map(i => `
            <span class="legend-item">
                <i class="swatch" style="background:var(${STATUS_VAR[i.status]})"></i>
                ${esc(STATUS_LABEL[i.status])} <b>${i.count}</b>
            </span>`).join('');
        wireTips(host);
    }

    function wireTips(host) {
        const tip = $('#tooltip');
        $$('[data-tip]', host).forEach(node => {
            node.addEventListener('mousemove', ev => {
                const [title, sub] = node.dataset.tip.split('|');
                tip.innerHTML = `<b>${esc(title)}</b><div class="tt-sub">${esc(sub)}</div>`;
                tip.hidden = false;
                const pad = 14;
                let left = ev.clientX + pad;
                if (left + tip.offsetWidth > window.innerWidth - 8) left = ev.clientX - tip.offsetWidth - pad;
                tip.style.left = `${left}px`;
                tip.style.top = `${Math.max(8, ev.clientY - tip.offsetHeight - 10)}px`;
            });
            node.addEventListener('mouseleave', () => { tip.hidden = true; });
        });
    }

    /* ----------------------------- dashboard ----------------------------- */

    function tile(label, icon, value, foot, bar) {
        return `<div class="tile">
            <div class="tile-label"><svg><use href="#${icon}"/></svg>${esc(label)}</div>
            <div class="tile-value">${esc(value)}</div>
            <div class="tile-foot">${foot}</div>
            ${bar !== undefined ? `<div class="tile-bar"><i style="width:${Math.min(100, bar)}%"></i></div>` : ''}
        </div>`;
    }

    function renderDashboard() {
        const s = state.stats;
        if (!s) return;

        const empty = s.totalOutreach === 0;
        $('#dash-empty').hidden = !empty;
        $('#dash-body').hidden = empty;
        if (empty) return;

        const cap = state.queue ? state.queue.dailySendLimit : 0;
        const capPct = cap ? (s.sentToday / cap) * 100 : 0;

        $('#dash-tiles').innerHTML = [
            tile('In queue', 'i-clock', s.queued,
                s.queued ? 'waiting to go out' : 'nothing waiting'),
            tile('Sent today', 'i-send', s.sentToday,
                cap ? `of a ${cap}/day cap` : 'today', capPct),
            tile('Open rate', 'i-eye', `${s.openRate}%`,
                `${s.opened} thread${s.opened === 1 ? '' : 's'} opened`),
            tile('Reply rate', 'i-reply', `${s.replyRate}%`,
                `${s.replied} repl${s.replied === 1 ? 'y' : 'ies'} received`)
        ].join('');

        $('#chart-total').textContent = s.last14Days.reduce((a, d) => a + d.sent, 0);
        renderSendsChart(s.last14Days);
        renderPipeline(s.byStatus);

        $('#upcoming-list').innerHTML = s.upcomingFollowUps.length
            ? s.upcomingFollowUps.map(o => `
                <div class="list-row">
                    <div class="avatar">${esc(initials(o.recipientName, o.recipientEmail))}</div>
                    <div class="grow">
                        <div class="title">${esc(o.recipientName || o.recipientEmail)}</div>
                        <div class="meta">${esc(o.company || o.recipientEmail)} · follow-up
                            ${o.followUpsSent + 1} of ${o.maxFollowUps}</div>
                    </div>
                    <div class="when">${esc(fmtWhen(o.nextFollowUpAt))}</div>
                </div>`).join('')
            : '<div class="list-empty">No follow-ups scheduled.</div>';

        $('#activity-list').innerHTML = s.recentActivity.length
            ? s.recentActivity.map(m => `
                <div class="list-row">
                    <div class="avatar">${esc(initials(m.recipientName, m.recipientEmail))}</div>
                    <div class="grow">
                        <div class="title">${esc(m.subject)}</div>
                        <div class="meta">${esc(m.recipientEmail)} ·
                            ${m.kind === 'FOLLOW_UP' ? `follow-up ${m.sequenceNo}` : 'opening email'}</div>
                    </div>
                    <div class="when">${esc(MSG_STATUS_LABEL[m.status])} ·
                        ${esc(fmtWhen(m.sentAt || m.queuedAt))}</div>
                </div>`).join('')
            : '<div class="list-empty">Nothing has moved yet.</div>';
    }

    /* ------------------------------ outreach ----------------------------- */

    async function loadOutreach() {
        const q = $('#outreach-search').value.trim();
        const status = $('#outreach-filter').value;
        const params = new URLSearchParams();
        if (q) params.set('q', q);
        if (status) params.set('status', status);
        state.outreach = await api(`/api/outreach?${params}`);
        renderOutreach();
    }

    function renderOutreach() {
        const rows = state.outreach;
        $('#outreach-empty').hidden = rows.length > 0;
        $('#outreach-rows').innerHTML = rows.map(o => {
            const actions = [];
            if (o.status === 'DRAFT') {
                actions.push(`<button class="icon-btn sm" data-queue="${o.id}" title="Queue opening email">
                    <svg><use href="#i-send"/></svg></button>`);
            }
            if (o.status === 'SENT' || o.status === 'OPENED') {
                actions.push(`<button class="icon-btn sm" data-followup="${o.id}" title="Send a follow-up now">
                    <svg><use href="#i-reply"/></svg></button>`);
            }
            if (o.status !== 'REPLIED' && o.status !== 'CLOSED' && o.firstSentAt) {
                actions.push(`<button class="icon-btn sm" data-replied="${o.id}" title="Mark as replied">
                    <svg><use href="#i-check"/></svg></button>`);
            }
            actions.push(`<button class="icon-btn sm danger" data-delete="${o.id}" title="Delete thread">
                <svg><use href="#i-trash"/></svg></button>`);

            return `<tr class="clickable" data-open="${o.id}">
                <td>
                    <div style="display:flex;align-items:center;gap:10px">
                        <div class="avatar">${esc(initials(o.recipientName, o.recipientEmail))}</div>
                        <div style="min-width:0">
                            <div class="cell-main">${esc(o.recipientName || '—')}</div>
                            <div class="cell-sub">${esc(o.recipientEmail)}</div>
                        </div>
                    </div>
                </td>
                <td>
                    <div class="cell-main">${esc(o.company || '—')}</div>
                    <div class="cell-sub">${esc(o.position || '—')}</div>
                </td>
                <td>${pill(o.status)}</td>
                <td class="num">${o.followUpsSent}<span style="color:var(--muted)">/${o.maxFollowUps}</span></td>
                <td class="mono">${esc(fmtWhen(o.lastSentAt))}</td>
                <td class="mono">${o.nextFollowUpAt ? esc(fmtWhen(o.nextFollowUpAt))
                    : '<span style="color:var(--muted)">—</span>'}</td>
                <td><div class="row-actions">${actions.join('')}</div></td>
            </tr>`;
        }).join('');
    }

    async function openThread(id) {
        const detail = await api(`/api/outreach/${id}`);
        const o = detail.outreach;
        state.openDrawerId = id;

        const actions = [];
        if (o.status === 'DRAFT') {
            actions.push(`<button class="btn primary" data-act="queue">
                <svg><use href="#i-send"/></svg> Queue opening email</button>`);
        }
        if (o.status === 'SENT' || o.status === 'OPENED') {
            actions.push(`<button class="btn primary" data-act="followup">
                <svg><use href="#i-reply"/></svg> Send follow-up now</button>`);
        }
        if (o.status !== 'REPLIED') {
            actions.push('<button class="btn ghost" data-act="replied">Mark as replied</button>');
        }
        if (o.status !== 'CLOSED') {
            actions.push('<button class="btn ghost" data-act="closed">Close thread</button>');
        }
        actions.push('<button class="btn danger" data-act="delete">Delete</button>');

        const timeline = detail.messages.length ? detail.messages.map(m => `
            <div class="tl-item">
                <div class="tl-rail">
                    <i class="tl-dot" style="background:${m.status === 'FAILED' ? 'var(--s-failed)'
                        : m.status === 'SENT' ? 'var(--s-sent)' : 'var(--s-queued)'}"></i>
                    <div class="tl-line"></div>
                </div>
                <div class="tl-body">
                    <div class="tl-title">${esc(m.subject)}</div>
                    <div class="tl-meta">
                        ${m.kind === 'FOLLOW_UP' ? `Follow-up ${m.sequenceNo}` : 'Opening email'} ·
                        ${esc(MSG_STATUS_LABEL[m.status])} ·
                        ${esc(fmtWhen(m.sentAt || m.scheduledAt))}
                        ${m.openCount ? ` · opened ${m.openCount}×` : ''}
                    </div>
                    ${m.lastError ? `<div class="tl-error">${esc(m.lastError)}</div>` : ''}
                </div>
            </div>`).join('') : '<div class="list-empty">No emails yet for this thread.</div>';

        $('#drawer').innerHTML = `
            <div class="drawer-head">
                <div style="min-width:0">
                    <h2 style="font-size:17px">${esc(o.recipientName || o.recipientEmail)}</h2>
                    <p style="color:var(--muted);font-size:12.5px;margin-top:3px">${esc(o.recipientEmail)}</p>
                </div>
                <div style="display:flex;gap:8px;align-items:center">
                    ${pill(o.status)}
                    <button class="icon-btn" data-act="close-drawer"><svg><use href="#i-x"/></svg></button>
                </div>
            </div>
            <div class="drawer-body">
                <div class="drawer-actions">${actions.join('')}</div>
                <dl class="kv">
                    <dt>Company</dt><dd>${esc(o.company || '—')}</dd>
                    <dt>Role</dt><dd>${esc(o.position || '—')}</dd>
                    <dt>First sent</dt><dd>${esc(fmtWhen(o.firstSentAt))}</dd>
                    <dt>Last sent</dt><dd>${esc(fmtWhen(o.lastSentAt))}</dd>
                    <dt>Opens</dt><dd>${o.openCount || 0}${o.openedAt
                        ? ` · first ${esc(fmtWhen(o.openedAt))}` : ''}</dd>
                    <dt>Follow-ups</dt><dd>${o.followUpsSent} of ${o.maxFollowUps}
                        ${o.autoFollowUp ? '· automatic' : '· manual only'}</dd>
                    <dt>Next follow-up</dt><dd>${o.nextFollowUpAt ? esc(fmtWhen(o.nextFollowUpAt)) : '—'}</dd>
                    <dt>Every</dt><dd>${o.followUpIntervalDays} days of silence</dd>
                    ${o.notes ? `<dt>Notes</dt><dd>${esc(o.notes)}</dd>` : ''}
                </dl>
                <div>
                    <h3 style="margin-bottom:12px">Timeline</h3>
                    <div class="timeline">${timeline}</div>
                </div>
            </div>`;

        $('#drawer').hidden = false;
        $('#drawer-scrim').hidden = false;

        $('#drawer').onclick = async ev => {
            const btn = ev.target.closest('[data-act]');
            if (!btn) return;
            const act = btn.dataset.act;
            if (act === 'close-drawer') return closeDrawer();
            try {
                if (act === 'queue') {
                    await api(`/api/outreach/${id}/queue`, { method: 'POST', body: {} });
                    toast('Opening email queued — it will go out on the next free slot.');
                } else if (act === 'followup') {
                    await api(`/api/outreach/${id}/follow-up`, { method: 'POST', body: {} });
                    toast('Follow-up queued.');
                } else if (act === 'replied') {
                    await api(`/api/outreach/${id}/status`, { method: 'POST', body: { status: 'REPLIED' } });
                    toast('Marked as replied — follow-ups stopped.');
                } else if (act === 'closed') {
                    await api(`/api/outreach/${id}/status`, { method: 'POST', body: { status: 'CLOSED' } });
                    toast('Thread closed.');
                } else if (act === 'delete') {
                    if (!confirm('Delete this thread and its email history?')) return;
                    await api(`/api/outreach/${id}`, { method: 'DELETE' });
                    toast('Thread deleted.');
                    closeDrawer();
                    return refreshCurrent();
                }
                await openThread(id);
                refreshCurrent();
            } catch (err) {
                toast(err.message, 'err');
            }
        };
    }

    function closeDrawer() {
        $('#drawer').hidden = true;
        $('#drawer-scrim').hidden = true;
        state.openDrawerId = null;
    }

    /* ------------------------------- queue ------------------------------- */

    function localCountdown() {
        if (!state.queue) return 0;
        const elapsed = Math.floor((Date.now() - state.queueAt) / 1000);
        return Math.max(0, state.queue.nextSendInSeconds - elapsed);
    }

    const STATE_STYLE = {
        SENDING: ['live', 'Sending now'],
        THROTTLED: ['wait', 'Spacing sends'],
        IDLE: ['off', 'Idle'],
        PAUSED: ['halt', 'Paused'],
        NOT_CONFIGURED: ['halt', 'Not configured'],
        DAILY_LIMIT: ['halt', 'Daily cap reached'],
        OUTSIDE_WINDOW: ['halt', 'Outside send window'],
        ERROR: ['halt', 'Error']
    };

    function renderPaceCard() {
        const q = state.queue;
        const count = $('#pace-count');
        const stateLine = $('#pace-state');
        const dots = $('#pace-dots');

        if (!q) return;
        const waiting = q.items.filter(i => i.message.status === 'QUEUED').length;
        const remaining = localCountdown();

        if (q.paused) {
            count.textContent = '—';
        } else if (waiting === 0 && remaining === 0) {
            count.textContent = 'idle';
        } else {
            count.textContent = clock(remaining);
        }

        const full = Math.max(1, q.minIntervalSeconds + q.jitterSeconds);
        const filled = q.paused ? 0 : Math.round(((full - Math.min(remaining, full)) / full) * 8);
        dots.innerHTML = Array.from({ length: 8 },
            (_, i) => `<i class="${i < filled ? 'on' : ''}"></i>`).join('');

        stateLine.textContent = q.dispatcherDetail;

        const badge = $('#nav-queue-count');
        badge.hidden = waiting === 0;
        badge.textContent = waiting;
    }

    function renderQueue() {
        const q = state.queue;
        if (!q) return;

        const [style, label] = STATE_STYLE[q.dispatcherState] || ['off', q.dispatcherState];
        const remaining = localCountdown();
        const full = Math.max(1, q.minIntervalSeconds + q.jitterSeconds);
        const circumference = 2 * Math.PI * 50;
        const progress = q.paused ? 0 : (full - Math.min(remaining, full)) / full;
        const waiting = q.items.filter(i => i.message.status === 'QUEUED').length;
        const capPct = q.dailySendLimit ? Math.min(100, (q.sentToday / q.dailySendLimit) * 100) : 0;

        $('#pace-panel').innerHTML = `
            <div class="pace-ring">
                <svg viewBox="0 0 116 116">
                    <defs>
                        <linearGradient id="paceGrad" x1="0" y1="0" x2="1" y2="1">
                            <stop offset="0%" stop-color="var(--accent)"/>
                            <stop offset="100%" stop-color="var(--accent-2)"/>
                        </linearGradient>
                    </defs>
                    <circle class="track-c" cx="58" cy="58" r="50"/>
                    <circle class="prog-c" cx="58" cy="58" r="50"
                        stroke-dasharray="${circumference}"
                        stroke-dashoffset="${circumference * (1 - progress)}"/>
                </svg>
                <div class="pace-ring-label">
                    <b>${q.paused ? '—' : (waiting === 0 && remaining === 0 ? '0' : remaining)}</b>
                    <span>seconds</span>
                </div>
            </div>
            <div class="pace-info">
                <div class="state-line">
                    <span class="state-badge ${style}"><i class="beacon"></i>${esc(label)}</span>
                    <h3>${waiting === 0 ? 'Queue is clear' :
                        `${waiting} email${waiting === 1 ? '' : 's'} waiting`}</h3>
                </div>
                <p>${esc(q.dispatcherDetail)}</p>
                <div class="pace-facts">
                    <div class="pace-fact"><span>Minimum gap</span><b>${q.minIntervalSeconds}s</b></div>
                    <div class="pace-fact"><span>Random extra</span><b>0–${q.jitterSeconds}s</b></div>
                    <div class="pace-fact"><span>Sent today</span>
                        <b>${q.sentToday} / ${q.dailySendLimit}</b></div>
                </div>
                <div class="tile-bar" style="max-width:280px"><i style="width:${capPct}%"></i></div>
            </div>
            <div class="pace-actions">
                <button class="btn ${q.paused ? 'primary' : 'ghost'}" data-action="toggle-pause">
                    <svg><use href="#${q.paused ? 'i-play' : 'i-pause'}"/></svg>
                    ${q.paused ? 'Resume sending' : 'Pause sending'}
                </button>
            </div>`;

        $('#queue-empty').hidden = q.items.length > 0;
        $('#queue-rows').innerHTML = q.items.map(item => {
            const m = item.message;
            const sending = m.status === 'SENDING';
            return `<tr>
                <td class="num" style="color:var(--muted)">${item.position + 1}</td>
                <td>
                    <div class="cell-main">${esc(m.recipientName || m.recipientEmail)}</div>
                    <div class="cell-sub">${esc(m.recipientEmail)}</div>
                </td>
                <td><div class="cell-main" style="max-width:280px;overflow:hidden;
                    text-overflow:ellipsis;white-space:nowrap">${esc(m.subject)}</div></td>
                <td><span class="kind-chip ${m.kind === 'FOLLOW_UP' ? 'follow' : ''}">
                    ${m.kind === 'FOLLOW_UP' ? 'FOLLOW-UP ' + m.sequenceNo : 'OPENING'}</span></td>
                <td class="mono">${sending
                    ? '<span style="color:var(--s-replied)">sending now</span>'
                    : esc(fmtEta(item.etaSeconds))}</td>
                <td><div class="row-actions">${sending ? '' :
                    `<button class="icon-btn sm danger" data-cancel="${m.id}" title="Remove from queue">
                        <svg><use href="#i-x"/></svg></button>`}</div></td>
            </tr>`;
        }).join('');
    }

    /* ----------------------------- templates ----------------------------- */

    const SAMPLE_VARS = {
        name: 'Priya Raman', first_name: 'Priya', company: 'Northwind Labs',
        role: 'Backend Engineer', date: new Date().toLocaleDateString([], {
            day: 'numeric', month: 'long', year: 'numeric'
        }), day: new Date().toLocaleDateString([], { weekday: 'long' })
    };

    function fillTokens(text) {
        const vars = Object.assign({}, SAMPLE_VARS, {
            my_name: (state.settings && state.settings.fromName) || 'Your name',
            my_email: (state.settings && state.settings.fromEmail) || 'you@example.com'
        });
        return String(text || '').replace(/\{\{\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*}}/g,
            (_, key) => vars[key.toLowerCase()] ?? '');
    }

    async function loadTemplates() {
        state.templates = await api('/api/templates');
        if (!state.templates.some(t => t.id === state.activeTemplateId)) {
            state.activeTemplateId = state.templates.length ? state.templates[0].id : null;
        }
        renderTemplates();
    }

    function renderTemplates() {
        $('#template-items').innerHTML = state.templates.map(t => `
            <div class="template-item ${t.id === state.activeTemplateId ? 'active' : ''}"
                 data-template="${t.id}">
                <div class="grow">
                    <div class="title">${esc(t.name)}
                        ${t.isDefault ? '<span class="star" title="Default">★</span>' : ''}</div>
                    <div class="meta">${esc(t.subject || 'no subject')}</div>
                </div>
                <span class="kind-chip ${t.kind === 'FOLLOW_UP' ? 'follow' : ''}">
                    ${t.kind === 'FOLLOW_UP' ? 'FOLLOW-UP' : 'OPENING'}</span>
            </div>`).join('') || '<div class="list-empty">No templates.</div>';

        const t = state.templates.find(x => x.id === state.activeTemplateId);
        const editor = $('#template-editor');
        if (!t) {
            editor.innerHTML = '<div class="list-empty">Create a template to get started.</div>';
            return;
        }

        editor.innerHTML = `
            <div class="card-head">
                <div>
                    <h3>Edit template</h3>
                    <p class="card-sub">Follow-ups reuse the opening subject with "Re:" so they
                        thread into the same conversation.</p>
                </div>
                ${t.isDefault ? '<span class="pill ok"><i class="dot"></i>Default</span>'
                    : '<button class="btn tiny ghost" data-tpl-act="default">Make default</button>'}
            </div>
            <div class="form-grid">
                <label class="field">
                    <span>Name</span>
                    <input id="tpl-name" type="text" value="${esc(t.name)}">
                </label>
                <label class="field">
                    <span>Used for</span>
                    <select id="tpl-kind" class="select">
                        <option value="INITIAL" ${t.kind === 'INITIAL' ? 'selected' : ''}>Opening email</option>
                        <option value="FOLLOW_UP" ${t.kind === 'FOLLOW_UP' ? 'selected' : ''}>Follow-up</option>
                    </select>
                </label>
                <label class="field span-2">
                    <span>Subject</span>
                    <input id="tpl-subject" type="text" value="${esc(t.subject)}">
                </label>
                <label class="field span-2">
                    <span>Body <em>HTML allowed</em></span>
                    <textarea id="tpl-body" rows="12">${esc(t.bodyHtml)}</textarea>
                </label>
            </div>
            <div class="token-row" id="tpl-tokens">
                ${['name', 'first_name', 'company', 'role', 'my_name', 'my_email', 'date', 'day']
                    .map(tok => `<button type="button" class="token" data-token="${tok}">{{${tok}}}</button>`).join('')}
            </div>
            <div class="preview-frame">
                <div class="preview-subject" id="tpl-preview-subject"></div>
                <div class="preview-body" id="tpl-preview-body"></div>
            </div>
            <div class="card-foot">
                <button class="btn primary" data-tpl-act="save">Save template</button>
                <button class="btn danger" data-tpl-act="delete" style="margin-left:auto">Delete</button>
            </div>`;

        const updatePreview = () => {
            $('#tpl-preview-subject').textContent = fillTokens($('#tpl-subject').value) || '(no subject)';
            $('#tpl-preview-body').innerHTML = fillTokens($('#tpl-body').value);
        };
        updatePreview();
        $('#tpl-subject').addEventListener('input', updatePreview);
        $('#tpl-body').addEventListener('input', updatePreview);

        $('#tpl-tokens').addEventListener('click', ev => {
            const btn = ev.target.closest('[data-token]');
            if (!btn) return;
            const area = $('#tpl-body');
            const token = `{{${btn.dataset.token}}}`;
            const start = area.selectionStart ?? area.value.length;
            area.value = area.value.slice(0, start) + token + area.value.slice(area.selectionEnd ?? start);
            area.focus();
            area.selectionStart = area.selectionEnd = start + token.length;
            updatePreview();
        });

        // Assigned, not added: renderTemplates runs repeatedly and must not stack handlers.
        editor.onclick = async ev => {
            const btn = ev.target.closest('[data-tpl-act]');
            if (!btn) return;
            try {
                if (btn.dataset.tplAct === 'save') {
                    await api(`/api/templates/${t.id}`, {
                        method: 'PUT',
                        body: {
                            name: $('#tpl-name').value,
                            kind: $('#tpl-kind').value,
                            subject: $('#tpl-subject').value,
                            bodyHtml: $('#tpl-body').value
                        }
                    });
                    toast('Template saved.');
                } else if (btn.dataset.tplAct === 'default') {
                    await api(`/api/templates/${t.id}/default`, { method: 'POST' });
                    toast('Set as the default template.');
                } else if (btn.dataset.tplAct === 'delete') {
                    if (!confirm(`Delete the template "${t.name}"?`)) return;
                    await api(`/api/templates/${t.id}`, { method: 'DELETE' });
                    state.activeTemplateId = null;
                    toast('Template deleted.');
                }
                await loadTemplates();
            } catch (err) {
                toast(err.message, 'err');
            }
        };
    }

    /* ------------------------------ settings ----------------------------- */

    async function loadSettings() {
        state.settings = await api('/api/settings');
        fillSettingsForm();
    }

    function fillSettingsForm() {
        const s = state.settings;
        const form = $('#settings-form');
        Object.entries(s).forEach(([key, value]) => {
            const field = form.elements[key];
            if (!field) return;
            if (field.type === 'checkbox') field.checked = !!value;
            else field.value = value ?? '';
        });

        form.elements.smtpPassword.value = '';
        form.elements.imapPassword.value = '';
        $('#smtp-pass-hint').textContent = s.smtpPasswordSet ? 'saved · leave blank to keep' : 'required';
        $('#imap-pass-hint').textContent = s.imapPasswordSet ? 'saved · leave blank to keep' : '';

        const floor = s.minIntervalFloorSeconds;
        form.elements.minIntervalSeconds.min = floor;
        $('#interval-floor-hint').textContent = `never below ${floor}s`;

        const attach = $('#attachment-state');
        attach.textContent = !s.attachmentPath ? ''
            : s.attachmentReady ? ' · file found' : ' · FILE NOT FOUND, nothing will be attached';
        attach.style.color = s.attachmentReady ? 'var(--ok, #2e7d32)' : 'var(--err, #c62828)';

        const smtpState = $('#smtp-state');
        smtpState.className = `pill ${s.smtpConfigured ? 'ok' : 'err'}`;
        smtpState.innerHTML = `<i class="dot"></i>${s.smtpConfigured ? 'Ready to send' : 'Incomplete'}`;

        updatePaceOutputs();
    }

    function updatePaceOutputs() {
        const form = $('#settings-form');
        const interval = Number(form.elements.minIntervalSeconds.value);
        const jitter = Number(form.elements.jitterSeconds.value);
        $('#interval-out').textContent = `${interval}s`;
        $('#jitter-out').textContent = jitter ? `+0–${jitter}s` : 'none';

        const average = interval + jitter / 2;
        const perHour = Math.floor(3600 / average);
        const fifty = Math.round((50 * average) / 60);
        $('#pace-explainer').textContent =
            `Emails leave one at a time, at least ${interval} seconds apart` +
            (jitter ? `, plus a random 0–${jitter}s so the rhythm is not mechanical` : '') +
            `. That is about ${perHour} an hour — a batch of 50 takes roughly ${fifty} minutes.`;
    }

    async function saveSettings(ev) {
        ev.preventDefault();
        const form = $('#settings-form');
        const body = {};
        Array.from(form.elements).forEach(field => {
            if (!field.name) return;
            if (field.type === 'checkbox') body[field.name] = field.checked;
            else if (field.type === 'number' || field.type === 'range') {
                body[field.name] = field.value === '' ? null : Number(field.value);
            } else body[field.name] = field.value;
        });
        if (!body.smtpPassword) delete body.smtpPassword;
        if (!body.imapPassword) delete body.imapPassword;

        try {
            state.settings = await api('/api/settings', { method: 'PUT', body });
            fillSettingsForm();
            $('#settings-dirty').textContent = '';
            toast('Settings saved.');
        } catch (err) {
            toast(err.message, 'err');
        }
    }

    /* ---------------------------- add recipients -------------------------- */

    function parseRecipients(text) {
        const out = [];
        for (const raw of text.split(/\r?\n/)) {
            const line = raw.trim();
            if (!line) continue;
            const match = line.match(/[\w.+-]+@[\w-]+\.[\w.-]+/);
            if (!match) { out.push({ invalid: line }); continue; }
            const email = match[0];
            const rest = [];
            let name = '';
            for (const part of line.split(',').map(p => p.trim()).filter(Boolean)) {
                if (part.includes(email)) {
                    const cleaned = part.replace(/<?\s*[\w.+-]+@[\w-]+\.[\w.-]+\s*>?/, '').trim();
                    if (cleaned) name = cleaned;
                } else {
                    rest.push(part);
                }
            }
            out.push({ name, email, company: rest[0] || '', position: rest[1] || '' });
        }
        return out;
    }

    function openAddModal() {
        const initialTemplates = state.templates.filter(t => t.kind === 'INITIAL');
        const followTemplates = state.templates.filter(t => t.kind === 'FOLLOW_UP');
        const defaults = state.settings || { defaultFollowUpIntervalDays: 4, defaultMaxFollowUps: 2 };

        const templateOptions = list => list.map(t =>
            `<option value="${t.id}" ${t.isDefault ? 'selected' : ''}>${esc(t.name)}</option>`).join('');

        $('#modal').innerHTML = `
            <div class="modal-head">
                <div>
                    <h2 style="font-size:17px">Add recipients</h2>
                    <p style="color:var(--muted);font-size:12.5px;margin-top:3px">
                        Queued emails still go out one at a time, spaced apart.</p>
                </div>
                <button class="icon-btn" data-modal-act="close"><svg><use href="#i-x"/></svg></button>
            </div>
            <div class="modal-body">
                <div class="tabs">
                    <button class="tab active" data-tab="single" type="button">One person</button>
                    <button class="tab" data-tab="bulk" type="button">Paste a list</button>
                </div>

                <div data-panel="single" class="form-grid">
                    <label class="field"><span>Name</span>
                        <input id="add-name" type="text" placeholder="Priya Raman"></label>
                    <label class="field"><span>Email</span>
                        <input id="add-email" type="email" placeholder="priya@company.com"></label>
                    <label class="field"><span>Company</span>
                        <input id="add-company" type="text" placeholder="Northwind Labs"></label>
                    <label class="field"><span>Role</span>
                        <input id="add-position" type="text" placeholder="Backend Engineer"></label>
                </div>

                <div data-panel="bulk" hidden>
                    <label class="field">
                        <span>One per line <em>Name &lt;email&gt;, Company, Role</em></span>
                        <textarea id="add-bulk" rows="7" placeholder="Priya Raman <priya@northwind.com>, Northwind Labs, Backend Engineer
tomas@lumen.io, Lumen Systems, Java Developer
hiring@brightpath.com"></textarea>
                    </label>
                    <p class="note" id="bulk-summary">Nothing pasted yet.</p>
                </div>

                <div class="form-grid" style="border-top:1px solid var(--border);padding-top:14px">
                    <label class="field"><span>Opening template</span>
                        <select id="add-template" class="select">${templateOptions(initialTemplates)}</select></label>
                    <label class="field"><span>Follow-up template</span>
                        <select id="add-followup-template" class="select">
                            ${templateOptions(followTemplates)}</select></label>
                    <label class="field"><span>Follow up after (days of silence)</span>
                        <input id="add-interval" type="number" min="1" max="90"
                               value="${defaults.defaultFollowUpIntervalDays}"></label>
                    <label class="field"><span>Maximum follow-ups</span>
                        <input id="add-max" type="number" min="0" max="10"
                               value="${defaults.defaultMaxFollowUps}"></label>
                    <div class="field span-2 switch-row">
                        <label class="switch">
                            <input type="checkbox" id="add-auto" checked><span class="track"></span>
                            <span class="switch-label">Follow up automatically</span>
                        </label>
                        <label class="switch">
                            <input type="checkbox" id="add-queue" checked><span class="track"></span>
                            <span class="switch-label">Queue the opening email now</span>
                        </label>
                    </div>
                </div>
            </div>
            <div class="modal-foot">
                <span class="spacer" id="add-hint"></span>
                <button class="btn ghost" data-modal-act="close">Cancel</button>
                <button class="btn primary" data-modal-act="save">Add</button>
            </div>`;

        $('#modal').hidden = false;
        $('#modal-scrim').hidden = false;

        let tab = 'single';
        const modal = $('#modal');

        modal.querySelector('.tabs').addEventListener('click', ev => {
            const btn = ev.target.closest('[data-tab]');
            if (!btn) return;
            tab = btn.dataset.tab;
            $$('.tab', modal).forEach(t => t.classList.toggle('active', t === btn));
            $$('[data-panel]', modal).forEach(p => { p.hidden = p.dataset.panel !== tab; });
        });

        const bulkArea = $('#add-bulk');
        bulkArea.addEventListener('input', () => {
            const parsed = parseRecipients(bulkArea.value);
            const bad = parsed.filter(p => p.invalid).length;
            $('#bulk-summary').textContent = parsed.length === 0
                ? 'Nothing pasted yet.'
                : `${parsed.length - bad} recipient${parsed.length - bad === 1 ? '' : 's'} recognised`
                  + (bad ? ` · ${bad} line${bad === 1 ? '' : 's'} without an email address will be skipped` : '');
        });

        // #modal is reused across opens, so assign rather than add.
        modal.onclick = async ev => {
            const btn = ev.target.closest('[data-modal-act]');
            if (!btn) return;
            if (btn.dataset.modalAct === 'close') return closeModal();

            const shared = {
                initialTemplateId: Number($('#add-template').value) || null,
                followUpTemplateId: Number($('#add-followup-template').value) || null,
                followUpIntervalDays: Number($('#add-interval').value),
                maxFollowUps: Number($('#add-max').value),
                autoFollowUp: $('#add-auto').checked,
                queueNow: $('#add-queue').checked
            };

            try {
                btn.disabled = true;
                if (tab === 'single') {
                    await api('/api/outreach', {
                        method: 'POST',
                        body: Object.assign({
                            recipientName: $('#add-name').value,
                            recipientEmail: $('#add-email').value,
                            company: $('#add-company').value,
                            position: $('#add-position').value
                        }, shared)
                    });
                    toast(shared.queueNow ? 'Added and queued.' : 'Added as a draft.');
                } else {
                    const recipients = parseRecipients(bulkArea.value).filter(r => !r.invalid);
                    if (!recipients.length) throw new Error('No valid email addresses found in that list.');
                    const result = await api('/api/outreach/bulk', {
                        method: 'POST',
                        body: Object.assign({ recipients }, shared)
                    });
                    toast(`${result.created.length} added`
                        + (result.skipped.length ? `, ${result.skipped.length} skipped` : '') + '.');
                    if (result.skipped.length) {
                        setTimeout(() => toast('Skipped: ' + result.skipped.join('; '), 'err'), 400);
                    }
                }
                closeModal();
                refreshCurrent();
            } catch (err) {
                toast(err.message, 'err');
                btn.disabled = false;
            }
        };
    }

    function closeModal() {
        $('#modal').hidden = true;
        $('#modal-scrim').hidden = true;
        $('#modal').innerHTML = '';
    }

    async function openNewTemplateModal() {
        try {
            const created = await api('/api/templates', {
                method: 'POST',
                body: {
                    name: 'Untitled template',
                    kind: 'INITIAL',
                    subject: 'Application for {{role}} at {{company}}',
                    bodyHtml: '<p>Hi {{first_name}},</p>\n<p>Write your opening here.</p>\n'
                        + '<p>Best,<br>{{my_name}}</p>'
                }
            });
            state.activeTemplateId = created.id;
            await loadTemplates();
            toast('Template created — edit it on the right.');
        } catch (err) {
            toast(err.message, 'err');
        }
    }

    /* ------------------------------ routing ------------------------------ */

    const PAGES = {
        dashboard: ['Dashboard', 'Everything you have sent, and everything still to send.'],
        outreach: ['Outreach', 'Every thread, its status and when the next follow-up lands.'],
        queue: ['Send queue', 'Emails leave one at a time, never faster than the interval you set.'],
        templates: ['Templates', 'Write once, personalise per recipient with placeholders.'],
        settings: ['Settings', 'Mail account, sending pace and reply detection.']
    };

    function topbarFor(view) {
        if (view === 'dashboard') {
            return `${state.demoLoaded
                ? '<button class="btn ghost tiny" data-action="clear-demo">Remove demo data</button>' : ''}
                <button class="btn primary" data-action="add-recipients">
                    <svg><use href="#i-plus"/></svg> Add recipients</button>`;
        }
        if (view === 'queue') {
            return '<button class="btn ghost" data-action="refresh"><svg><use href="#i-refresh"/></svg> Refresh</button>';
        }
        if (view === 'templates') {
            return '<button class="btn primary" data-action="new-template"><svg><use href="#i-plus"/></svg> New template</button>';
        }
        return '';
    }

    async function go(view) {
        if (!PAGES[view]) view = 'dashboard';
        state.view = view;

        $$('.nav-item').forEach(a => a.classList.toggle('active', a.dataset.view === view));
        $$('.view').forEach(section => { section.hidden = section.id !== `view-${view}`; });
        $('#page-title').textContent = PAGES[view][0];
        $('#page-sub').textContent = PAGES[view][1];
        $('#topbar-actions').innerHTML = topbarFor(view);

        await refreshCurrent();
    }

    async function refreshCurrent() {
        try {
            if (state.view === 'dashboard') {
                const [stats, demo] = await Promise.all([api('/api/stats'), api('/api/demo')]);
                state.stats = stats;
                if (demo.loaded !== state.demoLoaded) {
                    state.demoLoaded = demo.loaded;
                    $('#topbar-actions').innerHTML = topbarFor('dashboard');
                }
                renderDashboard();
            } else if (state.view === 'outreach') {
                await loadOutreach();
            } else if (state.view === 'queue') {
                await pollQueue();
                renderQueue();
            } else if (state.view === 'templates') {
                await loadTemplates();
            } else if (state.view === 'settings') {
                await loadSettings();
            }

            const count = $('#nav-outreach-count');
            if (state.stats) {
                count.hidden = state.stats.totalOutreach === 0;
                count.textContent = state.stats.totalOutreach;
            }
        } catch (err) {
            toast(err.message, 'err');
        }
    }

    /* ------------------------------ polling ------------------------------ */

    async function pollQueue() {
        try {
            state.queue = await api('/api/queue');
            state.queueAt = Date.now();
            renderPaceCard();
            if (state.view === 'queue') renderQueue();
        } catch {
            /* a dropped poll is not worth a toast; the next one will recover */
        }
    }

    function startTimers() {
        pollQueue();
        setInterval(pollQueue, 2500);
        setInterval(() => {
            renderPaceCard();
            if (state.view === 'queue' && state.queue) {
                const remaining = localCountdown();
                const label = $('.pace-ring-label b');
                if (label && !state.queue.paused) label.textContent = remaining;
            }
        }, 1000);
        setInterval(() => {
            if (state.view === 'dashboard') refreshCurrent();
            if (state.view === 'outreach') loadOutreach().catch(() => {});
        }, 8000);
    }

    /* -------------------------------- init ------------------------------- */

    function applyTheme(theme) {
        document.documentElement.dataset.theme = theme;
        try { localStorage.setItem('jobtrail-theme', theme); } catch { /* private mode */ }
    }

    function wireGlobalActions() {
        document.addEventListener('click', async ev => {
            const actionBtn = ev.target.closest('[data-action]');
            if (actionBtn) {
                const action = actionBtn.dataset.action;
                try {
                    if (action === 'add-recipients') {
                        if (!state.templates.length) state.templates = await api('/api/templates');
                        if (!state.settings) state.settings = await api('/api/settings');
                        openAddModal();
                    } else if (action === 'load-demo') {
                        actionBtn.disabled = true;
                        const res = await api('/api/demo/seed', { method: 'POST' });
                        toast(res.message);
                        state.demoLoaded = true;
                        await refreshCurrent();
                    } else if (action === 'clear-demo') {
                        const res = await api('/api/demo', { method: 'DELETE' });
                        toast(res.message);
                        state.demoLoaded = false;
                        await refreshCurrent();
                    } else if (action === 'toggle-pause') {
                        await api('/api/queue/pause', {
                            method: 'POST', body: { paused: !state.queue.paused }
                        });
                        await pollQueue();
                    } else if (action === 'refresh') {
                        await pollQueue();
                        toast('Refreshed.');
                    } else if (action === 'new-template') {
                        await openNewTemplateModal();
                    } else if (action === 'test-smtp') {
                        actionBtn.disabled = true;
                        const res = await api('/api/settings/test-smtp', { method: 'POST' });
                        toast(res.message, res.ok ? 'ok' : 'err');
                    } else if (action === 'test-email') {
                        actionBtn.disabled = true;
                        const res = await api('/api/settings/test-email', {
                            method: 'POST', body: { to: $('#test-email-to').value }
                        });
                        toast(res.message, res.ok ? 'ok' : 'err');
                    } else if (action === 'test-imap') {
                        actionBtn.disabled = true;
                        const res = await api('/api/settings/test-imap', { method: 'POST' });
                        toast(res.message, res.ok ? 'ok' : 'err');
                    }
                } catch (err) {
                    toast(err.message, 'err');
                } finally {
                    actionBtn.disabled = false;
                }
                return;
            }

            const queueBtn = ev.target.closest('[data-queue]');
            const followBtn = ev.target.closest('[data-followup]');
            const repliedBtn = ev.target.closest('[data-replied]');
            const deleteBtn = ev.target.closest('[data-delete]');
            const cancelBtn = ev.target.closest('[data-cancel]');
            const templateItem = ev.target.closest('[data-template]');
            const openRow = ev.target.closest('[data-open]');

            try {
                if (queueBtn) {
                    ev.stopPropagation();
                    await api(`/api/outreach/${queueBtn.dataset.queue}/queue`, { method: 'POST', body: {} });
                    toast('Queued.');
                    await loadOutreach();
                } else if (followBtn) {
                    ev.stopPropagation();
                    await api(`/api/outreach/${followBtn.dataset.followup}/follow-up`,
                        { method: 'POST', body: {} });
                    toast('Follow-up queued.');
                    await loadOutreach();
                } else if (repliedBtn) {
                    ev.stopPropagation();
                    await api(`/api/outreach/${repliedBtn.dataset.replied}/status`,
                        { method: 'POST', body: { status: 'REPLIED' } });
                    toast('Marked as replied.');
                    await loadOutreach();
                } else if (deleteBtn) {
                    ev.stopPropagation();
                    if (!confirm('Delete this thread and its email history?')) return;
                    await api(`/api/outreach/${deleteBtn.dataset.delete}`, { method: 'DELETE' });
                    toast('Deleted.');
                    await loadOutreach();
                } else if (cancelBtn) {
                    await api(`/api/messages/${cancelBtn.dataset.cancel}/cancel`, { method: 'POST' });
                    toast('Removed from the queue.');
                    await pollQueue();
                } else if (templateItem) {
                    state.activeTemplateId = Number(templateItem.dataset.template);
                    renderTemplates();
                } else if (openRow) {
                    await openThread(Number(openRow.dataset.open));
                }
            } catch (err) {
                toast(err.message, 'err');
            }
        });

        $('#drawer-scrim').addEventListener('click', closeDrawer);
        $('#modal-scrim').addEventListener('click', closeModal);
        document.addEventListener('keydown', ev => {
            if (ev.key === 'Escape') { closeDrawer(); closeModal(); }
        });

        $('#theme-toggle').addEventListener('click', () => {
            const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
            applyTheme(next);
            if (state.view === 'dashboard' && state.stats) renderDashboard();
        });

        let searchTimer;
        $('#outreach-search').addEventListener('input', () => {
            clearTimeout(searchTimer);
            searchTimer = setTimeout(() => loadOutreach().catch(e => toast(e.message, 'err')), 220);
        });
        $('#outreach-filter').addEventListener('change', () =>
            loadOutreach().catch(e => toast(e.message, 'err')));

        const form = $('#settings-form');
        form.addEventListener('submit', saveSettings);
        form.addEventListener('input', ev => {
            $('#settings-dirty').textContent = 'Unsaved changes';
            if (ev.target.name === 'minIntervalSeconds' || ev.target.name === 'jitterSeconds') {
                updatePaceOutputs();
            }
        });

        window.addEventListener('hashchange', () => go(location.hash.slice(1)));
    }

    function init() {
        let stored = null;
        try { stored = localStorage.getItem('jobtrail-theme'); } catch { /* ignore */ }
        // ?theme=light|dark wins, so a link can pin the appearance.
        const asked = new URLSearchParams(location.search).get('theme');
        applyTheme(['light', 'dark'].includes(asked) ? asked
            : stored || (window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark'));

        wireGlobalActions();
        startTimers();
        go(location.hash.slice(1) || 'dashboard');
    }

    init();
})();
