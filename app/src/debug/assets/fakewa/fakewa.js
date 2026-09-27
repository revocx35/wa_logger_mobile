/*
 * DEBUG BUILDS ONLY — a fake "WhatsApp Web" for end-to-end tests on the emulator (scripts/e2e.py).
 * WaEngine serves this page in place of https://web.whatsapp.com/ when the debug preference `fake_wa` is
 * set, so the real bridge (walm-bridge.js), the real message port and the whole app run against it.
 * It imitates the module shapes the bridge uses (see bridge-test/fakewa.mjs, the Node twin used by the
 * unit tests). Drive it from DevTools (scripts/devtools.mjs): fake.link(), fake.incoming(...), …
 */
(function () {
  'use strict';

  function Emitter() {
    this.handlers = new Map();
  }
  Emitter.prototype.on = function (names, fn) {
    var self = this;
    names.split(' ').forEach(function (n) {
      if (!self.handlers.has(n)) self.handlers.set(n, []);
      self.handlers.get(n).push({ fn: fn, once: false });
    });
  };
  Emitter.prototype.once = function (name, fn) {
    if (!this.handlers.has(name)) this.handlers.set(name, []);
    this.handlers.get(name).push({ fn: fn, once: true });
  };
  Emitter.prototype.off = function (name, fn) {
    this.handlers.set(name, (this.handlers.get(name) || []).filter(function (h) { return h.fn !== fn; }));
  };
  Emitter.prototype.emit = function (name) {
    var args = Array.prototype.slice.call(arguments, 1);
    var list = this.handlers.get(name) || [];
    this.handlers.set(name, list.filter(function (h) { return !h.once; }));
    list.forEach(function (h) { h.fn.apply(null, args); });
  };
  function mix(target) {
    Emitter.call(target);
    Object.keys(Emitter.prototype).forEach(function (k) { target[k] = Emitter.prototype[k]; });
    return target;
  }

  function Wid(s) {
    this._s = s;
    this.server = s.split('@')[1];
    this.user = s.split('@')[0];
  }
  Wid.prototype.toString = function () { return this._s; };

  function MsgKey(o) {
    this.fromMe = !!o.fromMe;
    this.remote = new Wid(o.remote);
    this.id = o.id;
    if (o.participant) this.participant = new Wid(o.participant);
    if (o.fromMe) this.self = 'out';
  }
  MsgKey.prototype.toString = function () {
    return this.fromMe + '_' + this.remote + '_' + this.id + (this.participant ? '_' + this.participant : '') + (this.self ? '_' + this.self : '');
  };

  function Model(attrs) {
    mix(this);
    for (var k in attrs) this[k] = attrs[k];
  }
  Model.prototype.serialize = function () {
    var out = {};
    for (var k in this) {
      if (k === 'handlers' || typeof this[k] === 'function') continue;
      out[k] = this[k];
    }
    return out;
  };

  function Collection() {
    mix(this);
    this.models = new Map();
  }
  Collection.prototype.add = function (m, key) {
    this.models.set(key || String(m.id), m);
    this.emit('add', m);
  };
  Collection.prototype.get = function (id) { return this.models.get(String(id)); };
  Collection.prototype.getModelsArray = function () { return Array.from(this.models.values()); };

  var ME = '905550000001@c.us';
  var Socket = mix({ state: 'OPENING', hasSynced: false, takeover: function () {}, logout: function () { setState('UNPAIRED'); return Promise.resolve(); } });
  var Conn = mix({ ref: '2@FAKEREF' + Date.now(), pushname: 'Test Owner' });
  var Cmd = mix({ refreshQR: function () { Conn.ref = '2@FAKEREF' + Date.now(); Conn.emit('change:ref'); } });
  var Msg = new Collection();
  Msg.getMessagesById = function (ids) { return Promise.resolve({ messages: ids.map(function (i) { return Msg.get(i); }).filter(Boolean) }); };
  var Chat = new Collection();
  var Contact = new Collection();
  var media = new Map();
  var older = new Map();
  var t0 = Math.floor(Date.now() / 1000) - 86400;

  function setState(s) {
    Socket.state = s;
    Socket.emit('change:state', Socket, s);
  }

  var modules = {
    WAWebSocketModel: { Socket: Socket },
    WAWebConnModel: { Conn: Conn },
    WAWebCmd: { Cmd: Cmd },
    WAWebCollections: { Msg: Msg, Chat: Chat, Contact: Contact, ProfilePicThumb: new Collection() },
    WAWebMiscBrowserUtils: { info: function () { return { os: 'Linux', name: 'Chrome' }; } },
    WAWebCompanionRegClientUtils: { DEVICE_PLATFORM: '1' },
    WAWebAdvSignatureApi: {},
    WAWebUserPrefsInfoStore: { waNoiseInfo: { get: function () { return Promise.resolve({ staticKeyPair: { pubKey: 'NOISEKEY' } }); } } },
    WAWebSignalStoreApi: { waSignalStore: { getRegistrationInfo: function () { return Promise.resolve({ identityKeyPair: { pubKey: 'IDENTITY' } }); } } },
    WABase64: { encodeB64: function (x) { return btoa(String(x)); } },
    WAWebUserPrefsMultiDevice: { getADVSecretKey: function () { return 'ADVSECRET'; } },
    WAWebUserPrefsMeUser: { getMaybeMePnUser: function () { return new Wid(ME); }, getMaybeMeLidUser: function () { return new Wid('100000000000001@lid'); } },
    WAWebWidFactory: { createWid: function (s) { return new Wid(String(s)); } },
    WAWebLidMigrationUtils: { toPn: function () { return null; } },
    WAWebFindChatAction: { findOrCreateLatestChat: function (w) { return Promise.resolve({ chat: Chat.get(String(w)) }); } },
    WAWebChatLoadMessages: {
      loadEarlierMsgs: function (o) {
        var id = String(o.chat.id);
        var list = older.get(id) || [];
        older.delete(id);
        return Promise.resolve(list);
      },
    },
    WAWebContactProfilePicThumbBridge: { requestProfilePicFromServer: function () { return Promise.resolve(null); } },
    WAWebDownloadManager: {
      downloadManager: {
        downloadAndMaybeDecrypt: function (o) {
          var url = media.get(o.directPath);
          if (!url) return Promise.reject(Object.assign(new Error('gone'), { status: 404 }));
          return fetch(url).then(function (r) { return r.arrayBuffer(); });
        },
      },
    },
    WAWebAltDeviceLinkingApi: {
      setPairingType: function () {},
      initializeAltDeviceLinking: function () { return Promise.resolve(); },
      startAltLinkingFlow: function () { setTimeout(function () { window.fake.link(); }, 3000); return Promise.resolve('FAKE1234'); },
      initializeQRLinking: function () { return Promise.resolve(); },
    },
    WAWebLaunchSocketUtils: { refreshQR: function () {} },
    WAWebSyncGatingUtils: {},
    WAWebNonMessageDataRequestPlaceholderMessageResendUtils: { handlePlaceholderMsgsSeen: function () {} },
    WAWebOfflineHandler: { OfflineMessageHandler: { getOfflineDeliveryProgress: function () { return 100; } } },
    WAWebAddonReactionTableMode: { reactionTableMode: { bulkUpsert: function (l) { return l.length; } } },
  };

  var seq = 0;
  function nextId() { return 'FAKE' + Date.now().toString(36).toUpperCase() + (seq++); }

  function chat(id, name, group) {
    var c = { id: new Wid(id), formattedTitle: name, name: name, t: t0, archive: false, pin: 0, mute: null, isGroup: !!group };
    c.msgs = new Collection();
    Chat.models.set(id, c);
    if (!group) Contact.models.set(id, { id: new Wid(id), name: name, pushname: name.split(' ')[0] });
    return c;
  }

  function makeMsg(chatId, o) {
    var fromMe = !!o.fromMe;
    var group = /@g\.us$/.test(chatId);
    var key = new MsgKey({ fromMe: fromMe, remote: chatId, id: o.id || nextId(), participant: group && !fromMe ? o.author : null });
    var m = new Model({
      id: key,
      type: o.type || 'chat',
      body: o.body,
      caption: o.caption,
      t: o.t || Math.floor(Date.now() / 1000),
      from: new Wid(fromMe ? ME : chatId),
      to: new Wid(fromMe ? chatId : ME),
      author: group && !fromMe ? new Wid(o.author) : undefined,
      ack: fromMe ? 1 : 0,
      isNewMsg: o.isNewMsg !== false,
      notifyName: o.notifyName,
    });
    ['mimetype', 'filename', 'size', 'width', 'height', 'duration', 'directPath', 'isViewOnce', 'quotedMsg', 'quotedStanzaID', 'quotedParticipant', 'lat', 'lng', 'loc', 'pollName', 'pollOptions', 'subtype', 'recipients'].forEach(function (k) {
      if (o[k] !== undefined) m[k] = o[k];
    });
    if (o.media) {
      var path = '/v/fake/' + key.id;
      media.set(path, o.media);
      m.directPath = path;
      m.mediaData = { mediaStage: 'RESOLVED' };
    }
    return m;
  }

  function add(chatId, o) {
    var m = makeMsg(chatId, o);
    var c = Chat.get(chatId);
    if (c) {
      c.t = m.t;
      c.msgs.add(m, String(m.id));
    }
    Msg.add(m, String(m.id));
    return String(m.id);
  }

  window.fake = {
    ME: ME,
    state: function () { return Socket.state; },
    /** Simulates scanning the QR code / entering the pairing code. */
    link: function () {
      setState('OPENING');
      setTimeout(function () { setState('CONNECTED'); }, 300);
      setTimeout(function () { Socket.hasSynced = true; Socket.emit('change:hasSynced'); }, 800);
      return 'linking';
    },
    unlink: function () {
      Socket.hasSynced = false;
      Cmd.emit('logout');
      setState('UNPAIRED');
      return 'unlinked';
    },
    /** Sample chats with history (loaded before linking; the app imports it on first sync). */
    seed: function () {
      chat('905551112233@c.us', 'Ali Veli');
      chat('905554445566@c.us', 'Ayşe Yılmaz');
      chat('120363000000000001@g.us', 'Family', true);
      var hist = [
        ['905551112233@c.us', { body: 'Selam! Yarın buluşuyor muyuz?', t: t0 + 60 }],
        ['905551112233@c.us', { body: 'Evet, saat 3 uygun mu?', fromMe: true, t: t0 + 120 }],
        ['905551112233@c.us', { body: 'Olur *harika* 👍', t: t0 + 180 }],
        ['905554445566@c.us', { body: 'Toplantı notlarını gönderir misin?', t: t0 + 240 }],
        ['120363000000000001@g.us', { body: 'Pazar günü kahvaltı?', author: '905551112233@c.us', t: t0 + 300 }],
        ['120363000000000001@g.us', { body: 'Ben varım', fromMe: true, t: t0 + 360 }],
      ];
      hist.forEach(function (h) {
        var o = h[1];
        o.isNewMsg = false;
        add(h[0], o);
      });
      // Older messages only reachable through loadEarlierMsgs (like real history).
      older.set('905551112233@c.us', [makeMsg('905551112233@c.us', { body: 'Geçen hafta ki fotoğrafları attın mı?', t: t0 + 10, isNewMsg: false })]);
      return Chat.models.size;
    },
    incoming: function (chatId, body, author) {
      return add(chatId, { body: body, author: author, notifyName: 'Sender' });
    },
    outgoing: function (chatId, body) {
      return add(chatId, { body: body, fromMe: true });
    },
    image: function (chatId, url, caption) {
      return add(chatId, { type: 'image', caption: caption, mimetype: 'image/png', media: url, width: 64, height: 64, size: 1000 });
    },
    voice: function (chatId, url) {
      return add(chatId, { type: 'ptt', mimetype: 'audio/wav', media: url, duration: 1 });
    },
    revoke: function (id) {
      var m = Msg.get(id);
      m.type = 'revoked';
      m.body = undefined;
      m.caption = undefined;
      Msg.emit('change:type', m);
      return 'revoked';
    },
    edit: function (id, body) {
      var m = Msg.get(id);
      var prev = m.body;
      m.body = body;
      m.latestEditMsgKey = new MsgKey({ fromMe: m.id.fromMe, remote: String(m.id.remote), id: nextId() });
      m.latestEditSenderTimestampMs = Date.now();
      Msg.emit('change:body', m, body, prev);
      return 'edited';
    },
    react: function (id, sender, emoji) {
      modules.WAWebAddonReactionTableMode.reactionTableMode.bulkUpsert([
        { reactionParentKey: Msg.get(id).id, author: new Wid(sender), reactionText: emoji, reactionTimestamp: Date.now() },
      ]);
      return 'reacted';
    },
    counts: function () { return { chats: Chat.models.size, msgs: Msg.models.size }; },
  };

  // WhatsApp Web boots after the document-start bridge: define the module system a moment later.
  setTimeout(function () {
    window.Debug = { VERSION: '2.3000.0-fake' };
    window.require = function (name) {
      if (!(name in modules)) throw new Error('Requiring unknown module "' + name + '"');
      return modules[name];
    };
    setTimeout(function () { setState('UNPAIRED'); }, 500);
  }, 800);
})();
