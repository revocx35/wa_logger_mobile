/*
 * wa_logger mobile — page bridge.
 *
 * Injected into https://web.whatsapp.com (main frame only) at document start by WaEngine
 * (WebViewCompat.addDocumentStartJavaScript). It talks to the app only through `WalBridge`, the
 * origin-restricted message port from WebViewCompat.addWebMessageListener:
 *   page → app: JSON strings {t: 'hello' | 'wa' | 'state' | 'qr' | 'loading' | 'authenticated' | 'ready' |
 *               'logout' | 'ev' | 'res' | 'log', ...}
 *   app → page: JSON commands {id, op, args} answered with {t: 'res', id, ok, data | error}
 *
 * What it does is what whatsapp-web.js (Apache-2.0, https://github.com/wwebjs/whatsapp-web.js) does
 * inside the page — the event hooks, QR/pairing-code login and message serialization are adapted from
 * its Client.js / Injected code (see NOTICE) — plus the direct model reads of the wa_logger server's
 * `wa/pageapi.ts` (the library's chat helpers break on current WhatsApp Web builds) and its chunked
 * in-page media download. Message content only ever goes to the app, never anywhere else, and nothing
 * here writes to the console.
 */
(function () {
  'use strict';
  if (window.top !== window) return;
  if (window.__walm) return;
  var port = window.WalBridge;
  if (!port || typeof port.postMessage !== 'function') return;
  Object.defineProperty(window, '__walm', { value: 1 });

  var BRIDGE_VERSION = 1;
  // WhatsApp Web replaces the global timer functions with its own; keep the native ones.
  var T = {
    set: window.setTimeout.bind(window),
    clear: window.clearTimeout.bind(window),
    every: window.setInterval.bind(window),
    stop: window.clearInterval.bind(window),
  };
  var TIMEOUT = { timeout: true };
  function sleep(ms) {
    return new Promise(function (r) { T.set(r, ms); });
  }
  /** Races [p] against a timer (WhatsApp's own calls can wait forever); the timer is always cleared. */
  function within(p, ms) {
    var timer;
    var limit = new Promise(function (r) {
      timer = T.set(function () {
        r(TIMEOUT);
      }, ms);
    });
    return Promise.race([p, limit]).finally(function () {
      T.clear(timer);
    });
  }

  function post(obj) {
    try {
      port.postMessage(JSON.stringify(obj));
    } catch (e) {
      /* the app side is gone (reload) */
    }
  }
  function note(msg) {
    post({ t: 'log', msg: String(msg).slice(0, 300) });
  }
  function req(name) {
    return window.require(name);
  }
  function tryReq(name) {
    try {
      return window.require(name);
    } catch (e) {
      return null;
    }
  }

  /* ------------------------------------------------------------ ids */

  function widStr(w) {
    if (!w) return null;
    if (typeof w === 'string') return w;
    if (typeof w._serialized === 'string') return w._serialized;
    try {
      var s = w.toString();
      return s && s !== '[object Object]' ? s : null;
    } catch (e) {
      return null;
    }
  }

  /** Message key as WhatsApp prints it (MsgKey.toString()), e.g. "false_123@c.us_ABCDEF". */
  function keyStr(k) {
    if (!k) return null;
    if (typeof k === 'string') return k;
    if (typeof k._serialized === 'string' && k._serialized) return k._serialized;
    try {
      var s = k.toString();
      if (s && s !== '[object Object]' && s.indexOf('_') > 0) return s;
    } catch (e) {
      /* rebuild below */
    }
    var remote = widStr(k.remote);
    if (!remote || !k.id) return null;
    var participant = widStr(k.participant);
    return (k.fromMe ? 'true' : 'false') + '_' + remote + '_' + k.id + (participant ? '_' + participant : '') + (k.self ? '_' + k.self : '');
  }

  function keyObj(k) {
    if (!k || typeof k !== 'object') return k || null;
    return {
      fromMe: !!k.fromMe,
      remote: widStr(k.remote),
      id: typeof k.id === 'string' ? k.id : null,
      participant: widStr(k.participant),
      self: typeof k.self === 'string' ? k.self : undefined,
      _serialized: keyStr(k),
    };
  }

  /* -------------------------------------------------- serialization */

  var PLAIN = [
    'type', 'subtype', 't', 'body', 'caption', 'notifyName', 'isViewOnce', 'directPath', 'mimetype', 'filename',
    'size', 'width', 'height', 'duration', 'isGif', 'isLive', 'lat', 'lng', 'loc', 'clientUrl', 'vcardFormattedName',
    'pollName', 'pollSelectableOptionsCount', 'isVideoCall', 'callOutcome', 'callDuration', 'inviteGrpName',
    'forwardingScore', 'isForwarded', 'isStatusV3', 'ack', 'latestEditSenderTimestampMs', 'quotedStanzaID', 'isNewMsg',
  ];
  var WIDS = ['from', 'to', 'author', 'quotedParticipant'];
  var WID_LISTS = ['mentionedJidList', 'recipients', 'templateParams'];

  function plainValue(v) {
    var t = typeof v;
    return v === null || t === 'string' || t === 'number' || t === 'boolean' ? v : undefined;
  }

  /** The fields the app's mapper reads (a subset of whatsapp-web.js getMessageModel()). */
  function ser(m) {
    var s;
    try {
      s = typeof m.serialize === 'function' ? m.serialize() : m;
    } catch (e) {
      s = m;
    }
    var out = { id: keyObj(m.id || s.id) };
    var i;
    for (i = 0; i < PLAIN.length; i++) {
      var v = plainValue(s[PLAIN[i]]);
      if (v === undefined) v = plainValue(m[PLAIN[i]]);
      if (v !== undefined) out[PLAIN[i]] = v;
    }
    if (out.isStatusV3 === undefined && m.isStatusV3 !== undefined) out.isStatusV3 = !!m.isStatusV3;
    for (i = 0; i < WIDS.length; i++) {
      var w = widStr(s[WIDS[i]]);
      if (w) out[WIDS[i]] = w;
    }
    for (i = 0; i < WID_LISTS.length; i++) {
      var list = s[WID_LISTS[i]];
      if (Array.isArray(list)) {
        out[WID_LISTS[i]] = list.slice(0, 256).map(function (x) {
          return widStr(x) || (plainValue(x) === undefined ? null : x);
        });
      }
    }
    if (s.latestEditMsgKey) out.latestEditMsgKey = keyObj(s.latestEditMsgKey);
    if (s.protocolMessageKey) out.protocolMessageKey = keyObj(s.protocolMessageKey);
    if (Array.isArray(s.vcardList)) {
      out.vcardList = s.vcardList.slice(0, 30).map(function (c) {
        return { displayName: plainValue(c && c.displayName) || null, vcard: typeof (c && c.vcard) === 'string' ? c.vcard : '' };
      });
    }
    if (Array.isArray(s.pollOptions)) {
      out.pollOptions = s.pollOptions.slice(0, 64).map(function (o) {
        return { name: o && typeof o.name === 'string' ? o.name : '' };
      });
    }
    var q = s.quotedMsg;
    if (q && typeof q === 'object') {
      out.quotedMsg = {
        type: plainValue(q.type),
        body: typeof q.body === 'string' ? q.body.slice(0, 4096) : undefined,
        caption: plainValue(q.caption),
        pollName: plainValue(q.pollName),
      };
    }
    return out;
  }

  function isBlob(s) {
    return typeof s === 'string' && s.length >= 200 && /^[A-Za-z0-9+/]+={0,2}$/.test(s.slice(0, 4096));
  }

  /* --------------------------------------------------------- boot */

  var S = null; // WAWebSocketModel.Socket
  var synced = false;
  var hooked = false;

  function waitForWhatsApp() {
    var tries = 0;
    var iv = T.every(function () {
      tries++;
      var ok = false;
      try {
        ok = typeof window.require === 'function' && !!(window.Debug && window.Debug.VERSION) && !!tryReq('WAWebSocketModel');
      } catch (e) {
        ok = false;
      }
      if (!ok) {
        if (tries % 40 === 0) post({ t: 'loading', percent: null, waiting: tries });
        return;
      }
      T.stop(iv);
      try {
        start();
      } catch (e) {
        note('start failed: ' + (e && e.message));
      }
    }, 250);
  }

  /** How this linked device is named on the phone (whatsapp-web.js setDeviceName). */
  function setDeviceName(os, browser) {
    var U = tryReq('WAWebMiscBrowserUtils');
    if (!U || typeof U.info !== 'function' || U.__walm) return;
    var orig = U.info;
    U.info = function () {
      var i = orig.apply(this, arguments) || {};
      var copy = {};
      for (var k in i) copy[k] = i[k];
      copy.os = os;
      copy.name = browser;
      return copy;
    };
    U.__walm = true;
  }

  function regUtils() {
    var out = {};
    ['WAWebCompanionRegClientUtils', 'WAWebAdvSignatureApi', 'WAWebUserPrefsInfoStore', 'WAWebSignalStoreApi'].forEach(function (n) {
      var m = tryReq(n);
      if (m) for (var k in m) out[k] = m[k];
    });
    return out;
  }

  var qrBusy = false;
  function emitQr() {
    if (qrBusy || !S || (S.state !== 'UNPAIRED' && S.state !== 'UNPAIRED_IDLE')) return;
    qrBusy = true;
    (async function () {
      var RU = regUtils();
      var reg = await RU.waSignalStore.getRegistrationInfo();
      var noise = await RU.waNoiseInfo.get();
      var B64 = req('WABase64');
      var ref = req('WAWebConnModel').Conn.ref;
      if (!ref) return;
      var qr = [
        ref,
        B64.encodeB64(noise.staticKeyPair.pubKey),
        B64.encodeB64(reg.identityKeyPair.pubKey),
        req('WAWebUserPrefsMultiDevice').getADVSecretKey(),
        RU.DEVICE_PLATFORM,
      ].join(',');
      // What WhatsApp Web itself encodes now: a link, so a phone's camera app opens WhatsApp directly.
      post({ t: 'qr', qr: 'https://wa.me/settings/linked_devices#' + qr });
    })()
      .catch(function (e) {
        note('qr failed: ' + (e && e.message));
      })
      .then(function () {
        qrBusy = false;
      });
  }

  function start() {
    setDeviceName(window.__walmDeviceName || 'wa_logger mobile', 'Chrome');
    S = req('WAWebSocketModel').Socket;
    var Conn = req('WAWebConnModel').Conn;
    var Cmd = req('WAWebCmd').Cmd;
    post({ t: 'wa', version: String(window.Debug.VERSION), state: String(S.state) });

    S.on('change:state', function (_m, state) {
      post({ t: 'state', state: String(state) });
      if (state === 'UNPAIRED' || state === 'UNPAIRED_IDLE') emitQr();
      if (state === 'CONFLICT') {
        // Another WhatsApp Web tab took the session over: take it back (whatsapp-web.js takeoverOnConflict).
        T.set(function () {
          try {
            S.takeover();
          } catch (e) {
            /* ignore */
          }
        }, 5000);
      }
      if (state === 'CONNECTED') armReadyFallback();
    });
    S.on('change:hasSynced', function () {
      if (S.hasSynced) onSynced();
    });
    Conn.on('change:ref', function () {
      emitQr();
    });
    Cmd.on('offline_progress_update_from_bridge', function () {
      try {
        post({ t: 'loading', percent: req('WAWebOfflineHandler').OfflineMessageHandler.getOfflineDeliveryProgress() });
      } catch (e) {
        /* ignore */
      }
    });
    Cmd.on('logout', function () {
      post({ t: 'logout' });
    });
    Cmd.on('logout_from_bridge', function () {
      post({ t: 'logout' });
    });

    if (S.state === 'UNPAIRED' || S.state === 'UNPAIRED_IDLE') emitQr();
    if (S.hasSynced) onSynced();
    else if (S.state === 'CONNECTED') armReadyFallback();
  }

  /**
   * `hasSynced` has been seen never to change on some WhatsApp Web builds (the wa_logger server met the
   * same stall through whatsapp-web.js). If the socket is connected for 60 s without it, continue anyway.
   */
  var fallbackTimer = null;
  function armReadyFallback() {
    if (synced || fallbackTimer) return;
    fallbackTimer = T.set(function () {
      fallbackTimer = null;
      if (!synced && S && S.state === 'CONNECTED') {
        note('hasSynced did not change; continuing (fallback)');
        onSynced();
      }
    }, 60000);
  }

  function meInfo() {
    var id = null;
    var lid = null;
    try {
      var U = req('WAWebUserPrefsMeUser');
      var pn = U.getMaybeMePnUser ? U.getMaybeMePnUser() : null;
      var l = U.getMaybeMeLidUser ? U.getMaybeMeLidUser() : null;
      id = widStr(pn) || widStr(l);
      lid = widStr(l);
    } catch (e) {
      /* ignore */
    }
    var name = null;
    try {
      name = req('WAWebConnModel').Conn.pushname || null;
    } catch (e) {
      /* ignore */
    }
    return { id: id, lid: lid, name: name };
  }

  function onSynced() {
    if (synced) return;
    synced = true;
    if (fallbackTimer) T.clear(fallbackTimer);
    post({ t: 'authenticated' });
    try {
      hook();
    } catch (e) {
      note('hook failed: ' + (e && e.message));
    }
    post({ t: 'ready', me: meInfo() });
    stayOffline();
  }

  /**
   * The hidden page counts as "visible" for Chromium, and WhatsApp Web could then show the owner as online
   * around the clock. Send "unavailable" presence after linking and every few minutes, except while the
   * owner is using the page on screen.
   */
  var shownOnScreen = false;
  function stayOffline() {
    var send = function () {
      if (shownOnScreen) return;
      var P = tryReq('WAWebPresenceChatAction');
      if (!P || typeof P.sendPresenceUnavailable !== 'function') return;
      try {
        Promise.resolve(P.sendPresenceUnavailable()).catch(function () {});
      } catch (e) {
        /* ignore */
      }
    };
    send();
    T.every(send, 4 * 60000);
  }

  /* ------------------------------------------------------- events */

  // Recently seen messages by stanza id, so a revoke can carry the original content ("before").
  var recent = new Map();
  function remember(id, s) {
    if (!id) return;
    recent.set(id, s);
    if (recent.size > 200) recent.delete(recent.keys().next().value);
  }

  function ev(name, data) {
    var out = { t: 'ev', ev: name };
    for (var k in data) out[k] = data[k];
    post(out);
  }

  function wrap(fn) {
    return function () {
      try {
        fn.apply(this, arguments);
      } catch (e) {
        note('event handler failed: ' + (e && e.message));
      }
    };
  }

  /** Calls `observe(args)` before the original function (whatsapp-web.js injectToFunction). */
  function observe(moduleName, path, observer) {
    try {
      var mod = req(moduleName);
      var parts = path.split('.');
      var name = parts.pop();
      for (var i = 0; i < parts.length; i++) {
        if (!mod[parts[i]]) return false;
        mod = mod[parts[i]];
      }
      var orig = mod[name];
      if (typeof orig !== 'function' || orig.__walm) return false;
      var target = mod;
      var replaced = function () {
        try {
          observer(Array.prototype.slice.call(arguments));
        } catch (e) {
          /* never break WhatsApp */
        }
        return orig.apply(target, arguments);
      };
      replaced.__walm = true;
      mod[name] = replaced;
      return true;
    } catch (e) {
      return false;
    }
  }

  var pendingResend = new Set();
  var resendTimer = null;
  function requestResend(msg) {
    pendingResend.add(msg);
    if (resendTimer) return;
    resendTimer = T.set(function () {
      resendTimer = null;
      var msgs = Array.from(pendingResend);
      pendingResend.clear();
      if (!msgs.length) return;
      try {
        req('WAWebNonMessageDataRequestPlaceholderMessageResendUtils').handlePlaceholderMsgsSeen(msgs, true);
      } catch (e) {
        /* ignore */
      }
    }, 5000);
  }

  function hook() {
    if (hooked) return;
    hooked = true;
    var C = req('WAWebCollections');
    var Msg = C.Msg;
    var Chat = C.Chat;

    // Ask the phone to resend messages that arrived undecryptable ("Waiting for this message").
    var gating = tryReq('WAWebSyncGatingUtils');
    if (gating) {
      try {
        gating.isPlaceholderMessageResendEnabled = function () {
          return true;
        };
      } catch (e) {
        /* read-only on some builds */
      }
    }

    Msg.on('add', wrap(function (msg) {
      if (!msg.isNewMsg) return;
      var s = ser(msg);
      if (msg.type !== 'ciphertext') {
        remember(msg.id && msg.id.id, s);
        ev('message', { msg: s });
        return;
      }
      ev('message', { msg: s });
      if (msg.subtype && String(msg.subtype).slice(-19) === '_unavailable_fanout') return;
      requestResend(msg);
      msg.once('change:type', wrap(function (m2) {
        pendingResend.delete(m2);
        if (m2.type === 'revoked') return;
        var s2 = ser(m2);
        remember(m2.id && m2.id.id, s2);
        ev('message', { msg: s2 });
      }));
    }));

    Msg.on('change:type', wrap(function (msg) {
      if (msg.type !== 'revoked') return;
      var before = msg.id && recent.get(msg.id.id);
      ev('revoke', { after: ser(msg), before: before || null });
    }));

    Msg.on('change:ack', wrap(function (msg, ack) {
      if (typeof ack !== 'number') return;
      ev('ack', { id: keyStr(msg.id), ack: ack });
    }));

    Msg.on('remove', wrap(function (msg) {
      if (!msg.isNewMsg) return;
      ev('revoke_me', { id: keyStr(msg.id) });
    }));

    Msg.on('change:body change:caption', wrap(function (msg, newBody, prevBody) {
      if (msg.type === 'revoked') return;
      // Thumbnail updates of media also come through here: send only a flag, never the image.
      var blob = isBlob(newBody) || isBlob(prevBody);
      ev('edit', {
        msg: ser(msg),
        newBody: blob || typeof newBody !== 'string' ? null : newBody,
        prevBody: blob || typeof prevBody !== 'string' ? null : prevBody,
        blob: blob,
      });
    }));

    Chat.on('remove', wrap(function (chat) {
      var id = widStr(chat && chat.id);
      if (id) ev('chat_removed', { id: id });
    }));

    Chat.on('change:archive', wrap(function (chat, curr) {
      var id = widStr(chat && chat.id);
      if (id) ev('chat_archived', { id: id, archived: !!curr });
    }));

    observe('WAWebAddonReactionTableMode', 'reactionTableMode.bulkUpsert', function (args) {
      var list = args[0];
      if (!Array.isArray(list)) return;
      var out = [];
      for (var i = 0; i < list.length && i < 500; i++) {
        var r = list[i];
        if (!r) continue;
        out.push({
          msgId: keyStr(r.reactionParentKey),
          senderId: widStr(r.author) || widStr(r.from),
          reaction: typeof r.reactionText === 'string' ? r.reactionText : '',
          timestamp: typeof r.reactionTimestamp === 'number' ? r.reactionTimestamp : null,
        });
      }
      if (out.length) ev('reactions', { reactions: out });
    });
  }

  /* ----------------------------------------------------- page API */

  function chatName(c) {
    return c.formattedTitle || c.name || (c.contact && (c.contact.name || c.contact.pushname)) || null;
  }

  function chatLite(c) {
    return {
      id: widStr(c.id),
      name: chatName(c),
      timestamp: typeof c.t === 'number' && c.t > 0 ? c.t * 1000 : null,
      archived: !!c.archive,
      pinned: !!c.pin,
      muted: !!(c.mute && c.mute.expiration !== 0 && c.mute.expiration !== undefined),
    };
  }

  async function findChat(id) {
    var C = req('WAWebCollections');
    var wid = req('WAWebWidFactory').createWid(id);
    var chat = C.Chat.get(id) || C.Chat.get(wid);
    if (chat) return chat;
    try {
      var found = await req('WAWebFindChatAction').findOrCreateLatestChat(wid);
      return (found && found.chat) || null;
    } catch (e) {
      return null;
    }
  }

  var api = {
    ping: function () {
      return typeof window.require === 'function' && S ? (synced ? 'ok' : 'loading') : 'loading';
    },

    status: function () {
      return { state: S ? String(S.state) : null, synced: synced, version: window.Debug && window.Debug.VERSION ? String(window.Debug.VERSION) : null, me: synced ? meInfo() : null };
    },

    listChats: function () {
      var out = [];
      var models = req('WAWebCollections').Chat.getModelsArray();
      for (var i = 0; i < models.length; i++) {
        try {
          var c = chatLite(models[i]);
          if (c.id) out.push(c);
        } catch (e) {
          /* skip one broken chat model */
        }
      }
      return out;
    },

    chatInfo: function (args) {
      var C = req('WAWebCollections');
      var c = C.Chat.get(args.id) || C.Chat.get(req('WAWebWidFactory').createWid(args.id));
      if (!c) return null;
      var l = chatLite(c);
      delete l.id;
      return l;
    },

    contactInfo: function (args) {
      var id = args.id;
      var C = req('WAWebCollections');
      var wid = req('WAWebWidFactory').createWid(id);
      var c = C.Contact.get(id) || C.Contact.get(wid);
      // "@lid" ids hide the phone number; WhatsApp keeps a LID → phone mapping.
      var pn = (c && c.phoneNumber) || null;
      if (!pn && /@lid$/.test(id)) {
        try {
          pn = req('WAWebLidMigrationUtils').toPn(wid);
        } catch (e) {
          pn = null;
        }
      }
      var pnSer = pn ? widStr(pn) : null;
      var pnContact = pnSer ? C.Contact.get(pnSer) : null;
      var m = pnSer ? /^(\d{5,20})@/.exec(pnSer) : null;
      var digits = m ? m[1] : null;
      if (!c && !pnContact) return digits ? { name: null, pushname: null, phone: '+' + digits, isMe: false, isBusiness: false } : null;
      return {
        name: (c && c.name) || (pnContact && pnContact.name) || (c && c.verifiedName) || (pnContact && pnContact.verifiedName) || null,
        pushname: (c && c.pushname) || (pnContact && pnContact.pushname) || null,
        phone: digits ? '+' + digits : null,
        isMe: !!((c && c.isMe) || (pnContact && pnContact.isMe)),
        isBusiness: !!((c && c.isBusiness) || (pnContact && pnContact.isBusiness)),
      };
    },

    /** whatsapp-web.js Chat.fetchMessages (notifications skipped) without its fragile chat model. */
    fetchMessages: async function (args) {
      var chat = await findChat(args.id);
      if (!chat || !chat.msgs) return [];
      var max = Math.max(1, Math.min(args.limit | 0 || 1, 5000));
      var real = function (m) {
        return !m.isNotification;
      };
      var msgs = chat.msgs.getModelsArray().filter(real);
      var rounds = 0;
      var loader = tryReq('WAWebChatLoadMessages');
      while (msgs.length < max && rounds++ < 100 && loader) {
        var loaded = null;
        try {
          loaded = await within(loader.loadEarlierMsgs({ chat: chat }), 30000);
        } catch (e) {
          loaded = null;
        }
        if (!loaded || loaded === TIMEOUT || !loaded.length) break;
        msgs = loaded.filter(real).concat(msgs);
      }
      msgs.sort(function (a, b) {
        return (a.t || 0) - (b.t || 0);
      });
      if (msgs.length > max) msgs = msgs.slice(msgs.length - max);
      var out = [];
      for (var i = 0; i < msgs.length; i++) {
        try {
          out.push(ser(msgs[i]));
        } catch (e) {
          /* skip one unserializable message */
        }
      }
      return out;
    },

    profilePicUrl: async function (args) {
      var C = req('WAWebCollections');
      var cached = C.ProfilePicThumb && C.ProfilePicThumb.get && C.ProfilePicThumb.get(args.id);
      if (cached && cached.eurl) return cached.eurl;
      var chat = C.Chat.get(args.id) || (await findChat(args.id));
      if (!chat) return null;
      var res = await within(req('WAWebContactProfilePicThumbBridge').requestProfilePicFromServer(chat), 20000);
      return res && res !== TIMEOUT && res.eurl ? res.eurl : null;
    },

    /**
     * Downloads and decrypts a message's media inside the page (the internals whatsapp-web.js uses) and
     * keeps it here until the app pulls it in slices (mediaSlice) and releases it.
     */
    mediaPrepare: async function (args) {
      var store = (window.__walmMedia = window.__walmMedia || new Map());
      store.forEach(function (v, k) {
        if (Date.now() - v.t > 10 * 60000) store.delete(k);
      });
      var Msg = req('WAWebCollections').Msg;
      var msg = Msg.get(args.id);
      if (!msg) {
        try {
          var got = await within(Msg.getMessagesById([args.id]), 20000);
          msg = got && got !== TIMEOUT && got.messages ? got.messages[0] : null;
        } catch (e) {
          msg = null;
        }
      }
      if (!msg) return { status: 'unavailable', reason: 'not_found' };
      if (msg.isViewOnce) return { status: 'view_once' };
      if (!msg.mediaData || !msg.directPath) return { status: 'unavailable', reason: 'no_media' };
      if (typeof msg.size === 'number' && msg.size > args.max) return { status: 'too_large', size: msg.size };
      if (msg.mediaData.mediaStage === 'REUPLOADING') return { status: 'retry', reason: 'reuploading' };
      if (msg.mediaData.mediaStage !== 'RESOLVED') {
        try {
          await within(msg.downloadMedia({ downloadEvenIfExpensive: true, rmrReason: 1 }), 20000);
        } catch (e) {
          /* fall through to the stage check */
        }
        var deadline = Date.now() + 8000;
        while (Date.now() < deadline) {
          var st0 = String(msg.mediaData.mediaStage);
          if (st0 === 'RESOLVED' || st0.indexOf('ERROR') >= 0 || st0 === 'NEED_POKE') break;
          await sleep(500);
        }
      }
      var stage = String(msg.mediaData.mediaStage);
      if (stage.indexOf('ERROR') >= 0) return { status: 'failed', reason: stage };
      // NEED_POKE after asking to download = the CDN copy expired; WhatsApp Web only re-requests it from the
      // phone on a manual click.
      if (stage === 'NEED_POKE') return { status: 'unavailable', reason: 'expired' };
      if (stage !== 'RESOLVED') return { status: 'retry', reason: stage };
      var qpl = {
        addAnnotations: function () {
          return this;
        },
        addPoint: function () {
          return this;
        },
      };
      var buf;
      try {
        buf = await within(
          req('WAWebDownloadManager').downloadManager.downloadAndMaybeDecrypt({
            directPath: msg.directPath,
            encFilehash: msg.encFilehash,
            filehash: msg.filehash,
            mediaKey: msg.mediaKey,
            mediaKeyTimestamp: msg.mediaKeyTimestamp,
            type: msg.type,
            signal: new AbortController().signal,
            downloadQpl: qpl,
          }),
          120000,
        );
      } catch (e) {
        if (e && (e.status === 404 || e.status === 410)) return { status: 'unavailable', reason: 'gone' };
        return { status: 'failed', reason: 'download_error' };
      }
      if (buf === TIMEOUT) return { status: 'retry', reason: 'download_timeout' };
      if (!buf || typeof buf.byteLength !== 'number') return { status: 'failed', reason: 'no_data' };
      if (buf.byteLength > args.max) return { status: 'too_large', size: buf.byteLength };
      var token = Date.now().toString(36) + Math.random().toString(36).slice(2);
      store.set(token, { u8: new Uint8Array(buf), t: Date.now() });
      return { status: 'ok', token: token, size: buf.byteLength, mimetype: msg.mimetype || null, filename: msg.filename || null };
    },

    mediaSlice: async function (args) {
      var entry = window.__walmMedia && window.__walmMedia.get(args.token);
      if (!entry) return null;
      var blob = new Blob([entry.u8.subarray(args.offset, args.offset + args.length)]);
      var dataUrl = await new Promise(function (resolve, reject) {
        var fr = new FileReader();
        fr.onload = function () {
          resolve(String(fr.result));
        };
        fr.onerror = function () {
          reject(fr.error);
        };
        fr.readAsDataURL(blob);
      });
      return dataUrl.slice(dataUrl.indexOf(',') + 1);
    },

    mediaRelease: function (args) {
      if (window.__walmMedia) window.__walmMedia.delete(args.token);
      return true;
    },

    logout: async function () {
      await req('WAWebSocketModel').Socket.logout();
      return true;
    },

    refreshQr: function () {
      try {
        req('WAWebCmd').Cmd.refreshQR();
      } catch (e) {
        /* ignore */
      }
      emitQr();
      return true;
    },

    /** Link with a phone number instead of the QR code (whatsapp-web.js requestPairingCode). */
    pairingCode: async function (args) {
      var phone = String(args.phone || '').replace(/\D/g, '');
      if (phone.length < 7 || phone.length > 15) throw new Error('bad phone number');
      var P = req('WAWebAltDeviceLinkingApi');
      P.setPairingType('ALT_DEVICE_LINKING');
      await P.initializeAltDeviceLinking();
      return await P.startAltLinkingFlow(phone, args.notify !== false);
    },

    /** Lays the page out at a desktop width while it is shown on the phone's screen (0 = back to normal). */
    setViewport: function (args) {
      var meta = document.querySelector('meta[name=viewport]');
      if (!meta) {
        meta = document.createElement('meta');
        meta.name = 'viewport';
        document.head.appendChild(meta);
      }
      if (!meta.dataset.walmOrig) meta.dataset.walmOrig = meta.content || 'width=device-width';
      meta.content = args.width > 0 ? 'width=' + (args.width | 0) + ',user-scalable=yes' : meta.dataset.walmOrig;
      shownOnScreen = args.width > 0;
      return true;
    },

    cancelPairing: async function () {
      try {
        req('WAWebLaunchSocketUtils').refreshQR();
      } catch (e) {
        /* ignore */
      }
      await req('WAWebAltDeviceLinkingApi').initializeQRLinking();
      return true;
    },
  };

  function handle(cmd) {
    if (!cmd || typeof cmd.id !== 'number' || typeof cmd.op !== 'string') return;
    var fn = Object.prototype.hasOwnProperty.call(api, cmd.op) ? api[cmd.op] : null;
    if (!fn) {
      post({ t: 'res', id: cmd.id, ok: false, error: 'unknown op' });
      return;
    }
    Promise.resolve()
      .then(function () {
        if (typeof window.require !== 'function' || !S) throw new Error('WhatsApp Web is not loaded yet');
        return fn(cmd.args || {});
      })
      .then(
        function (data) {
          post({ t: 'res', id: cmd.id, ok: true, data: data === undefined ? null : data });
        },
        function (e) {
          post({ t: 'res', id: cmd.id, ok: false, error: String((e && e.message) || e).slice(0, 200) });
        },
      );
  }

  port.addEventListener('message', function (event) {
    var cmd;
    try {
      cmd = JSON.parse(event.data);
    } catch (e) {
      return;
    }
    handle(cmd);
  });

  post({ t: 'hello', v: BRIDGE_VERSION });
  waitForWhatsApp();
})();
